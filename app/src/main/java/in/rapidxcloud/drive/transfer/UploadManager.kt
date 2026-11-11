package `in`.rapidxcloud.drive.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import `in`.rapidxcloud.drive.crypto.AesChunkStreamer
import `in`.rapidxcloud.drive.crypto.DualKeySplit
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.crypto.ThumbnailCrypto
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.UploadCompleteResponse
import `in`.rapidxcloud.drive.transfer.byos.ByosSessionPool
import `in`.rapidxcloud.drive.transfer.mtproto.MtprotoParallelUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * UploadManager manages client-side encrypted file uploads:
 *
 * 1. Zero-Knowledge E2EE: AES-256-GCM chunked stream encryption with dual-key splitting.
 * 2. Multi-tier transfer: Streams ciphertext directly from disk without RAM buffering.
 * 3. Chunked ingestion: Handles multi-gigabyte files via backend chunked ingestion.
 */
class UploadManager(
    private val context: Context,
    private val api: CanonDriveApi,
    private val byosPool: ByosSessionPool? = null
) {
    companion object {
        private const val TAG = "UploadManager"
    }

    data class UploadProgress(
        val loadedBytes: Long,
        val totalBytes: Long,
        val percentage: Float,
        val isOver2GbFallback: Boolean
    )

    private val mtprotoUploader = byosPool?.let { MtprotoParallelUploader(context, api, it) }

    suspend fun uploadFile(
        fileUri: Uri,
        folderId: String?,
        isE2E: Boolean = true,
        onProgress: (UploadProgress) -> Unit = {}
    ): Result<UploadCompleteResponse> = withContext(Dispatchers.IO) {
        try {
            val (fileName, fileSize) = queryFileInfo(fileUri)
            val is2GbPlus = TransferConstants.isLargeFileBackendRequired(fileSize)

            if (is2GbPlus) {
                Log.i(TAG, "File '$fileName' ($fileSize bytes) is 2GB+. Routing to backend chunked ingestion.")
            }

            if (isE2E) {
                uploadE2eInternal(fileUri, fileName, fileSize, folderId, is2GbPlus, onProgress)
            } else {
                uploadPlaintextInternal(fileUri, fileName, fileSize, folderId, is2GbPlus, onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }

    private suspend fun uploadE2eInternal(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        folderId: String?,
        is2GbPlus: Boolean,
        onProgress: (UploadProgress) -> Unit
    ): Result<UploadCompleteResponse> {
        val encKey = SessionKeyStore.getEncKey()
            ?: return Result.failure(IllegalStateException("Vault locked: Master key not present"))

        val mimeType = resolveMimeType(context, uri, fileName)
        val uploadKeys = DualKeySplit.prepareUploadKeys(encKey, useDualKeySplit = true)
        val (metaData, metaIv) = AesChunkStreamer.encryptMetadata(encKey, fileName, mimeType)

        val tempCipherFile = File(context.cacheDir, "upload_${UUID.randomUUID()}.enc")
        try {
            onProgress(UploadProgress(0L, maxOf(1L, fileSize), 0.05f, is2GbPlus))

            val manifest = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempCipherFile).use { output ->
                    AesChunkStreamer.encryptStreamToOutput(
                        inputStream = input,
                        outputStream = output,
                        fileKey = uploadKeys.fileKey,
                        totalBytes = fileSize
                    ) { loaded, total ->
                        val ratio = if (total > 0) (loaded.toFloat() / total) * 0.35f else 0.1f
                        onProgress(UploadProgress(loaded, total, ratio, isOver2GbFallback = false))
                    }
                }
            } ?: return Result.failure(IllegalStateException("Unable to read file from storage"))

            val actualPlaintextSize = if (fileSize > 0) fileSize else tempCipherFile.length()

            var cipherThumbBytes: ByteArray? = null
            var thumbIvB64: String? = null
            var thumbPart: MultipartBody.Part? = null
            var thumbIvPart: RequestBody? = null
            try {
                val rawThumb = ThumbnailCrypto.extractThumbnail(context, uri, mimeType)
                if (rawThumb != null) {
                    val (cipherThumb, ivB64) = ThumbnailCrypto.encryptThumbnail(encKey, rawThumb)
                    cipherThumbBytes = cipherThumb
                    thumbIvB64 = ivB64
                    val thumbBody = cipherThumb.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    thumbPart = MultipartBody.Part.createFormData("thumbnail", "thumb.jpg", thumbBody)
                    thumbIvPart = ivB64.toRequestBody("text/plain".toMediaTypeOrNull())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Thumbnail extraction non-fatal skip: ${t.message}")
            }

            val manifestJson = com.google.gson.Gson().toJson(manifest)
            val cipherFileSize = tempCipherFile.length()
            val chunkSize = TransferConstants.BACKEND_CHUNK_SIZE_BYTES
            val totalChunks = ((cipherFileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)

            // For large files (> 90MB or > 2GB), use backend chunked ingestion to prevent mobile proxy timeouts
            if (is2GbPlus || cipherFileSize > 90 * 1024 * 1024L) {
                val sessionId = UUID.randomUUID().toString().replace("-", "")
                val buffer = ByteArray(chunkSize)
                var totalRead = 0L

                java.io.FileInputStream(tempCipherFile).use { input ->
                    for (chunkIndex in 0 until totalChunks) {
                        val bytesRead = readFully(input, buffer)
                        if (bytesRead <= 0) break

                        val chunkBytes = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                        val chunkBody = chunkBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                        val chunkPart = MultipartBody.Part.createFormData("chunk", "part_$chunkIndex", chunkBody)

                        val uploadRes = api.uploadChunk(
                            chunk = chunkPart,
                            sessionId = sessionId.toRequestBody("text/plain".toMediaTypeOrNull()),
                            chunkIndex = chunkIndex.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                            totalChunks = totalChunks.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                        )

                        if (!uploadRes.isSuccessful) {
                            return Result.failure(Exception("E2E Chunk $chunkIndex upload failed: ${uploadRes.code()}"))
                        }

                        totalRead += bytesRead
                        val ratio = 0.35f + (totalRead.toFloat() / cipherFileSize) * 0.65f
                        onProgress(UploadProgress(totalRead, cipherFileSize, ratio, is2GbPlus))
                    }
                }

                // Finalize chunked E2E upload with companion thumbnail
                val completePayload = mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "server_share" to DualKeySplit.toB64(uploadKeys.serverShare),
                    "encrypted_client_share" to DualKeySplit.toB64(uploadKeys.sealedClientShare),
                    "encrypted_metadata" to metaData,
                    "metadata_iv" to metaIv,
                    "manifest" to manifestJson,
                    "plaintext_size" to actualPlaintextSize
                )
                if (folderId != null) completePayload["folder_id"] = folderId
                if (cipherThumbBytes != null && thumbIvB64 != null) {
                    completePayload["thumbnail_b64"] = Base64.encodeToString(cipherThumbBytes, Base64.NO_WRAP)
                    completePayload["thumb_iv"] = thumbIvB64
                }

                val completeRes = api.completeE2eUpload(completePayload)
                return if (completeRes.isSuccessful && completeRes.body() != null) {
                    onProgress(UploadProgress(actualPlaintextSize, actualPlaintextSize, 1.0f, is2GbPlus))
                    Result.success(completeRes.body()!!)
                } else {
                    val err = completeRes.errorBody()?.string() ?: "HTTP ${completeRes.code()}"
                    Log.e(TAG, "Chunked E2E finalize failed: $err")
                    Result.failure(Exception("Chunked E2E finalize failed ($err)"))
                }
            } else {
                // Direct multipart E2E upload via server relay (/api/upload/e2e)
                val fileBody = tempCipherFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", "$fileName.e2e", fileBody)

                onProgress(UploadProgress(actualPlaintextSize / 2, actualPlaintextSize, 0.5f, is2GbPlus))

                val res = api.uploadE2e(
                    file = filePart,
                    folderId = folderId?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    serverShare = DualKeySplit.toB64(uploadKeys.serverShare).toRequestBody("text/plain".toMediaTypeOrNull()),
                    encryptedClientShare = DualKeySplit.toB64(uploadKeys.sealedClientShare).toRequestBody("text/plain".toMediaTypeOrNull()),
                    encryptedMetadata = metaData.toRequestBody("text/plain".toMediaTypeOrNull()),
                    metadataIv = metaIv.toRequestBody("text/plain".toMediaTypeOrNull()),
                    manifest = manifestJson.toRequestBody("text/plain".toMediaTypeOrNull()),
                    plaintextSize = actualPlaintextSize.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                    thumbnail = thumbPart,
                    thumbIv = thumbIvPart
                )

                return if (res.isSuccessful && res.body() != null) {
                    onProgress(UploadProgress(actualPlaintextSize, actualPlaintextSize, 1.0f, is2GbPlus))
                    Result.success(res.body()!!)
                } else {
                    val err = res.errorBody()?.string() ?: "HTTP ${res.code()}"
                    Log.e(TAG, "Upload failed from backend: $err")
                    Result.failure(Exception("Upload failed ($err)"))
                }
            }
        } finally {
            if (tempCipherFile.exists()) tempCipherFile.delete()
        }
    }

    private suspend fun uploadPlaintextInternal(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        folderId: String?,
        is2GbPlus: Boolean,
        onProgress: (UploadProgress) -> Unit
    ): Result<UploadCompleteResponse> {
        val chunkSize = TransferConstants.BACKEND_CHUNK_SIZE_BYTES
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)

        // For files > 2GB or large files, use the backend chunked ingestion pipeline
        if (is2GbPlus || totalChunks > 1) {
            val sessionId = UUID.randomUUID().toString()
            val buffer = ByteArray(chunkSize)
            var totalRead = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                for (chunkIndex in 0 until totalChunks) {
                    val bytesRead = readFully(input, buffer)
                    if (bytesRead <= 0) break

                    val chunkBytes = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                    val chunkBody = chunkBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    val chunkPart = MultipartBody.Part.createFormData("chunk", "part_$chunkIndex", chunkBody)

                    val uploadRes = api.uploadChunk(
                        chunk = chunkPart,
                        sessionId = sessionId.toRequestBody("text/plain".toMediaTypeOrNull()),
                        chunkIndex = chunkIndex.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                        totalChunks = totalChunks.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                    )

                    if (!uploadRes.isSuccessful) {
                        return Result.failure(Exception("Chunk $chunkIndex upload failed: ${uploadRes.code()}"))
                    }

                    totalRead += bytesRead
                    val progressRatio = totalRead.toFloat() / fileSize
                    onProgress(UploadProgress(totalRead, fileSize, progressRatio, is2GbPlus))
                }
            } ?: return Result.failure(IllegalStateException("Cannot open input stream for $fileName"))

            // Complete chunked upload on backend
            val completePayload = mutableMapOf<String, Any>(
                "session_id" to sessionId,
                "file_name" to fileName,
                "total_size" to fileSize
            )
            if (folderId != null) completePayload["folder_id"] = folderId
            val completeRes = api.completeUpload(completePayload)
            return if (completeRes.isSuccessful && completeRes.body() != null) {
                onProgress(UploadProgress(fileSize, fileSize, 1.0f, is2GbPlus))
                Result.success(completeRes.body()!!)
            } else {
                Result.failure(Exception("Failed to finalize backend chunked upload: ${completeRes.code()}"))
            }
        } else {
            // Single-part upload (< 10 MB): Stream via temp file to avoid JVM heap allocation
            val tempFile = File(context.cacheDir, "upload_single_${UUID.randomUUID()}.tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: return Result.failure(IllegalStateException("Unable to read file"))

                val body = tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", fileName, body)
                val folderIdBody = folderId?.toRequestBody("text/plain".toMediaTypeOrNull())

                val res = api.uploadFile(part, folderIdBody)
                return if (res.isSuccessful && res.body() != null) {
                    onProgress(UploadProgress(fileSize, fileSize, 1.0f, false))
                    Result.success(res.body()!!)
                } else {
                    Result.failure(Exception("Upload failed: ${res.code()}"))
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = input.read(buffer, total, buffer.size - total)
            if (r == -1) break
            total += r
        }
        return total
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = "upload_${System.currentTimeMillis()}"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
            }
        }

        if (size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Throwable) {}
        }

        if (size <= 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (_: Throwable) {}
        }

        return Pair(name, size)
    }

    private fun resolveMimeType(context: Context, uri: Uri, fileName: String): String {
        val ct = try { context.contentResolver.getType(uri) } catch (_: Throwable) { null }
        if (!ct.isNullOrBlank() && ct != "application/octet-stream") return ct
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName).lowercase()
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return fromExt ?: "application/octet-stream"
    }
}
