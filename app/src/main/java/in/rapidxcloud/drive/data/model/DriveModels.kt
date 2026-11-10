package in.rapidxcloud.drive.data.model

import com.google.gson.annotations.SerializedName

data class FolderItem(
    val id: String,
    val name: String,
    val parent_id: String? = null,
    val item_count: Int = 0,
    val size: Long = 0L,
    val is_hidden: Boolean = false,
    val is_locked: Boolean = false,
    val modified_at: String? = null,
    val created_at: String? = null
)

data class FileItem(
    val id: String,
    val name: String,
    val folder_id: String? = null,
    val size: Long = 0L,
    val mime: String? = null,
    val mime_type: String? = null,
    val is_hidden: Boolean = false,
    val is_starred: Boolean = false,
    val modified_at: String? = null,
    val created_at: String? = null,
    val encryption_lane: String = "none", // "none", "envelope", "e2e"
    val encrypted_metadata: String? = null,
    val metadata_iv: String? = null,
    val server_share: String? = null,
    val encrypted_client_share: String? = null,
    val manifest: Any? = null,
    val encrypted_id: String? = null,
    val upload_status: String? = null,
    val has_thumb: Boolean = false,
    val thumb_size: Long = 0L,
    val thumb_mime: String? = null,
    val thumb_iv: String? = null,
    val message_id: Long? = null,
    val chat_id: Long? = null,
    val share_token: String? = null
) {
    val effectiveMime: String get() = mime ?: mime_type ?: "application/octet-stream"
    val effectiveKey: String get() = encrypted_id ?: id
}

data class BreadcrumbItem(
    val id: String?,
    val name: String
)

data class FolderListResponse(
    val status: String = "ok",
    val folder: FolderItem? = null,
    val folders: List<FolderItem> = emptyList(),
    val files: List<FileItem> = emptyList(),
    val breadcrumb: List<BreadcrumbItem>? = null,
    val path: List<BreadcrumbItem>? = null,
    val storage: StorageUsage? = null,
    val total_count: Int = 0,
    val has_more: Boolean = false,
    val next_offset: Int? = null
)

data class StorageUsage(
    val used: Long = 0L,
    val total: Long = 0L
)

data class TrashListResponse(
    val status: String = "ok",
    val folders: List<FolderItem> = emptyList(),
    val files: List<FileItem> = emptyList()
)

data class QuotaMetric(
    val used_bytes: Long = 0L,
    val limit_bytes: Long = 0L,
    val left_bytes: Long = 0L,
    val percent: Float = 0f,
    val resets_at: String = "",
    val resets_in_human: String? = null,
    val is_unlimited: Boolean = false
)

data class DirectTgUsage(
    val used_bytes: Long = 0L,
    val rolling_24h_bytes: Long = 0L,
    val is_unlimited: Boolean = true,
    val quota_consumed_bytes: Long = 0L
)

data class AccountQuotas(
    val upload: QuotaMetric = QuotaMetric(),
    val download: QuotaMetric = QuotaMetric(),
    val direct_tg: DirectTgUsage = DirectTgUsage()
)

data class PlanLimits(
    val tier: String = "starter",
    val name: String = "Starter",
    val price_inr: Int = 0,
    val max_file_size: Long = 2147483648L, // 2 GB
    val daily_upload_limit: Long = 10737418240L, // 10 GB
    val max_concurrent_uploads: Int = 2,
    val daily_download_bandwidth: Long = 21474836480L, // 20 GB
    val download_workers: Int = 1,
    val leech_file_limit: Long = 2147483648L,
    val max_bot_tokens: Int = 8,
    val can_rename_leech: Boolean = false,
    val can_edit_metadata: Boolean = false,
    val can_pin_share: Boolean = false,
    val can_expiry_share: Boolean = false,
    val can_gdrive_sync: Boolean = false
)

data class UserProfile(
    val id: String,
    val account_no: Long? = null,
    val tg_user_id: Long? = null,
    val username: String? = null,
    val email: String? = null,
    val display_name: String? = null,
    val avatar_data: String? = null,
    val is_admin: Boolean = false,
    val storage_used: Long = 0L,
    val created_at: String = "",
    val channel_id: String? = null,
    val backup_channel_id: String? = null,
    val byos_enabled: Boolean = false,
    val e2e_enabled: Boolean = true,
    val enc_salt: String? = null,
    val auto_terminate_days: Int = 30,
    val bot_token_count: Int = 0,
    val plan: String = "starter",
    val plan_limits: PlanLimits? = null,
    val quotas: AccountQuotas? = null,
    val storage_breakdown: StorageBreakdown? = null
)

