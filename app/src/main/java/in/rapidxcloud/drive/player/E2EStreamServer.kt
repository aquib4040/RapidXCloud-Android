package `in`.rapidxcloud.drive.player

import android.content.Context
import android.util.Log
import android.util.LruCache
import `in`.rapidxcloud.drive.crypto.AesChunkStreamer
import `in`.rapidxcloud.drive.crypto.DualKeySplit
import `in`.rapidxcloud.drive.crypto.NativeCrypto
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.E2EChunk
import `in`.rapidxcloud.drive.data.model.E2EManifest
import `in`.rapidxcloud.drive.data.model.FileItem
import `in`.rapidxcloud.drive.data.repository.SettingsRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * E2EStreamServer — Ultra-low latency local HTTP proxy for chunk-based E2EE video streaming.
 *
 * Runs on 127.0.0.1 and handles HTTP/1.1 Range requests (RFC 7233) from internal LibVLC:
 * - Maps byte range requests to 4MiB AES-256-GCM chunk boundaries on-the-fly.
 * - Decrypts chunks in-RAM using Rust NativeCrypto with zero disk overhead.
 * - LRU memory cache for recent chunks ensures instant seeking and stutter-free playback.
 * - Proxies non-E2EE streams transparently with auth headers.
 */
object E2EStreamServer {
    private const val TAG = "E2EStreamServer"
    private var serverSocket: ServerSocket? = null
    var serverPort: Int = 0
        private set

    private var serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeStreams = ConcurrentHashMap<String, StreamSession>()

