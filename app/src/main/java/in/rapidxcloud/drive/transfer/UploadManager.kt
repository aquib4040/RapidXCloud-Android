package `in`.rapidxcloud.drive.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
 * UploadManager handles file uploads with exact rapidxcloud.in web client & gotd microservice parity:
 *
 * 1. Native MTProto Parallel Engine: Files <= 1.91 GB use direct MTProto upload with 8 workers
 *    and 512 KiB chunks (upload.saveFilePart for < 10 MB, upload.saveBigFilePart for >= 10 MB).
 * 2. 30-Second Stall Watchdog: If direct MTProto transfer stalls (0 bytes in 30s), it gracefully
 *    falls back to the VPS server relay (upload.rapidxcloud.in / /api/upload/e2e).
 * 3. 2GB+ File Fallback: Files > 1.91 GB bypass direct MTProto and route directly to the backend
 *    chunked split pipeline.
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
                Log.i(TAG, "File '$fileName' ($fileSize bytes) is 2GB+. Bypassing direct MTProto and routing directly to backend cluster.")
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

        val uploadKeys = DualKeySplit.prepareUploadKeys(encKey, useDualKey = true)
        val (metaData, metaIv) = AesChunkStreamer.encryptMetadata(encKey, fileName, "application/octet-stream")

        val tempCipherFile = File(context.cacheDir, "upload_${UUID.randomUUID()}.enc")
        try {
            val manifest = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempCipherFile).use { output ->
                    AesChunkStreamer.encryptStreamToOutput(
                        inputStream = input,
                        outputStream = output,
                        fileKey = uploadKeys.fileKey,
                        totalBytes = fileSize
                    )
                }
            } ?: return Result.failure(IllegalStateException("Unable to read file input stream"))

            var thumbPart: MultipartBody.Part? = null
            var thumbIvPart: RequestBody? = null
            var rawThumbIv: String? = null
            val rawThumb = ThumbnailCrypto.extractThumbnail(context, uri, "image/jpeg")
            if (rawThumb != null) {
                val (cipherThumb, ivB64) = ThumbnailCrypto.encryptThumbnail(encKey, rawThumb)
                val thumbBody = cipherThumb.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                thumbPart = MultipartBody.Part.createFormData("thumbnail", "thumb.jpg", thumbBody)
                thumbIvPart = ivB64.toRequestBody("text/plain".toMediaTypeOrNull())
                rawThumbIv = ivB64
            }

            val manifestJson = com.google.gson.Gson().toJson(manifest)

            // Step 1: Try Direct MTProto Upload (8 parallel workers, 512 KiB chunks) if <= 1.91 GB and BYOS pool present
            if (!is2GbPlus && mtprotoUploader != null) {
                try {
                    val directParams = MtprotoParallelUploader.DirectUploadParams(
                        file = tempCipherFile,
                        fileName = fileName,
                        folderId = folderId,
                        plaintextSize = fileSize,
                        serverShare = DualKeySplit.toB64(uploadKeys.serverShare),
                        encryptedClientShare = DualKeySplit.toB64(uploadKeys.sealedClientShare),
                        encryptedMetadata = metaData,
                        metadataIv = metaIv,
                        manifestJson = manifestJson,
                        thumbIv = rawThumbIv
                    )

                    val directResult = mtprotoUploader.uploadDirectMtproto(directParams) { loaded, total ->
                        val ratio = if (total > 0) loaded.toFloat() / total else 0f
                        onProgress(UploadProgress(loaded, total, ratio, isOver2GbFallback = false))
                    }

                    if (directResult.isSuccess) {
                        return directResult
                    } else {
                        Log.w(TAG, "Direct MTProto upload failed/stalled; falling back to VPS relay: ${directResult.exceptionOrNull()?.message}")
                    }
                } catch (directErr: Exception) {
                    Log.w(TAG, "Direct MTProto upload exception; executing automatic VPS relay fallback: ${directErr.message}")
                }
            }

            // Step 2: Fallback to VPS Server Relay (upload.rapidxcloud.in / /api/upload/e2e)
            val fileBody = tempCipherFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", "$fileName.e2e", fileBody)

            onProgress(UploadProgress(fileSize / 2, fileSize, 0.5f, is2GbPlus))

            val res = api.uploadE2e(
                file = filePart,
                folderId = folderId?.toRequestBody("text/plain".toMediaTypeOrNull()),
                serverShare = DualKeySplit.toB64(uploadKeys.serverShare).toRequestBody("text/plain".toMediaTypeOrNull()),
                encryptedClientShare = DualKeySplit.toB64(uploadKeys.sealedClientShare).toRequestBody("text/plain".toMediaTypeOrNull()),
                encryptedMetadata = metaData.toRequestBody("text/plain".toMediaTypeOrNull()),
                metadataIv = metaIv.toRequestBody("text/plain".toMediaTypeOrNull()),
                manifest = manifestJson.toRequestBody("text/plain".toMediaTypeOrNull()),
                plaintextSize = fileSize.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                thumbnail = thumbPart,
                thumbIv = thumbIvPart
            )

            if (res.isSuccessful && res.body() != null) {
                onProgress(UploadProgress(fileSize, fileSize, 1.0f, is2GbPlus))
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("E2E upload failed with code ${res.code()}: ${res.errorBody()?.string()}"))
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
            val completePayload = mutableMapOf<String, Any?>(
                "session_id" to sessionId,
                "file_name" to fileName,
                "folder_id" to folderId,
                "total_size" to fileSize
            )
            val completeRes = api.completeUpload(completePayload)
            return if (completeRes.isSuccessful && completeRes.body() != null) {
                onProgress(UploadProgress(fileSize, fileSize, 1.0f, is2GbPlus))
                Result.success(completeRes.body()!!)
            } else {
                Result.failure(Exception("Failed to finalize backend chunked upload: ${completeRes.code()}"))
            }
        } else {
            // Single-part upload (< 85 MB)
            val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Unable to read file"))
            val body = fileBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            val folderIdBody = folderId?.toRequestBody("text/plain".toMediaTypeOrNull())

            val res = api.uploadFile(part, folderIdBody)
            return if (res.isSuccessful && res.body() != null) {
                onProgress(UploadProgress(fileSize, fileSize, 1.0f, false))
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("Upload failed: ${res.code()}"))
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
        return Pair(name, size)
    }
}
