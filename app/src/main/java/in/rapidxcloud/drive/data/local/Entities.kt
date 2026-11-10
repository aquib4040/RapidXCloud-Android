package `in`.rapidxcloud.drive.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_folders")
data class CachedFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val itemCount: Int,
    val size: Long,
    val isHidden: Boolean,
    val isLocked: Boolean,
    val modifiedAt: String?,
    val createdAt: String?
)

@Entity(tableName = "cached_files")
data class CachedFileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val folderId: String?,
    val size: Long,
    val mime: String?,
    val isHidden: Boolean,
    val isStarred: Boolean,
    val modifiedAt: String?,
    val createdAt: String?,
    val encryptionLane: String,
    val encryptedMetadata: String?,
    val metadataIv: String?,
    val serverShare: String?,
    val encryptedClientShare: String?,
    val encryptedId: String?,
    val hasThumb: Boolean,
    val thumbMime: String?,
    val thumbIv: String?
)

@Entity(tableName = "backup_history")
data class BackupHistoryEntity(
    @PrimaryKey val localUri: String, // Android MediaStore content URI
    val fileHash: String,            // SHA-256 hash of first 64KB + length for fast deduplication
    val fileName: String,
    val fileSize: Long,
    val relativePath: String,        // e.g. "DCIM/Camera"
    val cloudFolderId: String?,      // Uploaded folder ID
    val cloudFileId: String,         // Resulting file ID in cloud
    val backedUpAt: Long             // System.currentTimeMillis()
)

@Entity(tableName = "active_transfers")
data class ActiveTransferEntity(
    @PrimaryKey val transferId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val isUpload: Boolean,
    val isE2e: Boolean,
    val status: String, // "QUEUED", "TRANSFERRING", "COMPLETED", "FAILED", "PAUSED"
    val targetFolderId: String?,
    val localFilePath: String?,
    val errorMessage: String? = null
)
