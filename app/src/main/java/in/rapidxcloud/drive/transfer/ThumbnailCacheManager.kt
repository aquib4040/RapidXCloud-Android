package `in`.rapidxcloud.drive.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.crypto.ThumbnailCrypto
import `in`.rapidxcloud.drive.data.api.RapidXCloudApi
import `in`.rapidxcloud.drive.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * High-performance, Memory-safe Thumbnail Cache & Decryption Manager:
 *
 * 1. Fast Two-Tier Caching: In-RAM LruCache (0ms lookup) + Persistent WebP disk cache.
 * 2. Negative Caching: Never re-requests files without thumbnails or 404s during scroll.
 * 3. Subsampled RGB_565 Decoding: Prevents OutOfMemoryError and native heap exhaustion during rapid scrolling.
 * 4. Rate Limit Backoff: Backs off on HTTP 429 to protect bandwidth and prevent request flooding.
 * 5. Concurrency Gate: Limits simultaneous decrypt/network jobs to prevent CPU thrashing.
 */
class ThumbnailCacheManager(
    private val context: Context,
    private val api: RapidXCloudApi
) {
    private val thumbDir = File(context.cacheDir, "thumbnails").apply { if (!exists()) mkdirs() }
    private val semaphore = Semaphore(2) // Max 2 concurrent thumbnail network fetches
    private val failedKeys = Collections.synchronizedSet(HashSet<String>())
    private val floodWaitUntilMs = AtomicLong(0L)

    // Memory cache: 1/8th of available runtime heap (typically 16-32 MB)
    private val memoryCache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    companion object {
        private const val TAG = "ThumbCache"
        private const val MAX_THUMB_DIMENSION = 360 // Optimal size for grid and column cards
    }

    suspend fun getThumbnailBitmap(fileItem: FileItem): Bitmap? = withContext(Dispatchers.IO) {
        // Optimization: If file metadata confirms no thumbnail exists, do not hit network or disk
        if (!fileItem.has_thumb) {
            return@withContext null
        }

        val fileKey = fileItem.encrypted_id ?: fileItem.id
        if (failedKeys.contains(fileKey)) {
            return@withContext null
        }

        // 1. Check in-memory LruCache (0ms lookup, zero GC allocation)
        synchronized(memoryCache) {
            val memBitmap = memoryCache.get(fileKey)
            if (memBitmap != null && !memBitmap.isRecycled) {
                return@withContext memBitmap
            }
        }

        // 2. Check disk cache
        val cachedFile = File(thumbDir, "$fileKey.webp")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                val decoded = decodeSampledBitmapFromFile(cachedFile.absolutePath, MAX_THUMB_DIMENSION, MAX_THUMB_DIMENSION)
                if (decoded != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(fileKey, decoded)
                    }
                    return@withContext decoded
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Corrupt cached thumbnail for $fileKey, deleting: ${e.message}")
                cachedFile.delete()
            }
        }

        // 3. FloodWait protection: If backed off due to 429, skip network request
        if (System.currentTimeMillis() < floodWaitUntilMs.get()) {
            return@withContext null
        }

        // 4. Fetch from backend with concurrency control
        semaphore.withPermit {
            // Re-check memory cache inside permit in case another coroutine loaded it
            synchronized(memoryCache) {
                val memBitmap = memoryCache.get(fileKey)
                if (memBitmap != null && !memBitmap.isRecycled) {
                    return@withContext memBitmap
                }
            }

            try {
                val res = api.getThumbnail(fileKey)
                if (res.code() == 429) {
                    val retryAfterSec = res.headers()["Retry-After"]?.toLongOrNull() ?: 5L
                    val waitMs = (retryAfterSec * 1000L).coerceIn(2000L, 60000L)
                    floodWaitUntilMs.set(System.currentTimeMillis() + waitMs)
                    Log.w(TAG, "Flood wait active on thumbnails: backing off for ${waitMs}ms")
                    return@withContext null
                }

                if (!res.isSuccessful || res.body() == null) {
                    failedKeys.add(fileKey)
                    return@withContext null
                }

                val rawBytes = res.body()!!.bytes()
                if (rawBytes.isEmpty()) {
                    failedKeys.add(fileKey)
                    return@withContext null
                }

                val finalBytes = if (fileItem.encryption_lane == "e2e") {
                    val encKey = SessionKeyStore.getEncKey()
                    if (encKey == null) {
                        return@withContext null
                    }
                    try {
                        ThumbnailCrypto.decryptThumbnail(encKey, rawBytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed decrypting thumbnail for ${fileItem.displayName}: ${e.message}")
                        failedKeys.add(fileKey)
                        return@withContext null
                    }
                } else {
                    rawBytes
                }

                // Write to disk cache in background
                try {
                    FileOutputStream(cachedFile).use { out ->
                        out.write(finalBytes)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed saving thumbnail disk cache: ${e.message}")
                }

                // Subsampled decode with memory-efficient RGB_565 config
                val bitmap = decodeSampledBitmapFromByteArray(finalBytes, MAX_THUMB_DIMENSION, MAX_THUMB_DIMENSION)
                if (bitmap != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(fileKey, bitmap)
                    }
                } else {
                    failedKeys.add(fileKey)
                }
                return@withContext bitmap
            } catch (e: Throwable) {
                Log.w(TAG, "Error fetching thumbnail for ${fileItem.displayName}: ${e.message}")
                failedKeys.add(fileKey)
                return@withContext null
            }
        }
    }

    private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 50% RAM savings
            BitmapFactory.decodeFile(path, options)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError in decodeSampledBitmapFromFile, purging cache", oom)
            clearMemoryCache()
            System.gc()
            null
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeSampledBitmapFromByteArray(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 50% RAM savings
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError in decodeSampledBitmapFromByteArray, purging cache", oom)
            clearMemoryCache()
            System.gc()
            null
        } catch (e: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    fun evictThumbnail(fileId: String) {
        synchronized(memoryCache) {
            memoryCache.remove(fileId)
        }
        failedKeys.remove(fileId)
        val cachedFile = File(thumbDir, "$fileId.webp")
        if (cachedFile.exists()) {
            cachedFile.delete()
        }
    }

    fun clearMemoryCache() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }
}
