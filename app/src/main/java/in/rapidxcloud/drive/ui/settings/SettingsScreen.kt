package `in`.rapidxcloud.drive.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import `in`.rapidxcloud.drive.RapidXCloudApp
import `in`.rapidxcloud.drive.BuildConfig
import android.os.Build
import `in`.rapidxcloud.drive.updater.AppUpdateManager
import `in`.rapidxcloud.drive.updater.UpdateCheckResult
import `in`.rapidxcloud.drive.updater.DownloadProgress
import `in`.rapidxcloud.drive.backup.BackupForegroundService
import `in`.rapidxcloud.drive.crypto.KeyHierarchy
import `in`.rapidxcloud.drive.crypto.NativeCrypto
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.model.ActiveSession
import `in`.rapidxcloud.drive.data.model.BotToken
import `in`.rapidxcloud.drive.data.model.ShareItem
import `in`.rapidxcloud.drive.data.model.UserProfile
import `in`.rapidxcloud.drive.ui.components.QuotaProgressBar
import `in`.rapidxcloud.drive.ui.components.formatBytes
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as RapidXCloudApp
    val repo = app.driveRepository
    val settingsRepo = app.settingsRepository
    val authRepo = app.authRepository
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var sessions by remember { mutableStateOf<List<ActiveSession>>(emptyList()) }
    var byosTokens by remember { mutableStateOf<List<BotToken>>(emptyList()) }
    var activeShares by remember { mutableStateOf<List<ShareItem>>(emptyList()) }
    var maxBotTokens by remember { mutableStateOf(8) }
    var tierName by remember { mutableStateOf("Starter") }

    // Backup & Encryption State
    var isBackupEnabled by remember { mutableStateOf(false) }
    var isAutoDelete by remember { mutableStateOf(false) }
    var isDualKey by remember { mutableStateOf(true) }
    var isInstantBackup by remember { mutableStateOf(true) }
    var isBackupWifiOnly by remember { mutableStateOf(true) }
    var isBackupChargingOnly by remember { mutableStateOf(false) }
    var backupSchedule by remember { mutableStateOf("instant") }
    var backupExtensions by remember { mutableStateOf(setOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "mov")) }
    var backupFolders by remember { mutableStateOf(setOf("Camera", "Pictures", "Screenshots", "WhatsApp", "Download")) }
    var serverUrl by remember { mutableStateOf("") }

    // Profile Edit State
    var displayNameInput by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }
    var isSavingProfile by remember { mutableStateOf(false) }

    // BYOS State
    var byosEnabled by remember { mutableStateOf(false) }
    var channelIdInput by remember { mutableStateOf("") }
    var backupChannelIdInput by remember { mutableStateOf("") }
    var newBotTokenInput by remember { mutableStateOf("") }
    var isSavingByosSettings by remember { mutableStateOf(false) }
    var isVerifyingToken by remember { mutableStateOf(false) }

    // Telegram Link State
    var isLinkingTelegram by remember { mutableStateOf(false) }
    var isTelegramPolling by remember { mutableStateOf(false) }
    var isUnlinkingTelegram by remember { mutableStateOf(false) }
    var telegramPollingJob by remember { mutableStateOf<Job?>(null) }

    // Session TTL & Inactive Termination
    var autoTerminateDays by remember { mutableStateOf(30) }
    var isUpdatingTtl by remember { mutableStateOf(false) }
    var isTerminatingOthers by remember { mutableStateOf(false) }
    var isTerminatingInactive by remember { mutableStateOf(false) }

    // Recovery Key Dialog
    var showExportKeyDialog by remember { mutableStateOf(false) }
    var exportedRecoveryKey by remember { mutableStateOf<String?>(null) }

    // In-App Updater State
    val updateManager = remember { AppUpdateManager() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress>(DownloadProgress.Idle) }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun refreshAll() {
        scope.launch {
            val p = repo.getProfile(forceRefresh = true).getOrNull()
            profile = p
            p?.let { u ->
                displayNameInput = u.display_name ?: ""
                usernameInput = u.username ?: ""
                byosEnabled = u.byos_enabled
                channelIdInput = u.channel_id ?: ""
                backupChannelIdInput = u.backup_channel_id ?: ""
                autoTerminateDays = u.auto_terminate_days
            }

            sessions = repo.listSessions().getOrNull() ?: emptyList()

            val byosRes = repo.listByosTokens().getOrNull()
            byosTokens = byosRes?.tokens ?: emptyList()
            maxBotTokens = byosRes?.max_tokens ?: (if (p?.plan == "ultra") 45 else if (p?.plan == "pro") 20 else 8)
            tierName = if (p?.plan == "ultra") "Ultra Power" else if (p?.plan == "pro") "Pro Vault" else "Starter"

            val sharesRes = repo.listActiveShares().getOrNull()
            val allShares = mutableListOf<ShareItem>()
            sharesRes?.files?.let { allShares.addAll(it) }
            sharesRes?.folders?.let { allShares.addAll(it) }
            activeShares = allShares

            isBackupEnabled = settingsRepo.isBackupEnabled()
            isAutoDelete = settingsRepo.isAutoDeleteEnabled()
            isDualKey = settingsRepo.isDualKeyEnabled()
            isInstantBackup = settingsRepo.isBackupEnabled()
            isBackupWifiOnly = settingsRepo.isBackupWifiOnly()
            isBackupChargingOnly = settingsRepo.isBackupChargingOnly()
            backupSchedule = settingsRepo.getBackupSchedule()
            backupExtensions = settingsRepo.getBackupExtensions().toSet()
            backupFolders = settingsRepo.getBackupFolders()
            serverUrl = settingsRepo.getServerUrl()
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    DisposableEffect(Unit) {
        onDispose {
            telegramPollingJob?.cancel()
        }
    }

    val tabList = listOf(
        Pair("Profile", Icons.Default.Person),
        Pair("Backup", Icons.Default.CloudSync),
        Pair("Devices", Icons.Default.Devices),
        Pair("Telegram", Icons.Default.Send),
        Pair("Private Node", Icons.Default.Dns),
        Pair("Shares", Icons.Default.Share)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings & Vault", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp)
                        Text(
                            profile?.email ?: profile?.username ?: "rapidxcloud.in",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Top Navigation Tabs (1:1 Web Parity) ──────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = CyanAccent,
                edgePadding = 8.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CyanAccent
                    )
                }
            ) {
                tabList.forEachIndexed { index, (label, icon) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) },
                        selectedContentColor = CyanAccent,
                        unselectedContentColor = TextSecondary
                    )
                }
            }

            // ── Tab Content ──────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (selectedTab) {
                    // ─────────────────────────────────────────────────────────────────
                    // TAB 0: PROFILE & STORAGE (GENERAL)
                    // ─────────────────────────────────────────────────────────────────
                    0 -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        // Profile Overview Card
                        item {
                            profile?.let { user ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(CyanAccent.copy(alpha = 0.2f))
                                                        .border(1.dp, CyanAccent.copy(alpha = 0.5f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        (user.display_name ?: user.username ?: "U").take(1).uppercase(),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 22.sp,
                                                        color = CyanAccent
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        user.display_name ?: user.username ?: "Vault User",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 16.sp,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        "@${user.username ?: "user"}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(user.plan.uppercase(), fontWeight = FontWeight.Bold) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    labelColor = if (user.plan == "ultra") AmberAccent else CyanAccent,
                                                    containerColor = DarkSurface
                                                ),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (user.plan == "ultra") AmberAccent else CyanAccent
                                                )
                                            )
                                        }

                                        Divider(color = DarkBorder)

                                        // 10-Digit User ID and Permanent UUID
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("10-Digit User ID", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                                Text(
                                                    user.account_no?.toString() ?: user.id.take(10).uppercase(),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextPrimary
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val idVal = user.account_no?.toString() ?: user.id.take(10).uppercase()
                                                    copyToClipboard("User ID", idVal)
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Copy", fontSize = 11.sp)
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Permanent UUID", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                                Text(
                                                    user.id,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            OutlinedButton(
                                                onClick = { copyToClipboard("Account UUID", user.id) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Copy", fontSize = 11.sp)
                                            }
                                        }

                                        // Storage Quota Progress Bars
                                        Divider(color = DarkBorder)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Storage Ingested", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                            Text(formatBytes(user.storage_used), fontWeight = FontWeight.Bold, color = CyanAccent)
                                        }

                                        user.quotas?.let { q ->
                                            QuotaProgressBar(title = "Daily Upload Bandwidth", metric = q.upload)
                                            QuotaProgressBar(title = "Daily Download Bandwidth", metric = q.download)
                                        }

                                        // Admin Plan Override
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
                        }

                        // Edit Profile Form
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Edit Profile Details", fontWeight = FontWeight.Bold, color = TextPrimary)

                                    OutlinedTextField(
                                        value = displayNameInput,
                                        onValueChange = { displayNameInput = it },
                                        label = { Text("Display Name") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = usernameInput,
                                        onValueChange = { usernameInput = it },
                                        label = { Text("Username") },
                                        prefix = { Text("@", color = CyanAccent) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    Button(
                                        onClick = {
                                            isSavingProfile = true
                                            scope.launch {
                                                val res = repo.updateUserProfile(displayNameInput, usernameInput)
                                                isSavingProfile = false
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "Profile updated successfully", Toast.LENGTH_SHORT).show()
                                                    refreshAll()
                                                } else {
                                                    Toast.makeText(context, "Failed to update profile", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        enabled = !isSavingProfile,
                                        modifier = Modifier.align(Alignment.End),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                                    ) {
                                        if (isSavingProfile) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                                        } else {
                                            Text("Save Profile")
                                        }
                                    }
                                }
                            }
                        }

                        // Mobile Backup Summary Card (Links to dedicated Backup tab)
                        item {
                            val isByosUser = profile?.byos_enabled == true || profile?.is_admin == true
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (isBackupEnabled) EmeraldAccent.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.CloudSync,
                                                contentDescription = null,
                                                tint = if (isBackupEnabled) EmeraldAccent else CyanAccent
                                            )
                                            Text("Automated Mobile Backup", fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        Surface(
                                            color = if (isBackupEnabled) EmeraldAccent.copy(alpha = 0.2f) else DarkSurface,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text(
                                                if (isBackupEnabled) "Active" else "Disabled",
                                                color = if (isBackupEnabled) EmeraldAccent else TextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        "Zero-knowledge encrypted sync for photos, videos, and device files with Wi-Fi only, charging only, and rate-limiting controls.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { selectedTab = 1 },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface, contentColor = CyanAccent),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
                                        ) {
                                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Backup Settings", fontSize = 12.sp)
                                        }

                                        if (isByosUser) {
                                            Button(
                                                onClick = {
                                                    Toast.makeText(context, "Scanning phone for new photos & videos...", Toast.LENGTH_SHORT).show()
                                                    BackupForegroundService.start(context)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Back Up", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Zero-Knowledge E2EE & Master Recovery Key
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldAccent)
                                        Text("Zero-Knowledge Cryptography", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Crypto Engine", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                if (NativeCrypto.isAvailable) "Rust Native SDK v${NativeCrypto.engineVersion} (Active)"
                                                else "JVM Fallback (JCA)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (NativeCrypto.isAvailable) EmeraldAccent else Color(0xFFF59E0B)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Dual-Key Split (Proton model)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                if (isDualKey) "FileKey = ClientShare ⊕ ServerShare (Both required to decrypt)"
                                                else "Solo User Key active: Zero key shares are sent to backend!",
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
                                                        if (it) "Dual-Key Split enabled" else "Solo User Key active: Zero key shares stored on backend",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
                                    }

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
                        }

                        // Hardcoded Secure Cloud Gateway Status
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Encrypted Cloud Gateway", fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Surface(
                                            color = EmeraldAccent.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.3f))
                                        ) {
                                            Text(
                                                "SECURE TLS 1.3",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                fontSize = 10.sp,
                                                color = EmeraldAccent
                                            )
                                        }
                                    }
                                    Text(
                                        "Connected securely with edge certificate validation and AES-256-GCM transport defense.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        // App Updates & System Specifications
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("App Updates & System", fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text(
                                                "Current: v${BuildConfig.VERSION_NAME} • Min Android 11 (API 30)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                                        Surface(
                                            color = DarkSurface,
                                            shape = RoundedCornerShape(6.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                                        ) {
                                            Text(
                                                deviceAbi,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                color = CyanAccent
                                            )
                                        }
                                    }

                                    Text(
                                        "Checks GitHub Releases for new updates matching your device architecture with in-place background download, real-time notification, and direct installation.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    Button(
                                        onClick = {
                                            isCheckingUpdate = true
                                            downloadProgress = DownloadProgress.Idle
                                            scope.launch {
                                                val res = updateManager.checkForUpdate(BuildConfig.VERSION_NAME)
                                                isCheckingUpdate = false
                                                updateResult = res
                                                showUpdateDialog = true
                                            }
                                        },
                                        enabled = !isCheckingUpdate,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CyanAccent,
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        if (isCheckingUpdate) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                color = Color.Black,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Checking GitHub Releases...", fontWeight = FontWeight.Bold)
                                        } else {
                                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Check for Updates", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Sign Out
                        item {
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

                    // ─────────────────────────────────────────────────────────────────
                    // TAB 1: AUTOMATED MOBILE BACKUP (GOOGLE PHOTOS STYLE)
                    // ─────────────────────────────────────────────────────────────────
                    1 -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        val isByosUser = profile?.byos_enabled == true || profile?.is_admin == true

                        // Master Backup Toggle Card
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (isBackupEnabled) EmeraldAccent.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .background(
                                                        if (isBackupEnabled) EmeraldAccent.copy(alpha = 0.15f) else DarkSurface,
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.CloudSync,
                                                    contentDescription = null,
                                                    tint = if (isBackupEnabled) EmeraldAccent else TextSecondary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Column {
                                                Text("Backup & Sync", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                                                Text(
                                                    if (!isByosUser) "Requires Private Node (BYOS)"
                                                    else if (isBackupEnabled) "Backup is ON"
                                                    else "Backup is OFF",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (!isByosUser) AmberAccent else if (isBackupEnabled) EmeraldAccent else TextSecondary
                                                )
                                            }
                                        }

                                        if (isByosUser) {
                                            Switch(
                                                checked = isBackupEnabled,
                                                onCheckedChange = { checked ->
                                                    isBackupEnabled = checked
                                                    scope.launch {
                                                        settingsRepo.setBackupEnabled(checked)
                                                        if (checked) {
                                                            BackupForegroundService.start(context)
                                                            Toast.makeText(context, "Backup service started", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    if (!isByosUser) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(AmberAccent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                                .border(1.dp, AmberAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    "Automated mobile backup is exclusively available for Private Node (BYOS) users to ensure unlimited personal storage bandwidth. Please configure your Telegram Bot Token in the Private Node tab to activate.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = AmberAccent
                                                )
                                                Button(
                                                    onClick = { selectedTab = 4 },
                                                    colors = ButtonDefaults.buttonColors(containerColor = AmberAccent, contentColor = Color.Black),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Configure Private Node", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    } else {
                                        Text(
                                            "RapidXCloud automatically scans device directories and backs up photos, videos, and files with client-side AES-256-GCM zero-knowledge encryption directly to your vault.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )

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
                                            Text("Back Up Now", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Network & Charging Constraints (Google Photos Style)
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Wifi, contentDescription = null, tint = CyanAccent)
                                        Text("Network & Power Constraints", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }

                                    // Wi-Fi Only Toggle
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Back up over Wi-Fi only", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Text("Avoid consuming mobile carrier cellular data quota", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                        Switch(
                                            checked = isBackupWifiOnly,
                                            onCheckedChange = { checked ->
                                                isBackupWifiOnly = checked
                                                scope.launch { settingsRepo.setBackupWifiOnly(checked) }
                                            }
                                        )
                                    }

                                    Divider(color = DarkBorder)

                                    // Charging Only Toggle
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Back up while charging only", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Text("Sync only when connected to a charger to preserve battery", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                        Switch(
                                            checked = isBackupChargingOnly,
                                            onCheckedChange = { checked ->
                                                isBackupChargingOnly = checked
                                                scope.launch { settingsRepo.setBackupChargingOnly(checked) }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Backup Schedule & Pacing Card
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = AmberAccent)
                                        Text("Backup Schedule & Pacing", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }

                                    Text(
                                        "Select how often RapidXCloud checks for and ingests media files from your phone:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            Pair("instant", "Instant"),
                                            Pair("daily", "Daily"),
                                            Pair("weekly", "Weekly"),
                                            Pair("manual", "Manual")
                                        ).forEach { (schKey, label) ->
                                            FilterChip(
                                                selected = backupSchedule == schKey,
                                                onClick = {
                                                    backupSchedule = schKey
                                                    scope.launch { settingsRepo.setBackupSchedule(schKey) }
                                                },
                                                label = { Text(label, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CyanAccent,
                                                    selectedLabelColor = Color.Black
                                                )
                                            )
                                        }
                                    }

                                    // Rate Limiting & Flood Shield Indicator
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(DarkSurface, RoundedCornerShape(8.dp))
                                            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Default.Speed, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                                                Text("Frontend Rate Limiter & Flood Shield Active", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = EmeraldAccent)
                                            }
                                            Text(
                                                "750ms inter-file pacing + HTTP 429 Retry-After backoff protects device RAM, prevents crashes, and avoids rate limits.",
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Device Folders to Back Up
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = CyanAccent)
                                        Text("Device Folders to Back Up", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }

                                    Text(
                                        "Select device folders to monitor and back up automatically:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    val availableFolders = listOf("Camera", "Pictures", "Screenshots", "WhatsApp", "Download", "Movies")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        availableFolders.take(3).forEach { folder ->
                                            val isSelected = backupFolders.contains(folder)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    val updated = if (isSelected) backupFolders - folder else backupFolders + folder
                                                    backupFolders = updated
                                                    scope.launch { settingsRepo.setBackupFolders(updated) }
                                                },
                                                label = { Text(folder, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CyanAccent,
                                                    selectedLabelColor = Color.Black
                                                )
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        availableFolders.drop(3).forEach { folder ->
                                            val isSelected = backupFolders.contains(folder)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    val updated = if (isSelected) backupFolders - folder else backupFolders + folder
                                                    backupFolders = updated
                                                    scope.launch { settingsRepo.setBackupFolders(updated) }
                                                },
                                                label = { Text(folder, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CyanAccent,
                                                    selectedLabelColor = Color.Black
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // File Types & Extensions
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Extension, contentDescription = null, tint = CyanAccent)
                                        Text("File Formats & Extensions", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }

                                    Text(
                                        "Only files matching the enabled extensions below will be ingested:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    val availableExts = listOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "mov", "pdf", "docx", "zip")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableExts.take(5).forEach { ext ->
                                            val isSelected = backupExtensions.contains(ext)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    val updated = if (isSelected) backupExtensions - ext else backupExtensions + ext
                                                    backupExtensions = updated
                                                    scope.launch { settingsRepo.setBackupExtensions(updated) }
                                                },
                                                label = { Text(".$ext", fontSize = 10.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CyanAccent,
                                                    selectedLabelColor = Color.Black
                                                )
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableExts.drop(5).forEach { ext ->
                                            val isSelected = backupExtensions.contains(ext)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    val updated = if (isSelected) backupExtensions - ext else backupExtensions + ext
                                                    backupExtensions = updated
                                                    scope.launch { settingsRepo.setBackupExtensions(updated) }
                                                },
                                                label = { Text(".$ext", fontSize = 10.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CyanAccent,
                                                    selectedLabelColor = Color.Black
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Free Up Storage / Auto-Delete Original
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (isAutoDelete) AmberAccent.copy(alpha = 0.6f) else DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = AmberAccent)
                                            Text("Free up phone space", fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        Switch(
                                            checked = isAutoDelete,
                                            onCheckedChange = { checked ->
                                                isAutoDelete = checked
                                                scope.launch { settingsRepo.setAutoDeleteEnabled(checked) }
                                            }
                                        )
                                    }

                                    Text(
                                        "Automatically deletes original local files from your phone storage AFTER they have been successfully encrypted and uploaded to RapidXCloud.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    if (isAutoDelete) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(AmberAccent.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                "⚠️ Caution: Files deleted locally can only be accessed by downloading or streaming them from RapidXCloud.",
                                                fontSize = 11.sp,
                                                color = AmberAccent
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // End-to-End Encryption for Backups
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, EmeraldAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldAccent)
                                        Text("Zero-Knowledge Backup Encryption", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }

                                    Text(
                                        "Every backup file and its metadata (original name, mime type, size) is encrypted in RAM on-device using AES-256-GCM before transmission. Telegram and RapidXCloud servers can never see your unencrypted photos.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Dual-Key Split (Proton Model)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                if (isDualKey) "FileKey = ClientShare ⊕ ServerShare"
                                                else "Solo User Key active",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = EmeraldAccent
                                            )
                                        }
                                        Switch(
                                            checked = isDualKey,
                                            onCheckedChange = { checked ->
                                                isDualKey = checked
                                                scope.launch { settingsRepo.setDualKeyEnabled(checked) }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────────────────
                    // TAB 2: SESSIONS & DEVICES (TELEGRAM-STYLE DEVICE MANAGEMENT)
                    // ─────────────────────────────────────────────────────────────────
                    2 -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Active Device Sessions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                                    Text("Telegram-style authorized devices & clients", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                AssistChip(
                                    onClick = {},
                                    label = { Text("${sessions.size} devices") },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = DarkSurface, labelColor = CyanAccent)
                                )
                            }
                        }

                        // Sessions List
                        items(sessions) { sess ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (sess.is_current) CyanAccent.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(10.dp)),
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
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Default.Devices,
                                            contentDescription = null,
                                            tint = if (sess.is_current) CyanAccent else TextSecondary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    sess.device_info.ifEmpty { "Android Client" },
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                if (sess.is_current) {
                                                    Surface(
                                                        color = EmeraldAccent.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text("This Device", color = EmeraldAccent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                                    }
                                                }
                                            }
                                            Text(
                                                "${sess.ip_address} • ${sess.last_active_at}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    if (!sess.is_current) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    repo.terminateSession(sess.id)
                                                    refreshAll()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Cancel, contentDescription = "Terminate Session", tint = RoseAccent)
                                        }
                                    }
                                }
                            }
                        }

                        // Terminate All Other Sessions
                        item {
                            Button(
                                onClick = {
                                    isTerminatingOthers = true
                                    scope.launch {
                                        repo.terminateOtherSessions()
                                        isTerminatingOthers = false
                                        refreshAll()
                                        Toast.makeText(context, "All other sessions terminated", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isTerminatingOthers && sessions.count { !it.is_current } > 0,
                                colors = ButtonDefaults.buttonColors(containerColor = RoseAccent),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isTerminatingOthers) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                } else {
                                    Text("Terminate All Other Sessions")
                                }
                            }
                        }

                        // Auto-Terminate Inactive Devices
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Session Timeout & Auto-Termination", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Automatically revoke devices that haven't been active:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(7 to "1 Week", 30 to "1 Month", 90 to "3 Months").forEach { (days, label) ->
                                            FilterChip(
                                                selected = autoTerminateDays == days,
                                                onClick = {
                                                    autoTerminateDays = days
                                                    isUpdatingTtl = true
                                                    scope.launch {
                                                        repo.setSessionTtl(days)
                                                        isUpdatingTtl = false
                                                        Toast.makeText(context, "Session timeout set to $label", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                label = { Text(label) }
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            isTerminatingInactive = true
                                            scope.launch {
                                                repo.terminateInactiveSessions()
                                                isTerminatingInactive = false
                                                refreshAll()
                                                Toast.makeText(context, "Inactive sessions terminated", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = !isTerminatingInactive,
                                        modifier = Modifier.fillMaxWidth(),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                                    ) {
                                        Text("Terminate Inactive Devices Now", color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────────────────
                    // TAB 3: TELEGRAM INTEGRATION (BOT LINK)
                    // ─────────────────────────────────────────────────────────────────
                    3 -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        val isLinked = profile?.tg_user_id != null

                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (isLinked) EmeraldAccent.copy(alpha = 0.5f) else DarkBorder,
                                        RoundedCornerShape(12.dp)
                                    ),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                if (isLinked) Icons.Default.CheckCircle else Icons.Default.Send,
                                                contentDescription = null,
                                                tint = if (isLinked) EmeraldAccent else CyanAccent
                                            )
                                            Text(
                                                if (isLinked) "Telegram Account Connected" else "Telegram Account Link",
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        }
                                        Surface(
                                            color = if (isLinked) EmeraldAccent.copy(alpha = 0.2f) else DarkSurface,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                if (isLinked) "CONNECTED" else "UNLINKED",
                                                color = if (isLinked) EmeraldAccent else TextMuted,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    if (isLinked) {
                                        Text(
                                            "Linked to Telegram User ID: ${profile?.tg_user_id}",
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            "Your account can upload and stream files directly through the Telegram bot with real-time transfer updates.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )

                                        Button(
                                            onClick = {
                                                isUnlinkingTelegram = true
                                                scope.launch {
                                                    val res = repo.unlinkTelegram()
                                                    isUnlinkingTelegram = false
                                                    if (res.isSuccess) {
                                                        Toast.makeText(context, "Telegram disconnected", Toast.LENGTH_SHORT).show()
                                                        refreshAll()
                                                    } else {
                                                        Toast.makeText(context, "Failed to disconnect Telegram", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            enabled = !isUnlinkingTelegram,
                                            colors = ButtonDefaults.buttonColors(containerColor = RoseAccent),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isUnlinkingTelegram) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                            } else {
                                                Text("Disconnect Telegram")
                                            }
                                        }
                                    } else {
                                        Text(
                                            "Connect your Telegram account to enable bot transfers, mobile bot notifications, and direct Telegram access.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )

                                        if (isTelegramPolling) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(CyanAccent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                                    .padding(12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanAccent)
                                                    Text(
                                                        "Waiting for Telegram authorization...",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = CyanAccent
                                                    )
                                                }
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                isLinkingTelegram = true
                                                scope.launch {
                                                    val tokenResult = repo.getTelegramLinkToken()
                                                    isLinkingTelegram = false
                                                    tokenResult.onSuccess { url ->
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                        context.startActivity(intent)
                                                        Toast.makeText(context, "Opening Telegram bot...", Toast.LENGTH_SHORT).show()

                                                        // Start polling
                                                        isTelegramPolling = true
                                                        telegramPollingJob?.cancel()
                                                        telegramPollingJob = scope.launch {
                                                            var attempts = 0
                                                            while (attempts < 30) {
                                                                delay(3000)
                                                                attempts++
                                                                val me = repo.getProfile(forceRefresh = true).getOrNull()
                                                                if (me?.tg_user_id != null) {
                                                                    profile = me
                                                                    isTelegramPolling = false
                                                                    Toast.makeText(context, "Telegram account successfully connected!", Toast.LENGTH_SHORT).show()
                                                                    break
                                                                }
                                                            }
                                                            isTelegramPolling = false
                                                        }
                                                    }.onFailure {
                                                        Toast.makeText(context, "Failed to generate link token", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            enabled = !isLinkingTelegram,
                                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isLinkingTelegram) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                                            } else {
                                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Connect Telegram")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────────────────
                    // TAB 4: PRIVATE NODE / BYOS (DEDICATED BOT GATEWAY)
                    // ─────────────────────────────────────────────────────────────────
                    4 -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        // BYOS Toggle & Tier Limits
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Private Node (BYOS)", fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text("Dedicated Telegram channels and bot token pool", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                        Switch(
                                            checked = byosEnabled,
                                            onCheckedChange = { checked ->
                                                byosEnabled = checked
                                            }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Plan Tier: $tierName", style = MaterialTheme.typography.bodySmall, color = CyanAccent)
                                        Text("Tokens: ${byosTokens.size} / $maxBotTokens", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                    }
                                }
                            }
                        }

                        // Channels Configuration
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Telegram Channels", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Channels must begin with -100 (e.g. -1001234567890). Add your bot as Admin.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                                    OutlinedTextField(
                                        value = channelIdInput,
                                        onValueChange = { channelIdInput = it },
                                        label = { Text("Main Channel ID") },
                                        placeholder = { Text("-100...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = backupChannelIdInput,
                                        onValueChange = { backupChannelIdInput = it },
                                        label = { Text("Backup Channel ID (Optional)") },
                                        placeholder = { Text("-100...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    Button(
                                        onClick = {
                                            isSavingByosSettings = true
                                            scope.launch {
                                                val res = repo.saveByosSettings(
                                                    enabled = byosEnabled,
                                                    channelId = channelIdInput,
                                                    backupChannelId = backupChannelIdInput
                                                )
                                                isSavingByosSettings = false
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "BYOS settings saved successfully", Toast.LENGTH_SHORT).show()
                                                    refreshAll()
                                                } else {
                                                    Toast.makeText(context, "Failed to save BYOS settings", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        enabled = !isSavingByosSettings,
                                        modifier = Modifier.align(Alignment.End),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                                    ) {
                                        if (isSavingByosSettings) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                                        } else {
                                            Text("Save Channel Settings")
                                        }
                                    }
                                }
                            }
                        }

                        // Add Bot Token
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkCard)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Add Bot Token to Pool", fontWeight = FontWeight.Bold, color = TextPrimary)

                                    OutlinedTextField(
                                        value = newBotTokenInput,
                                        onValueChange = { newBotTokenInput = it },
                                        label = { Text("Telegram Bot Token") },
                                        placeholder = { Text("123456789:ABCDef...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    Button(
                                        onClick = {
                                            if (newBotTokenInput.isBlank()) {
                                                Toast.makeText(context, "Please enter a bot token", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            isVerifyingToken = true
                                            scope.launch {
                                                val res = repo.verifyByosToken(
                                                    botToken = newBotTokenInput.trim(),
                                                    channelId = channelIdInput.ifBlank { null },
                                                    backupChannelId = backupChannelIdInput.ifBlank { null }
                                                )
                                                isVerifyingToken = false
                                                if (res.isSuccess) {
                                                    newBotTokenInput = ""
                                                    Toast.makeText(context, "Bot token verified & saved!", Toast.LENGTH_SHORT).show()
                                                    refreshAll()
                                                } else {
                                                    Toast.makeText(context, "Token verification failed", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        enabled = !isVerifyingToken && newBotTokenInput.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                                    ) {
                                        if (isVerifyingToken) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                                        } else {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Verify & Add Bot Token")
                                        }
                                    }
                                }
                            }
                        }

                        // Bot Tokens List
                        items(byosTokens) { token ->
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
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(token.bot_username?.let { "@$it" } ?: "Bot Token", fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Surface(
                                                color = EmeraldAccent.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text("ACTIVE", color = EmeraldAccent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                            }
                                        }
                                        Text(
                                            token.token_preview,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                repo.deleteByosToken(token.id)
                                                refreshAll()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RoseAccent)
                                    }
                                }
                            }
                        }

                        // Parallel Engine Metrics
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, CyanAccent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                                        Text("High-Speed Parallel Upload Engine", fontWeight = FontWeight.Bold, color = CyanAccent, fontSize = 13.sp)
                                    }
                                    Text("• Multi-worker chunked streaming with parallel connections", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Text("• Adaptive chunk sizing and dynamic transport routing", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Text("• Real-time connection watchdog with automatic server failover", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────────────────
                    // TAB 5: ACTIVE PUBLIC SHARES
                    // ─────────────────────────────────────────────────────────────────
                    5 -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Active Public Shares", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                                    Text("Files and folders with active public download links", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                AssistChip(
                                    onClick = {},
                                    label = { Text("${activeShares.size} links") },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = DarkSurface, labelColor = CyanAccent)
                                )
                            }
                        }

                        if (activeShares.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkCard, RoundedCornerShape(12.dp))
                                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                                        Text("No active public share links", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        Text("Items you share publicly will appear here for 1-click management.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    }
                                }
                            }
                        } else {
                            items(activeShares) { share ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, DarkBorder, RoundedCornerShape(10.dp)),
                                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    if (share.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = if (share.isFolder) AmberAccent else CyanAccent,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Column {
                                                    Text(
                                                        share.displayName,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (share.size != null && share.size > 0) {
                                                            Text(formatBytes(share.size), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                                        }
                                                        if (share.password_protected) {
                                                            Surface(
                                                                color = AmberAccent.copy(alpha = 0.2f),
                                                                shape = RoundedCornerShape(3.dp)
                                                            ) {
                                                                Text("PIN Protected", color = AmberAccent, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Divider(color = DarkBorder)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "https://rapidxcloud.in/d/${share.effectiveToken.take(16)}...",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = TextMuted,
                                                modifier = Modifier.weight(1f)
                                            )

                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        val fullUrl = "https://rapidxcloud.in/d/${share.effectiveToken}"
                                                        copyToClipboard("Share Link", fullUrl)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Copy", fontSize = 11.sp)
                                                }

                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            val res = repo.revokeShareItem(share)
                                                            if (res.isSuccess) {
                                                                Toast.makeText(context, "Public share revoked", Toast.LENGTH_SHORT).show()
                                                                refreshAll()
                                                            } else {
                                                                Toast.makeText(context, "Failed to revoke share", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = RoseAccent),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text("Revoke", fontSize = 11.sp)
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
    }

    // ── Export / View Recovery Key Dialog ─────────────────────────────────────
    if (showExportKeyDialog) {
        val masterSecret = SessionKeyStore.getMasterSecret()
        if (exportedRecoveryKey == null) {
            exportedRecoveryKey = KeyHierarchy.generateRecoveryCode()
        }

        AlertDialog(
            onDismissRequest = { showExportKeyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = CyanAccent)
                    Text("Master Recovery Key", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your Recovery Key is the ONLY way to recover access to your end-to-end encrypted files if you ever forget your password. RapidxCloud support CANNOT restore your files without it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            exportedRecoveryKey ?: "GENERATING...",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = CyanAccent
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        exportedRecoveryKey?.let { code ->
                            copyToClipboard("Recovery Key", code)
                            showExportKeyDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy & Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportKeyDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // APP UPDATER MODAL DIALOG
    // ─────────────────────────────────────────────────────────────────
    if (showUpdateDialog && updateResult != null) {
        val result = updateResult!!

        AlertDialog(
            onDismissRequest = {
                if (downloadProgress !is DownloadProgress.Downloading) {
                    showUpdateDialog = false
                }
            },
            title = {
                when (result) {
                    is UpdateCheckResult.Available -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.NewReleases, contentDescription = null, tint = CyanAccent)
                            Text("Update Available: ${result.version}", fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }
                    is UpdateCheckResult.NoUpdate -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenAccent)
                            Text("Up to Date", fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RoseAccent)
                            Text("Update Check Failed", fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }
                }
            },
            text = {
                when (result) {
                    is UpdateCheckResult.Available -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = DarkSurface,
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        "Target ABI: ${result.compatibleAbi}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = CyanAccent
                                    )
                                }
                                Text(
                                    formatBytes(result.asset.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                            }

                            Text(
                                "Release Notes:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .background(DarkSurface, RoundedCornerShape(8.dp))
                                    .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    result.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            when (val prog = downloadProgress) {
                                is DownloadProgress.Downloading -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        LinearProgressIndicator(
                                            progress = prog.percent / 100f,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = CyanAccent,
                                            trackColor = DarkSurface
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "${formatBytes(prog.bytesDownloaded)} / ${formatBytes(prog.totalBytes)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                            Text(
                                                "${prog.percent}%",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = CyanAccent
                                            )
                                        }
                                        Text(
                                            "Downloading update with live notification progress...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                is DownloadProgress.Success -> {
                                    Surface(
                                        color = GreenAccent.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, GreenAccent.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = GreenAccent, modifier = Modifier.size(16.dp))
                                            Text(
                                                "Download complete! Ready to install.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = GreenAccent
                                            )
                                        }
                                    }
                                }
                                is DownloadProgress.Failed -> {
                                    Surface(
                                        color = RoseAccent.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, RoseAccent.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Error, contentDescription = null, tint = RoseAccent, modifier = Modifier.size(16.dp))
                                            Text(
                                                prog.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = RoseAccent
                                            )
                                        }
                                    }
                                }
                                is DownloadProgress.Idle -> {
                                    Text(
                                        "Tap 'Download & Install' to automatically upgrade to ${result.version}. The installer will replace the old version and preserve all your data.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    is UpdateCheckResult.NoUpdate -> {
                        Text(
                            "RapidXCloud v${result.currentVersion} is currently the latest version. No updates are required.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    is UpdateCheckResult.Error -> {
                        Text(
                            result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RoseAccent
                        )
                    }
                }
            },
            confirmButton = {
                when (result) {
                    is UpdateCheckResult.Available -> {
                        when (val prog = downloadProgress) {
                            is DownloadProgress.Downloading -> {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    colors = ButtonDefaults.buttonColors(disabledContainerColor = DarkSurface)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = CyanAccent, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Downloading...", color = TextSecondary)
                                }
                            }
                            is DownloadProgress.Success -> {
                                Button(
                                    onClick = {
                                        updateManager.launchInstall(context, prog.apkFile)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                                ) {
                                    Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Install Now", fontWeight = FontWeight.Bold)
                                }
                            }
                            else -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            updateManager.downloadAndInstall(context, result.asset) { p ->
                                                downloadProgress = p
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download & Install", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    else -> {
                        Button(
                            onClick = { showUpdateDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                        ) {
                            Text("OK", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                if (result is UpdateCheckResult.Available && downloadProgress !is DownloadProgress.Downloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("Later", color = TextSecondary)
                    }
                }
            },
            containerColor = DarkCard
        )
    }
}
