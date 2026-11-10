package `in`.rapidxcloud.drive.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PreparedUploadKeys(
    val fileKey: SecretKey,
    val serverShare: ByteArray,
    val sealedClientShare: ByteArray
)

object DualKeySplit {
    const val KEY_LEN = 32
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128
    private val random = SecureRandom()

    fun makeRandomKey(): ByteArray {
        val b = ByteArray(KEY_LEN)
        random.nextBytes(b)
        return b
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        if (NativeCrypto.isAvailable) {
            val nativeResult = NativeCrypto.xorShares(a, b)
            if (nativeResult != null && nativeResult.size == a.size) {
                return nativeResult
            }
        }

        require(a.size == b.size) { "Share length mismatch for XOR" }
        val out = ByteArray(a.size)
        for (i in a.indices) {
            out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return out
    }

    fun seal(encKey: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val sealed = ByteArray(IV_LEN + ciphertext.size)
        System.arraycopy(iv, 0, sealed, 0, IV_LEN)
        System.arraycopy(ciphertext, 0, sealed, IV_LEN, ciphertext.size)
        return sealed
    }

    fun unseal(encKey: SecretKey, sealed: ByteArray): ByteArray {
        require(sealed.size >= IV_LEN + 16) { "Sealed client share is too short" }
        val iv = sealed.copyOfRange(0, IV_LEN)
        val ciphertext = sealed.copyOfRange(IV_LEN, sealed.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Prepares upload keys matching web-new split.ts:
     * - When useDualKeySplit is TRUE (default): FileKey = ServerShare ⊕ ClientShare.
     * - When useDualKeySplit is FALSE (Solo User Key mode): FileKey is sealed directly under encKey;
     *   serverShare is EMPTY (0 bytes) so NO key share ever reaches the backend!
     */
    fun prepareUploadKeys(
        encKey: SecretKey,
        useDualKeySplit: Boolean = true
    ): PreparedUploadKeys {
        val fileKeyRaw = makeRandomKey()

        if (!useDualKeySplit) {
            // Solo User Key mode: FileKey is sealed directly under user's encKey. Server gets 0 bytes.
            val sealedClientShare = seal(encKey, fileKeyRaw)
            val fileKey = SecretKeySpec(fileKeyRaw, "AES")
            return PreparedUploadKeys(
                fileKey = fileKey,
                serverShare = ByteArray(0),
                sealedClientShare = sealedClientShare
            )
        }

        // Dual-Key Split Mode
        val serverShare = makeRandomKey()
        val clientShare = xor(fileKeyRaw, serverShare)
        val sealedClientShare = seal(encKey, clientShare)
        val fileKey = SecretKeySpec(fileKeyRaw, "AES")

        return PreparedUploadKeys(
            fileKey = fileKey,
            serverShare = serverShare,
            sealedClientShare = sealedClientShare
        )
    }

    /**
     * Download-side key recovery:
     * - If serverShare is null or empty (Solo User Key mode), unsealed share is the raw FileKey.
     * - If serverShare is present (Dual-Key mode), FileKey = ClientShare ⊕ ServerShare.
     */
    fun recoverFileKey(
        encKey: SecretKey,
        sealedClientShare: ByteArray,
        serverShare: ByteArray?
    ): SecretKey {
        val clientShare = unseal(encKey, sealedClientShare)
        val fileKeyRaw = if (serverShare == null || serverShare.isEmpty() || serverShare.all { it == 0.toByte() }) {
            clientShare
        } else {
            xor(clientShare, serverShare)
        }
        return SecretKeySpec(fileKeyRaw, "AES")
    }

    fun toB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromB64(b64: String?): ByteArray {
        if (b64.isNullOrEmpty()) return ByteArray(0)
        return Base64.decode(b64, Base64.NO_WRAP)
    }
}
