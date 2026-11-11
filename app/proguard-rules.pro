# ProGuard configuration for RapidXCloud Android application

-keep class in.rapidxcloud.drive.data.model.** { *; }
-keep class in.rapidxcloud.drive.crypto.NativeCrypto { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Retrofit & Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# LibVLC Embedded Player Engine
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.libvlc.**
-dontwarn org.videolan.medialibrary.**

# Argon2 JVM & Native
-keep class com.lambdapioneer.argon2kt.** { *; }
-dontwarn com.lambdapioneer.argon2kt.**

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
