package `in`.rapidxcloud.drive.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BackupWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        BackupForegroundService.start(context)
        return Result.success()
    }
}