    // In-memory 16 MiB chunk cache (stores up to 4 decrypted 4MiB chunks for seamless seeking)
    private val chunkCache = object : LruCache<String, ByteArray>(4) {
        override fun sizeOf(key: String, value: ByteArray): Int = 1
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class StreamSession(
        val file: FileItem,
        val fileKey: ByteArray?,
        val directUrl: String,
        val manifest: E2EManifest?,
        val totalSize: Long,
        val mimeType: String
    )

    @Synchronized
    fun start() {
        if (serverSocket != null && !serverSocket!!.isClosed) return

        try {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverPort = serverSocket!!.localPort
            Log.i(TAG, "E2EStreamServer active on 127.0.0.1:$serverPort")

            serverScope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start E2EStreamServer", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        chunkCache.evictAll()
        activeStreams.clear()
    }

    suspend fun registerStream(
        context: Context,
        api: CanonDriveApi,
        file: FileItem
    ): String {
        start()

        val fileId = file.encrypted_id ?: file.id
        val isE2E = file.encryption_lane == "e2e" && file.encrypted_client_share != null
        val encKey = SessionKeyStore.getEncKey()

        val fileKeyBytes = if (isE2E && encKey != null && file.encrypted_client_share != null) {
            val clientShare = DualKeySplit.fromB64(file.encrypted_client_share)
            val serverShare = file.server_share?.let { DualKeySplit.fromB64(it) }
            val secretKey = DualKeySplit.recoverFileKey(encKey, clientShare, serverShare)
            secretKey.encoded
        } else {
            null
        }

        val serverBase = SettingsRepository(context).getServerUrl().trimEnd('/')
        val directUrl = resolveUrl(api, serverBase, fileId)

        val manifest: E2EManifest? = if (isE2E) {
            file.manifest?.let {
                val json = com.google.gson.Gson().toJson(it)
                try {
                    com.google.gson.Gson().fromJson(json, E2EManifest::class.java)
                } catch (_: Exception) { null }
            } ?: run {
                val totalPlain = file.size
                val chunkPlain = AesChunkStreamer.PLAINTEXT_CHUNK_SIZE.toLong()
                val chunkCount = if (totalPlain <= 0) 1 else ((totalPlain + chunkPlain - 1) / chunkPlain).toInt()
                val chunks = ArrayList<E2EChunk>(chunkCount)
                var pOff = 0L
                var cOff = 0L
                for (i in 0 until chunkCount) {
                    val pEnd = min(pOff + chunkPlain - 1, totalPlain - 1)
                    val pLen = pEnd - pOff + 1
                    val cLen = 12 + pLen + 16
                    chunks.add(E2EChunk(i, pOff, pEnd, cOff, cOff + cLen - 1))
                    pOff += pLen
                    cOff += cLen
                }
                E2EManifest(totalPlain, chunkPlain, chunks)
            }
        } else {
            null
        }

        val session = StreamSession(
            file = file,
            fileKey = fileKeyBytes,
            directUrl = directUrl,
            manifest = manifest,
            totalSize = file.size,
            mimeType = file.effectiveMime
        )
        activeStreams[fileId] = session

        return "http://127.0.0.1:$serverPort/stream?id=$fileId"
    }

    private suspend fun resolveUrl(api: CanonDriveApi, serverBase: String, fileId: String): String {
        return try {
            val res = api.getDownloadLink(mapOf("encrypted_id" to fileId))
            if (res.isSuccessful && res.body()?.download_url != null) {
                val u = res.body()!!.download_url
                if (u.startsWith("http://") || u.startsWith("https://")) u else "$serverBase${if (u.startsWith("/")) "" else "/"}$u"
            } else {
                "$serverBase/file/$fileId?dl=1"
            }
        } catch (_: Exception) {
            "$serverBase/file/$fileId?dl=1"
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendError(output, 400, "Bad Request")
                return
            }

            val path = parts[1]
            val fileId = path.substringAfter("id=", "").substringBefore("&")
            val session = activeStreams[fileId]
            if (session == null) {
                sendError(output, 404, "Stream Not Found")
                return
            }

            var rangeHeader: String? = null
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                if (line!!.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line!!.substringAfter(":").trim()
                }
            }

            if (session.fileKey != null && session.manifest != null) {
                serveE2ERange(output, session, rangeHeader)
            } else {
                proxyPlaintextRange(output, session, rangeHeader)
            }
        } catch (e: Exception) {
            // Socket was closed by client seek / exit
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveE2ERange(output: OutputStream, session: StreamSession, rangeHeader: String?) {
        val totalSize = session.totalSize
        var start = 0L
        var end = totalSize - 1

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = spec.indexOf('-')
            if (dashIdx != -1) {
                val sStr = spec.substring(0, dashIdx).trim()
                val eStr = spec.substring(dashIdx + 1).trim()
                if (sStr.isNotEmpty()) start = sStr.toLongOrNull() ?: 0L
                if (eStr.isNotEmpty()) end = eStr.toLongOrNull() ?: (totalSize - 1)
            }
        }

        start = start.coerceIn(0L, totalSize - 1)
        end = end.coerceIn(start, totalSize - 1)
        val contentLength = end - start + 1

        val headers = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: ${session.mimeType}\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Range: bytes $start-$end/$totalSize\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()

        val manifest = session.manifest ?: return
        val fileKey = session.fileKey ?: return
        val totalChunks = manifest.chunks.size

        // Find intersecting chunks
        for (chunk in manifest.chunks) {
            if (chunk.plain_end < start || chunk.plain_start > end) continue

            val decrypted = getOrDecryptChunk(session, chunk, fileKey, totalChunks) ?: break

            val sliceStart = (max(start, chunk.plain_start) - chunk.plain_start).toInt()
            val sliceEnd = (min(end, chunk.plain_end) - chunk.plain_start).toInt() + 1
            val len = sliceEnd - sliceStart

            if (len > 0 && sliceStart < decrypted.size) {
                output.write(decrypted, sliceStart, min(len, decrypted.size - sliceStart))
                output.flush()
            }
        }
    }

    private fun getOrDecryptChunk(
        session: StreamSession,
        chunk: E2EChunk,
        fileKeyBytes: ByteArray,
        totalChunks: Int
    ): ByteArray? {
        val cacheKey = "${session.file.id}_${chunk.index}"
        synchronized(chunkCache) {
            val cached = chunkCache.get(cacheKey)
            if (cached != null) return cached
        }

        // Fetch encrypted chunk range from remote edge
        val reqBuilder = Request.Builder()
            .url(session.directUrl)
            .header("Range", "bytes=${chunk.cipher_start}-${chunk.cipher_end}")
            .header("User-Agent", "RapidXCloud-Android-VlcEngine/1.0")

        SessionKeyStore.getAuthToken()?.let {
            reqBuilder.header("Authorization", "Bearer $it")
        }

        val resp = httpClient.newCall(reqBuilder.build()).execute()
        if (!resp.isSuccessful && resp.code != 206) {
            resp.close()
            return null
        }

        val sealed = resp.body?.bytes() ?: run { resp.close(); return null }
        val aad = AesChunkStreamer.chunkAAD(chunk.index, totalChunks)

        val plaintext = if (NativeCrypto.isAvailable) {
            NativeCrypto.decryptStreamChunk(fileKeyBytes, sealed, aad)
        } else {
            val secretKey = SecretKeySpec(fileKeyBytes, "AES")
            AesChunkStreamer.decryptChunk(secretKey, sealed, aad)
        }

        if (plaintext != null) {
            synchronized(chunkCache) {
                chunkCache.put(cacheKey, plaintext)
            }
        }
        return plaintext
    }

    private fun proxyPlaintextRange(output: OutputStream, session: StreamSession, rangeHeader: String?) {
        val reqBuilder = Request.Builder()
            .url(session.directUrl)
            .header("User-Agent", "RapidXCloud-Android-VlcEngine/1.0")

        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }
        SessionKeyStore.getAuthToken()?.let {
            reqBuilder.header("Authorization", "Bearer $it")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val code = response.code
            val statusLine = "HTTP/1.1 $code ${response.message}\r\n"
            output.write(statusLine.toByteArray(Charsets.US_ASCII))

            for (name in response.headers.names()) {
                val value = response.header(name)
                output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
            }
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()

            response.body?.byteStream()?.copyTo(output)
            output.flush()
        }
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val res = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n$message"
        output.write(res.toByteArray(Charsets.US_ASCII))
        output.flush()
    }
}

