package in.rapidxcloud.drive.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey

object SessionKeyStore {
    private var _encKey: SecretKey? = null
    private var _masterSecret: ByteArray? = null
    private var _authToken: String? = null
    private var _refreshToken: String? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun setKeys(encKey: SecretKey, masterSecret: ByteArray?) {
        _encKey = encKey
        _masterSecret = masterSecret
        _isUnlocked.value = true
    }

    fun getEncKey(): SecretKey? = _encKey

    fun getMasterSecret(): ByteArray? = _masterSecret

    fun setTokens(access: String?, refresh: String?) {
        _authToken = access
        _refreshToken = refresh
    }

    fun getAuthToken(): String? = _authToken
    fun getRefreshToken(): String? = _refreshToken

    fun clear() {
        _encKey = null
        _masterSecret?.fill(0)
        _masterSecret = null
        _authToken = null
        _refreshToken = null
        _isUnlocked.value = false
    }
}
