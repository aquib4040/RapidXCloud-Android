# ProGuard configuration for CanonDrive Android application

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
