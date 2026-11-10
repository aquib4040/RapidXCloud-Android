package `in`.rapidxcloud.drive.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.rapidxcloud.drive.data.repository.AuthRepository
import `in`.rapidxcloud.drive.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Auth theme color palette
private val AuthBg = Color(0xFF07080A)
private val AuthCardBg = Color(0xFF0D0F14)
private val AuthInputBg = Color(0xFF090B0E)
private val AuthHeaderTile = Color(0xFF111317)
private val AuthBorder = Color(0x14FFFFFF) // white/[0.08]
private val AuthInputBorder = Color(0x1AFFFFFF) // white/[0.1]
private val AuthTextPrimary = Color(0xFFF1F5F9)
private val AuthTextMuted = Color(0xFF94A3B8)
private val AuthTextDarkMuted = Color(0xFF64748B)
private val AuthPlaceholder = Color(0xFF475569)
private val AuthEmerald = Color(0xFF10B981)
private val AuthDanger = Color(0xFFEF4444)

@Composable
fun LoginScreen(
    authRepository: AuthRepository,
    settingsRepository: SettingsRepository,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf<String>("signin") } // "signin" or "signup"
    var emailOrUsername by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Live Username availability
    var isCheckingUsername by remember { mutableStateOf(false) }
    var isUsernameAvailable by remember { mutableStateOf<Boolean?>(null) }
    var usernameDebounceJob by remember { mutableStateOf<Job?>(null) }

    // Checkboxes (Signup)
    var acceptTerms by remember { mutableStateOf(false) }
    var acceptPrivacy by remember { mutableStateOf(false) }
    var acceptCookies by remember { mutableStateOf(false) }

    // Cloudflare Turnstile CAPTCHA state
    var turnstileToken by remember { mutableStateOf<String?>(null) }
    var turnstileSiteKey by remember { mutableStateOf("0x4AAAAAADwA3BWuz2NPDaMQ") }

    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var signupSuccessEmail by remember { mutableStateOf<String?>(null) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryCodeInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var isRecovering by remember { mutableStateOf(false) }

    fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun submit() {
        errorMessage = null

        val emailClean = emailOrUsername.trim()
        val passClean = password

        if (emailClean.isBlank()) {
            errorMessage = "Please enter your email or username."
            return
        }
        if (passClean.length < 6) {
            errorMessage = "Password must be at least 6 characters."
            return
        }

        if (mode == "signup") {
            if (username.trim().length < 3) {
                errorMessage = "Username must be at least 3 characters."
                return
            }
            if (passClean != confirmPassword) {
                errorMessage = "Passwords do not match."
                return
            }
            if (!acceptTerms || !acceptPrivacy || !acceptCookies) {
                errorMessage = "Please accept the Terms, Privacy Policy, and Cookie Policy."
                return
            }
            if (turnstileToken.isNullOrBlank()) {
                errorMessage = "Please complete the Cloudflare security verification."
                return
            }
        }

        isBusy = true
        scope.launch {
            if (mode == "signin") {
                val res = authRepository.login(emailClean, passClean, turnstileToken)
                isBusy = false
                if (res.isSuccess) {
                    onLoginSuccess()
                } else {
                    errorMessage = res.exceptionOrNull()?.message ?: "Authentication failed"
                }
            } else {
                val res = authRepository.signUp(emailClean, passClean, username.trim().lowercase(), turnstileToken)
                isBusy = false
                if (res.isSuccess) {
                    signupSuccessEmail = emailClean
                } else {
                    errorMessage = res.exceptionOrNull()?.message ?: "Account registration failed"
                }
            }
        }
    }

    Scaffold(
        containerColor = AuthBg,
        topBar = {
            // ── 1:1 Top Bar Header ──────────────────────────────────────────
            Surface(
                color = AuthBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, AuthBorder))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo + Brand Title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AuthHeaderTile)
                                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "RapidxCloud Logo",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            "RapidxCloud",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White,
                            letterSpacing = (-0.5).sp
                        )
                    }

                    // Green dot + Zero-Knowledge Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AuthEmerald)
                        )
                        Text(
                            "AES-256-GCM Zero-Knowledge Portal",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AuthTextDarkMuted
                        )
                    }
                }
            }
        },
        bottomBar = {
            // ── 1:1 Footer ──────────────────────────────────────────────────
            Surface(
                color = AuthBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, AuthBorder))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "© 2026 RapidxCloud · Zero-Knowledge Cryptography",
                        fontSize = 11.sp,
                        color = AuthTextDarkMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Maintained by", fontSize = 11.sp, color = AuthTextDarkMuted)
                        Text(
                            "aquib",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { openWebUrl("https://github.com/aquib4040") }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            // ── 1:1 Authentication Card Box ─────────────────────────────────
            Card(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 24.dp)
                    .border(1.dp, AuthBorder, RoundedCornerShape(10.dp)),
                colors = CardDefaults.cardColors(containerColor = AuthCardBg),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header Titles
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (mode == "signin") "Unlock Private Vault" else "Create Vault Account",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = if (mode == "signin")
                                "Enter your credentials to derive your encryption session."
                            else
                                "Start with 2 GB free forever. No credit card required.",
                            fontSize = 12.sp,
                            color = AuthTextMuted,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Mode Switcher Pill (Sign In | Create Account)
                    Surface(
                        color = AuthInputBg,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuthBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (mode == "signin") Color.White else Color.Transparent)
                                    .clickable {
                                        mode = "signin"
                                        errorMessage = null
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Sign In",
                                    fontSize = 12.sp,
                                    fontWeight = if (mode == "signin") FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (mode == "signin") Color(0xFF07080A) else AuthTextMuted
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (mode == "signup") Color.White else Color.Transparent)
                                    .clickable {
                                        mode = "signup"
                                        errorMessage = null
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Create Account",
                                    fontSize = 12.sp,
                                    fontWeight = if (mode == "signup") FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (mode == "signup") Color(0xFF07080A) else AuthTextMuted
                                )
                            }
                        }
                    }

                    if (signupSuccessEmail != null) {
                        // ── Verification Email Dispatched Card ───────────────────────
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AuthInputBg, RoundedCornerShape(6.dp))
                                .border(1.dp, AuthBorder, RoundedCornerShape(6.dp))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AuthEmerald,
                                modifier = Modifier.size(28.dp)
                            )
                            Text("Verify your email address", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp)
                            Text(
                                "We dispatched a secure activation link to $signupSuccessEmail. Click the link to complete account setup.",
                                fontSize = 12.sp,
                                color = AuthTextMuted,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = {
                                signupSuccessEmail = null
                                mode = "signin"
                            }) {
                                Text("Return to Login", color = Color.White, fontSize = 11.sp, textDecoration = TextDecoration.Underline)
                            }
                        }
                    } else {
                        // ── Input Fields Form ─────────────────────────────────────────
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Username Input (Signup Only)
                            if (mode == "signup") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Username", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                        if (isCheckingUsername) {
                                            Text("Checking...", fontSize = 10.sp, color = AuthTextDarkMuted)
                                        } else if (isUsernameAvailable == true) {
                                            Text("Available", fontSize = 10.sp, color = AuthEmerald, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    OutlinedTextField(
                                        value = username,
                                        onValueChange = { input ->
                                            username = input
                                            usernameDebounceJob?.cancel()
                                            if (input.trim().length >= 3) {
                                                isCheckingUsername = true
                                                usernameDebounceJob = scope.launch {
                                                    delay(300)
                                                    val res = authRepository.checkUsername(input.trim())
                                                    isCheckingUsername = false
                                                    isUsernameAvailable = res.getOrDefault(true)
                                                }
                                            } else {
                                                isCheckingUsername = false
                                                isUsernameAvailable = null
                                            }
                                        },
                                        placeholder = { Text("vaultuser", color = AuthPlaceholder, fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = AuthInputBg,
                                            unfocusedContainerColor = AuthInputBg,
                                            focusedBorderColor = Color(0x33FFFFFF),
                                            unfocusedBorderColor = AuthInputBorder,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                }
                            }

                            // Email Address (or Username / TG ID for Sign In)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Email Address", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                OutlinedTextField(
                                    value = emailOrUsername,
                                    onValueChange = { emailOrUsername = it },
                                    placeholder = { Text("name@example.com", color = AuthPlaceholder, fontSize = 12.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = AuthInputBg,
                                        unfocusedContainerColor = AuthInputBg,
                                        focusedBorderColor = Color(0x33FFFFFF),
                                        unfocusedBorderColor = AuthInputBorder,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                )
                            }

                            // Password Input with "Forgot?" Link
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (mode == "signin") "Master Password" else "Create Password",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                    if (mode == "signin") {
                                        Text(
                                            "Forgot?",
                                            fontSize = 11.sp,
                                            color = AuthTextMuted,
                                            modifier = Modifier.clickable { showRecoveryDialog = true }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    placeholder = { Text("••••••••••••", color = AuthPlaceholder, fontSize = 12.sp) },
                                    singleLine = true,
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showPassword = !showPassword }) {
                                            Icon(
                                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null,
                                                tint = AuthTextDarkMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = if (mode == "signin") ImeAction.Done else ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = AuthInputBg,
                                        unfocusedContainerColor = AuthInputBg,
                                        focusedBorderColor = Color(0x33FFFFFF),
                                        unfocusedBorderColor = AuthInputBorder,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                )
                            }

                            // Confirm Password & Terms Checkboxes (Signup Only)
                            if (mode == "signup") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Confirm Password", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                    OutlinedTextField(
                                        value = confirmPassword,
                                        onValueChange = { confirmPassword = it },
                                        placeholder = { Text("••••••••••••", color = AuthPlaceholder, fontSize = 12.sp) },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { submit() }),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = AuthInputBg,
                                            unfocusedContainerColor = AuthInputBg,
                                            focusedBorderColor = Color(0x33FFFFFF),
                                            unfocusedBorderColor = AuthInputBorder,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = acceptTerms,
                                            onCheckedChange = { acceptTerms = it },
                                            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black)
                                        )
                                        Text("I agree to the Terms of Service", fontSize = 11.sp, color = AuthTextMuted)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = acceptPrivacy,
                                            onCheckedChange = { acceptPrivacy = it },
                                            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black)
                                        )
                                        Text("I accept the Privacy Policy and zero-knowledge key custody terms", fontSize = 11.sp, color = AuthTextMuted)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = acceptCookies,
                                            onCheckedChange = { acceptCookies = it },
                                            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black)
                                        )
                                        Text("I consent to the Cookie Policy and essential local storage", fontSize = 11.sp, color = AuthTextMuted)
                                    }
                                }
                            }

                            // Error Display
                            if (errorMessage != null) {
                                Surface(
                                    color = AuthDanger.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, AuthDanger.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        errorMessage!!,
                                        color = AuthDanger,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            // Cloudflare Turnstile Bot Defense
                            TurnstileWidget(
                                siteKey = turnstileSiteKey,
                                onVerify = { token ->
                                    turnstileToken = token
                                    errorMessage = null
                                },
                                onError = {
                                    android.util.Log.w("LoginScreen", "Cloudflare Turnstile challenge notice")
                                }
                            )

                            // Submit Button (Pure High-Contrast White Background)
                            Button(
                                onClick = { submit() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .padding(top = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF07080A),
                                    disabledContainerColor = Color.White.copy(alpha = 0.5f)
                                )
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Deriving Cryptographic Session...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                } else {
                                    Text(
                                        if (mode == "signin") "Unlock Vault" else "Create Free Vault",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF07080A)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Master Recovery Key Dialog ───────────────────────────────────────
    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = {
                Text("Restore Vault with Recovery Key", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter your 32-character Crockford Master Recovery Key to restore zero-knowledge custody and re-wrap your vault credentials.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuthTextMuted
                    )
                    OutlinedTextField(
                        value = recoveryCodeInput,
                        onValueChange = { recoveryCodeInput = it },
                        placeholder = { Text("XXXX-XXXX-XXXX-XXXX...", color = AuthPlaceholder, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AuthInputBg,
                            unfocusedContainerColor = AuthInputBg,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = AuthInputBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = { newPasswordInput = it },
                        placeholder = { Text("New Master Password", color = AuthPlaceholder, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AuthInputBg,
                            unfocusedContainerColor = AuthInputBg,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = AuthInputBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isRecovering = true
                        scope.launch {
                            val res = authRepository.recoverWithKey(recoveryCodeInput.trim(), newPasswordInput)
                            isRecovering = false
                            if (res.isSuccess) {
                                showRecoveryDialog = false
                                onLoginSuccess()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text("Restore Vault", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryDialog = false }) {
                    Text("Cancel", color = AuthTextMuted)
                }
            },
            containerColor = AuthCardBg
        )
    }
}
