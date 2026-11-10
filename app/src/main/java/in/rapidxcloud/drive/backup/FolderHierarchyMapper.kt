package in.rapidxcloud.drive.backup

import in.rapidxcloud.drive.data.api.CanonDriveApi
import in.rapidxcloud.drive.data.model.FolderItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FolderHierarchyMapper(private val api: CanonDriveApi) {
    private val folderCache = mutableMapOf<String, String>() // path -> folder_id
    private val mutex = Mutex()

    companion object {
        const val ROOT_BACKUP_FOLDER_NAME = "Mobile Backup"
    }

    /**
     * Resolves or recursively creates the cloud folder structure for a mobile file path.
     * Example input: "DCIM/Camera"
     * Result in cloud: "Mobile Backup" -> "Camera"
     * Returns the target cloud folder ID.
     */
    suspend fun resolveTargetFolderId(relativeMobileDir: String): String? = mutex.withLock {
        val cleanDir = relativeMobileDir.trim().trim('/')
        // Extract leaf folder (e.g. "Camera" from "DCIM/Camera", "Screenshots" from "Pictures/Screenshots")
        val leafName = if (cleanDir.contains('/')) cleanDir.substringAfterLast('/') else cleanDir
        val targetFolderName = if (leafName.isEmpty()) "Phone Files" else leafName
        val cacheKey = "$ROOT_BACKUP_FOLDER_NAME/$targetFolderName"

        folderCache[cacheKey]?.let { return it }

        // 1. Get or create "Mobile Backup" root folder
        val rootBackupId = getOrCreateFolder(ROOT_BACKUP_FOLDER_NAME, parentId = null) ?: return null

        // 2. Get or create target leaf folder inside "Mobile Backup"
        val leafFolderId = getOrCreateFolder(targetFolderName, parentId = rootBackupId) ?: return null

        folderCache[cacheKey] = leafFolderId
        return leafFolderId
    }

    private suspend fun getOrCreateFolder(name: String, parentId: String?): String? {
        try {
            // Check existing folders in parent
            val listRes = api.listFolder(mapOf("folder_id" to parentId))
            if (listRes.isSuccessful && listRes.body() != null) {
                val existing = listRes.body()!!.folders.find { it.name.equals(name, ignoreCase = true) }
                if (existing != null) {
                    return existing.id
                }
            }

            // Create folder
            val createRes = api.createFolder(mapOf("name" to name, "parent_id" to parentId))
            if (createRes.isSuccessful && createRes.body() != null) {
                val id = createRes.body()!!["folder_id"]?.toString()
                if (id != null) return id
            }
        } catch (e: Exception) {
            // Log / ignore
        }
        return null
    }
}
