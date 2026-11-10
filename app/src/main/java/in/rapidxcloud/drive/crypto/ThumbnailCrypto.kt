package `in`.rapidxcloud.drive.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ThumbnailCrypto {
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128
    private val random = SecureRandom()
    private val THUMB_AAD = "0|1".toByteArray(Charsets.UTF_8)

    /**
     * Extracts low-overhead thumbnail Bitmap from an image or video file.
     */
    fun extractThumbnail(context: Context, uri: Uri, mimeType: String): ByteArray? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
            } else {
                val input = context.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(input)
                input?.close()
                bmp?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt(), true) }
            } ?: return null

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
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
