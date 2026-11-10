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
import in.rapidxcloud.drive.data.model.FolderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.crypto.SecretKey

class FolderDownloader(
    private val context: Context,
    private val api: CanonDriveApi
) {
    companion object {
        private const val TAG = "FolderDownloader"
    }

    /**
     * Recursively downloads an entire cloud folder directly to Android storage WITHOUT zipping.
     * Default destination: Downloads/CanonDrive/{FolderName}/...
     */
    suspend fun downloadFolderRecursively(
        rootFolderId: String,
        folderName: String,
        onProgress: (currentFile: String, completedFiles: Int, totalFiles: Int, bytesMoved: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val baseDownloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CanonDrive"
        )
        val targetRoot = File(baseDownloadsDir, folderName)
        if (!targetRoot.exists()) {
            targetRoot.mkdirs()
        }

        // 1. Crawl all files and subfolders recursively
        val allFiles = mutableListOf<Pair<FileItem, File>>() // FileItem to local target directory
        crawlFolder(rootFolderId, targetRoot, allFiles)

        Log.i(TAG, "Starting unzipped direct download for ${allFiles.size} files in '$folderName'...")

        var completed = 0
        var totalBytesMoved = 0L
        val encKey = SessionKeyStore.getEncKey()

        for ((fileItem, localDir) in allFiles) {
            val destinationFile = File(localDir, fileItem.name)
            onProgress(fileItem.name, completed, allFiles.size, totalBytesMoved)

            try {
                if (fileItem.encryption_lane == "e2e" && encKey != null && fileItem.encrypted_client_share != null) {
                    downloadAndDecryptE2e(fileItem, destinationFile, encKey) { chunkBytes ->
                        totalBytesMoved += chunkBytes
                        onProgress(fileItem.name, completed, allFiles.size, totalBytesMoved)
                    }
                } else {
                    downloadDirectStream(fileItem, destinationFile) { streamBytes ->
                        totalBytesMoved += streamBytes
                        onProgress(fileItem.name, completed, allFiles.size, totalBytesMoved)
                    }
                }
                completed++
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading ${fileItem.name}: ${e.message}", e)
            }
        }

        return@withContext true
    }

    private suspend fun crawlFolder(
        folderId: String,
        currentLocalDir: File,
        collector: MutableList<Pair<FileItem, File>>
    ) {
        if (!currentLocalDir.exists()) {
            currentLocalDir.mkdirs()
        }

        val res = api.listFolder(mapOf("folder_id" to folderId))
        if (!res.isSuccessful || res.body() == null) return

        val data = res.body()!!
        for (file in data.files) {
            collector.add(Pair(file, currentLocalDir))
        }

        for (subfolder in data.folders) {
            val subLocalDir = File(currentLocalDir, subfolder.name)
            crawlFolder(subfolder.id, subLocalDir, collector)
        }
    }

    private suspend fun downloadAndDecryptE2e(
        fileItem: FileItem,
        destinationFile: File,
        encKey: SecretKey,
        onBytes: (Long) -> Unit
    ) {
        val clientShare = DualKeySplit.fromB64(fileItem.encrypted_client_share)
        val serverShare = fileItem.server_share?.let { DualKeySplit.fromB64(it) }
        val fileKey = DualKeySplit.recoverFileKey(encKey, clientShare, serverShare)

        val fileId = fileItem.encrypted_id ?: fileItem.id
        val streamRes = api.downloadDirectFile(fileId, dl = 1)
        if (!streamRes.isSuccessful || streamRes.body() == null) {
            throw IllegalStateException("Failed to download E2E file stream: ${streamRes.code()}")
        }

        val manifest = fileItem.manifest?.let {
            val json = com.google.gson.Gson().toJson(it)
            com.google.gson.Gson().fromJson(json, E2EManifest::class.java)
        } ?: E2EManifest(fileItem.size, AesChunkStreamer.PLAINTEXT_CHUNK_SIZE.toLong(), emptyList())

        streamRes.body()!!.byteStream().use { input ->
            FileOutputStream(destinationFile).use { output ->
                AesChunkStreamer.decryptStreamToOutput(input, output, fileKey, manifest) { loaded, _ ->
                    onBytes(loaded)
                }
            }
        }
    }

    private suspend fun downloadDirectStream(
        fileItem: FileItem,
        destinationFile: File,
        onBytes: (Long) -> Unit
    ) {
        val fileId = fileItem.encrypted_id ?: fileItem.id
        val streamRes = api.downloadDirectFile(fileId, dl = 1)
        if (!streamRes.isSuccessful || streamRes.body() == null) {
            throw IllegalStateException("Failed to stream file: ${streamRes.code()}")
        }

        val buffer = ByteArray(64 * 1024)
        streamRes.body()!!.byteStream().use { input ->
            FileOutputStream(destinationFile).use { output ->
                var r: Int
                while (input.read(buffer).also { r = it } != -1) {
                    output.write(buffer, 0, r)
                    onBytes(r.toLong())
                }
            }
        }
    }
}
