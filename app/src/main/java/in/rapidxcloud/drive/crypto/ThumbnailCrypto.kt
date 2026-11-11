package `in`.rapidxcloud.drive.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ThumbnailCrypto {
    private const val TAG = "ThumbnailCrypto"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128
    private const val TARGET_MAX_DIM = 320
    private val random = SecureRandom()
    private val THUMB_AAD = "0|1".toByteArray(Charsets.UTF_8)

    /**
     * Extracts low-overhead thumbnail Bitmap from an image or video file.
     * Safely handles SAF URIs, MediaStore URIs, and direct content streams.
     */
    fun extractThumbnail(context: Context, uri: Uri, mimeType: String? = null): ByteArray? {
        val resolvedMime = resolveMimeType(context, uri, mimeType)
        return try {
            val bitmap = if (isVideo(resolvedMime, uri)) {
                extractVideoThumbnail(context, uri)
            } else {
                extractImageThumbnail(context, uri)
            } ?: return null

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Throwable) {
            Log.w(TAG, "extractThumbnail failed for $uri: ${e.message}")
            null
        }
    }

    private fun isVideo(mimeType: String?, uri: Uri): Boolean {
        if (mimeType?.startsWith("video/", ignoreCase = true) == true) return true
        val uriStr = uri.toString().lowercase()
        return uriStr.endsWith(".mp4") || uriStr.endsWith(".mkv") || uriStr.endsWith(".webm") ||
               uriStr.endsWith(".mov") || uriStr.endsWith(".avi") || uriStr.endsWith(".ts") ||
               uriStr.endsWith(".flv") || uriStr.endsWith(".3gp") || uriStr.endsWith(".m4v") ||
               uriStr.contains("video")
    }

    private fun resolveMimeType(context: Context, uri: Uri, mimeType: String?): String? {
        if (!mimeType.isNullOrBlank() && mimeType != "application/octet-stream") return mimeType
        try {
            val ct = context.contentResolver.getType(uri)
            if (!ct.isNullOrBlank()) return ct
        } catch (_: Throwable) {}
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        if (!ext.isNullOrBlank()) {
            val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            if (!fromExt.isNullOrBlank()) return fromExt
        }
        return mimeType
    }

    private fun extractVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        // 1. Try MediaMetadataRetriever (universal for SAF, MediaStore, File)
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                var frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame == null) {
                    frame = retriever.frameAtTime
                }
                if (frame != null) {
                    return scaleToFit(frame, TARGET_MAX_DIM)
                }
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.d(TAG, "MediaMetadataRetriever failed: ${e.message}")
        }

        // 2. Fallback to ContentResolver loadThumbnail on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bmp = context.contentResolver.loadThumbnail(uri, Size(TARGET_MAX_DIM, TARGET_MAX_DIM), null)
                if (bmp != null) return scaleToFit(bmp, TARGET_MAX_DIM)
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun extractImageThumbnail(context: Context, uri: Uri): Bitmap? {
        // 1. On Android 10+, try loadThumbnail first (fast MediaStore thumbnail if cached)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bmp = context.contentResolver.loadThumbnail(uri, Size(TARGET_MAX_DIM, TARGET_MAX_DIM), null)
                if (bmp != null) return scaleToFit(bmp, TARGET_MAX_DIM)
            } catch (_: Throwable) {
                // Common with SAF URIs or downloads: fallback to stream decode
            }
        }

        // 2. Robust fallback: Subsampled stream decode via BitmapFactory
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth > 0 && options.outHeight > 0) {
                var sampleSize = 1
                while (options.outWidth / (sampleSize * 2) >= TARGET_MAX_DIM &&
                       options.outHeight / (sampleSize * 2) >= TARGET_MAX_DIM) {
                    sampleSize *= 2
                }
                options.inSampleSize = sampleSize
                options.inJustDecodeBounds = false

                val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                if (decoded != null) {
                    return scaleToFit(decoded, TARGET_MAX_DIM)
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "BitmapFactory stream decode failed: ${e.message}")
        }

        return null
    }

    private fun scaleToFit(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src

        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val dstW = (w * ratio).toInt().coerceAtLeast(1)
        val dstH = (h * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, dstW, dstH, true)
        if (scaled != src) {
            src.recycle()
        }
        return scaled
    }

    /**
     * Encrypts thumbnail bytes under encKey using AES-GCM and AAD "0|1".
     * Prepends IV to match WebCrypto sealed format.
     */
    fun encryptThumbnail(encKey: SecretKey, rawBytes: ByteArray): Pair<ByteArray, String> { // cipherBytes, ivB64
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(THUMB_AAD)
        val ciphertext = cipher.doFinal(rawBytes)

        val sealed = ByteArray(IV_LEN + ciphertext.size)
        System.arraycopy(iv, 0, sealed, 0, IV_LEN)
        System.arraycopy(ciphertext, 0, sealed, IV_LEN, ciphertext.size)

        return Pair(sealed, Base64.encodeToString(iv, Base64.NO_WRAP))
    }

    /**
     * Decrypts encrypted thumbnail bytes under encKey using AES-GCM and AAD "0|1".
     */
    fun decryptThumbnail(encKey: SecretKey, sealed: ByteArray): ByteArray {
        require(sealed.size >= IV_LEN + 16) { "Ciphertext thumbnail too small" }
        val iv = sealed.copyOfRange(0, IV_LEN)
        val ciphertext = sealed.copyOfRange(IV_LEN, sealed.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(THUMB_AAD)
        return cipher.doFinal(ciphertext)
    }
}
