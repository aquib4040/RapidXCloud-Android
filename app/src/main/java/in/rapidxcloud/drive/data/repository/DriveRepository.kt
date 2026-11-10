package `in`.rapidxcloud.drive.data.repository

import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.local.*
import `in`.rapidxcloud.drive.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DriveRepository(
    private val api: CanonDriveApi,
    private val database: AppDatabase
) {
    private var cachedProfile: UserProfile? = null

    suspend fun getProfile(forceRefresh: Boolean = false): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedProfile != null) {
            return@withContext Result.success(cachedProfile!!)
        }
        try {
            val res = api.getProfile()
            if (res.isSuccessful && res.body() != null) {
                cachedProfile = res.body()!!.user
                Result.success(res.body()!!.user)
            } else {
                Result.failure(Exception("Failed to load profile (${res.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCachedUserProfile(): UserProfile? = cachedProfile

    suspend fun listFolder(
        folderId: String?,
        sortBy: String = "created_at",
        order: String = "desc",
        filterType: String = "all",
        offset: Int = 0,
        limit: Int = 50
    ): Result<FolderListResponse> = withContext(Dispatchers.IO) {
        try {
            val params = mutableMapOf<String, Any?>(
                "folder_id" to folderId,
                "sort_by" to sortBy,
                "order" to order,
                "filter_type" to filterType,
                "offset" to offset,
                "limit" to limit
            )
            val res = api.listFolder(params)
            if (res.isSuccessful && res.body() != null) {
                val data = res.body()!!

                // Update Room Cache for offline browsing
                val folderEntities = data.folders.map {
                    CachedFolderEntity(
                        id = it.id,
                        name = it.name,
                        parentId = folderId,
                        itemCount = it.item_count,
                        size = it.size,
                        isHidden = it.is_hidden,
                        isLocked = it.is_locked,
                        modifiedAt = it.modified_at,
                        createdAt = it.created_at
                    )
                }
                val fileEntities = data.files.map {
                    CachedFileEntity(
                        id = it.id,
                        name = it.name,
                        folderId = folderId,
                        size = it.size,
                        mime = it.effectiveMime,
                        isHidden = it.is_hidden,
                        isStarred = it.is_starred,
                        modifiedAt = it.modified_at,
                        createdAt = it.created_at,
                        encryptionLane = it.encryption_lane,
                        encryptedMetadata = it.encrypted_metadata,
                        metadataIv = it.metadata_iv,
                        serverShare = it.server_share,
                        encryptedClientShare = it.encrypted_client_share,
                        encryptedId = it.encrypted_id,
                        hasThumb = it.has_thumb,
                        thumbMime = it.thumb_mime,
                        thumbIv = it.thumb_iv
                    )
                }
                database.folderDao().clearFoldersInParent(folderId)
                database.fileDao().clearFilesInFolder(folderId)
                database.folderDao().insertFolders(folderEntities)
                database.fileDao().insertFiles(fileEntities)

                Result.success(data)
            } else {
                Result.failure(Exception("Failed to fetch folder list (${res.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFolder(name: String, parentId: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            val res = api.createFolder(mapOf("name" to name, "parent_id" to parentId))
            if (res.isSuccessful && res.body() != null) {
                val id = res.body()!!["folder_id"]?.toString() ?: ""
                Result.success(id)
            } else {
                Result.failure(Exception("Failed to create folder"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameFolder(folderId: String, newName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.renameFolder(mapOf("folder_id" to folderId, "name" to newName))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun trashFolder(folderId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.trashFolder(mapOf("folder_id" to folderId, "trashed" to true))
            database.folderDao().deleteFolder(folderId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFolderPermanently(folderId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.deleteFolder(mapOf("folder_id" to folderId))
            database.folderDao().deleteFolder(folderId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.renameFile(mapOf("file_id" to fileId, "name" to newName))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun trashFile(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.trashFile(mapOf("file_id" to fileId, "trashed" to true))
            database.fileDao().deleteFile(fileId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFilePermanently(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.deleteFile(mapOf("file_id" to fileId))
            database.fileDao().deleteFile(fileId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleStar(itemId: String, kind: String = "file"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.toggleStar(mapOf("item_id" to itemId, "kind" to kind))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listStarred(): Result<FolderListResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.listStarred(emptyMap())
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("Failed to load starred items"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listTrash(): Result<TrashListResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.listTrash()
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("Failed to load trash"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun emptyTrash(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.emptyTrash()
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listPics(): Result<PicsListResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.listPics()
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("RapidxPics is exclusively available for Ultra subscribers or Admin."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePic(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.deletePic(fileId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun overridePlan(newPlan: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.overridePlan(mapOf("plan" to newPlan))
            getProfile(forceRefresh = true)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listSessions(): Result<List<ActiveSession>> = withContext(Dispatchers.IO) {
        try {
            val res = api.listSessions()
            Result.success(res.body()?.sessions ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun terminateSession(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.terminateSession(sessionId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun terminateOtherSessions(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.terminateOtherSessions()
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listByosTokens(): Result<ByosTokensResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.listByosTokens()
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("Failed to load BYOS bot tokens"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setSessionTtl(days: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.setSessionTtl(mapOf("auto_terminate_days" to days))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun terminateInactiveSessions(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.terminateInactiveSessions()
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTelegramLinkToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val res = api.getTelegramLinkToken()
            val url = res.body()?.get("url")
            if (res.isSuccessful && !url.isNullOrBlank()) {
                Result.success(url)
            } else {
                Result.failure(Exception("Failed to get Telegram link token"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unlinkTelegram(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.unlinkTelegram()
            if (res.isSuccessful) {
                getProfile(forceRefresh = true)
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to unlink Telegram"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyByosChannels(channelId: String, backupChannelId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf("channel_id" to channelId.trim())
            if (!backupChannelId.isNullOrBlank()) {
                map["backup_channel_id"] = backupChannelId.trim()
            }
            val res = api.verifyByosChannels(map)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveByosSettings(enabled: Boolean, channelId: String?, backupChannelId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>(
                "byos_enabled" to enabled,
                "channel_id" to channelId?.trim()?.ifEmpty { null },
                "backup_channel_id" to backupChannelId?.trim()?.ifEmpty { null }
            )
            val res = api.saveByosSettings(map)
            if (res.isSuccessful) {
                getProfile(forceRefresh = true)
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to save BYOS settings"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyByosToken(botToken: String, channelId: String? = null, backupChannelId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf("bot_token" to botToken.trim())
            if (!channelId.isNullOrBlank()) map["channel_id"] = channelId.trim()
            if (!backupChannelId.isNullOrBlank()) map["backup_channel_id"] = backupChannelId.trim()
            val res = api.verifyByosToken(map)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteByosToken(tokenId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.deleteByosToken(tokenId)
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUserProfile(displayName: String?, username: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, String>()
            if (!displayName.isNullOrBlank()) map["display_name"] = displayName.trim()
            if (!username.isNullOrBlank()) map["username"] = username.trim()
            val res = api.updateProfile(map)
            if (res.isSuccessful) {
                getProfile(forceRefresh = true)
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to update profile"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listActiveShares(): Result<SharesActiveResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.listActiveShares()
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("Failed to load active shares"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeShare(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.revokeShare(mapOf("token" to token))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeShareItem(item: ShareItem): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = if (item.isFolder) {
                val fId = item.folder_id ?: item.id
                api.setFolderPublic(mapOf("folder_id" to fId, "is_public" to false))
            } else {
                val fileId = item.file_id ?: item.id
                api.setFilePublic(mapOf("file_id" to fileId, "is_public" to false))
            }
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFile(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.trashFile(mapOf("file_id" to fileId, "trashed" to false))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFolder(folderId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.trashFolder(mapOf("folder_id" to folderId, "trashed" to false))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun moveFile(fileId: String, targetFolderId: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.moveFile(mapOf("file_id" to fileId, "folder_id" to targetFolderId))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun moveFolder(folderId: String, targetParentId: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.moveFolder(mapOf("folder_id" to folderId, "parent_id" to targetParentId))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun copyFile(fileId: String, targetFolderId: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.copyFile(mapOf("file_id" to fileId, "folder_id" to targetFolderId))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createShare(
        fileId: String,
        expiresInHours: Float? = null,
        maxDownloads: Int? = null,
        password: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>("file_id" to fileId)
            if (expiresInHours != null && expiresInHours > 0) map["expires_in_hours"] = expiresInHours
            if (maxDownloads != null && maxDownloads > 0) map["max_downloads"] = maxDownloads
            if (!password.isNullOrBlank()) map["password"] = password.trim()

            val res = api.createShare(map)
            val body = res.body()
            val token = body?.get("token")?.toString() ?: body?.get("share_url")?.toString() ?: ""
            if (res.isSuccessful && token.isNotEmpty()) {
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to generate share link"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listSharedWithMe(): Result<SharedWithMeResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.listSharedWithMe()
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception("Failed to load shared with me"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeCollaborator(permissionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.removeCollaborator(mapOf("permission_id" to permissionId))
            Result.success(res.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
