package `in`.rapidxcloud.drive.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import `in`.rapidxcloud.drive.data.model.UserProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class StoredSession(
    val accessToken: String,
    val refreshToken: String?,
    val encKeyBytesB64: String?,
    val masterSecretB64: String?,
    val userProfile: UserProfile?
)

/**
 * SecureSessionStorage persists user authentication tokens, derived encryption keys,
 * and user profile in Android Keystore-backed AES-256-GCM storage.
 *
 * This ensures:
 * 1. The user NEVER gets logged out when swiping the app away from RAM.
 * 2. Key material is encrypted at rest using a device-bound hardware Keystore key.
 * 3. Clearing or logging out wipes the key and preferences immediately.
 */
object SecureSessionStorage {
    private const val TAG = "SecureSession"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "RapidXCloudSessionMaster"
    private const val PREFS_NAME = "rapidxcloud_secure_session"
    private const val LEGACY_PREFS_NAME = "canondrive_secure_session"

    private const val KEY_ENCRYPTED_DATA = "enc_session_data"
    private const val KEY_IV = "enc_session_iv"

    private val gson = Gson()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        encKey: SecretKey?,
        masterSecret: ByteArray?,
        userProfile: UserProfile?
    ) {
        try {
            val encKeyB64 = encKey?.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val masterSecB64 = masterSecret?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

            val session = StoredSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                encKeyBytesB64 = encKeyB64,
                masterSecretB64 = masterSecB64,
                userProfile = userProfile
            )

            val json = gson.toJson(session)
            val masterKey = getOrCreateSecretKey()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_ENCRYPTED_DATA, Base64.encodeToString(cipherText, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()

            Log.i(TAG, "User session & encryption keys securely persisted to Android Keystore.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist secure session", e)
        }
    }

    fun loadSession(context: Context): StoredSession? {
        try {
            var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var cipherTextB64 = prefs.getString(KEY_ENCRYPTED_DATA, null)
            var ivB64 = prefs.getString(KEY_IV, null)

            // Transparent migration from legacy "canondrive_secure_session" if present
            if (cipherTextB64 == null || ivB64 == null) {
                val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val legCipher = legacyPrefs.getString(KEY_ENCRYPTED_DATA, null)
                val legIv = legacyPrefs.getString(KEY_IV, null)
                if (legCipher != null && legIv != null) {
                    cipherTextB64 = legCipher
                    ivB64 = legIv
                    prefs.edit()
                        .putString(KEY_ENCRYPTED_DATA, legCipher)
                        .putString(KEY_IV, legIv)
                        .apply()
                    legacyPrefs.edit().clear().apply()
                } else {
                    return null
                }
            }

            val cipherText = Base64.decode(cipherTextB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)

            val masterKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))

            val plainJson = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            return gson.fromJson(plainJson, StoredSession::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "No valid persisted session found or decryption failed: ${e.message}")
            return null
        }
    }

    fun clearSession(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            legacyPrefs.edit().clear().apply()

            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            Log.i(TAG, "Persisted session wiped cleanly.")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing session", e)
        }
    }
}
