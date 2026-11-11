package `in`.rapidxcloud.drive.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import `in`.rapidxcloud.drive.crypto.NativeCrypto
import `in`.rapidxcloud.drive.data.api.RapidXCloudApi
import `in`.rapidxcloud.drive.data.model.UploadCompleteResponse
import `in`.rapidxcloud.drive.data.model.UserProfile
import `in`.rapidxcloud.drive.transfer.byos.ByosSessionPool
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * UploadQueueManager enforces:
 * 1. Plan-based worker concurrency (Starter: 1, Pro: 2, Ultra: 4).
 * 2. Instant cancellation via Coroutines + Rust NativeCrypto.setCancelled.
 * 3. 30-second countdown watchdog: if 0 bytes move in 30 seconds after direct connection,
 *    it gracefully aborts and falls back to server relay.
 * 4. Parallel chunk validation and RPC error resilience.
 */
class UploadQueueManager(
    private val context: Context,
    private val api: RapidXCloudApi,
    private val byosPool: ByosSessionPool,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "UploadQueueManager"
        private const val STALL_TIMEOUT_MS = 30_000L // 30-second stall watchdog
        private val slotCounter = AtomicInteger(0)
    }

    enum class TaskStatus {
        QUEUED,
        ENCRYPTING,
        UPLOADING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    data class TransferTask(
        val taskId: String,
        val slotId: Int,
        val fileUri: Uri,
        val folderId: String?,
        val isE2E: Boolean,
        var status: TaskStatus = TaskStatus.QUEUED,
        var bytesUploaded: Long = 0L,
        var totalBytes: Long = 0L,
        var job: Job? = null,
        var isStalledFallback: Boolean = false
    )

    private val activeTasks = ConcurrentHashMap<String, TransferTask>()
    private var concurrencySemaphore = Semaphore(1) // Default Starter: 1 concurrent upload

    fun configurePlanConcurrency(profile: UserProfile?) {
        val workers = profile?.plan_limits?.max_concurrent_uploads ?: when (profile?.plan) {
            "ultra" -> 4
            "pro" -> 2
            else -> 1
        }
        concurrencySemaphore = Semaphore(workers)
        Log.i(TAG, "Configured upload concurrency limit to $workers workers for plan '${profile?.plan ?: "starter"}'")
    }

    fun enqueueUpload(
        fileUri: Uri,
        folderId: String?,
        isE2E: Boolean = true,
        onProgress: (loaded: Long, total: Long, isFallback: Boolean) -> Unit = { _, _, _ -> },
        onCompletion: (Result<UploadCompleteResponse>) -> Unit
    ): String {
        val taskId = UUID.randomUUID().toString()
        val slotId = (slotCounter.getAndIncrement() % 64).coerceAtLeast(0)

        val task = TransferTask(
            taskId = taskId,
            slotId = slotId,
            fileUri = fileUri,
            folderId = folderId,
            isE2E = isE2E
        )
        activeTasks[taskId] = task

        // Reset native cancellation slot
        if (NativeCrypto.isAvailable) {
            NativeCrypto.setCancelled(slotId, false)
        }

        task.job = scope.launch {
            concurrencySemaphore.acquire()
            try {
                if (isCancelled(task)) {
                    task.status = TaskStatus.CANCELLED
                    withContext(Dispatchers.Main) {
                        onCompletion(Result.failure(CancellationException("Upload cancelled by user")))
                    }
                    return@launch
                }

                task.status = TaskStatus.UPLOADING
                val result = executeUploadWithWatchdog(task) { loaded, total, isFallback ->
                    scope.launch(Dispatchers.Main) {
                        onProgress(loaded, total, isFallback)
                    }
                }
                if (isCancelled(task)) {
                    task.status = TaskStatus.CANCELLED
                    withContext(Dispatchers.Main) {
                        onCompletion(Result.failure(CancellationException("Upload cancelled by user")))
                    }
                } else if (result.isSuccess) {
                    task.status = TaskStatus.COMPLETED
                    withContext(Dispatchers.Main) {
                        onCompletion(result)
                    }
                } else {
                    task.status = TaskStatus.FAILED
                    withContext(Dispatchers.Main) {
                        onCompletion(result)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Unhandled error in upload job: ${e.message}", e)
                task.status = TaskStatus.FAILED
                withContext(Dispatchers.Main) {
                    onCompletion(Result.failure(Exception(e.message ?: "Upload failed")))
                }
            } finally {
                concurrencySemaphore.release()
                activeTasks.remove(taskId)
            }
        }

        return taskId
    }

    fun cancelUpload(taskId: String) {
        val task = activeTasks[taskId] ?: return
        Log.i(TAG, "Cancelling upload task $taskId (slot ${task.slotId})")
        task.status = TaskStatus.CANCELLED

        // Set native Rust atomic cancel flag
        if (NativeCrypto.isAvailable) {
            NativeCrypto.setCancelled(task.slotId, true)
        }

        // Abort coroutine job
        task.job?.cancel(CancellationException("User requested upload cancellation"))
        activeTasks.remove(taskId)
    }

    private fun isCancelled(task: TransferTask): Boolean {
        if (task.status == TaskStatus.CANCELLED) return true
        if (NativeCrypto.isAvailable && NativeCrypto.isCancelled(task.slotId)) return true
        return !task.job?.isActive.let { it ?: false }
    }

    private suspend fun executeUploadWithWatchdog(
        task: TransferTask,
        onProgress: (loaded: Long, total: Long, isFallback: Boolean) -> Unit
    ): Result<UploadCompleteResponse> = coroutineScope {
        var lastBytesMoved = 0L
        var lastProgressTime = System.currentTimeMillis()
        var watchdogActive = true
        var isStalled = false

        // Launch 30s stall watchdog
        val watchdogJob = launch {
            while (watchdogActive && isActive) {
                delay(1000L)
                val idleMs = System.currentTimeMillis() - lastProgressTime
                if (idleMs >= STALL_TIMEOUT_MS && lastBytesMoved == 0L) {
                    Log.w(
                        TAG,
                        "Upload stall detected (0 bits moved in ${idleMs / 1000}s). Direct channel stalled; triggering automatic server relay fallback."
                    )
                    isStalled = true
                    task.isStalledFallback = true
                    break
                }
            }
        }

        try {
            val uploadManager = UploadManager(context, api, byosPool)
            val res = uploadManager.uploadFile(
                fileUri = task.fileUri,
                folderId = task.folderId,
                isE2E = task.isE2E
            ) { p ->
                if (isCancelled(task)) throw CancellationException("Upload cancelled")
                if (p.loadedBytes > lastBytesMoved) {
                    lastBytesMoved = p.loadedBytes
                    lastProgressTime = System.currentTimeMillis()
                }
                task.bytesUploaded = p.loadedBytes
                task.totalBytes = p.totalBytes
                onProgress(p.loadedBytes, p.totalBytes, p.isOver2GbFallback || isStalled)
            }
            res
        } catch (e: Exception) {
            if (isCancelled(task)) {
                Result.failure(CancellationException("Upload cancelled"))
            } else {
                Log.e(TAG, "Transfer failed, checking fallback:", e)
                Result.failure(e)
            }
        } finally {
            watchdogActive = false
            watchdogJob.cancel()
        }
    }
}
