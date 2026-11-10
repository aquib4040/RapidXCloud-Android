package `in`.rapidxcloud.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.rapidxcloud.drive.data.repository.AuthState
import `in`.rapidxcloud.drive.ui.auth.LoginScreen
import `in`.rapidxcloud.drive.ui.navigation.AppNavigation
import `in`.rapidxcloud.drive.ui.theme.CanonDriveTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestAndroidPermissions()

        val app = application as CanonDriveApp
        val authRepo = app.authRepository
        val settingsRepo = app.settingsRepository

        // Handle System Share Target (Direct Upload from other apps)
        handleIncomingShareIntent(intent)

        setContent {
            CanonDriveTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val authState by authRepo.authState.collectAsState()

                    when (authState) {
                        is AuthState.Authenticated -> {
                            AppNavigation(
                                onLogout = { /* Handled inside AppNavigation */ }
                            )
                        }
                        else -> {
                            LoginScreen(
                                authRepository = authRepo,
                                settingsRepository = settingsRepo,
                                onLoginSuccess = { /* AuthState becomes Authenticated */ }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestAndroidPermissions() {
        // 1. Android 13+ Notification Permission (Required for foreground service status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. Android 11+ (API 30+) Full Storage Access (MANAGE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        // 3. Request Battery Optimization Exemption so background automated backup is never killed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Non-fatal fallback
                }
            }
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    android.widget.Toast.makeText(this, "Uploading shared file to vault...", android.widget.Toast.LENGTH_SHORT).show()
                    androidx.lifecycle.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val app = application as CanonDriveApp
                        val uploadManager = `in`.rapidxcloud.drive.transfer.UploadManager(this@MainActivity, app.apiClient)
                        val res = uploadManager.uploadFile(uri, folderId = null, isE2E = true)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (res.isSuccess) {
                                android.widget.Toast.makeText(this@MainActivity, "Shared file uploaded to vault!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(this@MainActivity, "Shared upload failed: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    android.widget.Toast.makeText(this, "Uploading ${uris.size} shared files to vault...", android.widget.Toast.LENGTH_SHORT).show()
                    androidx.lifecycle.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val app = application as CanonDriveApp
                        val uploadManager = `in`.rapidxcloud.drive.transfer.UploadManager(this@MainActivity, app.apiClient)
                        var success = 0
                        for (u in uris) {
                            val res = uploadManager.uploadFile(u, folderId = null, isE2E = true)
                            if (res.isSuccess) success++
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(this@MainActivity, "Uploaded $success/${uris.size} shared files!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
