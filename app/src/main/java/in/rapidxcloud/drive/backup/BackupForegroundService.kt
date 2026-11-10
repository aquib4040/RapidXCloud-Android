package in.rapidxcloud.drive.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import in.rapidxcloud.drive.CanonDriveApp
import in.rapidxcloud.drive.R
import in.rapidxcloud.drive.crypto.AesChunkStreamer
import in.rapidxcloud.drive.crypto.DualKeySplit
import in.rapidxcloud.drive.crypto.SessionKeyStore
import in.rapidxcloud.drive.crypto.ThumbnailCrypto
import in.rapidxcloud.drive.data.model.UploadCompleteResponse
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class BackupForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_BACKUP = "in.rapidxcloud.drive.action.START_BACKUP"
        const val ACTION_CANCEL_BACKUP = "in.rapidxcloud.drive.action.CANCEL_BACKUP"
        private const val TAG = "BackupService"

        fun start(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START_BACKUP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_BACKUP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Initializing automated mobile backup...", 0, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isRunning) {
            isRunning = true
            serviceScope.launch {
                runBackupProcess()
                stopForeground(true)
                stopSelf()
                isRunning = false
            }
        }

        return START_STICKY
    }

    private suspend fun runBackupProcess() {
        val app = application as CanonDriveApp
        val repo = app.driveRepository
        val deduplicator = app.backupDeduplicator
        val mapper = app.folderHierarchyMapper
        val prefs = app.settingsRepository

        // 1. Verify BYOS (Private Node) check: Automated backup is strictly for BYOS users
        val profile = repo.getCachedUserProfile()
        val isByos = profile?.byos_enabled == true || profile?.is_admin == true
        if (!isByos) {
            Log.w(TAG, "Automated mobile backup skipped: BYOS (Private Node) is not enabled for this user.")
            updateNotification("Backup paused: Private Node (BYOS) is required.", 0, 0)
            delay(3000)
            return
        }

        // 2. Load Backup Preferences
        val isBackupEnabled = prefs.isBackupEnabled()
        if (!isBackupEnabled) {
            Log.i(TAG, "Automated backup is disabled in settings.")
            return
        }

        val extensions = prefs.getBackupExtensions() // e.g. ["jpg", "png", "mp4", "pdf"]
        val autoDelete = prefs.isAutoDeleteEnabled() // strictly false by default

        // 3. Scan MediaStore for candidate files
        val filesToBackup = queryMediaStoreCandidates(extensions)
        Log.i(TAG, "Found ${filesToBackup.size} candidate files on device for backup.")

        var processed = 0
        for (item in filesToBackup) {
            try {
                // Check deduplication in database
                if (deduplicator.isAlreadyBackedUp(item.uri, item.size)) {
                    continue
                }

                updateNotification("Encrypting & backing up: ${item.name}", processed, filesToBackup.size)

                // Resolve target cloud folder ID (e.g. "Mobile Backup" / "Camera")
                val targetFolderId = mapper.resolveTargetFolderId(item.relativePath)

                // Perform E2E Upload
                val cloudFileId = performE2eUpload(item, targetFolderId)
                if (cloudFileId != null) {
                    deduplicator.recordBackupSuccess(
                        uri = item.uri,
                        fileName = item.name,
                        fileSize = item.size,
                        relativePath = item.relativePath,
                        cloudFolderId = targetFolderId,
                        cloudFileId = cloudFileId
                    )

                    // Optional Auto-Delete original (strictly default OFF)
                    if (autoDelete) {
                        try {
                            contentResolver.delete(item.uri, null, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "Auto-delete failed for ${item.name}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed backing up ${item.name}: ${e.message}", e)
            }
            processed++
        }

        updateNotification("Backup complete ($processed files processed)", filesToBackup.size, filesToBackup.size)
        delay(2000)
    }

    private suspend fun performE2eUpload(item: MediaItem, folderId: String?): String? {
        val app = application as CanonDriveApp
        val encKey = SessionKeyStore.getEncKey() ?: return null
        val api = app.apiClient

        // Prepare upload keys (Dual-Key Split or Solo User Key)
        val useDualKey = app.settingsRepository.isDualKeyEnabled()
        val uploadKeys = DualKeySplit.prepareUploadKeys(encKey, useDualKey)

        // Metadata encryption
        val (metaData, metaIv) = AesChunkStreamer.encryptMetadata(encKey, item.name, item.mimeType)

        // Stream and encrypt file in 4MB chunks to temporary cache file
        val tempCipherFile = File(cacheDir, "e2e_backup_${System.currentTimeMillis()}.enc")
        val manifest = contentResolver.openInputStream(item.uri)?.use { input ->
            FileOutputStream(tempCipherFile).use { output ->
                AesChunkStreamer.encryptStreamToOutput(
                    inputStream = input,
                    outputStream = output,
                    fileKey = uploadKeys.fileKey,
                    totalBytes = item.size
                )
            }
        } ?: return null

        // Thumbnail extraction & encryption
        var thumbPart: MultipartBody.Part? = null
        var thumbIvPart: okhttp3.RequestBody? = null
        val rawThumb = ThumbnailCrypto.extractThumbnail(this, item.uri, item.mimeType)
        if (rawThumb != null) {
            val (cipherThumb, ivB64) = ThumbnailCrypto.encryptThumbnail(encKey, rawThumb)
            val thumbBody = cipherThumb.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            thumbPart = MultipartBody.Part.createFormData("thumbnail", "thumb.jpg", thumbBody)
            thumbIvPart = ivB64.toRequestBody("text/plain".toMediaTypeOrNull())
        }

        val fileBody = tempCipherFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", item.name + ".e2e", fileBody)

        val res = api.uploadE2e(
            file = filePart,
            folderId = folderId?.toRequestBody("text/plain".toMediaTypeOrNull()),
            serverShare = DualKeySplit.toB64(uploadKeys.serverShare).toRequestBody("text/plain".toMediaTypeOrNull()),
            encryptedClientShare = DualKeySplit.toB64(uploadKeys.sealedClientShare).toRequestBody("text/plain".toMediaTypeOrNull()),
            encryptedMetadata = metaData.toRequestBody("text/plain".toMediaTypeOrNull()),
            metadataIv = metaIv.toRequestBody("text/plain".toMediaTypeOrNull()),
            manifest = com.google.gson.Gson().toJson(manifest).toRequestBody("text/plain".toMediaTypeOrNull()),
            plaintextSize = item.size.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
            thumbnail = thumbPart,
            thumbIv = thumbIvPart
        )

        tempCipherFile.delete()

        return if (res.isSuccessful && res.body() != null) {
            res.body()!!.file_id ?: res.body()!!.encrypted_id
        } else {
            null
        }
    }

    data class MediaItem(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
        val relativePath: String
    )

    private fun queryMediaStoreCandidates(allowedExtensions: List<String>): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        )

        val uri = MediaStore.Files.getContentUri("external")
        contentResolver.query(
            uri,
            projection,
            "${MediaStore.MediaColumns.SIZE} > 0",
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                val rawPath = cursor.getString(pathCol) ?: "DCIM/Camera"

                val ext = name.substringAfterLast('.', "").lowercase()
                if (allowedExtensions.isNotEmpty() && !allowedExtensions.contains(ext)) {
                    continue
                }

                val itemUri = Uri.withAppendedPath(uri, id.toString())
                list.add(
                    MediaItem(
                        uri = itemUri,
                        name = name,
                        size = size,
                        mimeType = mime,
                        relativePath = rawPath
                    )
                )
            }
        }
        return list
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.backup_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.backup_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RapidXCloud Mobile Backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, current, total))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
