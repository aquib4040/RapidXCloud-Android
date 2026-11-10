package in.rapidxcloud.drive.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import in.rapidxcloud.drive.crypto.SessionKeyStore
import in.rapidxcloud.drive.crypto.ThumbnailCrypto
import in.rapidxcloud.drive.data.api.CanonDriveApi
import in.rapidxcloud.drive.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ThumbnailCacheManager(
    private val context: Context,
    private val api: CanonDriveApi
) {
    private val thumbDir = File(context.cacheDir, "thumbnails").apply { if (!exists()) mkdirs() }
    private val semaphore = Semaphore(2) // Max 2 concurrent thumbnail fetches to prevent Telegram FloodWait

    companion object {
        private const val TAG = "ThumbCache"
    }

    suspend fun getThumbnailBitmap(fileItem: FileItem): Bitmap? = withContext(Dispatchers.IO) {
        val fileKey = fileItem.encrypted_id ?: fileItem.id
        val cachedFile = File(thumbDir, "$fileKey.webp")

        // 1. Return from disk cache if already present
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)
        }

        // 2. Fetch from backend with concurrency control
        semaphore.withPermit {
            try {
                val res = api.getThumbnail(fileKey)
                if (!res.isSuccessful || res.body() == null) {
                    return@withContext null
                }

                val rawBytes = res.body()!!.bytes()
                if (rawBytes.isEmpty()) return@withContext null

                val finalBytes = if (fileItem.encryption_lane == "e2e") {
                    val encKey = SessionKeyStore.getEncKey() ?: return@withContext null
                    try {
                        ThumbnailCrypto.decryptThumbnail(encKey, rawBytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed decrypting thumbnail for ${fileItem.name}: ${e.message}")
                        return@withContext null
                    }
                } else {
                    rawBytes
                }

                // Cache to disk
                FileOutputStream(cachedFile).use { out ->
                    out.write(finalBytes)
                }

                return@withContext BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size)
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching thumbnail for ${fileItem.name}: ${e.message}")
                return@withContext null
            }
        }
    }

    fun evictThumbnail(fileId: String) {
        val cachedFile = File(thumbDir, "$fileId.webp")
        if (cachedFile.exists()) {
            cachedFile.delete()
        }
    }
}
