package `in`.rapidxcloud.drive.crypto

import android.util.Base64
import `in`.rapidxcloud.drive.data.model.KeyBag
import `in`.rapidxcloud.drive.data.model.WrappedSecret
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

object KeyHierarchy {
    const val WRAP_INFO_PW = "master-wrap-password"
    const val WRAP_INFO_RECOVERY = "master-wrap-recovery"
    const val FILE_ENCRYPTION_INFO = "file-encryption"

    private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128
    private val random = SecureRandom()

    fun generateMasterSecret(): ByteArray {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes
    }

    fun generateRecoveryCode(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val sb = StringBuilder()
        for (i in 0 until 32) {
            val idx = (bytes[i].toInt() and 0xFF) and 31
            sb.append(RECOVERY_ALPHABET[idx])
            if (i % 5 == 4 && i != 31) {
                sb.append("-")
            }
        }
        return sb.toString()
    }

    fun normalizeRecoveryCode(code: String): String =
        code.uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun saltForEmail(email: String): ByteArray {
        val norm = email.trim().lowercase()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest("rxc-e2e-salt-v1|$norm".toByteArray(Charsets.UTF_8))
        return digest.copyOf(16) // 128-bit salt
    }

    /**
     * Derives 1:1 WebCrypto-compatible auth credential (authKeyHex + "_Aa1!")
     * from email + password via Argon2id (m=64MB, t=3, p=1) and HKDF-SHA256 (info: "login-auth").
     */
    fun deriveAuthKeyHex(email: String, password: String): String {
        val salt = saltForEmail(email)
        val masterKey = Argon2Engine.deriveRaw(password, salt)
        val authKeyBytes = hkdf(masterKey, "login-auth", 32)
        val hex = authKeyBytes.joinToString("") { "%02x".format(it) }
        return hex + "_Aa1!"
    }

    fun hkdf(ikm: ByteArray, info: String, length: Int = 32): ByteArray {
        if (NativeCrypto.isAvailable) {
            val nativeOut = NativeCrypto.hkdfExpand(ikm, info.toByteArray(Charsets.UTF_8), length)
            if (nativeOut != null && nativeOut.size == length) {
                return nativeOut
            }
        }

        // JVM Standard HKDF-SHA256 Extract and Expand
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))

        val t = ByteArray(32)
        macExpand.update(infoBytes)
        macExpand.update(1.toByte())
        val okm = macExpand.doFinal()

        return okm.copyOf(length)
    }

    private fun deriveWrapKey(secret: String, salt: ByteArray, info: String): SecretKey {
        val mk = Argon2Engine.deriveRaw(secret, salt)
        val wrapRaw = hkdf(mk, info, 32)
        return SecretKeySpec(wrapRaw, "AES")
    }

    fun aeadWrap(key: SecretKey, plaintext: ByteArray): String {
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val sealed = ByteArray(IV_LEN + ciphertext.size)
        System.arraycopy(iv, 0, sealed, 0, IV_LEN)
        System.arraycopy(ciphertext, 0, sealed, IV_LEN, ciphertext.size)

        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun aeadUnwrap(key: SecretKey, b64Data: String): ByteArray {
        val sealed = Base64.decode(b64Data, Base64.NO_WRAP)
        if (sealed.size < IV_LEN + 16) {
            throw IllegalArgumentException("Ciphertext payload too short")
        }

        val iv = sealed.copyOfRange(0, IV_LEN)
        val ciphertext = sealed.copyOfRange(IV_LEN, sealed.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun wrapMaster(masterSecret: ByteArray, secret: String, info: String): WrappedSecret {
        val salt = Argon2Engine.generateSalt(16)
        val key = deriveWrapKey(secret, salt, info)
        val dataB64 = aeadWrap(key, masterSecret)
        return WrappedSecret(data = dataB64, salt = Base64.encodeToString(salt, Base64.NO_WRAP))
    }

    fun unwrapMaster(wrapped: WrappedSecret, secret: String, info: String): ByteArray {
        val salt = Base64.decode(wrapped.salt, Base64.NO_WRAP)
        val key = deriveWrapKey(secret, salt, info)
        val unwrapped = aeadUnwrap(key, wrapped.data)
        if (unwrapped.size != 32) {
            throw IllegalStateException("Unwrapped master key invalid length")
        }
        return unwrapped
    }

    fun deriveEncKey(masterSecret: ByteArray): SecretKey {
        val rawEnc = hkdf(masterSecret, FILE_ENCRYPTION_INFO, 32)
        return SecretKeySpec(rawEnc, "AES")
    }

    /**
     * Complete new user enrollment: creates master secret M, wraps under password and recovery code,
     * returns encKey, keyBag, and human-readable recovery code.
     */
    fun enrollMaster(password: String): Triple<SecretKey, KeyBag, String> {
        val m = generateMasterSecret()
        val recoveryCode = generateRecoveryCode()

        val wrappedPw = wrapMaster(m, password, WRAP_INFO_PW)
        val wrappedRecovery = wrapMaster(m, normalizeRecoveryCode(recoveryCode), WRAP_INFO_RECOVERY)

        val encKey = deriveEncKey(m)
        val keyBag = KeyBag(wrapped_pw = wrappedPw, wrapped_recovery = wrappedRecovery)

        return Triple(encKey, keyBag, recoveryCode)
    }

    /**
     * Unlock from server key bag with password.
     */
    fun unlockWithPassword(keyBag: KeyBag, password: String): Pair<SecretKey, ByteArray> {
        val m = unwrapMaster(keyBag.wrapped_pw, password, WRAP_INFO_PW)
        val encKey = deriveEncKey(m)
        return Pair(encKey, m)
    }

    /**
     * Recovery unlock with recovery code and re-wrapping under new password.
     */
    fun recoverWithCode(
        keyBag: KeyBag,
        recoveryCode: String,
        newPassword: String
    ): Triple<SecretKey, WrappedSecret, ByteArray> {
        val m = unwrapMaster(keyBag.wrapped_recovery, normalizeRecoveryCode(recoveryCode), WRAP_INFO_RECOVERY)
        val newWrappedPw = wrapMaster(m, newPassword, WRAP_INFO_PW)
        val encKey = deriveEncKey(m)
        return Triple(encKey, newWrappedPw, m)
    }
}
