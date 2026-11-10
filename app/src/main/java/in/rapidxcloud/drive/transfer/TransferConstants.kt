package `in`.rapidxcloud.drive.transfer

/**
 * Transfer configuration constants.
 */
object TransferConstants {
    /**
     * Single document threshold: ~1.91 GiB (2,050,851,897 bytes).
     * Files exceeding this threshold route through backend chunked split ingestion.
     */
    const val SPLIT_LIMIT_BYTES: Long = 2050851897L

    /**
     * Chunk size for backend chunked upload (10 MB per chunk to prevent mobile RAM pressure).
     */
    const val BACKEND_CHUNK_SIZE_BYTES: Int = 10 * 1024 * 1024 // 10 MB

    /**
     * Returns true if file exceeds the single document limit and requires backend routing.
     */
    fun isLargeFileBackendRequired(fileSizeBytes: Long): Boolean {
        return fileSizeBytes > SPLIT_LIMIT_BYTES
    }
}
