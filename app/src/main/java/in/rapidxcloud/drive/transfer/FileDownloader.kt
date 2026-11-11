package `in`.rapidxcloud.drive.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.FileItem
import `in`.rapidxcloud.drive.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * FileDownloader integrates multi-connection segmented downloading:
 *
 * 1. Multi-Connection Parallel Range Downloads via edge cache.
 * 2. Real Original Filenames & Extension preservation (E2EE metadata decryption parity).
 * 3. Live System Notification Progress with speed, percentage, bytes transferred, and tap-to-open intent.
 * 4. Separate caching for preview/stream playback vs user public downloads.
 */
class FileDownloader(
    private val context: Context,
    private val api: CanonDriveApi
) {
    companion object {
        const val DOWNLOAD_CHANNEL_ID = "file_download_channel"
        private const val TAG = "FileDownloader"
    }

    private val ariaDownloader = AriaSegmentDownloader(context, api)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "File Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress for files downloaded from RapidXCloud"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    suspend fun downloadSingleFile(
        fileItem: FileItem,
        subfolder: String = "",
        workers: Int = 4,
        showNotification: Boolean = true,
        onProgress: (loaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val isCache = subfolder == "previews" || subfolder == "stream_cache"
        val baseDir = if (isCache) {
            File(context.cacheDir, subfolder).apply { if (!exists()) mkdirs() }
        } else {
            val safeSubfolder = subfolder.replace("..", "").replace("[/\\\\]+".toRegex(), "/").trim('/')
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                if (safeSubfolder.isNotEmpty()) "RapidXCloud/$safeSubfolder" else "RapidXCloud"
            )
            if (!dir.exists()) dir.mkdirs()
            dir
        }

        // Sanitize file name and enforce real original filename + correct extension
        val rawName = fileItem.displayName.ifBlank { fileItem.name }.ifBlank { "download_${fileItem.id}" }
        var safeFileName = File(rawName).name.replace("[/\\\\]".toRegex(), "_")
        if (!safeFileName.contains('.')) {
            val mime = fileItem.displayMime.ifBlank { fileItem.mime_type }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (!ext.isNullOrBlank()) {
                safeFileName = "$safeFileName.$ext"
            } else if (fileItem.has_thumb || fileItem.thumb_mime?.startsWith("image/") == true) {
                safeFileName = "$safeFileName.jpg"
            }
        }

        // For temporary cache downloads (previews/streams), prefix with effective key to prevent filename collisions
        val finalFileName = if (isCache) {
            "${fileItem.effectiveKey}_$safeFileName"
        } else {
            safeFileName
        }

        val destFile = File(baseDir, finalFileName)
        if (!destFile.canonicalPath.startsWith(baseDir.canonicalPath)) {
            throw SecurityException("Path traversal attempt in filename: $safeFileName")
        }

        val notificationId = abs((fileItem.id + safeFileName).hashCode())
        val shouldNotify = showNotification && !isCache

        if (shouldNotify) {
            val initialNotify = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading $safeFileName")
                .setContentText("Connecting to edge cache...")
                .setProgress(100, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            notificationManager.notify(notificationId, initialNotify)
        }

        var lastNotificationTime = 0L

        try {
            Log.i(TAG, "Starting aria2-accelerated download for '$safeFileName' (${fileItem.size} bytes) with $workers workers")

            ariaDownloader.downloadAccelerated(
                fileItem = fileItem,
                destFile = destFile,
                maxWorkers = workers
            ) { speed ->
                onProgress(speed.bytesDownloaded, speed.totalBytes)

                if (shouldNotify) {
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationTime > 400 || speed.percentage >= 99f) {
                        lastNotificationTime = now
                        val pct = speed.percentage.toInt().coerceIn(0, 100)
                        val speedStr = if (speed.speedBytesPerSec > 0) " • ${formatBytes(speed.speedBytesPerSec)}/s" else ""
                        val contentText = "${formatBytes(speed.bytesDownloaded)} / ${formatBytes(speed.totalBytes)} ($pct%)$speedStr"

                        val progressNotify = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle("Downloading $safeFileName")
                            .setContentText(contentText)
                            .setProgress(100, pct, false)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .build()
                        notificationManager.notify(notificationId, progressNotify)
                    }
                }
            }

            if (shouldNotify) {
                // Build open-file intent
                val fileUri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                } catch (e: Exception) {
                    null
                }

                val openIntent = fileUri?.let { uri ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, fileItem.effectiveMime)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    PendingIntent.getActivity(
                        context,
                        notificationId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }

                val completeNotify = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download Complete")
                    .setContentText("$safeFileName saved to Downloads/RapidXCloud")
                    .setContentIntent(openIntent)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                notificationManager.notify(notificationId, completeNotify)
            }

            return@withContext destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for '$safeFileName': ${e.message}", e)
            if (shouldNotify) {
                val errorNotify = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Download Failed")
                    .setContentText("Could not download $safeFileName: ${e.message ?: "Network error"}")
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                notificationManager.notify(notificationId, errorNotify)
            }
            throw e
        }
    }
}