data class StorageBreakdown(
    val video: Long = 0L,
    val audio: Long = 0L,
    val image: Long = 0L,
    val document: Long = 0L,
    val other: Long = 0L
)

data class UserProfileResponse(
    val status: String,
    val user: UserProfile
)

data class AuthLoginResponse(
    val status: String,
    val access_token: String? = null,
    val refresh_token: String? = null,
    val user: UserProfile? = null,
    val detail: String? = null
)

data class WrappedSecret(
    val data: String, // base64(IV || ciphertext || tag)
    val salt: String  // base64 salt
)

data class KeyBag(
    val wrapped_pw: WrappedSecret,
    val wrapped_recovery: WrappedSecret
)

data class KeyBagResponse(
    val status: String = "ok",
    val key_bag: KeyBag? = null
)

data class E2EChunk(
    val index: Int,
    val plain_start: Long,
    val plain_end: Long,
    val cipher_start: Long,
    val cipher_end: Long
)

data class E2EManifest(
    val total_size: Long,
    val chunk_plaintext: Long,
    val chunks: List<E2EChunk>
)

data class DownloadMetadata(
    val download_url: String,
    val is_direct: Boolean = false,
    val size: Long = 0L
)

data class PicItem(
    val id: String,
    val name: String,
    val size: Long = 0L,
    val size_human: String = "",
    val created_at: String? = null,
    val token: String = "",
    val viewer_url: String = "",
    val direct_url: String = "",
    val embeds: Map<String, String> = emptyMap()
)

data class PicsListResponse(
    val status: String = "ok",
    val count: Int = 0,
    val images: List<PicItem> = emptyList()
)

data class ActiveSession(
    val id: String,
    val device_info: String = "",
    val browser: String = "",
    val os: String = "",
    val device_type: String = "mobile",
    val ip_address: String = "",
    val created_at: String = "",
    val last_active_at: String = "",
    val is_current: Boolean = false
)

data class SessionsResponse(
    val status: String = "ok",
    val sessions: List<ActiveSession> = emptyList()
)

data class BotToken(
    val id: String,
    val token_preview: String,
    val bot_username: String? = null,
    val is_healthy: Boolean = true,
    val created_at: String = ""
)

data class ByosTokensResponse(
    val status: String = "ok",
    val tokens: List<BotToken> = emptyList(),
    val max_tokens: Int = 8,
    val current_count: Int = 0,
    val can_add: Boolean = true
)

data class ShareItem(
    val id: String,
    val file_id: String? = null,
    val folder_id: String? = null,
    val file_name: String? = null,
    val folder_name: String? = null,
    val token: String = "",
    val share_url: String = "",
    val downloads: Int = 0,
    val max_downloads: Int? = null,
    val created_at: String = "",
    val expires_at: String? = null
)

data class SharesActiveResponse(
    val status: String = "ok",
    val files: List<ShareItem> = emptyList(),
    val folders: List<ShareItem> = emptyList()
)

data class MediaStream(
    val index: Int,
    val type: String, // "audio" | "subtitle"
    val codec: String = "",
    val language: String = "",
    val title: String = ""
)

data class LeechFileInfo(
    val filename: String,
    val size: Long = 0L,
    val streams: List<MediaStream> = emptyList()
)

data class LeechInfoResponse(
    val status: String = "ok",
    val message: String? = null,
    val data: LeechFileInfo? = null
)

data class ChunkStatusResponse(
    val status: String = "ok",
    val received: List<Int> = emptyList()
)

data class UploadCompleteResponse(
    val status: String = "ok",
    val upload_id: String? = null,
    val file_id: String? = null,
    val encrypted_id: String? = null,
    val detail: String? = null
)

data class ByosSession(
    val bot_id: String,
    val session_string: String? = null
)

data class ByosEnvelope(
    val status: String? = null,
    val api_id: Int? = null,
    val api_hash: String? = null,
    val tier: String? = "starter",
    val channel_id: Long? = null,
    val channel_access_hash: Long? = null,
    val active_hot_connections: Int? = 2,
    val download_workers: Int? = 1,
    val sessions: List<ByosSession>? = null,
    val tokens: List<String>? = null
)

data class GenericStatusResponse(
    val status: String = "ok",
    val message: String? = null,
    val detail: String? = null
)
