package `in`.rapidxcloud.drive

import android.app.Application
import androidx.room.Room
import `in`.rapidxcloud.drive.backup.BackupContentObserver
import `in`.rapidxcloud.drive.backup.BackupDeduplicator
import `in`.rapidxcloud.drive.backup.FolderHierarchyMapper
import `in`.rapidxcloud.drive.crypto.SessionKeyStore
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.local.AppDatabase
import `in`.rapidxcloud.drive.data.repository.AuthRepository
import `in`.rapidxcloud.drive.data.repository.DriveRepository
import `in`.rapidxcloud.drive.data.repository.SettingsRepository
import `in`.rapidxcloud.drive.transfer.FileDownloader
import `in`.rapidxcloud.drive.transfer.FolderDownloader
import `in`.rapidxcloud.drive.transfer.ThumbnailCacheManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class CanonDriveApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var apiClient: CanonDriveApi
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var driveRepository: DriveRepository
        private set

    lateinit var backupDeduplicator: BackupDeduplicator
        private set

    lateinit var folderHierarchyMapper: FolderHierarchyMapper
        private set

    lateinit var folderDownloader: FolderDownloader
        private set

    lateinit var fileDownloader: FileDownloader
        private set

    lateinit var thumbnailCacheManager: ThumbnailCacheManager
        private set

    lateinit var byosSessionPool: `in`.rapidxcloud.drive.transfer.byos.ByosSessionPool
        private set

    lateinit var uploadQueueManager: `in`.rapidxcloud.drive.transfer.UploadQueueManager
        private set

    private var backupObserver: BackupContentObserver? = null

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Room Database
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "canondrive.db"
        ).fallbackToDestructiveMigration().build()

        // 2. Settings DataStore Repository
        settingsRepository = SettingsRepository(applicationContext)

        // 3. Setup OkHttp and Retrofit
        val baseUrl = runBlocking { settingsRepository.getServerUrl() }
        apiClient = buildApiClient(baseUrl)

        // 4. Repositories and Managers
        authRepository = AuthRepository(applicationContext, apiClient)
        driveRepository = DriveRepository(apiClient, database)
        backupDeduplicator = BackupDeduplicator(applicationContext, database.backupDao())
        folderHierarchyMapper = FolderHierarchyMapper(apiClient)
        folderDownloader = FolderDownloader(applicationContext, apiClient)
        fileDownloader = FileDownloader(applicationContext, apiClient)
        thumbnailCacheManager = ThumbnailCacheManager(applicationContext, apiClient)
        byosSessionPool = `in`.rapidxcloud.drive.transfer.byos.ByosSessionPool(apiClient)
        uploadQueueManager = `in`.rapidxcloud.drive.transfer.UploadQueueManager(applicationContext, apiClient, byosSessionPool)

        // 5. Register automated background MediaStore content observer
        backupObserver = BackupContentObserver(applicationContext).apply {
            register()
        }

        // 6. Clean legacy update files left from previous app installations
        `in`.rapidxcloud.drive.updater.AppUpdateManager().cleanOldUpdateFiles(this)
    }

    fun rebuildApiClient(newBaseUrl: String) {
        apiClient = buildApiClient(newBaseUrl)
        authRepository = AuthRepository(applicationContext, apiClient)
        driveRepository = DriveRepository(apiClient, database)
        folderHierarchyMapper = FolderHierarchyMapper(apiClient)
        folderDownloader = FolderDownloader(applicationContext, apiClient)
        fileDownloader = FileDownloader(applicationContext, apiClient)
        thumbnailCacheManager = ThumbnailCacheManager(applicationContext, apiClient)
        byosSessionPool = `in`.rapidxcloud.drive.transfer.byos.ByosSessionPool(apiClient)
        uploadQueueManager = `in`.rapidxcloud.drive.transfer.UploadQueueManager(applicationContext, apiClient, byosSessionPool)
    }

    private fun buildApiClient(baseUrl: String): CanonDriveApi {
        val authInterceptor = Interceptor { chain ->
            val orig = chain.request()
            val builder = orig.newBuilder()
                .header("Content-Type", "application/json")
                .header("X-Device-Fingerprint", "android-${android.os.Build.MODEL}-${android.os.Build.ID}")

            val token = SessionKeyStore.getAuthToken()
            if (!token.isNullOrEmpty()) {
                builder.header("Authorization", "Bearer $token")
            }

            chain.proceed(builder.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CanonDriveApi::class.java)
    }

    override fun onTerminate() {
        super.onTerminate()
        backupObserver?.unregister()
    }
}
