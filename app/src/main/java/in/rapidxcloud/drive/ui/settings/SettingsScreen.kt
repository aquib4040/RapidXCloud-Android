package in.rapidxcloud.drive.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import in.rapidxcloud.drive.CanonDriveApp
import in.rapidxcloud.drive.backup.BackupForegroundService
import in.rapidxcloud.drive.data.model.ActiveSession
import in.rapidxcloud.drive.data.model.BotToken
import in.rapidxcloud.drive.data.model.UserProfile
import in.rapidxcloud.drive.ui.components.QuotaProgressBar
import in.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as CanonDriveApp
    val repo = app.driveRepository
    val settingsRepo = app.settingsRepository
    val authRepo = app.authRepository
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var sessions by remember { mutableStateOf<List<ActiveSession>>(emptyList()) }
    var byosTokens by remember { mutableStateOf<List<BotToken>>(emptyList()) }
    var isBackupEnabled by remember { mutableStateOf(false) }
    var isAutoDelete by remember { mutableStateOf(false) }
    var isDualKey by remember { mutableStateOf(true) }
    var isInstantBackup by remember { mutableStateOf(true) }
    var serverUrl by remember { mutableStateOf("") }
    var showAddTokenDialog by remember { mutableStateOf(false) }
    var showExportKeyDialog by remember { mutableStateOf(false) }
    var exportedKeyText by remember { mutableStateOf<String?>(null) }

    fun refreshAll() {
        scope.launch {
            profile = repo.getProfile(forceRefresh = true).getOrNull()
            sessions = repo.listSessions().getOrNull() ?: emptyList()
            byosTokens = repo.listByosTokens().getOrNull()?.tokens ?: emptyList()
            isBackupEnabled = settingsRepo.isBackupEnabled()
            isAutoDelete = settingsRepo.isAutoDeleteEnabled()
            isDualKey = settingsRepo.isDualKeyEnabled()
            isInstantBackup = settingsRepo.isInstantBackupFlow.let { settingsRepo.isBackupEnabled() }
            serverUrl = settingsRepo.getServerUrl()
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    val isByos = profile?.byos_enabled == true || profile?.is_admin == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Vault", fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 1. User Profile & Plan Badge ─────────────────────────────────────────
            profile?.let { user ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(user.display_name ?: user.username ?: "Vault User", fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("ID: ${user.account_no ?: user.id.take(8).uppercase()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(user.plan.uppercase(), fontWeight = FontWeight.Bold) },
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = if (user.plan == "ultra") AmberAccent else CyanAccent,
                                    containerColor = DarkSurface
                                ),
                                border = AssistChipDefaults.assistChipBorder(borderColor = if (user.plan == "ultra") AmberAccent else CyanAccent)
                            )
                        }

                        // Storage & Bandwidth Quotas
                        user.quotas?.let { q ->
                            Divider(color = DarkBorder)
                            QuotaProgressBar(title = "Daily Upload Bandwidth", metric = q.upload)
                            QuotaProgressBar(title = "Daily Download Bandwidth", metric = q.download)
                        }

                        // Plan Switcher (Admin / Testing)
                        if (user.is_admin) {
                            Divider(color = DarkBorder)
                            Text("ADMIN PLAN OVERRIDE", style = MaterialTheme.typography.labelSmall, color = AmberAccent)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("starter", "pro", "ultra").forEach { plan ->
                                    FilterChip(
                                        selected = user.plan == plan,
                                        onClick = {
                                            scope.launch {
                                                repo.overridePlan(plan)
                                                refreshAll()
                                            }
                                        },
                                        label = { Text(plan.uppercase()) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. Automated Mobile Backup (Google Photos Style) ─────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = CyanAccent)
                            Text("Automated Mobile Backup", fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        if (isByos) {
                            Switch(
                                checked = isBackupEnabled,
                                onCheckedChange = { checked ->
                                    isBackupEnabled = checked
                                    scope.launch {
                                        settingsRepo.setBackupEnabled(checked)
                                        if (checked) {
                                            BackupForegroundService.start(context)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    if (!isByos) {
                        // Strict requirement: BYOS Only
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AmberAccent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .border(1.dp, AmberAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Automated mobile backup is exclusively available for Private Node (BYOS) users to ensure unlimited, unthrottled personal storage bandwidth. Please configure your Telegram Bot Token in BYOS settings below to enable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberAccent
                            )
                        }
                    } else {
                        Text(
                            "Continuously watches camera & device folders and uploads newly captured photos & videos following your mobile directory structure to 'Mobile Backup'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        // Trigger Mode Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Instant write backup", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("Triggers immediately when a new photo is saved", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            Switch(
                                checked = isInstantBackup,
                                onCheckedChange = {
                                    isInstantBackup = it
                                    scope.launch { settingsRepo.setInstantBackup(it) }
                                }
                            )
                        }

                        // Auto-Delete Original Toggle (Strictly Default OFF)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-delete original from phone", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("Frees up storage after verified cloud backup (Default: OFF)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            Switch(
                                checked = isAutoDelete,
                                onCheckedChange = {
                                    isAutoDelete = it
                                    scope.launch { settingsRepo.setAutoDeleteEnabled(it) }
                                }
                            )
                        }

                        // Manual "Back Up Now" Button
                        Button(
                            onClick = {
                                Toast.makeText(context, "Scanning phone for new photos & videos...", Toast.LENGTH_SHORT).show()
                                BackupForegroundService.start(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface, contentColor = CyanAccent),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Back Up Now")
                        }
                    }
                }
            }

            // ── 3. Zero-Knowledge E2EE Cryptographic Settings ─────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldAccent)
                        Text("Military-Grade E2E Encryption", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    // Dual-Key Split Toggle (Code for if dual key split off key dont share to backend)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dual-Key Split (Proton model)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (isDualKey) "FileKey = ClientShare ⊕ ServerShare (Both required to decrypt)"
                                else "Solo User Key active: Zero key shares are sent to the backend!",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDualKey) TextSecondary else EmeraldAccent
                            )
                        }
                        Switch(
                            checked = isDualKey,
                            onCheckedChange = {
                                isDualKey = it
                                scope.launch {
                                    settingsRepo.setDualKeyEnabled(it)
                                    Toast.makeText(
                                        context,
                                        if (it) "Dual-Key Split enabled" else "Solo User Key active: Backend receives zero key shares",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }

                    // Export / View Recovery Code
                    OutlinedButton(
                        onClick = { showExportKeyDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export / View Recovery Key", color = TextPrimary)
                    }
                }
            }

            // ── 4. Active Sessions (Telegram-Style Device Manager) ────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Device Sessions", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("${sessions.size} devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }

                    sessions.forEach { sess ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurface)
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sess.device_info.ifEmpty { "Android Client" }, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text("${sess.ip_address} • ${sess.last_active_at}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                            if (!sess.is_current) {
                                IconButton(onClick = {
                                    scope.launch {
                                        repo.terminateSession(sess.id)
                                        refreshAll()
                                    }
                                }) {
                                    Icon(Icons.Default.Cancel, contentDescription = "Terminate", tint = RoseAccent)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                repo.terminateOtherSessions()
                                refreshAll()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoseAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Terminate All Other Sessions")
                    }
                }
            }

            // ── 5. Telegram & BYOS Bot Tokens ────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Private Node (BYOS) Bot Tokens", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Add multiple Telegram bot tokens for load balancing and unlimited bandwidth.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                    byosTokens.forEach { token ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurface)
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(token.bot_username ?: "Bot Token", fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text(token.token_preview, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    repo.deleteByosToken(token.id)
                                    refreshAll()
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RoseAccent)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddTokenDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Bot Token", color = TextPrimary)
                    }
                }
            }

            // ── 6. Configurable Instance API Base URL ─────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Instance API Base URL", fontWeight = FontWeight.Bold, color = TextPrimary)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                settingsRepo.setServerUrl(serverUrl)
                                app.rebuildApiClient(serverUrl)
                                Toast.makeText(context, "API URL updated to $serverUrl", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save URL")
                    }
                }
            }

            // ── 7. Logout ────────────────────────────────────────────────────────────
            Button(
                onClick = {
                    scope.launch {
                        authRepo.logout()
                        onLogout()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface, contentColor = RoseAccent),
                border = androidx.compose.foundation.BorderStroke(1.dp, RoseAccent.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out of Vault", fontWeight = FontWeight.Bold)
            }
        }
    }

    // Add Bot Token Dialog
    if (showAddTokenDialog) {
        var tokenInput by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddTokenDialog = false },
            title = { Text("Add Telegram Bot Token", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = { Text("123456789:ABCDef...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tokenInput.isNotBlank()) {
                            isVerifying = true
                            scope.launch {
                                val res = repo.verifyByosToken(tokenInput.trim())
                                isVerifying = false
                                if (res.isSuccess) {
                                    showAddTokenDialog = false
                                    refreshAll()
                                } else {
                                    Toast.makeText(context, "Token verification failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Text("Verify & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTokenDialog = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }
}
