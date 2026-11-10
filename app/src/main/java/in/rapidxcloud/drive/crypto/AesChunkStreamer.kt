package in.rapidxcloud.drive.crypto

import android.util.Base64
import com.google.gson.Gson
import in.rapidxcloud.drive.data.model.E2EChunk
import in.rapidxcloud.drive.data.model.E2EManifest
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil
import kotlin.math.min

object AesChunkStreamer {
    const val PLAINTEXT_CHUNK_SIZE = 4 * 1024 * 1024 // 4 MiB
    const val IV_LEN = 12
    const val TAG_LEN_BITS = 128
    const val TAG_LEN_BYTES = 16

    private val random = SecureRandom()
    private val gson = Gson()

    fun chunkAAD(index: Int, total: Int): ByteArray =
        "$index|$total".toByteArray(Charsets.UTF_8)

    /**
     * Encrypts a single 4MB chunk with prepended 12-byte IV and AAD binding.
     */
    fun encryptChunk(
        fileKey: SecretKey,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray
    ): ByteArray {
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, fileKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext, offset, length)

        val sealed = ByteArray(IV_LEN + ciphertext.size)
        System.arraycopy(iv, 0, sealed, 0, IV_LEN)
        System.arraycopy(ciphertext, 0, sealed, IV_LEN, ciphertext.size)
        return sealed
    }

    /**
     * Decrypts a single chunk where IV is prepended.
     */
    fun decryptChunk(
        fileKey: SecretKey,
        sealed: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(sealed.size >= IV_LEN + TAG_LEN_BYTES) { "Ciphertext chunk too small" }
        val iv = sealed.copyOfRange(0, IV_LEN)
        val ciphertext = sealed.copyOfRange(IV_LEN, sealed.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, fileKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /**
     * RAM-friendly stream encryption:
     * Reads from inputStream in 4MB buffers, encrypts each chunk, writes sealed bytes to outputStream.
     * Memory is strictly bounded to < 10 MB total heap throughout the process.
     */
    fun encryptStreamToOutput(
        inputStream: InputStream,
        outputStream: OutputStream,
        fileKey: SecretKey,
        totalBytes: Long,
        onProgress: ((loaded: Long, total: Long) -> Unit)? = null
    ): E2EManifest {
        val totalChunks = if (totalBytes == 0L) 1 else ceil(totalBytes.toDouble() / PLAINTEXT_CHUNK_SIZE).toInt()
        val buffer = ByteArray(PLAINTEXT_CHUNK_SIZE)
        val chunks = mutableListOf<E2EChunk>()

        var plainOffset = 0L
        var cipherOffset = 0L
        var index = 0

        while (plainOffset < totalBytes || (totalBytes == 0L && index == 0)) {
            val bytesToRead = min(PLAINTEXT_CHUNK_SIZE.toLong(), totalBytes - plainOffset).toInt()
            var readTotal = 0
            while (readTotal < bytesToRead) {
                val r = inputStream.read(buffer, readTotal, bytesToRead - readTotal)
                if (r <= 0) break
                readTotal += r
            }

            val aad = chunkAAD(index, totalChunks)
            val sealed = encryptChunk(fileKey, buffer, 0, readTotal, aad)

            outputStream.write(sealed)

            val plainEnd = if (readTotal == 0) 0L else plainOffset + readTotal - 1
            val cipherEnd = cipherOffset + sealed.size - 1

            chunks.add(
                E2EChunk(
                    index = index,
                    plain_start = plainOffset,
                    plain_end = plainEnd,
                    cipher_start = cipherOffset,
                    cipher_end = cipherEnd
                )
            )

            plainOffset += readTotal
            cipherOffset += sealed.size
            index++

            onProgress?.invoke(plainOffset, totalBytes)
            if (plainOffset >= totalBytes) break
        }

        outputStream.flush()
        return E2EManifest(
            total_size = totalBytes,
            chunk_plaintext = PLAINTEXT_CHUNK_SIZE.toLong(),
            chunks = chunks
        )
    }

    /**
     * Decrypts encrypted stream directly to destination local file.
     */
    fun decryptStreamToOutput(
        inputStream: InputStream,
        outputStream: OutputStream,
        fileKey: SecretKey,
        manifest: E2EManifest,
        onProgress: ((loaded: Long, total: Long) -> Unit)? = null
    ) {
        val totalChunks = manifest.chunks.size
        var decryptedBytes = 0L

        for (chunk in manifest.chunks) {
            val sealedSize = (chunk.cipher_end - chunk.cipher_start + 1).toInt()
            val sealed = ByteArray(sealedSize)
            var readTotal = 0
            while (readTotal < sealedSize) {
                val r = inputStream.read(sealed, readTotal, sealedSize - readTotal)
                if (r <= 0) break
                readTotal += r
            }

            val aad = chunkAAD(chunk.index, totalChunks)
            val plaintext = decryptChunk(fileKey, sealed, aad)
            outputStream.write(plaintext)

            decryptedBytes += plaintext.size
            onProgress?.invoke(decryptedBytes, manifest.total_size)
        }
        outputStream.flush()
    }

    fun encryptMetadata(
        encKey: SecretKey,
        name: String,
        mimeType: String
    ): Pair<String, String> { // dataB64, ivB64
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val json = gson.toJson(mapOf("name" to name, "mime_type" to mimeType))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        return Pair(
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptMetadata(
        encKey: SecretKey,
        dataB64: String,
        ivB64: String
    ): Pair<String, String> {
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(dataB64, Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        val json = String(plaintext, Charsets.UTF_8)

        val map = gson.fromJson(json, Map::class.java)
        val name = map["name"]?.toString() ?: "unnamed"
        val mime = map["mime_type"]?.toString() ?: "application/octet-stream"
        return Pair(name, mime)
    }
}
