package `in`.rapidxcloud.drive.crypto

import android.util.Log

object NativeCrypto {
    private const val TAG = "NativeCrypto"
    private var isLoaded = false

    init {
        val candidates = listOf("rapidxcloud_crypto", "rapidxclouddrive_crypto", "canondrive_crypto")
        for (lib in candidates) {
            try {
                System.loadLibrary(lib)
                isLoaded = true
                Log.i(TAG, "RapidXCloud Rust native crypto engine ($lib) loaded successfully.")
                break
            } catch (_: UnsatisfiedLinkError) {
                // Try next candidate
            }
        }
        if (!isLoaded) {
            Log.w(TAG, "Rust native crypto engine not available; JVM fallback will be used.")
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

    external fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray?

    external fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray?

    external fun encryptStreamChunk(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray?

    external fun decryptStreamChunk(
        key: ByteArray,
        sealedChunk: ByteArray,
        aad: ByteArray?
    ): ByteArray?

    external fun setCancelled(slotId: Int, cancelled: Boolean)
    external fun isCancelled(slotId: Int): Boolean

    external fun validateChunkPart(partSize: Int, partIndex: Int, totalParts: Int): Boolean
    external fun validateSessionString(sessionStr: String): Boolean

    external fun frameUploadPart(
        fileId: Long,
        partIndex: Int,
        totalParts: Int,
        totalFileSize: Long,
        chunk: ByteArray
    ): ByteArray?

    external fun parseTelethonSession(sessionStr: String): Long
}
