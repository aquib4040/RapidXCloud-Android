package in.rapidxcloud.drive.transfer

/**
 * Transfer configuration and fallback constants matching rapidxcloud.in web client:
 * web-new/src/lib/mtcute/direct-upload.ts and web-new/src/lib/mtcute/chunk-downloader.ts
 */
object TransferConstants {
    /**
     * Exact single-document threshold: 2,050,851,897 bytes (~1.91 GiB / 2 GB).
     *
     * In Telegram MTProto, single documents cannot exceed 2000 MB.
     * Files exceeding this threshold MUST bypass any client-side direct WebSocket/MTProto
     * connection and route strictly through the backend cluster for server-side chunked split handling.
     */
    const val SPLIT_LIMIT_BYTES: Long = 2050851897L

    /**
     * Chunk size for backend chunked upload to safely stay below Cloudflare 100 MB body cap.
     */
    const val BACKEND_CHUNK_SIZE_BYTES: Int = 85 * 1024 * 1024 // 85 MB

    /**
     * Returns true if file exceeds the single document limit and requires backend routing.
     */
    fun isLargeFileBackendRequired(fileSizeBytes: Long): Boolean {
        return fileSizeBytes > SPLIT_LIMIT_BYTES
    }
}
