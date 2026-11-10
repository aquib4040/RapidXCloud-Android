package `in`.rapidxcloud.drive.ui.leech

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.rapidxcloud.drive.CanonDriveApp
import `in`.rapidxcloud.drive.data.model.LeechFileInfo
import `in`.rapidxcloud.drive.ui.components.formatBytes
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun UrlLeechDialog(
    folderId: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as CanonDriveApp
    val api = app.apiClient
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var fileInfo by remember { mutableStateOf<LeechFileInfo?>(null) }
    var filename by remember { mutableStateOf("") }
    var encryptionLane by remember { mutableStateOf("envelope") } // "envelope", "e2e", "none"
    var isStarting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, tint = CyanAccent)
                Text("Remote URL Leech", color = TextPrimary)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Directly upload any HTTP/HTTPS web link straight to your cloud drive at up to 2 Gbps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        fileInfo = null
                    },
                    label = { Text("Direct Download URL") },
                    placeholder = { Text("https://example.com/file.zip") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Button(
                    onClick = {
                        if (url.isBlank()) return@Button
                        isFetching = true
                        scope.launch {
                            try {
                                val res = api.getFileInfoFromUrl(mapOf("url" to url.trim()))
                                isFetching = false
                                if (res.isSuccessful && res.body()?.data != null) {
                                    val data = res.body()!!.data!!
                                    fileInfo = data
                                    filename = data.filename
                                } else {
                                    Toast.makeText(context, "Could not fetch URL info", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                isFetching = false
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface, contentColor = CyanAccent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                    enabled = !isFetching
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanAccent, strokeWidth = 2.dp)
                    } else {
                        Text("Inspect Remote File")
                    }
                }

                fileInfo?.let { info ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DETECTED FILE INFO", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                            Text("Size: ${formatBytes(info.size)}", fontWeight = FontWeight.Bold, color = TextPrimary)

                            OutlinedTextField(
                                value = filename,
                                onValueChange = { filename = it },
                                label = { Text("Save As Filename") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Encryption Lane Selector
                            Text("Encryption Lane", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = encryptionLane == "envelope",
                                    onClick = { encryptionLane = "envelope" },
                                    label = { Text("Envelope (AES-256)") }
                                )
                                FilterChip(
                                    selected = encryptionLane == "none",
                                    onClick = { encryptionLane = "none" },
                                    label = { Text("Plaintext") }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fileInfo == null || url.isBlank()) return@Button
                    isStarting = true
                    scope.launch {
                        try {
                            val leechParams = mutableMapOf<String, Any>(
                                "url" to url.trim(),
                                "filename" to filename.trim(),
                                "encryption_lane" to encryptionLane
                            )
                            if (folderId != null) leechParams["path"] = folderId
                            val res = api.startFileDownloadFromUrl(leechParams)
                            isStarting = false
                            if (res.isSuccessful) {
                                Toast.makeText(context, "URL leeching task enqueued on cloud server!", Toast.LENGTH_LONG).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Failed to start leech", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            isStarting = false
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = fileInfo != null && !isStarting,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
            ) {
                Text("Start Cloud Leech")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = DarkCard
    )
}
