package in.rapidxcloud.drive.backup

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log

class BackupContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    private var lastTriggerTime = 0L

    companion object {
        private const val TAG = "BackupContentObserver"
        private const val DEBOUNCE_MS = 3000L // Debounce multiple rapid writes (e.g. burst photography)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < DEBOUNCE_MS) {
            return
        }
        lastTriggerTime = now

        Log.d(TAG, "New file write detected in MediaStore: $uri. Launching automated backup service...")
        BackupForegroundService.start(context)
    }

    fun register() {
        try {
            val cr = context.contentResolver
            cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
            cr.registerContentObserver(MediaStore.Files.getContentUri("external"), true, this)
            Log.i(TAG, "Backup content observer registered successfully across all media stores.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register content observer: ${e.message}")
        }
    }

    fun unregister() {
        try {
            context.contentResolver.unregisterContentObserver(this)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
