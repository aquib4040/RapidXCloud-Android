package in.rapidxcloud.drive.ui.starred

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import in.rapidxcloud.drive.CanonDriveApp
import in.rapidxcloud.drive.data.model.FileItem
import in.rapidxcloud.drive.ui.drive.FileListItem
import in.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun StarredScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as CanonDriveApp
    val repo = app.driveRepository
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refresh() {
        isLoading = true
        scope.launch {
            val res = repo.listStarred()
            isLoading = false
            if (res.isSuccess) {
                files = res.getOrNull()!!.files
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
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = CyanAccent)
        } else if (files.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                Text("No starred files", color = TextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.id }) { file ->
                    FileListItem(
                        file = file,
                        thumbManager = app.thumbnailCacheManager,
                        onMoreClick = {
                            scope.launch {
                                repo.toggleStar(file.id, "file")
                                refresh()
                            }
                        }
                    )
                }
            }
        }
    }
}
