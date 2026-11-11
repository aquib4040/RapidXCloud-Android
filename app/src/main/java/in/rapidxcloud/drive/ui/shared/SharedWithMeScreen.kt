package `in`.rapidxcloud.drive.ui.shared

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.rapidxcloud.drive.RapidXCloudApp
import `in`.rapidxcloud.drive.data.model.SharedWithMeFileItem
import `in`.rapidxcloud.drive.data.model.SharedWithMeFolderItem
import `in`.rapidxcloud.drive.ui.components.formatBytes
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SharedWithMeScreen(
    onNavigateFolder: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as RapidXCloudApp
    val repo = app.driveRepository
    val fileDownloader = app.fileDownloader
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<SharedWithMeFileItem>>(emptyList()) }
    var folders by remember { mutableStateOf<List<SharedWithMeFolderItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refresh() {
        isLoading = true
        scope.launch {
            val res = repo.listSharedWithMe()
            isLoading = false
            if (res.isSuccess) {
                val data = res.getOrNull()!!
                files = data.files
                folders = data.folders
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
                Column {
                    Text("Shared with Me", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Files and folders shared with you privately", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanAccent)
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
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.People, contentDescription = null, tint = TextMuted, modifier = Modifier.size(52.dp))
                    Text("No shared items yet", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 16.sp)
                    Text(
                        "When other users share files or folders with your username, they will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                val uniqueFolders = remember(folders) { folders.distinctBy { it.permission_id } }
                val uniqueFiles = remember(files) { files.distinctBy { it.permission_id } }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uniqueFolders.isNotEmpty()) {
                        item {
                            Text("SHARED FOLDERS", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        items(uniqueFolders, key = { "shared_folder_" + it.permission_id }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(10.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(32.dp))
                                        Column {
                                            Text(item.folder.name, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text(
                                                "Shared by @${item.owner?.username ?: item.owner?.display_name ?: "user"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    IconButton(onClick = {
                                        scope.launch {
                                            repo.removeCollaborator(item.permission_id)
                                            Toast.makeText(context, "Removed from Shared with me", Toast.LENGTH_SHORT).show()
                                            refresh()
                                        }
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = RoseAccent)
                                    }
                                }
                            }
                        }
                    }

                    if (uniqueFiles.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("SHARED FILES", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        items(uniqueFiles, key = { "shared_file_" + it.permission_id }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(10.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(30.dp))
                                        Column {
                                            Text(item.file.name, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(formatBytes(item.file.size), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                                Text("•", color = TextMuted)
                                                Text(
                                                    "@${item.owner?.username ?: item.owner?.display_name ?: "user"}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = CyanAccent
                                                )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(onClick = {
                                            Toast.makeText(context, "Downloading ${item.file.name}...", Toast.LENGTH_SHORT).show()
                                            scope.launch {
                                                try {
                                                    val saved = fileDownloader.downloadSingleFile(item.file) { _, _ -> }
                                                    Toast.makeText(context, "Saved to ${saved.absolutePath}", Toast.LENGTH_LONG).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Download", tint = CyanAccent)
                                        }

                                        IconButton(onClick = {
                                            scope.launch {
                                                repo.removeCollaborator(item.permission_id)
                                                Toast.makeText(context, "Removed from Shared with me", Toast.LENGTH_SHORT).show()
                                                refresh()
                                            }
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = RoseAccent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
