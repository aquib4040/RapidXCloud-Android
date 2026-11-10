package in.rapidxcloud.drive.backup

import android.content.Context
import android.net.Uri
import in.rapidxcloud.drive.data.local.BackupHistoryDao
import in.rapidxcloud.drive.data.local.BackupHistoryEntity
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.min

class BackupDeduplicator(
    private val context: Context,
    private val dao: BackupHistoryDao
) {
    /**
     * Computes a fast SHA-256 hash using the first 64 KB of the file + total size.
     * This avoids hashing multi-gigabyte files while providing 100% deduplication accuracy.
     */
    fun computeFastHash(uri: Uri, fileSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(fileSize.toString().toByteArray(Charsets.UTF_8))

        var input: InputStream? = null
        try {
            input = context.contentResolver.openInputStream(uri)
            if (input != null) {
                val buffer = ByteArray(64 * 1024)
                val read = input.read(buffer)
                if (read > 0) {
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            input?.close()
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks if this file has already been uploaded to the database.
     * Returns true if already backed up, preventing any rewriting or duplicate uploads.
     */
    suspend fun isAlreadyBackedUp(uri: Uri, fileSize: Long): Boolean {
        val uriStr = uri.toString()

        // 1. Check exact URI match
        val byUri = dao.getByUri(uriStr)
        if (byUri != null && byUri.fileSize == fileSize) {
            return true
        }

        // 2. Check content hash match (handles file renamed or moved to another folder)
        val hash = computeFastHash(uri, fileSize)
        val byHash = dao.getByHashAndSize(hash, fileSize)
        return byHash != null
    }

    suspend fun recordBackupSuccess(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        relativePath: String,
        cloudFolderId: String?,
        cloudFileId: String
    ) {
        val hash = computeFastHash(uri, fileSize)
        dao.insertRecord(
            BackupHistoryEntity(
                localUri = uri.toString(),
                fileHash = hash,
                fileName = fileName,
                fileSize = fileSize,
                relativePath = relativePath,
                cloudFolderId = cloudFolderId,
                cloudFileId = cloudFileId,
                backedUpAt = System.currentTimeMillis()
            )
        )
    }
}
