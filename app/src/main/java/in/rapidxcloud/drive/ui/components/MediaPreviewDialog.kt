package `in`.rapidxcloud.drive.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.model.FileItem
import `in`.rapidxcloud.drive.transfer.FileDownloader
import `in`.rapidxcloud.drive.transfer.ThumbnailCacheManager
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaPreviewDialog — Fullscreen zero-knowledge photo and video preview lightbox.
 *
 * Features:
 * - Instant 0ms thumbnail poster display while full file decrypts.
 * - In-RAM / cache on-the-fly decryption for Lane 1 (E2E) photos and videos.
 * - Interactive pinch-to-zoom and pan gestures for photos.
 * - Native video playback trigger via FileProvider.
 * - Direct download and share actions.
 */
@Composable
fun MediaPreviewDialog(
    file: FileItem,
    thumbnailManager: ThumbnailCacheManager,
    fileDownloader: FileDownloader,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullBitmap by remember(file.effectiveKey) { mutableStateOf<Bitmap?>(null) }
    var thumbnailBitmap by remember(file.effectiveKey) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(file.effectiveKey) { mutableStateOf(true) }
    var errorMessage by remember(file.effectiveKey) { mutableStateOf<String?>(null) }

    // Pinch-to-zoom & pan states
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val isVideo = file.effectiveMime.startsWith("video/") ||
            listOf("mp4", "mkv", "mov", "avi", "webm").any { file.displayName.endsWith(".$it", ignoreCase = true) }
    val isImage = file.effectiveMime.startsWith("image/") ||
            listOf("jpg", "jpeg", "png", "webp", "gif", "heic").any { file.displayName.endsWith(".$it", ignoreCase = true) } ||
            (!isVideo && (file.has_thumb || file.thumb_mime?.startsWith("image/") == true))

    // Load poster thumbnail + initiate high-res decryption
    LaunchedEffect(file.effectiveKey) {
        isLoading = true
        errorMessage = null

        // 1. Instant poster from thumbnail cache (fast low-res preview)
        val thumb = thumbnailManager.getThumbnailBitmap(file)
        thumbnailBitmap = thumb

        // 2. Fetch and decrypt full resolution original image in background
        if (isImage) {
            launch(Dispatchers.IO) {
                var downloadedFile: File? = null
                try {
                    downloadedFile = fileDownloader.downloadSingleFile(
                        fileItem = file,
                        subfolder = "previews",
                        showNotification = false
                    )
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(downloadedFile.absolutePath, options)
                    val (h, w) = options.outHeight to options.outWidth
                    var inSample = 1
                    if (h > 4096 || w > 4096) {
                        val maxDim = maxOf(h, w)
                        while ((maxDim / inSample) > 4096) {
                            inSample *= 2
                        }
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = inSample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = BitmapFactory.decodeFile(downloadedFile.absolutePath, decodeOpts)
                    withContext(Dispatchers.Main) {
                        if (decoded != null) {
                            fullBitmap = decoded
                        }
                        isLoading = false
                    }
                } catch (oom: OutOfMemoryError) {
                    Log.e("MediaPreview", "OutOfMemoryError in high-res decode, keeping thumbnail", oom)
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                } catch (e: Throwable) {
                    Log.e("MediaPreview", "Failed to load full image: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                } finally {
                    try {
                        if (downloadedFile != null && downloadedFile.exists()) {
                            downloadedFile.delete()
                        }
                    } catch (_: Throwable) {}
                }
            }
        } else {
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            // ── Top Action Bar ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        file.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatBytes(file.size),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        if (file.encryption_lane == "e2e") {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    "E2EE ZERO-KNOWLEDGE",
                                    color = Color(0xFF10B981),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Download Button
                IconButton(
                    onClick = {
                        scope.launch {
                            Toast.makeText(context, "Saving ${file.displayName} to Downloads...", Toast.LENGTH_SHORT).show()
                            try {
                                val saved = fileDownloader.downloadSingleFile(file) { _, _ -> }
                                Toast.makeText(context, "Saved to ${saved.name}", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download", tint = CyanAccent)
                }
            }

            // ── Main Content Viewport ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isImage -> {
                        val displayBmp = fullBitmap ?: thumbnailBitmap
                        if (displayBmp != null) {
                            Image(
                                bitmap = displayBmp.asImageBitmap(),
                                contentDescription = file.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(1f, 5f)
                                            if (scale > 1f) {
                                                offsetX += pan.x
                                                offsetY += pan.y
                                            } else {
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                        }
                                    }
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                    }
                            )
                        } else if (isLoading) {
                            CircularProgressIndicator(color = CyanAccent)
                        } else {
                            Icon(
                                Icons.Default.BrokenImage,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }

                    isVideo -> {
                        // Video Preview Card with Play Trigger
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161B22)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumbnailBitmap != null) {
                                Image(
                                    bitmap = thumbnailBitmap!!.asImageBitmap(),
                                    contentDescription = file.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Play Button Overlay: Launch Internal VLC Player
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val app = context.applicationContext as `in`.rapidxcloud.drive.RapidXCloudApp
                                            val streamUrl = `in`.rapidxcloud.drive.player.E2EStreamServer.registerStream(
                                                context = context,
                                                api = app.apiClient,
                                                file = file
                                            )
                                            `in`.rapidxcloud.drive.player.VlcPlayerActivity.start(
                                                context = context,
                                                streamUrl = streamUrl,
                                                fileName = file.displayName
                                            )
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play Video",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    else -> {
                        // Non-media document card
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(80.dp)
                            )
                            Text(
                                file.displayName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Preview is not available for this file type.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                if (isLoading && isImage && fullBitmap == null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            .size(28.dp),
                        color = CyanAccent,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
