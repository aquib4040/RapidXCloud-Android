package `in`.rapidxcloud.drive.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_FILE_NAME = "extra_file_name"

        fun start(context: Context, streamUrl: String, fileName: String) {
            val intent = Intent(context, VlcPlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_FILE_NAME, fileName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive sticky fullscreen & keep screen awake
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            finish()
            return
        }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Video Stream"

        initLibVLC(streamUrl)

        setContent {
            RapidXCloudTheme {
                VlcPlayerScreen(
                    fileName = fileName,
                    mediaPlayer = mediaPlayer,
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initLibVLC(streamUrl: String) {
        val options = arrayListOf(
            "--network-caching=1500",
            "--file-caching=1500",
            "--live-caching=1500",
            "--sub-autodetect-file",
            "--audio-time-stretch",
            "-vvv"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC).apply {
            val media = Media(libVLC, Uri.parse(streamUrl)).apply {
                setHWDecoderEnabled(true, false)
            }
            this.media = media
            media.release()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mediaPlayer?.let { player ->
            videoLayout?.let { layout ->
                player.detachViews()
                player.attachViews(layout, null, true, false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.let {
            it.stop()
            it.detachViews()
            it.release()
        }
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
    }

    @Composable
    private fun VlcPlayerScreen(
        fileName: String,
        mediaPlayer: MediaPlayer?,
        onBack: () -> Unit
    ) {
        var isPlaying by remember { mutableStateOf(false) }
        var isBuffering by remember { mutableStateOf(true) }
        var currentTimeMs by remember { mutableStateOf(0L) }
        var durationMs by remember { mutableStateOf(0L) }
        var controlsVisible by remember { mutableStateOf(true) }
        var showAudioDialog by remember { mutableStateOf(false) }
        var showSubDialog by remember { mutableStateOf(false) }

        var audioTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
        var subTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
        var currentAudioId by remember { mutableStateOf(-1) }
        var currentSubId by remember { mutableStateOf(-1) }

        // Auto-hide controls timer
        LaunchedEffect(controlsVisible, isPlaying) {
            if (controlsVisible && isPlaying) {
                delay(4000)
                controlsVisible = false
            }
        }

        // Live playback progress tracker
        LaunchedEffect(mediaPlayer) {
            mediaPlayer ?: return@LaunchedEffect
            while (isActive) {
                if (mediaPlayer.hasMedia()) {
                    isPlaying = mediaPlayer.isPlaying
                    currentTimeMs = mediaPlayer.time
                    val d = mediaPlayer.length
                    if (d > 0) durationMs = d
                    isBuffering = mediaPlayer.playerState == MediaPlayer.Event.Opening ||
                            (mediaPlayer.playerState == MediaPlayer.Event.Buffering && !mediaPlayer.isPlaying)

                    // Refresh track list periodically when ready
                    val a = mediaPlayer.audioTracks?.toList() ?: emptyList()
                    val s = mediaPlayer.spuTracks?.toList() ?: emptyList()
                    if (a.isNotEmpty()) {
                        audioTracks = a
                        currentAudioId = mediaPlayer.audioTrack
                    }
                    if (s.isNotEmpty()) {
                        subTracks = s
                        currentSubId = mediaPlayer.spuTrack
                    }
                }
                delay(350)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    controlsVisible = !controlsVisible
                }
        ) {
            // ── LibVLC Video Output Surface ─────────────────────────────
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        videoLayout = layout
                        mediaPlayer?.attachViews(layout, null, true, false)
                        mediaPlayer?.play()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Buffering Spinner
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(56.dp),
                    color = CyanAccent,
                    strokeWidth = 3.dp
                )
            }

            // ── Player Controls Overlay ─────────────────────────────────
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    // Top Bar (Back, Title, Audio/Subtitle Selectors)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = fileName,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Audio Track Selector
                            IconButton(
                                onClick = {
                                    audioTracks = mediaPlayer?.audioTracks?.toList() ?: emptyList()
                                    currentAudioId = mediaPlayer?.audioTrack ?: -1
                                    showAudioDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Audiotrack,
                                    contentDescription = "Audio Tracks",
                                    tint = if (audioTracks.size > 1) AmberAccent else Color.White
                                )
                            }

                            // Subtitle Track Selector
                            IconButton(
                                onClick = {
                                    subTracks = mediaPlayer?.spuTracks?.toList() ?: emptyList()
                                    currentSubId = mediaPlayer?.spuTrack ?: -1
                                    showSubDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Subtitles,
                                    contentDescription = "Subtitles",
                                    tint = if (currentSubId > 0) CyanAccent else Color.White
                                )
                            }
                        }
                    }

                    // Center Quick Controls (-10s, Play/Pause, +10s)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                mediaPlayer?.let { it.time = max(0L, it.time - 10000L) }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                        }

                        IconButton(
                            onClick = {
                                mediaPlayer?.let {
                                    if (it.isPlaying) it.pause() else it.play()
                                    isPlaying = it.isPlaying
                                }
                            },
                            modifier = Modifier
                                .size(72.dp)
                                .background(CyanAccent, CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = DarkBg,
                                modifier = Modifier.size(42.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                mediaPlayer?.let { it.time = min(durationMs, it.time + 10000L) }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }

                    // Bottom Bar (Progress, Time, Fullscreen Aspect)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Slider(
                            value = if (durationMs > 0) currentTimeMs.toFloat() / durationMs.toFloat() else 0f,
                            onValueChange = { ratio ->
                                mediaPlayer?.let {
                                    it.time = (ratio * durationMs).toLong()
                                    currentTimeMs = it.time
                                }
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = CyanAccent,
                                activeTrackColor = CyanAccent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatDuration(currentTimeMs)} / ${formatDuration(durationMs)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            IconButton(
                                onClick = {
                                    mediaPlayer?.let {
                                        val scales = arrayOf(null, "16:9", "4:3", "1:1")
                                        val current = it.aspectRatio
                                        val nextIdx = (scales.indexOf(current) + 1) % scales.size
                                        it.aspectRatio = scales[nextIdx]
                                        Toast.makeText(this@VlcPlayerActivity, "Aspect: ${scales[nextIdx] ?: "Original"}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                            }
                        }
                    }
                }
            }

            // ── Audio Track Switcher Dialog ──────────────────────────────
            if (showAudioDialog) {
                AlertDialog(
                    onDismissRequest = { showAudioDialog = false },
                    title = { Text("Select Audio Track", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(audioTracks) { track ->
                                val isSelected = track.id == currentAudioId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) CyanAccent.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable {
                                            mediaPlayer?.audioTrack = track.id
                                            currentAudioId = track.id
                                            showAudioDialog = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Audiotrack,
                                        contentDescription = null,
                                        tint = if (isSelected) CyanAccent else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = track.name.ifBlank { "Track ${track.id}" },
                                        color = if (isSelected) CyanAccent else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAudioDialog = false }) {
                            Text("Close", color = CyanAccent)
                        }
                    },
                    containerColor = DarkCard
                )
            }

            // ── Subtitle Track Switcher Dialog ───────────────────────────
            if (showSubDialog) {
                AlertDialog(
                    onDismissRequest = { showSubDialog = false },
                    title = { Text("Select Subtitles", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Option: Disable Subtitles (-1)
                            item {
                                val isOff = currentSubId == -1
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isOff) CyanAccent.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable {
                                            mediaPlayer?.spuTrack = -1
                                            currentSubId = -1
                                            showSubDialog = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.SubtitlesOff,
                                        contentDescription = null,
                                        tint = if (isOff) CyanAccent else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Subtitles Off",
                                        color = if (isOff) CyanAccent else Color.White,
                                        fontWeight = if (isOff) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            items(subTracks) { track ->
                                if (track.id != -1) {
                                    val isSelected = track.id == currentSubId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) CyanAccent.copy(alpha = 0.2f) else Color.Transparent)
                                            .clickable {
                                                mediaPlayer?.spuTrack = track.id
                                                currentSubId = track.id
                                                showSubDialog = false
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Subtitles,
                                            contentDescription = null,
                                            tint = if (isSelected) CyanAccent else Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = track.name.ifBlank { "Subtitle ${track.id}" },
                                            color = if (isSelected) CyanAccent else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSubDialog = false }) {
                            Text("Close", color = CyanAccent)
                        }
                    },
                    containerColor = DarkCard
                )
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = max(0L, ms / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }
}

