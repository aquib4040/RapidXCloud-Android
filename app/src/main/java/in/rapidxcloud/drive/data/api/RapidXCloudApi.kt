package `in`.rapidxcloud.drive.data.api

import `in`.rapidxcloud.drive.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JvmSuppressWildcards
interface RapidXCloudApi {

    // ── Authentication ──────────────────────────────────────────────────────────
    @POST("/api/auth/login")
    suspend fun login(@Body body: Map<String, Any>): Response<AuthLoginResponse>

    @POST
    suspend fun supabaseSignIn(
        @Url url: String = "https://auth.rapidxcloud.in/auth/v1/token?grant_type=password",
        @Header("apikey") apiKey: String = "sb_publishable_U16BONYkiTZsBJIQeuX6Kw_IHWyksFj",
        @Body body: Map<String, Any>
    ): Response<Map<String, Any>>

    @POST
    suspend fun supabaseSignUp(
        @Url url: String = "https://auth.rapidxcloud.in/auth/v1/signup",
        @Header("apikey") apiKey: String = "sb_publishable_U16BONYkiTZsBJIQeuX6Kw_IHWyksFj",
        @Body body: Map<String, Any>
    ): Response<Map<String, Any>>

    @GET("/api/users/check-username")
    suspend fun checkUsernameAvailable(@Query("username") username: String): Response<Map<String, Any>>

    @POST("/api/auth/refresh")
    suspend fun refreshToken(@Body body: Map<String, String>): Response<AuthLoginResponse>

