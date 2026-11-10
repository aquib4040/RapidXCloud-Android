package `in`.rapidxcloud.drive.crypto

import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.SecureRandom

object Argon2Engine {
    // Parity with RapidxCloud / Proton: 64 MB memory, 3 iterations, 1 parallelism, 32-byte key
    const val MEMORY_KB = 65536
    const val ITERATIONS = 3
    const val PARALLELISM = 1
    const val HASH_LENGTH = 32

    private val secureRandom = SecureRandom()

    fun generateSalt(len: Int = 16): ByteArray {
        val salt = ByteArray(len)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Derives 32-byte master key from secret and salt using Argon2id.
     * Uses Rust native engine if available, otherwise argon2kt JVM library.
     */
    fun deriveRaw(secret: String, salt: ByteArray): ByteArray {
        if (NativeCrypto.isAvailable) {
            val nativeOut = NativeCrypto.argon2id(
                password = secret,
                salt = salt,
                memoryKb = MEMORY_KB,
                iterations = ITERATIONS,
                parallelism = PARALLELISM,
                outLen = HASH_LENGTH
            )
            if (nativeOut != null && nativeOut.size == HASH_LENGTH) {
                return nativeOut
            }
        }

        // JVM Fallback
        val argon2Kt = Argon2Kt()
        val result = argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = secret.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = ITERATIONS,
            mCostInKibibyte = MEMORY_KB,
            parallelism = PARALLELISM,
            hashLengthInBytes = HASH_LENGTH
        )
        return result.rawHashAsByteArray()
    }

    fun toB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromB64(b64: String): ByteArray =
        Base64.decode(b64, Base64.NO_WRAP)
}
