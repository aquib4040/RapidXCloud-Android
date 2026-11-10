package in.rapidxcloud.drive.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import in.rapidxcloud.drive.data.model.BreadcrumbItem
import in.rapidxcloud.drive.data.model.FileItem
import in.rapidxcloud.drive.data.model.QuotaMetric
import in.rapidxcloud.drive.transfer.ThumbnailCacheManager
import in.rapidxcloud.drive.ui.theme.*

@Composable
fun FileIconView(
    mimeType: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 40
) {
    val (icon, color) = resolveIconAndColor(mimeType, name)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size((size * 0.6f).dp)
        )
    }
}

@Composable
fun FileThumbnailView(
    fileItem: FileItem,
    thumbnailManager: ThumbnailCacheManager,
    modifier: Modifier = Modifier,
    size: Int = 48
) {
    var bitmap by remember(fileItem.effectiveKey) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(fileItem.effectiveKey) { mutableStateOf(false) }

    val isMedia = fileItem.hasThumb || fileItem.effectiveMime.startsWith("image/") || fileItem.effectiveMime.startsWith("video/")

    LaunchedEffect(fileItem.effectiveKey) {
        if (isMedia) {
            bitmap = thumbnailManager.getThumbnailBitmap(fileItem)
            loaded = true
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = fileItem.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
        )
    } else {
        FileIconView(
            mimeType = fileItem.effectiveMime,
            name = fileItem.name,
            modifier = modifier,
            size = size
        )
    }
}

private fun resolveIconAndColor(mime: String, name: String): Pair<ImageVector, Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi", "mov") ->
            Pair(Icons.Default.Videocam, PurpleAccent)
        mime.startsWith("audio/") || ext in listOf("mp3", "wav", "flac", "ogg", "aac", "m4a") ->
            Pair(Icons.Default.Audiotrack, AmberAccent)
        mime.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "svg") ->
            Pair(Icons.Default.Image, EmeraldAccent)
        mime == "application/pdf" || ext == "pdf" ->
            Pair(Icons.Default.PictureAsPdf, RoseAccent)
        ext in listOf("zip", "rar", "7z", "tar", "gz") ->
            Pair(Icons.Default.FolderZip, AmberAccent)
        ext in listOf("doc", "docx", "txt", "md") ->
            Pair(Icons.Default.Description, CyanAccent)
        ext in listOf("kt", "rs", "py", "js", "ts", "html", "css", "json", "java", "c", "cpp") ->
            Pair(Icons.Default.Code, CyanAccentGlow)
        else ->
            Pair(Icons.Default.InsertDriveFile, TextSecondary)
    }
}

@Composable
fun QuotaProgressBar(
    title: String,
    metric: QuotaMetric,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(
                if (metric.is_unlimited) "Unlimited"
                else "${formatBytes(metric.used_bytes)} / ${formatBytes(metric.limit_bytes)} (${(metric.percent * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (metric.percent > 0.9f) RoseAccent else CyanAccent
            )
        }
        LinearProgressIndicator(
            progress = { if (metric.is_unlimited) 0.05f else metric.percent.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (metric.percent > 0.9f) RoseAccent else CyanAccent,
            trackColor = DarkCard
        )
    }
}

@Composable
fun BreadcrumbView(
    items: List<BreadcrumbItem>,
    onItemClick: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { index, item ->
            val isLast = index == items.size - 1
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                color = if (isLast) TextPrimary else TextSecondary,
                modifier = Modifier.clickable { onItemClick(item.id) }
            )
            if (!isLast) {
                Text("/", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}
