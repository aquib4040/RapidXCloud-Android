package `in`.rapidxcloud.drive.data.repository

import android.content.Context
import android.util.Log
import `in`.rapidxcloud.drive.crypto.KeyHierarchy
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.api.RapidXCloudApi
import `in`.rapidxcloud.drive.data.model.AuthLoginResponse
import `in`.rapidxcloud.drive.data.model.KeyBag
import `in`.rapidxcloud.drive.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Authenticated(val user: UserProfile) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthRepository(
    private val context: Context,
    private val api: RapidXCloudApi
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    companion object {
        private const val TAG = "AuthRepo"
    }

    private fun parseErrorMessage(jsonStr: String?, defaultMsg: String): String {
        if (jsonStr.isNullOrBlank()) return defaultMsg
        return try {
            val obj = JSONObject(jsonStr)
            when {
                obj.has("msg") -> obj.getString("msg")
                obj.has("error_description") -> obj.getString("error_description")
                obj.has("message") -> obj.getString("message")
                obj.has("status") -> obj.getString("status")
                else -> defaultMsg
            }
        } catch (_: Exception) {
            defaultMsg
        }
    }

    suspend fun checkUsername(username: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = api.checkUsernameAvailable(username.trim().lowercase())
            if (res.isSuccessful && res.body() != null) {
                val available = res.body()!!["available"] as? Boolean ?: false
                Result.success(available)
            } else {
                Result.success(true)
            }
        } catch (_: Exception) {
            Result.success(true)
        }
    }

    suspend fun signUp(
        email: String,
        password: String,
        username: String,
        captchaToken: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        _authState.value = AuthState.Loading
        try {
            val credential = try {
                KeyHierarchy.deriveAuthKeyHex(email, password)
            } catch (e: Exception) {
                Log.w(TAG, "Argon2 derivation error, using raw password: ${e.message}")
                password
            }

            val body = mutableMapOf<String, Any>(
                "email" to email.trim(),
                "password" to credential,
                "data" to mapOf(
                    "username" to username.trim().lowercase(),
                    "terms_accepted_at" to java.time.Instant.now().toString(),
                    "privacy_accepted_at" to java.time.Instant.now().toString(),
                    "cookies_accepted_at" to java.time.Instant.now().toString()
                )
            )

            if (!captchaToken.isNullOrBlank()) {
                body["gotrue_meta_security"] = mapOf("captcha_token" to captchaToken)
            }

            val res = api.supabaseSignUp(body = body)

            if (!res.isSuccessful || res.body() == null) {
                val errJson = res.errorBody()?.string()
                val errMsg = parseErrorMessage(errJson, "Registration failed (${res.code()})")
                _authState.value = AuthState.Error(errMsg)
                return@withContext Result.failure(Exception(errMsg))
            }

            val resBody = res.body()!!
            val accessToken = resBody["access_token"] as? String
            val refreshToken = resBody["refresh_token"] as? String

            if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                SessionKeyStore.setTokens(accessToken, refreshToken)
                tryUnlockOrEnrollKeyBag(password)
                val profileRes = api.getProfile()
                profileRes.body()?.user?.let { u ->
                    _authState.value = AuthState.Authenticated(u)
                    `in`.rapidxcloud.drive.crypto.SecureSessionStorage.saveSession(
                        context = context,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        encKey = SessionKeyStore.getEncKey(),
                        masterSecret = SessionKeyStore.getMasterSecret(),
                        userProfile = u
                    )
                }
            } else {
                _authState.value = AuthState.Idle
            }
            Result.success(true)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Registration error")
            Result.failure(e)
        }
    }

    suspend fun login(
        tgUserIdOrUsername: String,
        password: String,
        captchaToken: String? = null
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        _authState.value = AuthState.Loading
        try {
            // 1. If it looks like an email, authenticate with standard auth endpoint
            if (tgUserIdOrUsername.contains("@")) {
                val credential = try {
                    KeyHierarchy.deriveAuthKeyHex(tgUserIdOrUsername, password)
                } catch (e: Exception) {
                    Log.w(TAG, "Argon2 derivation fallback: ${e.message}")
                    password
                }

                val supaBody = mutableMapOf<String, Any>(
                    "email" to tgUserIdOrUsername.trim(),
                    "password" to credential
                )
                if (!captchaToken.isNullOrBlank()) {
                    supaBody["gotrue_meta_security"] = mapOf("captcha_token" to captchaToken)
                }

                val supaRes = api.supabaseSignIn(body = supaBody)
                if (supaRes.isSuccessful && supaRes.body() != null) {
                    val body = supaRes.body()!!
                    val accessToken = body["access_token"] as? String
                    val refreshToken = body["refresh_token"] as? String
                    if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                        SessionKeyStore.setTokens(accessToken, refreshToken)
                        tryUnlockOrEnrollKeyBag(password)
                        val profileRes = api.getProfile()
                        val user = profileRes.body()?.user ?: UserProfile(
                            id = body["user"]?.let { (it as? Map<*, *>)?.get("id")?.toString() } ?: "user",
                            email = tgUserIdOrUsername,
                            username = tgUserIdOrUsername.substringBefore("@")
                        )
                        _authState.value = AuthState.Authenticated(user)
                        return@withContext Result.success(user)
                    }
                } else if (!supaRes.isSuccessful) {
                    val errJson = supaRes.errorBody()?.string()
                    val errMsg = parseErrorMessage(errJson, "Sign in failed (${supaRes.code()})")
                    _authState.value = AuthState.Error(errMsg)
                    return@withContext Result.failure(Exception(errMsg))
                }
            }

            // 2. Direct backend /api/auth/login (Username / TG ID)
            val payload = mutableMapOf<String, Any>("password" to password)
            val tgId = tgUserIdOrUsername.toLongOrNull()
            if (tgId != null) {
                payload["tg_user_id"] = tgId
            } else {
                payload["username"] = tgUserIdOrUsername
            }

            val res = api.login(payload)
            if (!res.isSuccessful || res.body() == null || res.body()!!.status != "ok") {
                val err = res.body()?.detail ?: "Invalid credentials (${res.code()})"
                _authState.value = AuthState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            val loginData = res.body()!!
            SessionKeyStore.setTokens(loginData.access_token, loginData.refresh_token)

            // Unlock or Enroll E2EE KeyBag
            tryUnlockOrEnrollKeyBag(password)

            val profileRes = api.getProfile()
            val user = profileRes.body()?.user ?: loginData.user ?: throw Exception("Profile load error")

            _authState.value = AuthState.Authenticated(user)
            `in`.rapidxcloud.drive.crypto.SecureSessionStorage.saveSession(
                context = context,
                accessToken = loginData.access_token ?: "",
                refreshToken = loginData.refresh_token,
                encKey = SessionKeyStore.getEncKey(),
                masterSecret = SessionKeyStore.getMasterSecret(),
                userProfile = user
            )
            return@withContext Result.success(user)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Authentication error")
            return@withContext Result.failure(e)
        }
    }

    private suspend fun tryUnlockOrEnrollKeyBag(password: String) {
        try {
            val bagRes = api.getKeyBag()
            val bag = bagRes.body()?.key_bag
            if (bag != null) {
                val (encKey, m) = KeyHierarchy.unlockWithPassword(bag, password)
                SessionKeyStore.setKeys(encKey, m)
                Log.i(TAG, "Master encryption key unlocked successfully from key bag.")
            } else {
                // New user enrollment
                val (encKey, newBag, recoveryCode) = KeyHierarchy.enrollMaster(password)
                val enrollRes = api.enrollKeyBag(mapOf("key_bag" to newBag))
                if (enrollRes.isSuccessful) {
                    SessionKeyStore.setKeys(encKey, null)
                    Log.i(TAG, "Master encryption key enrolled for new account.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Key bag unlock notice: ${e.message}")
        }
    }

    suspend fun recoverWithKey(recoveryCode: String, newPassword: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val bagRes = api.getKeyBag()
            val bag = bagRes.body()?.key_bag ?: return@withContext Result.failure(Exception("No key bag found on server"))
            val (encKey, newWrappedPw, m) = KeyHierarchy.recoverWithCode(bag, recoveryCode, newPassword)

            val rewrapRes = api.rewrapKeyBag(mapOf("wrapped_pw" to newWrappedPw))
            if (rewrapRes.isSuccessful) {
                SessionKeyStore.setKeys(encKey, m)
                return@withContext Result.success(true)
            } else {
                return@withContext Result.failure(Exception("Failed to re-wrap key bag"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun restoreSessionIfAvailable(): Boolean = withContext(Dispatchers.IO) {
        val stored = `in`.rapidxcloud.drive.crypto.SecureSessionStorage.loadSession(context) ?: return@withContext false
        if (stored.accessToken.isBlank()) return@withContext false

        Log.i(TAG, "Restoring active session from secure Keystore storage...")
        SessionKeyStore.setTokens(stored.accessToken, stored.refreshToken)

        if (!stored.encKeyBytesB64.isNullOrBlank()) {
            try {
                val rawKey = android.util.Base64.decode(stored.encKeyBytesB64, android.util.Base64.NO_WRAP)
                val masterSec = stored.masterSecretB64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                SessionKeyStore.setKeysFromBytes(rawKey, masterSec)
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring encryption key bytes: ${e.message}")
            }
        }

        if (stored.userProfile != null) {
            _authState.value = AuthState.Authenticated(stored.userProfile)
        }

        // Validate or refresh with server in background
        try {
            val res = api.getProfile()
            if (res.isSuccessful && res.body()?.user != null) {
                val fresh = res.body()!!.user
                _authState.value = AuthState.Authenticated(fresh)
                `in`.rapidxcloud.drive.crypto.SecureSessionStorage.saveSession(
                    context = context,
                    accessToken = stored.accessToken,
                    refreshToken = stored.refreshToken,
                    encKey = SessionKeyStore.getEncKey(),
                    masterSecret = SessionKeyStore.getMasterSecret(),
                    userProfile = fresh
                )
            } else if (res.code() == 401) {
                Log.w(TAG, "Session expired on server (HTTP 401). Prompting re-auth.")
                logout()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline or transient error validating session: ${e.message}")
        }
        return@withContext true
    }

    suspend fun logout() {
        try {
            val refresh = SessionKeyStore.getRefreshToken()
            if (refresh != null) {
                api.logout(mapOf("refresh_token" to refresh))
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            `in`.rapidxcloud.drive.crypto.SecureSessionStorage.clearSession(context)
            SessionKeyStore.clear()
            _authState.value = AuthState.Idle
        }
    }
}
