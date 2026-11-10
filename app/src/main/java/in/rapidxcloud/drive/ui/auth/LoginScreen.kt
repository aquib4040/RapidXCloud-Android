package `in`.rapidxcloud.drive.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.rapidxcloud.drive.data.repository.AuthRepository
import `in`.rapidxcloud.drive.data.repository.AuthState
import `in`.rapidxcloud.drive.data.repository.SettingsRepository
import `in`.rapidxcloud.drive.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authRepository: AuthRepository,
    settingsRepository: SettingsRepository,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showServerConfig by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serverUrl = settingsRepository.getServerUrl()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Header with Server Config Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = CyanAccent)
                        }
                        Column {
                            Text(
                                "RapidXCloud",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                "Zero-Knowledge Vault",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    IconButton(onClick = { showServerConfig = !showServerConfig }) {
                        Icon(Icons.Default.Dns, contentDescription = "Server Config", tint = TextSecondary)
                    }
                }

                Divider(color = DarkBorder)

                // Optional Server Base URL configuration
                if (showServerConfig) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Instance API Base URL", style = MaterialTheme.typography.bodySmall, color = CyanAccent)
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    settingsRepository.setServerUrl(serverUrl)
                                    showServerConfig = false
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                        ) {
                            Text("Save URL", fontSize = 12.sp)
                        }
                    }
                }

                // Error alert
                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RoseAccent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .border(1.dp, RoseAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(errorMessage!!, color = RoseAccent, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Username / TG ID input
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("USERNAME OR TELEGRAM ID", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. 529482910 or username", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                // Password input
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("VAULT MASTER PASSWORD", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("••••••••••••", color = TextMuted) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                // Security Notice Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                    Text(
                        "Zero-Knowledge Dual-Key Split active. Password never touches the server.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                // Submit Button
                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            errorMessage = "Please enter both username/ID and password"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val result = authRepository.login(username.trim(), password)
                            isLoading = false
                            if (result.isSuccess) {
                                onLoginSuccess()
                            } else {
                                errorMessage = result.exceptionOrNull()?.message ?: "Login failed"
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text("Unlock Vault", fontWeight = FontWeight.Bold)
                    }
                }

                // Forgot Password / Recovery Key Link
                TextButton(
                    onClick = { showRecoveryDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Lost password? Unlock with Recovery Key", style = MaterialTheme.typography.bodySmall, color = CyanAccent)
                }
            }
        }
    }

    if (showRecoveryDialog) {
        RecoveryKeyDialog(
            authRepository = authRepository,
            onDismiss = { showRecoveryDialog = false },
            onSuccess = {
                showRecoveryDialog = false
                onLoginSuccess()
            }
        )
    }
}

@Composable
fun RecoveryKeyDialog(
    authRepository: AuthRepository,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var recoveryCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Recovery", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your 32-character recovery code (XXXXX-XXXXX-...) and choose a new master password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (err != null) {
                    Text(err!!, color = RoseAccent, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = recoveryCode,
                    onValueChange = { recoveryCode = it },
                    placeholder = { Text("Recovery Code") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    placeholder = { Text("New Password (min 8 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (recoveryCode.isBlank() || newPassword.length < 8) {
                        err = "Code is required and new password must be at least 8 characters"
                        return@Button
                    }
                    isSubmitting = true
                    err = null
                    scope.launch {
                        val res = authRepository.recoverWithKey(recoveryCode.trim(), newPassword)
                        isSubmitting = false
                        if (res.isSuccess) {
                            onSuccess()
                        } else {
                            err = res.exceptionOrNull()?.message ?: "Recovery failed"
                        }
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
            ) {
                Text("Restore Access")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = DarkCard
    )
}
