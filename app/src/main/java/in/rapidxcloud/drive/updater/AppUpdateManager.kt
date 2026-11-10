package in.rapidxcloud.drive.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GitHubReleaseAsset> = emptyList()
)

data class GitHubReleaseAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("size") val size: Long
)

sealed class UpdateCheckResult {
    data class NoUpdate(val currentVersion: String) : UpdateCheckResult()
    data class Available(
        val version: String,
        val releaseNotes: String,
        val asset: GitHubReleaseAsset,
        val compatibleAbi: String
    ) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class DownloadProgress {
    object Idle : DownloadProgress()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percent: Int) : DownloadProgress()
    data class Success(val apkFile: File) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}

class AppUpdateManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    companion object {
        private const val GITHUB_REPO_API = "https://api.github.com/repos/aquib4040/RapidXCloud-Android/releases/latest"
        private const val NOTIFICATION_CHANNEL_ID = "rapidxcloud_updater_channel"
        private const val NOTIFICATION_ID = 4040
    }

    /**
     * Clean old updater cache files from disk
     */
    fun cleanOldUpdateFiles(context: Context) {
        try {
            val updateDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            updateDir.listFiles { _, name -> name.startsWith("rapidxcloud-update") && name.endsWith(".apk") }?.forEach {
                it.delete()
            }
        } catch (_: Exception) {}
    }

    /**
     * Checks GitHub API for the latest release and compares with the currently running app version
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_REPO_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "RapidXCloud-Android-App")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext UpdateCheckResult.Error("GitHub API responded with code: ${response.code}")
            }

            val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response from GitHub API")
            val release = gson.fromJson(body, GitHubRelease::class.java)

            val isNewer = isVersionNewer(currentVersion, release.tagName)
            if (!isNewer) {
                return@withContext UpdateCheckResult.NoUpdate(currentVersion)
            }

            val compatible = findBestCompatibleAsset(release.assets)
                ?: return@withContext UpdateCheckResult.Error("New version ${release.tagName} found, but no compatible APK asset was found for your architecture.")

            UpdateCheckResult.Available(
                version = release.tagName,
                releaseNotes = release.body ?: "Bug fixes and performance improvements.",
                asset = compatible.first,
                compatibleAbi = compatible.second
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    /**
     * Finds the most optimized APK asset matching the device's hardware ABI hierarchy
     */
    fun findBestCompatibleAsset(assets: List<GitHubReleaseAsset>): Pair<GitHubReleaseAsset, String>? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val deviceAbis = Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a")

        // 1. Check priority matches according to device supported ABIs
        for (abi in deviceAbis) {
            val match = apkAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (match != null) {
                return Pair(match, abi)
            }
        }

        // 2. Check universal APK fallback
        val universal = apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        if (universal != null) {
            return Pair(universal, "universal")
        }

        // 3. Fallback to any APK produced by release
        return Pair(apkAssets.first(), deviceAbis.firstOrNull() ?: "generic")
    }

    /**
     * Compares semver version tags: e.g. "v1.0.1" > "1.0.0"
     */
    fun isVersionNewer(current: String, remote: String): Boolean {
        val curParts = parseVersionParts(current)
        val remParts = parseVersionParts(remote)

        val maxLen = maxOf(curParts.size, remParts.size)
        for (i in 0 until maxLen) {
            val c = curParts.getOrElse(i) { 0 }
            val r = remParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun parseVersionParts(v: String): List<Int> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        return clean.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Downloads the APK file, displays live system notification progress, and launches package installer
     */
    suspend fun downloadAndInstall(
        context: Context,
        asset: GitHubReleaseAsset,
        onProgress: (DownloadProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        cleanOldUpdateFiles(context)

        val updateDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        if (!updateDir.exists()) updateDir.mkdirs()

        val destFile = File(updateDir, "rapidxcloud-update-${asset.name}")
        if (destFile.exists()) destFile.delete()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure Notification Channel exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress when updating RapidXCloud Android"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notifyBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Updating RapidXCloud")
            .setContentText("Connecting to release server...")
            .setOngoing(true)
            .setProgress(100, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        notificationManager.notify(NOTIFICATION_ID, notifyBuilder.build())

        try {
            val request = Request.Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", "RapidXCloud-Android-App")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed with HTTP ${response.code}")
            }

            val responseBody = response.body ?: throw IllegalStateException("Empty download payload")
            val totalBytes = if (asset.size > 0) asset.size else responseBody.contentLength()

            val inputStream = responseBody.byteStream()
            val outputStream = FileOutputStream(destFile)

            val buffer = ByteArray(32 * 1024)
            var bytesReadTotal = 0L
            var lastUpdateMs = System.currentTimeMillis()

            inputStream.use { input ->
                outputStream.use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesReadTotal += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs > 250 || bytesReadTotal == totalBytes) {
                            lastUpdateMs = now
                            val percent = if (totalBytes > 0) ((bytesReadTotal * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                            withContext(Dispatchers.Main) {
                                onProgress(DownloadProgress.Downloading(bytesReadTotal, totalBytes, percent))
                            }

                            val mbDownloaded = String.format("%.1f", bytesReadTotal / (1024.0 * 1024.0))
                            val mbTotal = String.format("%.1f", totalBytes / (1024.0 * 1024.0))

                            notifyBuilder
                                .setProgress(100, percent, false)
                                .setContentText("$mbDownloaded MB / $mbTotal MB ($percent%)")
                            notificationManager.notify(NOTIFICATION_ID, notifyBuilder.build())
                        }
                    }
                }
            }

            // Download Complete
            withContext(Dispatchers.Main) {
                onProgress(DownloadProgress.Success(destFile))
            }

            // Prepare install PendingIntent
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            notifyBuilder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update Downloaded")
                .setContentText("Tap to install the new version")
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            notificationManager.notify(NOTIFICATION_ID, notifyBuilder.build())

            // Direct trigger install on device
            withContext(Dispatchers.Main) {
                launchInstall(context, destFile)
            }

        } catch (e: Exception) {
            notificationManager.cancel(NOTIFICATION_ID)
            withContext(Dispatchers.Main) {
                onProgress(DownloadProgress.Failed(e.message ?: "Download failed"))
            }
        }
    }

    /**
     * Invokes the Android OS package installer with permission validation
     */
    fun launchInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        // On Android 8.0+ verify package install permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(installIntent)
    }
}
