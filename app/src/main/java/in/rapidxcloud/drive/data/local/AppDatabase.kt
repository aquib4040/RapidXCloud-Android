package `in`.rapidxcloud.drive.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedFolderDao {
    @Query("SELECT * FROM cached_folders WHERE parentId IS :parentId ORDER BY name ASC")
    fun getFoldersInParent(parentId: String?): Flow<List<CachedFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<CachedFolderEntity>)

    @Query("DELETE FROM cached_folders WHERE parentId IS :parentId")
    suspend fun clearFoldersInParent(parentId: String?)

    @Query("DELETE FROM cached_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)
}

@Dao
interface CachedFileDao {
    @Query("SELECT * FROM cached_files WHERE folderId IS :folderId ORDER BY name ASC")
    fun getFilesInFolder(folderId: String?): Flow<List<CachedFileEntity>>

    @Query("SELECT * FROM cached_files WHERE isStarred = 1")
    fun getStarredFiles(): Flow<List<CachedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<CachedFileEntity>)

    @Query("DELETE FROM cached_files WHERE folderId IS :folderId")
    suspend fun clearFilesInFolder(folderId: String?)

    @Query("DELETE FROM cached_files WHERE id = :id")
    suspend fun deleteFile(id: String)
}

@Dao
interface BackupHistoryDao {
    @Query("SELECT * FROM backup_history WHERE localUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BackupHistoryEntity?

    @Query("SELECT * FROM backup_history WHERE fileHash = :hash AND fileSize = :size LIMIT 1")
    suspend fun getByHashAndSize(hash: String, size: Long): BackupHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: BackupHistoryEntity)

    @Query("SELECT COUNT(*) FROM backup_history")
    fun getBackedUpCount(): Flow<Int>

    @Query("DELETE FROM backup_history WHERE localUri = :uri")
    suspend fun removeRecord(uri: String)
}

@Dao
interface ActiveTransferDao {
    @Query("SELECT * FROM active_transfers ORDER BY transferId DESC")
    fun getAllTransfers(): Flow<List<ActiveTransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: ActiveTransferEntity)

    @Query("UPDATE active_transfers SET transferredBytes = :transferred, status = :status WHERE transferId = :id")
    suspend fun updateProgress(id: String, transferred: Long, status: String)

    @Query("DELETE FROM active_transfers WHERE transferId = :id")
    suspend fun deleteTransfer(id: String)

    @Query("DELETE FROM active_transfers WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()
}

@Database(
    entities = [
        CachedFolderEntity::class,
        CachedFileEntity::class,
        BackupHistoryEntity::class,
        ActiveTransferEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): CachedFolderDao
    abstract fun fileDao(): CachedFileDao
    abstract fun backupDao(): BackupHistoryDao
    abstract fun transferDao(): ActiveTransferDao
}
