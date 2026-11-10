package `in`.rapidxcloud.drive.transfer

import android.content.Context
import android.os.Environment
import android.util.Log
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FolderDownloader(
    private val context: Context,
    private val api: CanonDriveApi
) {
    companion object {
        private const val TAG = "FolderDownloader"
    }

    private val ariaDownloader = AriaSegmentDownloader(context, api)

    /**
     * Recursively downloads an entire cloud folder directly to Android storage WITHOUT zipping.
     * Uses parallel segmented downloading to maximize transfer performance.
     * Destination: Downloads/CanonDrive/{FolderName}/...
     */
    suspend fun downloadFolderRecursively(
        rootFolderId: String,
        folderName: String,
        downloadWorkers: Int = 4,
        onProgress: (currentFile: String, completedFiles: Int, totalFiles: Int, bytesMoved: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val baseDownloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CanonDrive"
        )
        val safeFolderName = File(folderName).name.replace("[/\\\\]".toRegex(), "_").ifBlank { "folder_$rootFolderId" }
        val targetRoot = File(baseDownloadsDir, safeFolderName)
        if (!targetRoot.exists()) {
            targetRoot.mkdirs()
        }

        // 1. Crawl all files and subfolders recursively
        val allFiles = mutableListOf<Pair<FileItem, File>>()
        crawlFolder(rootFolderId, targetRoot, allFiles)

        Log.i(TAG, "Starting unzipped direct download for ${allFiles.size} files in '$safeFolderName' with aria2 multi-connection engine...")

        var completed = 0
        var totalBytesMoved = 0L

        for ((fileItem, localDir) in allFiles) {
            val safeFileName = File(fileItem.name).name.replace("[/\\\\]".toRegex(), "_").ifBlank { "file_${fileItem.id}" }
            val destinationFile = File(localDir, safeFileName)
            if (!destinationFile.canonicalPath.startsWith(targetRoot.canonicalPath)) {
                Log.w(TAG, "Skipping file with traversal path: ${fileItem.name}")
                continue
            }
            onProgress(fileItem.name, completed, allFiles.size, totalBytesMoved)

            try {
                var fileBytesDownloaded = 0L
                ariaDownloader.downloadAccelerated(
                    fileItem = fileItem,
                    destFile = destinationFile,
                    maxWorkers = downloadWorkers
                ) { speed ->
                    val delta = speed.bytesDownloaded - fileBytesDownloaded
                    if (delta > 0) {
                        totalBytesMoved += delta
                        fileBytesDownloaded = speed.bytesDownloaded
                    }
                    onProgress(fileItem.name, completed, allFiles.size, totalBytesMoved)
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
            val safeSubName = File(subfolder.name).name.replace("[/\\\\]".toRegex(), "_").ifBlank { "subfolder_${subfolder.id}" }
            val subLocalDir = File(currentLocalDir, safeSubName)
            if (subLocalDir.canonicalPath.startsWith(currentLocalDir.canonicalPath)) {
                crawlFolder(subfolder.id, subLocalDir, collector)
            }
        }
    }
}
