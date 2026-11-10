package `in`.rapidxcloud.drive.transfer

import android.content.Context
import android.util.Log
import `in`.rapidxcloud.drive.crypto.AesChunkStreamer
import `in`.rapidxcloud.drive.crypto.DualKeySplit
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.E2EManifest
import `in`.rapidxcloud.drive.data.model.FileItem
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * High-Speed Multi-Connection Segmented Downloader (aria2 architecture).
 *
 * Why this is possible on Android but not in Web Browsers:
 * 1. Web browsers lack raw random disk access (RandomAccessFile / FileChannel) and accumulate
 *    chunks in RAM, causing browser tab crashes on multi-GB files.
 * 2. Mobile clients have POSIX filesystem access, allowing pre-allocated sparse files and concurrent
 *    FileChannel.write() across N parallel HTTP Range streams directly into dl.rapidxcloud.in.
 * 3. Bypasses single-TCP congestion windows to saturate full 5G / Wi-Fi line bandwidth via Cloudflare CDN.
 */
class AriaSegmentDownloader(
    private val context: Context,
    private val api: CanonDriveApi
) {
    companion object {
        private const val TAG = "AriaDownloader"
        private const val MIN_SEGMENT_SIZE = 4 * 1024 * 1024L // 4 MiB min segment for multi-connection
        private const val BUFFER_SIZE = 128 * 1024 // 128 KiB per connection read buffer
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class DownloadSpeed(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val activeWorkers: Int,
        val percentage: Float
    )

    /**
     * Downloads a file using parallel segmented connections on dl.rapidxcloud.in (aria2 model).
     *
     * @param fileItem The file metadata
     * @param destFile Local destination file on device storage
     * @param maxWorkers Concurrency threads (e.g. 2 for Starter, 4 for Pro, 8 for Ultra)
     * @param onProgress Speed and progress callback
     */
    suspend fun downloadAccelerated(
        fileItem: FileItem,
        destFile: File,
        maxWorkers: Int = 4,
        onProgress: (DownloadSpeed) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val fileId = fileItem.encrypted_id ?: fileItem.id
        val isE2E = fileItem.encryption_lane == "e2e" && fileItem.encrypted_client_share != null
        val encKey = SessionKeyStore.getEncKey()

        // 1. Resolve direct CDN URL on dl.rapidxcloud.in
        val directUrl = resolveDirectUrl(fileId)
        val totalSize = fileItem.size.takeIf { it > 0 } ?: probeFileSize(directUrl)

        Log.i(TAG, "Starting accelerated aria2-style download: ${fileItem.name} ($totalSize bytes, $maxWorkers workers) from $directUrl")

        val targetFile = if (isE2E) {
            File(context.cacheDir, "e2e_enc_${System.currentTimeMillis()}.tmp")
        } else {
            destFile
        }

        try {
            // If file is very small or server doesn't report size, fallback to single stream
            if (totalSize < MIN_SEGMENT_SIZE || maxWorkers <= 1) {
                downloadSingleStream(directUrl, targetFile, totalSize, onProgress)
            } else {
                downloadMultiSegment(directUrl, targetFile, totalSize, maxWorkers, onProgress)
            }

            // If file is E2E, decrypt from the downloaded temp ciphertext
            if (isE2E && encKey != null && fileItem.encrypted_client_share != null) {
                Log.i(TAG, "Decrypting E2E chunks for ${fileItem.name}...")
                val clientShare = DualKeySplit.fromB64(fileItem.encrypted_client_share)
                val serverShare = fileItem.server_share?.let { DualKeySplit.fromB64(it) }
                val fileKey = DualKeySplit.recoverFileKey(encKey, clientShare, serverShare)

                val manifest = fileItem.manifest?.let {
                    val json = com.google.gson.Gson().toJson(it)
                    com.google.gson.Gson().fromJson(json, E2EManifest::class.java)
                } ?: E2EManifest(fileItem.size, AesChunkStreamer.PLAINTEXT_CHUNK_SIZE.toLong(), emptyList())

                targetFile.inputStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        AesChunkStreamer.decryptStreamToOutput(input, output, fileKey, manifest) { _, _ -> }
                    }
                }
            }

            return@withContext destFile
        } finally {
            if (isE2E && targetFile.exists()) {
                targetFile.delete()
            }
        }
    }

    private suspend fun resolveDirectUrl(fileId: String): String {
        return try {
            val res = api.getDownloadLink(mapOf("encrypted_id" to fileId))
            if (res.isSuccessful && res.body()?.download_url != null) {
                val url = res.body()!!.download_url
                if (url.startsWith("http")) url else "${SessionKeyStore.getAuthToken()?.let { "" } ?: ""}$url"
            } else {
                "https://drive.rapidxcloud.in/file/$fileId?dl=1"
            }
        } catch (e: Exception) {
            "https://drive.rapidxcloud.in/file/$fileId?dl=1"
        }
    }

    private fun probeFileSize(url: String): Long {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "RapidXCloud-Android-AriaEngine/1.0")
                .build()
            httpClient.newCall(req).execute().use { res ->
                res.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private suspend fun downloadMultiSegment(
        url: String,
        destFile: File,
        totalBytes: Long,
        numWorkers: Int,
        onProgress: (DownloadSpeed) -> Unit
    ) = coroutineScope {
        if (!destFile.parentFile!!.exists()) destFile.parentFile!!.mkdirs()

        // 1. Pre-allocate disk space (sparse/random-access file)
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val segmentSize = (totalBytes + numWorkers - 1) / numWorkers
        val totalDownloaded = AtomicLong(0L)
        var lastSnapshotTime = System.currentTimeMillis()
        var lastSnapshotBytes = 0L

        // Progress monitor coroutine
        val monitorJob = launch {
            while (isActive) {
                delay(500L)
                val current = totalDownloaded.get()
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastSnapshotTime) / 1000.0
                val speed = if (elapsedSec > 0) ((current - lastSnapshotBytes) / elapsedSec).toLong() else 0L

                lastSnapshotTime = now
                lastSnapshotBytes = current

                val ratio = if (totalBytes > 0) current.toFloat() / totalBytes else 0f
                onProgress(
                    DownloadSpeed(
                        bytesDownloaded = current,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speed,
                        activeWorkers = numWorkers,
                        percentage = ratio.coerceIn(0f, 1f)
                    )
                )
            }
        }

        try {
            // 2. Launch concurrent Range workers
            val workers = (0 until numWorkers).map { workerIndex ->
                val startByte = workerIndex * segmentSize
                val endByte = min((workerIndex + 1) * segmentSize - 1, totalBytes - 1)

                if (startByte <= endByte) {
                    async(Dispatchers.IO) {
                        downloadSegment(url, destFile, startByte, endByte, totalDownloaded)
                    }
                } else {
                    async { }
                }
            }

            workers.awaitAll()
        } finally {
            monitorJob.cancel()
        }
    }

    private fun downloadSegment(
        url: String,
        destFile: File,
        startByte: Long,
        endByte: Long,
        totalCounter: AtomicLong
    ) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$startByte-$endByte")
            .header("User-Agent", "RapidXCloud-Android-AriaEngine/1.0")
            .build()

        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("Segment HTTP ${response.code} for range $startByte-$endByte")
            }

            val body = response.body ?: throw IllegalStateException("Empty segment body")
            val raf = RandomAccessFile(destFile, "rw")
            val channel = raf.channel

            try {
                var writeOffset = startByte
                val buffer = ByteArray(BUFFER_SIZE)
                val byteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                val stream = body.byteStream()

                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    byteBuffer.clear()
                    byteBuffer.put(buffer, 0, read)
                    byteBuffer.flip()

                    while (byteBuffer.hasRemaining()) {
                        writeOffset += channel.write(byteBuffer, writeOffset)
                    }

                    totalCounter.addAndGet(read.toLong())
                }
            } finally {
                channel.close()
                raf.close()
            }
        }
    }

    private fun downloadSingleStream(
        url: String,
        destFile: File,
        totalBytes: Long,
        onProgress: (DownloadSpeed) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "RapidXCloud-Android-AriaEngine/1.0")
            .build()

        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} downloading single stream")
            }

            val body = response.body ?: throw IllegalStateException("Empty body")
            var downloaded = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            FileOutputStream(destFile).use { output ->
                body.byteStream().use { input ->
                    var r: Int
                    while (input.read(buffer).also { r = it } != -1) {
                        output.write(buffer, 0, r)
                        downloaded += r
                        val ratio = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                        onProgress(
                            DownloadSpeed(
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes,
                                speedBytesPerSec = 0L,
                                activeWorkers = 1,
                                percentage = ratio
                            )
                        )
                    }
                }
            }
        }
    }
}
