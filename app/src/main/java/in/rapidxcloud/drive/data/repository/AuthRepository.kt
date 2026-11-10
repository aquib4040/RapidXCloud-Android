package `in`.rapidxcloud.drive.data.repository

import android.content.Context
import android.util.Log
import `in`.rapidxcloud.drive.crypto.KeyHierarchy
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.AuthLoginResponse
import `in`.rapidxcloud.drive.data.model.KeyBag
import `in`.rapidxcloud.drive.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Authenticated(val user: UserProfile) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthRepository(
    private val context: Context,
    private val api: CanonDriveApi
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    companion object {
        private const val TAG = "AuthRepo"
    }

    suspend fun login(tgUserIdOrUsername: String, password: String):Result<UserProfile> = withContext(Dispatchers.IO) {
        _authState.value = AuthState.Loading
        try {
            val payload = mutableMapOf<String, Any>("password" to password)
            val tgId = tgUserIdOrUsername.toLongOrNull()
            if (tgId != null) {
                payload["tg_user_id"] = tgId
            } else {
                payload["username"] = tgUserIdOrUsername
            }

            val res = api.login(payload)
            if (!res.isSuccessful || res.body() == null || res.body()!!.status != "ok") {
                val err = res.body()?.detail ?: "Login failed (${res.code()})"
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

    suspend fun logout() {
        try {
            val refresh = SessionKeyStore.getRefreshToken()
            if (refresh != null) {
                api.logout(mapOf("refresh_token" to refresh))
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            SessionKeyStore.clear()
            _authState.value = AuthState.Idle
        }
    }
}
