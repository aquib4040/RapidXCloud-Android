package in.rapidxcloud.drive.ui.trash

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import in.rapidxcloud.drive.CanonDriveApp
import in.rapidxcloud.drive.data.model.FileItem
import in.rapidxcloud.drive.data.model.FolderItem
import in.rapidxcloud.drive.ui.components.formatBytes
import in.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TrashScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as CanonDriveApp
    val repo = app.driveRepository
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refresh() {
        isLoading = true
        scope.launch {
            val res = repo.listTrash()
            isLoading = false
            if (res.isSuccess) {
                folders = res.getOrNull()!!.folders
                files = res.getOrNull()!!.files
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trash / Bin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                if (folders.isNotEmpty() || files.isNotEmpty()) {
                    Button(
                        onClick = {
                            scope.launch {
                                repo.emptyTrash()
                                Toast.makeText(context, "Trash emptied", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoseAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Empty Trash")
                    }
                }
            }
        },
        containerColor = DarkBg
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = CyanAccent)
            } else if (folders.isEmpty() && files.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                    Text("Trash is empty", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(folders, key = { "f_" + it.id }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkCard)
                                .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(folder.name, modifier = Modifier.weight(1f), color = TextPrimary)
                            IconButton(onClick = {
                                scope.launch {
                                    repo.deleteFolderPermanently(folder.id)
                                    refresh()
                                }
                            }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Delete", tint = RoseAccent)
                            }
                        }
                    }
                    items(files, key = { "file_" + it.id }) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkCard)
                                .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    repo.deleteFilePermanently(file.id)
                                    refresh()
                                }
                            }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Delete", tint = RoseAccent)
                            }
                        }
                    }
                }
            }
        }
    }
}
