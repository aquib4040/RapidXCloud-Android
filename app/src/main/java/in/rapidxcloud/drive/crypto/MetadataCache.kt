package `in`.rapidxcloud.drive.crypto

import android.util.Log
import `in`.rapidxcloud.drive.data.model.FileItem
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * MetadataCache resolves and caches decrypted filenames and MIME types for Lane 1 (E2E) files.
 *
 * For e2e files, the backend stores an obfuscated random UUID as 'name' and application/octet-stream
 * as 'mime'. The true name and mime are encrypted inside 'encrypted_metadata'.
 *
 * This cache decrypts them in RAM using the session encKey and caches the result so file lists
 * display legitimate human filenames instantly without repeated cryptographic overhead.
 */
object MetadataCache {
    private const val TAG = "MetadataCache"
    private val nameCache = ConcurrentHashMap<String, Pair<String, String>>()

    fun resolve(item: FileItem, encKey: SecretKey?): FileItem {
        if (item.encryption_lane != "e2e" || item.encrypted_metadata.isNullOrBlank() || item.metadata_iv.isNullOrBlank()) {
            return item
        }

        val cacheKey = item.encrypted_id ?: item.id
        val cached = nameCache[cacheKey]
        if (cached != null) {
            return item.copy(decryptedName = cached.first, decryptedMime = cached.second)
        }

        if (encKey == null) {
            return item
        }

        return try {
            val (name, mime) = AesChunkStreamer.decryptMetadata(encKey, item.encrypted_metadata, item.metadata_iv)
            nameCache[cacheKey] = Pair(name, mime)
            item.copy(decryptedName = name, decryptedMime = mime)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption error for metadata on ${item.id}: ${e.message}")
            item
        }
    }

    fun resolveAll(files: List<FileItem>, encKey: SecretKey?): List<FileItem> {
        return files.map { resolve(it, encKey) }
    }

    fun clear() {
        nameCache.clear()
    }
}
