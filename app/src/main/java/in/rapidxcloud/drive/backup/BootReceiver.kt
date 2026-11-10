package `in`.rapidxcloud.drive.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Device boot completed. Re-arming automated mobile backup observer...")
            BackupForegroundService.start(context)
        }
    }
}