    @POST("/api/auth/logout")
    suspend fun logout(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @GET("/api/me")
    suspend fun getProfile(): Response<UserProfileResponse>

    @POST("/api/users/update-profile")
    suspend fun updateProfile(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @Multipart
    @POST("/api/users/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): Response<GenericStatusResponse>

    @DELETE("/api/users/avatar")
    suspend fun removeAvatar(): Response<GenericStatusResponse>

    // ── Folders & Files ─────────────────────────────────────────────────────────
    @POST("/api/folders/list")
    suspend fun listFolder(@Body params: Map<String, Any>): Response<FolderListResponse>

    @POST("/api/folders/create")
    suspend fun createFolder(@Body body: Map<String, Any>): Response<Map<String, Any>>

    @POST("/api/folders/rename")
    suspend fun renameFolder(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/folders/trash")
    suspend fun trashFolder(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/folders/delete")
    suspend fun deleteFolder(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/folders/move")
    suspend fun moveFolder(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/folders/hide-toggle")
    suspend fun toggleFolderHidden(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/files/rename")
    suspend fun renameFile(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/files/trash")
    suspend fun trashFile(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/files/delete")
    suspend fun deleteFile(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/files/move")
    suspend fun moveFile(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/files/copy")
    suspend fun copyFile(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/files/hide-toggle")
    suspend fun toggleFileHidden(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/starred/list")
    suspend fun listStarred(@Body body: Map<String, Any>): Response<FolderListResponse>

    @POST("/api/starred/toggle")
    suspend fun toggleStar(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/trash/list")
    suspend fun listTrash(@Body body: Map<String, Any> = emptyMap()): Response<TrashListResponse>

    @POST("/api/trash/empty")
    suspend fun emptyTrash(@Body body: Map<String, Any> = emptyMap()): Response<GenericStatusResponse>

    // ── PIN Protected Vault ─────────────────────────────────────────────────────
    @POST("/api/vault/verify-pin")
    suspend fun verifyVaultPin(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/vault/reset-pin")
    suspend fun resetVaultPin(@Body body: Map<String, String>): Response<GenericStatusResponse>

    // ── Previews & Downloads ────────────────────────────────────────────────────
    @GET("/api/file/{id}/thumb")
    @Streaming
    suspend fun getThumbnail(@Path("id") id: String): Response<ResponseBody>

    @POST("/api/file/download-link")
    suspend fun getDownloadLink(@Body body: Map<String, Any>): Response<DownloadMetadata>

    @GET("/file/{id}")
    @Streaming
    suspend fun downloadDirectFile(@Path("id") id: String, @Query("dl") dl: Int = 1): Response<ResponseBody>

    // ── Shares ──────────────────────────────────────────────────────────────────
    @POST("/api/share/create")
    suspend fun createShare(@Body body: Map<String, Any>): Response<Map<String, Any>>

    @GET("/api/shares/active")
    suspend fun listActiveShares(): Response<SharesActiveResponse>

    @POST("/api/share/revoke")
    suspend fun revokeShare(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/files/set_public")
    suspend fun setFilePublic(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/folders/set_public")
    suspend fun setFolderPublic(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/shares/shared-with-me")
    suspend fun listSharedWithMe(@Body body: Map<String, Any> = emptyMap()): Response<SharedWithMeResponse>

    @POST("/api/share/remove-collaborator")
    suspend fun removeCollaborator(@Body body: Map<String, String>): Response<GenericStatusResponse>

    // ── E2E KeyBag & Settings ───────────────────────────────────────────────────
    @GET("/api/keybag")
    suspend fun getKeyBag(): Response<KeyBagResponse>

    @POST("/api/keybag/enroll")
    suspend fun enrollKeyBag(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/keybag/rewrap")
    suspend fun rewrapKeyBag(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @POST("/api/settings/e2e")
    suspend fun setE2ePreference(@Body body: Map<String, Boolean>): Response<GenericStatusResponse>

    @POST("/api/settings/session-ttl")
    suspend fun setSessionTtl(@Body body: Map<String, Int>): Response<GenericStatusResponse>

    // ── Sessions Management (Telegram-style) ────────────────────────────────────
    @GET("/api/sessions")
    suspend fun listSessions(): Response<SessionsResponse>

    @DELETE("/api/sessions/{session_id}")
    suspend fun terminateSession(@Path("session_id") sessionId: String): Response<GenericStatusResponse>

    @POST("/api/sessions/terminate_others")
    suspend fun terminateOtherSessions(): Response<GenericStatusResponse>

    @POST("/api/sessions/terminate_inactive")
    suspend fun terminateInactiveSessions(): Response<GenericStatusResponse>

    // ── Telegram & BYOS (Private Node) ──────────────────────────────────────────
    @POST("/api/auth/link-token")
    suspend fun getTelegramLinkToken(@Body body: Map<String, Any> = emptyMap()): Response<Map<String, String>>

    @POST("/api/auth/unlink-telegram")
    suspend fun unlinkTelegram(): Response<GenericStatusResponse>

    @GET("/api/byos/tokens")
    suspend fun listByosTokens(): Response<ByosTokensResponse>

    @POST("/api/byos/verify-token")
    suspend fun verifyByosToken(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/byos/verify-channels")
    suspend fun verifyByosChannels(@Body body: Map<String, String>): Response<GenericStatusResponse>

    @POST("/api/byos/settings")
    suspend fun saveByosSettings(@Body body: Map<String, Any>): Response<GenericStatusResponse>

    @DELETE("/api/byos/tokens/{token_id}")
    suspend fun deleteByosToken(@Path("token_id") tokenId: String): Response<GenericStatusResponse>

    @GET("/api/byos/token-envelope")
    suspend fun getByosTokenEnvelope(): Response<ByosEnvelope>

    @POST("/api/byos/heartbeat")
    suspend fun byosHeartbeat(): Response<GenericStatusResponse>

    // ── URL Leech ───────────────────────────────────────────────────────────────
    @POST("/api/getFileInfoFromUrl")
    suspend fun getFileInfoFromUrl(@Body body: Map<String, String>): Response<LeechInfoResponse>

    @POST("/api/startFileDownloadFromUrl")
    suspend fun startFileDownloadFromUrl(@Body body: Map<String, Any>): Response<Map<String, Any>>

    @POST("/api/getFileDownloadProgress")
    suspend fun getFileDownloadProgress(@Body body: Map<String, String>): Response<Map<String, Any>>

    // ── Photos (RapidxPics) ─────────────────────────────────────────────────────
    @GET("/api/pics/list")
    suspend fun listPics(): Response<PicsListResponse>

    @Multipart
    @POST("/api/pics/upload")
    suspend fun uploadPic(
        @Part file: MultipartBody.Part,
        @Part("title") title: RequestBody,
        @Part("auto_delete") autoDelete: RequestBody
    ): Response<GenericStatusResponse>

    @DELETE("/api/pics/delete/{file_id}")
    suspend fun deletePic(@Path("file_id") fileId: String): Response<GenericStatusResponse>

    // ── Uploads & Chunked Transfers ─────────────────────────────────────────────
    @Multipart
    @POST("/api/upload")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("folder_id") folderId: RequestBody?,
        @Part thumbnail: MultipartBody.Part? = null
    ): Response<UploadCompleteResponse>

    @GET("/api/upload/chunk/status")
    suspend fun getChunkStatus(@Query("session_id") sessionId: String): Response<ChunkStatusResponse>

    @Multipart
    @POST("/api/upload/chunk")
    suspend fun uploadChunk(
        @Part chunk: MultipartBody.Part,
        @Part("session_id") sessionId: RequestBody,
        @Part("chunk_index") chunkIndex: RequestBody,
        @Part("total_chunks") totalChunks: RequestBody
    ): Response<GenericStatusResponse>

    @POST("/api/upload/complete")
    suspend fun completeUpload(@Body body: Map<String, Any>): Response<UploadCompleteResponse>

    @Multipart
    @POST("/api/upload/e2e")
    suspend fun uploadE2e(
        @Part file: MultipartBody.Part,
        @Part("folder_id") folderId: RequestBody?,
        @Part("server_share") serverShare: RequestBody,
        @Part("encrypted_client_share") encryptedClientShare: RequestBody,
        @Part("encrypted_metadata") encryptedMetadata: RequestBody,
        @Part("metadata_iv") metadataIv: RequestBody,
        @Part("manifest") manifest: RequestBody,
        @Part("plaintext_size") plaintextSize: RequestBody,
        @Part thumbnail: MultipartBody.Part?,
        @Part("thumb_iv") thumbIv: RequestBody?
    ): Response<UploadCompleteResponse>

    @POST("/api/upload/e2e/complete")
    suspend fun completeE2eUpload(@Body body: Map<String, Any>): Response<UploadCompleteResponse>

    @POST("/api/byos/complete")
    suspend fun completeByosUpload(@Body body: Map<String, Any>): Response<UploadCompleteResponse>

    @POST("/api/user/plan_override")
    suspend fun overridePlan(@Body body: Map<String, String>): Response<GenericStatusResponse>
}

typealias CanonDriveApi = RapidXCloudApi
