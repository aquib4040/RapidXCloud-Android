package in.rapidxcloud.drive.crypto

import android.util.Log

object NativeCrypto {
    private const val TAG = "NativeCrypto"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("canondrive_crypto")
            isLoaded = true
            Log.i(TAG, "Rust native crypto engine loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Rust native crypto engine not available; JVM fallback will be used: ${e.message}")
            isLoaded = false
        }
    }

    val isAvailable: Boolean get() = isLoaded

    external fun isNativeAvailable(): Boolean

    external fun argon2id(
        password: String,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int,
        outLen: Int
    ): ByteArray?

    external fun hkdfExpand(
        ikm: ByteArray,
        info: ByteArray,
        outLength: Int
    ): ByteArray?

    external fun xorShares(
        shareA: ByteArray,
        shareB: ByteArray
    ): ByteArray?

    external fun setCancelled(slotId: Int, cancelled: Boolean)
    external fun isCancelled(slotId: Int): Boolean

    external fun validateChunkPart(partSize: Int, partIndex: Int, totalParts: Int): Boolean
    external fun validateSessionString(sessionStr: String): Boolean
}
