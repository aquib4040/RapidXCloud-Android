package in.rapidxcloud.drive.ui.drive

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import in.rapidxcloud.drive.CanonDriveApp
import in.rapidxcloud.drive.data.model.BreadcrumbItem
import in.rapidxcloud.drive.data.model.FileItem
import in.rapidxcloud.drive.data.model.FolderItem
import in.rapidxcloud.drive.ui.components.BreadcrumbView
import in.rapidxcloud.drive.ui.components.FileThumbnailView
import in.rapidxcloud.drive.ui.components.formatBytes
import in.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    currentFolderId: String?,
    onNavigateFolder: (String?, String) -> Unit,
    onOpenUrlLeech: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as CanonDriveApp
    val repo = app.driveRepository
    val folderDownloader = app.folderDownloader
    val fileDownloader = app.fileDownloader
    val thumbManager = app.thumbnailCacheManager
    val scope = rememberCoroutineScope()

    var activeUploadTaskId by remember { mutableStateOf<String?>(null) }
    var uploadProgressRatio by remember { mutableFloatStateOf(0f) }
    var uploadStatusText by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }

    val uploadPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val profile = repo.getProfile().getOrNull()
                app.uploadQueueManager.configurePlanConcurrency(profile)

                for (uri in uris) {
                    isUploading = true
                    uploadStatusText = "Uploading..."
                    val taskId = app.uploadQueueManager.enqueueUpload(
                        fileUri = uri,
                        folderId = currentFolderId,
                        isE2E = true,
                        onProgress = { loaded, total, isFallback ->
                            if (total > 0) uploadProgressRatio = loaded.toFloat() / total
                            uploadStatusText = if (isFallback) "Relaying via server..." else "Uploading (${(uploadProgressRatio * 100).toInt()}%)..."
                        },
                        onCompletion = { result ->
                            isUploading = false
                            activeUploadTaskId = null
                            if (result.isSuccess) {
                                Toast.makeText(context, "Upload complete!", Toast.LENGTH_SHORT).show()
                            } else if (result.exceptionOrNull() is java.util.concurrent.CancellationException) {
                                Toast.makeText(context, "Upload cancelled", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Upload failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                            refresh()
                        }
                    )
                    activeUploadTaskId = taskId
                }
            }
        }
    }

    var folders by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var breadcrumbs by remember { mutableStateOf<List<BreadcrumbItem>>(listOf(BreadcrumbItem(null, "Home"))) }
    var isLoading by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var selectedFileForAction by remember { mutableStateOf<FileItem?>(null) }
    var selectedFolderForAction by remember { mutableStateOf<FolderItem?>(null) }

    fun refresh() {
        isLoading = true
        scope.launch {
            val res = repo.listFolder(currentFolderId)
            isLoading = false
            if (res.isSuccess) {
                val data = res.getOrNull()!!
                folders = data.folders
                files = data.files
                breadcrumbs = data.path ?: data.breadcrumb ?: listOf(BreadcrumbItem(null, "Home"))
            } else {
                Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(currentFolderId) {
        refresh()
    }

    val filteredFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(DarkSurface)) {
                // Top Search Bar & View Toggles
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search files and folders...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle View",
                            tint = TextSecondary
                        )
                    }

                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                }

                // Breadcrumb path
                BreadcrumbView(
                    items = breadcrumbs,
                    onItemClick = { id -> onNavigateFolder(id, "Folder") }
                )
                Divider(color = DarkBorder)

                // Active Upload Banner with Progress and Cancel Button
                if (isUploading && activeUploadTaskId != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        color = DarkCard,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { uploadProgressRatio },
                                modifier = Modifier.size(20.dp),
                                color = CyanAccent,
                                strokeWidth = 2.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    uploadStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                LinearProgressIndicator(
                                    progress = { uploadProgressRatio },
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                    color = CyanAccent,
                                    trackColor = DarkBorder
                                )
                            }
                            IconButton(
                                onClick = {
                                    activeUploadTaskId?.let { app.uploadQueueManager.cancelUpload(it) }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel Upload",
                                    tint = RedDanger,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showFabMenu) {
                    FloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            uploadPicker.launch("*/*")
                        },
                        containerColor = DarkCard,
                        contentColor = CyanAccent,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Upload File")
                    }

                    FloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            onOpenUrlLeech()
                        },
                        containerColor = CyanAccent,
                        contentColor = Color.Black,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = "URL Leech")
                    }

                    FloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            showNewFolderDialog = true
                        },
                        containerColor = DarkCard,
                        contentColor = CyanAccent,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                }

                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = CyanAccent,
                    contentColor = Color.Black
                ) {
                    Icon(if (showFabMenu) Icons.Default.Close else Icons.Default.Add, contentDescription = "Actions")
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
            if (isLoading && folders.isEmpty() && files.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = CyanAccent
                )
            } else if (filteredFolders.isEmpty() && filteredFiles.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                    Text("This folder is empty", color = TextSecondary, fontWeight = FontWeight.Medium)
                }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredFolders, key = { "f_" + it.id }) { folder ->
                            FolderGridItem(
                                folder = folder,
                                onClick = { onNavigateFolder(folder.id, folder.name) },
                                onLongClick = { selectedFolderForAction = folder }
                            )
                        }
                        items(filteredFiles, key = { "file_" + it.id }) { file ->
                            FileGridItem(
                                file = file,
                                thumbManager = thumbManager,
                                onClick = { selectedFileForAction = file }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (filteredFolders.isNotEmpty()) {
                            item {
                                Text("FOLDERS", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            items(filteredFolders, key = { "f_" + it.id }) { folder ->
                                FolderListItem(
                                    folder = folder,
                                    onClick = { onNavigateFolder(folder.id, folder.name) },
                                    onMoreClick = { selectedFolderForAction = folder }
                                )
                            }
                        }

                        if (filteredFiles.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("FILES", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            items(filteredFiles, key = { "file_" + it.id }) { file ->
                                FileListItem(
                                    file = file,
                                    thumbManager = thumbManager,
                                    onMoreClick = { selectedFileForAction = file }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            scope.launch {
                                repo.createFolder(newFolderName.trim(), currentFolderId)
                                showNewFolderDialog = false
                                refresh()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    // File Action Dialog
    selectedFileForAction?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedFileForAction = null },
            title = {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Size: ${formatBytes(file.size)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Encryption Lane: ${file.encryption_lane.uppercase()}", style = MaterialTheme.typography.bodySmall, color = CyanAccent)

                    Divider(color = DarkBorder)

                    // Download Action
                    TextButton(
                        onClick = {
                            selectedFileForAction = null
                            Toast.makeText(context, "Starting download for ${file.name}...", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                try {
                                    val savedFile = fileDownloader.downloadSingleFile(file) { _, _ -> }
                                    Toast.makeText(context, "Saved to ${savedFile.absolutePath}", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = CyanAccent)
                            Text("Download to Device", color = TextPrimary)
                        }
                    }

                    // Toggle Star Action
                    TextButton(
                        onClick = {
                            selectedFileForAction = null
                            scope.launch {
                                repo.toggleStar(file.id, "file")
                                refresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (file.is_starred) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, tint = AmberAccent)
                            Text(if (file.is_starred) "Unstar" else "Star", color = TextPrimary)
                        }
                    }

                    // Trash Action
                    TextButton(
                        onClick = {
                            selectedFileForAction = null
                            scope.launch {
                                repo.trashFile(file.id)
                                refresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = RoseAccent)
                            Text("Move to Trash", color = RoseAccent)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedFileForAction = null }) { Text("Close", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    // Folder Action Dialog (Includes Direct Folder Download Without Zipping!)
    selectedFolderForAction?.let { folder ->
        AlertDialog(
            onDismissRequest = { selectedFolderForAction = null },
            title = { Text(folder.name, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Folder details and actions", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Divider(color = DarkBorder)

                    // DIRECT FOLDER DOWNLOAD (NO ZIPPING!)
                    TextButton(
                        onClick = {
                            selectedFolderForAction = null
                            Toast.makeText(context, "Direct folder download started for '${folder.name}' (no zipping)...", Toast.LENGTH_LONG).show()
                            scope.launch {
                                val success = folderDownloader.downloadFolderRecursively(folder.id, folder.name) { currentFile, done, total, _ ->
                                    // Progress feedback
                                }
                                if (success) {
                                    Toast.makeText(context, "Folder '${folder.name}' saved intact to Downloads/CanonDrive/${folder.name}!", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DriveFolderUpload, contentDescription = null, tint = EmeraldAccent)
                            Text("Download Folder (Unzipped)", color = EmeraldAccent, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Trash Folder
                    TextButton(
                        onClick = {
                            selectedFolderForAction = null
                            scope.launch {
                                repo.trashFolder(folder.id)
                                refresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = RoseAccent)
                            Text("Move Folder to Trash", color = RoseAccent)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedFolderForAction = null }) { Text("Close", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }
}

@Composable
fun FolderListItem(
    folder: FolderItem,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(36.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.name, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${folder.item_count} items", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        IconButton(onClick = onMoreClick) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
        }
    }
}

@Composable
fun FileListItem(
    file: FileItem,
    thumbManager: in.rapidxcloud.drive.transfer.ThumbnailCacheManager,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FileThumbnailView(fileItem = file, thumbnailManager = thumbManager, size = 44)
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (file.encryption_lane == "e2e") {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(EmeraldAccent.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("E2E", fontSize = 10.sp, color = EmeraldAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        IconButton(onClick = onMoreClick) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
        }
    }
}

@Composable
fun FolderGridItem(
    folder: FolderItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(44.dp))
        Text(folder.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FileGridItem(
    file: FileItem,
    thumbManager: in.rapidxcloud.drive.transfer.ThumbnailCacheManager,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FileThumbnailView(fileItem = file, thumbnailManager = thumbManager, size = 64)
        Text(file.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatBytes(file.size), fontSize = 11.sp, color = TextSecondary)
    }
}
