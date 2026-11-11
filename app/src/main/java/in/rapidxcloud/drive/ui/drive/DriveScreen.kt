package `in`.rapidxcloud.drive.ui.drive

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import `in`.rapidxcloud.drive.CanonDriveApp
import `in`.rapidxcloud.drive.data.model.BreadcrumbItem
import `in`.rapidxcloud.drive.data.model.FileItem
import `in`.rapidxcloud.drive.data.model.FolderItem
import `in`.rapidxcloud.drive.ui.components.BreadcrumbView
import `in`.rapidxcloud.drive.ui.components.FileThumbnailView
import `in`.rapidxcloud.drive.ui.components.formatBytes
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    currentFolderId: String?,
    onNavigateFolder: (String?, String) -> Unit,
    onNavigateBack: () -> Unit = {},
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
    var fileToRename by remember { mutableStateOf<FileItem?>(null) }
    var folderToRename by remember { mutableStateOf<FolderItem?>(null) }
    var fileToShare by remember { mutableStateOf<FileItem?>(null) }
    var itemToMove by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // id to isFolder
    var fileForProperties by remember { mutableStateOf<FileItem?>(null) }
    var previewingFile by remember { mutableStateOf<FileItem?>(null) }

    BackHandler(
        enabled = previewingFile != null || showFabMenu || selectedFileForAction != null || selectedFolderForAction != null ||
                showNewFolderDialog || fileToRename != null || folderToRename != null || fileToShare != null ||
                itemToMove != null || fileForProperties != null || searchQuery.isNotEmpty() || currentFolderId != null
    ) {
        when {
            previewingFile != null -> previewingFile = null
            showFabMenu -> showFabMenu = false
            selectedFileForAction != null -> selectedFileForAction = null
            selectedFolderForAction != null -> selectedFolderForAction = null
            showNewFolderDialog -> showNewFolderDialog = false
            fileToRename != null -> fileToRename = null
            folderToRename != null -> folderToRename = null
            fileToShare != null -> fileToShare = null
            itemToMove != null -> itemToMove = null
            fileForProperties != null -> fileForProperties = null
            searchQuery.isNotEmpty() -> searchQuery = ""
            currentFolderId != null -> onNavigateBack()
        }
    }

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

    LaunchedEffect(currentFolderId) {
        refresh()
    }

    val filteredFolders = remember(folders, searchQuery) {
        val base = if (searchQuery.isBlank()) folders
        else folders.filter { it.name.contains(searchQuery, ignoreCase = true) }
        base.distinctBy { it.id }
    }

    val filteredFiles = remember(files, searchQuery) {
        val base = if (searchQuery.isBlank()) files
        else files.filter { it.displayName.contains(searchQuery, ignoreCase = true) || it.name.contains(searchQuery, ignoreCase = true) }
        base.distinctBy { it.effectiveKey }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF090B0E))) {
                // Top Header Bar matching drive.rapidxcloud.in
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (currentFolderId != null) {
                            IconButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF111317))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            }
                        }
                        Text(
                            if (currentFolderId != null && breadcrumbs.isNotEmpty()) breadcrumbs.last().name else "RapidxCloud",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // [ VAULT: UNLOCKED ] Badge (1:1 Web Parity)
                        Surface(
                            color = EmeraldAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(12.dp))
                                Text(
                                    "[ VAULT: UNLOCKED ]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldAccent,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        IconButton(onClick = { isGridView = !isGridView }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "Toggle View",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(onClick = { refresh() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Divider(color = Color(0x14FFFFFF))

                // Search Bar matching drive.rapidxcloud.in
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        placeholder = { Text("Search files, folders, or extensions...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0D0F14),
                            unfocusedContainerColor = Color(0xFF0D0F14),
                            focusedBorderColor = Color(0x33FFFFFF),
                            unfocusedBorderColor = Color(0x14FFFFFF),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(6.dp)
                    )
                }

                // Breadcrumb path
                BreadcrumbView(
                    items = breadcrumbs,
                    onItemClick = { id -> onNavigateFolder(id, "Folder") }
                )
                Divider(color = Color(0x14FFFFFF))

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
                                onClick = { previewingFile = file }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("FILES", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            items(filteredFiles, key = { "file_" + it.id }) { file ->
                                FileColumnCardItem(
                                    file = file,
                                    thumbManager = thumbManager,
                                    onClick = { previewingFile = file },
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

    // Media Preview Lightbox (Google Drive & Gallery Style)
    previewingFile?.let { file ->
        `in`.rapidxcloud.drive.ui.components.MediaPreviewDialog(
            file = file,
            thumbnailManager = thumbManager,
            fileDownloader = fileDownloader,
            onDismiss = { previewingFile = null }
        )
    }

    // File Action Dialog
    selectedFileForAction?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedFileForAction = null },
            title = {
                Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
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
                            Toast.makeText(context, "Starting download for ${file.displayName}...", Toast.LENGTH_SHORT).show()
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

                    // Share Link Action
                    TextButton(
                        onClick = {
                            val f = file
                            selectedFileForAction = null
                            fileToShare = f
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = CyanAccent)
                            Text("Create Share Link", color = TextPrimary)
                        }
                    }

                    // Rename Action
                    TextButton(
                        onClick = {
                            val f = file
                            selectedFileForAction = null
                            fileToRename = f
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = TextPrimary)
                            Text("Rename File", color = TextPrimary)
                        }
                    }

                    // Move File Action
                    TextButton(
                        onClick = {
                            val f = file
                            selectedFileForAction = null
                            itemToMove = Pair(f.id, false)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = TextPrimary)
                            Text("Move File", color = TextPrimary)
                        }
                    }

                    // Properties Action
                    TextButton(
                        onClick = {
                            val f = file
                            selectedFileForAction = null
                            fileForProperties = f
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary)
                            Text("Properties & Details", color = TextSecondary)
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
                                    Toast.makeText(context, "Folder '${folder.name}' saved intact to Downloads/RapidXCloud/${folder.name}!", Toast.LENGTH_LONG).show()
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

                    // Rename Folder
                    TextButton(
                        onClick = {
                            val f = folder
                            selectedFolderForAction = null
                            folderToRename = f
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = TextPrimary)
                            Text("Rename Folder", color = TextPrimary)
                        }
                    }

                    // Move Folder
                    TextButton(
                        onClick = {
                            val f = folder
                            selectedFolderForAction = null
                            itemToMove = Pair(f.id, true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = TextPrimary)
                            Text("Move Folder", color = TextPrimary)
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

    // Rename File Dialog
    fileToRename?.let { target ->
        var newName by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Rename File", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            scope.launch {
                                repo.renameFile(target.id, newName.trim())
                                fileToRename = null
                                refresh()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    // Rename Folder Dialog
    folderToRename?.let { target ->
        var newName by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text("Rename Folder", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            scope.launch {
                                repo.renameFolder(target.id, newName.trim())
                                folderToRename = null
                                refresh()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    // Share Link Dialog
    fileToShare?.let { target ->
        var shareExpiryHours by remember { mutableFloatStateOf(0f) }
        var sharePassword by remember { mutableStateOf("") }
        var generatedShareToken by remember { mutableStateOf<String?>(null) }
        var isCreatingShare by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { fileToShare = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = CyanAccent)
                    Text("Share Link", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(target.name, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    if (generatedShareToken == null) {
                        Text("Expiration Duration:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0f to "Never", 24f to "24h", 168f to "7d").forEach { (hours, label) ->
                                FilterChip(
                                    selected = shareExpiryHours == hours,
                                    onClick = { shareExpiryHours = hours },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = sharePassword,
                            onValueChange = { sharePassword = it },
                            label = { Text("Optional Password Protection") },
                            placeholder = { Text("Leave empty for public link") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        val fullUrl = "https://rapidxcloud.in/d/$generatedShareToken"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(fullUrl, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = CyanAccent)
                        }
                    }
                }
            },
            confirmButton = {
                if (generatedShareToken == null) {
                    Button(
                        onClick = {
                            isCreatingShare = true
                            scope.launch {
                                val res = repo.createShare(
                                    fileId = target.id,
                                    expiresInHours = if (shareExpiryHours > 0) shareExpiryHours else null,
                                    password = sharePassword.ifBlank { null }
                                )
                                isCreatingShare = false
                                res.onSuccess { token ->
                                    generatedShareToken = token
                                    val fullUrl = "https://rapidxcloud.in/d/$token"
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Share Link", fullUrl))
                                    Toast.makeText(context, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, "Failed to create share link", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isCreatingShare,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                    ) {
                        if (isCreatingShare) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                        else Text("Generate Link")
                    }
                } else {
                    Button(
                        onClick = { fileToShare = null },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                    ) {
                        Text("Done")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToShare = null }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    // Move Dialog
    itemToMove?.let { (targetId, isFolder) ->
        AlertDialog(
            onDismissRequest = { itemToMove = null },
            title = { Text(if (isFolder) "Move Folder" else "Move File", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select destination folder:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                    // Root option
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                scope.launch {
                                    if (isFolder) repo.moveFolder(targetId, null)
                                    else repo.moveFile(targetId, null)
                                    Toast.makeText(context, "Moved to Home root", Toast.LENGTH_SHORT).show()
                                    itemToMove = null
                                    refresh()
                                }
                            }
                            .background(DarkSurface)
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Home, contentDescription = null, tint = CyanAccent)
                            Text("Home (Root)", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    folders.filter { !isFolder || it.id != targetId }.forEach { f ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    scope.launch {
                                        if (isFolder) repo.moveFolder(targetId, f.id)
                                        else repo.moveFile(targetId, f.id)
                                        Toast.makeText(context, "Moved to '${f.name}'", Toast.LENGTH_SHORT).show()
                                        itemToMove = null
                                        refresh()
                                    }
                                }
                            .background(DarkSurface)
                            .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = AmberAccent)
                                Text(f.name, color = TextPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { itemToMove = null }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    // Properties Dialog
    fileForProperties?.let { f ->
        AlertDialog(
            onDismissRequest = { fileForProperties = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = CyanAccent)
                    Text("Properties & Details", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Divider(color = DarkBorder)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("File Size", color = TextSecondary, fontSize = 12.sp)
                        Text(formatBytes(f.size), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Encryption Lane", color = TextSecondary, fontSize = 12.sp)
                        Text(f.encryption_lane.uppercase(), color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MIME Type", color = TextSecondary, fontSize = 12.sp)
                        Text(f.mime_type ?: f.mime ?: "binary/octet-stream", color = TextPrimary, fontSize = 12.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("File ID", color = TextSecondary, fontSize = 12.sp)
                        Text(f.id.take(12) + "...", fontFamily = FontFamily.Monospace, color = TextMuted, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { fileForProperties = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Text("Close")
                }
            },
            dismissButton = {},
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
fun FileColumnCardItem(
    file: FileItem,
    thumbManager: `in`.rapidxcloud.drive.transfer.ThumbnailCacheManager,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Top Thumbnail Rectangle (Height 180.dp) ─────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color(0xFF0F141C)),
                contentAlignment = Alignment.Center
            ) {
                FileThumbnailView(
                    fileItem = file,
                    thumbnailManager = thumbManager,
                    modifier = Modifier.fillMaxSize(),
                    size = 180,
                    fillContainer = true
                )

                // Top-Left: E2EE / Encryption Lane Pill Badge
                if (file.encryption_lane == "e2e") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE60D1117))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Text(
                                "E2EE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else if (file.encryption_lane == "envelope") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE60D1117))
                            .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "ENVELOPE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent
                        )
                    }
                }

                // Bottom-Right (for Video): Duration Pill
                val isVid = file.effectiveMime.startsWith("video/") ||
                        listOf("mp4", "mkv", "mov", "avi", "webm").any { file.displayName.endsWith(".$it", ignoreCase = true) }
                if (isVid) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                "VIDEO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // ── Attached Bottom Metadata Rectangle ──────────────────────────────
            Surface(
                color = Color(0xFF131720),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.6f))
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.displayName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = formatBytes(file.size),
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Text("•", fontSize = 10.sp, color = TextMuted)
                            Text(
                                text = file.effectiveMime.substringAfter("/").uppercase(),
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(
                        onClick = onMoreClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "File Options",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileListItem(
    file: FileItem,
    thumbManager: `in`.rapidxcloud.drive.transfer.ThumbnailCacheManager,
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
            Text(file.displayName, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    thumbManager: `in`.rapidxcloud.drive.transfer.ThumbnailCacheManager,
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
        Box(contentAlignment = Alignment.TopStart) {
            FileThumbnailView(fileItem = file, thumbnailManager = thumbManager, size = 64)
            if (file.encryption_lane == "e2e") {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF10B981))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text("E2E", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
        Text(file.displayName, style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatBytes(file.size), fontSize = 11.sp, color = TextSecondary)
    }
}
