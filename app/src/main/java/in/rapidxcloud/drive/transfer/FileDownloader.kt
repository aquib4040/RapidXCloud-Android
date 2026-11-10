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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

/**
 * FileDownloader implements website-identical download streaming:
 *
 * 1. Zero-WebSocket Architecture: WebSockets are never used for downloads.
 * 2. CDN Redirect Flow:
 *      Request: drive.rapidxcloud.in/file/{id}?dl=1
 *      Response: HTTP 307 Redirect to https://dl.rapidxcloud.in/d/{token}?dl=1
 *      OkHttp automatically follows the redirect to dl.rapidxcloud.in with Cloudflare CDN Edge Cache enabled.
 * 3. 1 MiB chunk streaming with direct-to-disk write and instantaneous coroutine cancellation.
 */
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

        // Follows 307 redirect from drive.rapidxcloud.in to dl.rapidxcloud.in (Cloudflare CDN Cached)
        Log.i(TAG, "Requesting CDN download for '${fileItem.name}' (${fileItem.size} bytes). Following 307 redirect to dl.rapidxcloud.in")

        if (fileItem.encryption_lane == "e2e" && encKey != null && fileItem.encrypted_client_share != null) {
            val clientShare = DualKeySplit.fromB64(fileItem.encrypted_client_share)
            val serverShare = fileItem.server_share?.let { DualKeySplit.fromB64(it) }
            val fileKey = DualKeySplit.recoverFileKey(encKey, clientShare, serverShare)

            val streamRes = api.downloadDirectFile(fileId, dl = 1)
            if (!streamRes.isSuccessful || streamRes.body() == null) {
                throw IllegalStateException("Failed to download E2E file from CDN: ${streamRes.code()}")
            }

            val manifest = fileItem.manifest?.let {
                val json = com.google.gson.Gson().toJson(it)
                com.google.gson.Gson().fromJson(json, E2EManifest::class.java)
            } ?: E2EManifest(fileItem.size, AesChunkStreamer.PLAINTEXT_CHUNK_SIZE.toLong(), emptyList())

            streamRes.body()!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    AesChunkStreamer.decryptStreamToOutput(input, output, fileKey, manifest) { loaded, total ->
                        if (!isActive) throw CancellationException("Download cancelled by user")
                        onProgress(loaded, total)
                    }
                }
            }
        } else {
            // Direct backend CDN stream download (with 307 redirect to dl.rapidxcloud.in)
            val streamRes = api.downloadDirectFile(fileId, dl = 1)
            if (!streamRes.isSuccessful || streamRes.body() == null) {
                throw IllegalStateException("Failed to download file from CDN: ${streamRes.code()}")
            }

            var loaded = 0L
            val total = fileItem.size
            val buffer = ByteArray(64 * 1024)
            streamRes.body()!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    var r: Int
                    while (input.read(buffer).also { r = it } != -1) {
                        if (!isActive) {
                            destFile.delete()
                            throw CancellationException("Download cancelled by user")
                        }
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
