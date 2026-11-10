package in.rapidxcloud.drive.transfer

import android.content.Context
import android.os.Environment
import android.util.Log
import in.rapidxcloud.drive.crypto.AesChunkStreamer
import in.rapidxcloud.drive.crypto.DualKeySplit
import in.rapidxcloud.drive.crypto.SessionKeyStore
import in.rapidxcloud.drive.data.api.CanonDriveApi
import in.rapidxcloud.drive.data.model.E2EManifest
import in.rapidxcloud.drive.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileDownloader(
    private val context: Context,
    private val api: CanonDriveApi
) {
    companion object {
        private const val TAG = "FileDownloader"
    }

    suspend fun downloadSingleFile(
        fileItem: FileItem,
        subfolder: String = "",
        onProgress: (loaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            if (subfolder.isNotEmpty()) "CanonDrive/$subfolder" else "CanonDrive"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        val destFile = File(baseDir, fileItem.name)
        val encKey = SessionKeyStore.getEncKey()
        val fileId = fileItem.encrypted_id ?: fileItem.id

        // Website-like fallback check:
        // Files > 1.91 GB (2050851897 bytes) MUST use direct backend streaming reassembly (dl.rapidxcloud.in / api)
        // bypassing any direct client-side WebSocket/MTProto connection attempts.
        val isOver2Gb = TransferConstants.isLargeFileBackendRequired(fileItem.size)
        if (isOver2Gb) {
            Log.d(TAG, "File ${fileItem.name} is 2GB+ (${fileItem.size} bytes). Enforcing website-parity direct backend download.")
        }

        if (fileItem.encryption_lane == "e2e" && encKey != null && fileItem.encrypted_client_share != null) {
            val clientShare = DualKeySplit.fromB64(fileItem.encrypted_client_share)
            val serverShare = fileItem.server_share?.let { DualKeySplit.fromB64(it) }
            val fileKey = DualKeySplit.recoverFileKey(encKey, clientShare, serverShare)

            val streamRes = api.downloadDirectFile(fileId, dl = 1)
            if (!streamRes.isSuccessful || streamRes.body() == null) {
                throw IllegalStateException("Failed to download E2E file: ${streamRes.code()}")
            }

            val manifest = fileItem.manifest?.let {
                val json = com.google.gson.Gson().toJson(it)
                com.google.gson.Gson().fromJson(json, E2EManifest::class.java)
            } ?: E2EManifest(fileItem.size, AesChunkStreamer.PLAINTEXT_CHUNK_SIZE.toLong(), emptyList())

            streamRes.body()!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    AesChunkStreamer.decryptStreamToOutput(input, output, fileKey, manifest, onProgress)
                }
            }
        } else {
            // Direct backend stream download (handles 307 redirect to dl.rapidxcloud.in)
            val streamRes = api.downloadDirectFile(fileId, dl = 1)
            if (!streamRes.isSuccessful || streamRes.body() == null) {
                throw IllegalStateException("Failed to download file: ${streamRes.code()}")
            }

            var loaded = 0L
            val total = fileItem.size
            val buffer = ByteArray(64 * 1024)
            streamRes.body()!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    var r: Int
                    while (input.read(buffer).also { r = it } != -1) {
                        output.write(buffer, 0, r)
                        loaded += r
                        onProgress(loaded, total)
                    }
                }
            }
        }

        return@withContext destFile
    }
}
