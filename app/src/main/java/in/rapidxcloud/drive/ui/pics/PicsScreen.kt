package in.rapidxcloud.drive.ui.pics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import in.rapidxcloud.drive.CanonDriveApp
import in.rapidxcloud.drive.data.model.PicItem
import in.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun PicsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as CanonDriveApp
    val repo = app.driveRepository
    val scope = rememberCoroutineScope()

    var pics by remember { mutableStateOf<List<PicItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isUltraPlan by remember { mutableStateOf(true) }
    var selectedPicForEmbed by remember { mutableStateOf<PicItem?>(null) }

    fun refresh() {
        isLoading = true
        scope.launch {
            val profile = repo.getProfile().getOrNull()
            isUltraPlan = profile?.plan == "ultra" || profile?.is_admin == true

            if (isUltraPlan) {
                val res = repo.listPics()
                isLoading = false
                if (res.isSuccess) {
                    pics = res.getOrNull()!!.images
                }
            } else {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        if (!isUltraPlan) {
            // Plan Tier Guard for Starter / Pro users
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(48.dp))
                    Text("RapidxPics is an Ultra Feature", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        "Direct high-speed media hosting and forum embeds are exclusively available for Ultra tier subscribers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        } else if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = CyanAccent)
        } else if (pics.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                Text("No hosted pictures yet", color = TextSecondary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pics, key = { it.id }) { pic ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkCard)
                            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                            .clickable { selectedPicForEmbed = pic }
                    ) {
                        AsyncImage(
                            model = pic.direct_url,
                            contentDescription = pic.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    // Embed & Share Dialog matching drive.pics.tsx
    selectedPicForEmbed?.let { pic ->
        AlertDialog(
            onDismissRequest = { selectedPicForEmbed = null },
            title = {
                Text(pic.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = pic.direct_url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Text("Direct Image Link", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    OutlinedTextField(
                        value = pic.direct_url,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Direct Link", pic.direct_url))
                                Toast.makeText(context, "Direct link copied!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = CyanAccent)
                            }
                        }
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                repo.deletePic(pic.id)
                                selectedPicForEmbed = null
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoseAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Image")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedPicForEmbed = null }) { Text("Close", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }
}
