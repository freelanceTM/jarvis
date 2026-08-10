# Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# AndroidX Crypto
-keep class androidx.security.crypto.** { *; }

# Models & DTOs
-keep class com.jarvis.assistant.data.remote.dto.** { *; }
-keep class com.jarvis.assistant.data.local.entity.** { *; }
-keep class com.jarvis.assistant.domain.models.** { *; }
