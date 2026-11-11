package `in`.rapidxcloud.drive.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "rapidxcloud_settings")

class SettingsRepository(private val context: Context) {
    init {
        try {
            val legacyFile = java.io.File(context.filesDir, "datastore/canondrive_settings.preferences_pb")
            val newFile = java.io.File(context.filesDir, "datastore/rapidxcloud_settings.preferences_pb")
            if (legacyFile.exists() && !newFile.exists()) {
                legacyFile.parentFile?.mkdirs()
                legacyFile.renameTo(newFile)
            }
        } catch (_: Exception) {}
    }
    companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val KEY_AUTO_DELETE = booleanPreferencesKey("auto_delete_after_backup")
        val KEY_BACKUP_EXTENSIONS = stringSetPreferencesKey("backup_extensions")
        val KEY_BACKUP_INTERVAL_HOURS = intPreferencesKey("backup_interval_hours")
        val KEY_DUAL_KEY_ENABLED = booleanPreferencesKey("dual_key_enabled")
        val KEY_INSTANT_BACKUP = booleanPreferencesKey("instant_backup_on_write")
        val KEY_BACKUP_WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
        val KEY_BACKUP_CHARGING_ONLY = booleanPreferencesKey("backup_charging_only")
        val KEY_BACKUP_SCHEDULE = stringPreferencesKey("backup_schedule")
        val KEY_BACKUP_FOLDERS = stringSetPreferencesKey("backup_folders")

        const val DEFAULT_SERVER_URL = "https://drive.rapidxcloud.in"
        val DEFAULT_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "mov", "pdf", "docx", "zip")
        val DEFAULT_FOLDERS = setOf("Camera", "Pictures", "Screenshots", "WhatsApp", "Download")
    }

    val serverUrlFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    suspend fun getServerUrl(): String =
        context.dataStore.data.first()[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url.trim().trimEnd('/') }
    }

    val isBackupEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_BACKUP_ENABLED] ?: false
    }

    suspend fun isBackupEnabled(): Boolean =
        context.dataStore.data.first()[KEY_BACKUP_ENABLED] ?: false

    suspend fun setBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BACKUP_ENABLED] = enabled }
    }

    val isAutoDeleteFlow: Flow<Boolean> = context.dataStore.data.map {
        // Strictly default OFF as requested by user
        it[KEY_AUTO_DELETE] ?: false
    }

    suspend fun isAutoDeleteEnabled(): Boolean =
        context.dataStore.data.first()[KEY_AUTO_DELETE] ?: false

    suspend fun setAutoDeleteEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_DELETE] = enabled }
    }

    val backupExtensionsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_BACKUP_EXTENSIONS] ?: DEFAULT_EXTENSIONS
    }

    suspend fun getBackupExtensions(): List<String> =
        (context.dataStore.data.first()[KEY_BACKUP_EXTENSIONS] ?: DEFAULT_EXTENSIONS).toList()

    suspend fun setBackupExtensions(extensions: Set<String>) {
        context.dataStore.edit { it[KEY_BACKUP_EXTENSIONS] = extensions }
    }

    val isDualKeyFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_DUAL_KEY_ENABLED] ?: true
    }

    suspend fun isDualKeyEnabled(): Boolean =
        context.dataStore.data.first()[KEY_DUAL_KEY_ENABLED] ?: true

    suspend fun setDualKeyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DUAL_KEY_ENABLED] = enabled }
    }

    val isInstantBackupFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_INSTANT_BACKUP] ?: true
    }

    suspend fun setInstantBackup(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INSTANT_BACKUP] = enabled }
    }

    val isBackupWifiOnlyFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_BACKUP_WIFI_ONLY] ?: true
    }

    suspend fun isBackupWifiOnly(): Boolean =
        context.dataStore.data.first()[KEY_BACKUP_WIFI_ONLY] ?: true

    suspend fun setBackupWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BACKUP_WIFI_ONLY] = enabled }
    }

    val isBackupChargingOnlyFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_BACKUP_CHARGING_ONLY] ?: false
    }

    suspend fun isBackupChargingOnly(): Boolean =
        context.dataStore.data.first()[KEY_BACKUP_CHARGING_ONLY] ?: false

    suspend fun setBackupChargingOnly(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BACKUP_CHARGING_ONLY] = enabled }
    }

    val backupScheduleFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_BACKUP_SCHEDULE] ?: "instant"
    }

    suspend fun getBackupSchedule(): String =
        context.dataStore.data.first()[KEY_BACKUP_SCHEDULE] ?: "instant"

    suspend fun setBackupSchedule(schedule: String) {
        context.dataStore.edit { it[KEY_BACKUP_SCHEDULE] = schedule }
    }

    val backupFoldersFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_BACKUP_FOLDERS] ?: DEFAULT_FOLDERS
    }

    suspend fun getBackupFolders(): Set<String> =
        context.dataStore.data.first()[KEY_BACKUP_FOLDERS] ?: DEFAULT_FOLDERS

    suspend fun setBackupFolders(folders: Set<String>) {
        context.dataStore.edit { it[KEY_BACKUP_FOLDERS] = folders }
    }
}
