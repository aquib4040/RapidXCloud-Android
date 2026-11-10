package in.rapidxcloud.drive.transfer

import android.content.Context
import android.os.Environment
import android.util.Log
import in.rapidxcloud.drive.data.api.CanonDriveApi
import in.rapidxcloud.drive.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FileDownloader integrates aria2 multi-connection segmented downloading:
 *
 * 1. Multi-Connection Parallel Range Downloads directly from dl.rapidxcloud.in (Cloudflare CDN Edge Cache).
 * 2. Concurrency workers matched to plan limits:
 *      - Starter: 2 download workers
 *      - Pro: 4 download workers
 *      - Ultra: 8 download workers
 * 3. Direct-to-Disk Zero-RAM-Buffering using RandomAccessFile / FileChannel sparse files.
 */
class FileDownloader(
    private val context: Context,
    private val api: CanonDriveApi
) {
    companion object {
        private const val TAG = "FileDownloader"
    }

    private val ariaDownloader = AriaSegmentDownloader(context, api)

    suspend fun downloadSingleFile(
        fileItem: FileItem,
        subfolder: String = "",
        workers: Int = 4,
        onProgress: (loaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            if (subfolder.isNotEmpty()) "CanonDrive/$subfolder" else "CanonDrive"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        val destFile = File(baseDir, fileItem.name)

        Log.i(TAG, "Requesting aria2-accelerated download for '${fileItem.name}' (${fileItem.size} bytes) with $workers workers via dl.rapidxcloud.in")

        ariaDownloader.downloadAccelerated(
            fileItem = fileItem,
            destFile = destFile,
            maxWorkers = workers
        ) { speed ->
            onProgress(speed.bytesDownloaded, speed.totalBytes)
        }

        return@withContext destFile
    }
}
