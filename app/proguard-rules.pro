# OkHttp
-dontwarn okhttp3.**

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

# ============================================================================
# Этап 2 — Local AI: MediaPipe LLM Inference (com.google.mediapipe:tasks-genai)
# ============================================================================
# Нативный слой вызывает Java-классы через JNI, поэтому имена и члены
# MediaPipe-классов удалять/переименовывать нельзя.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# protobuf-javalite: рефлексия по generated-классам.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

# Guava (транзитивная зависимость tasks-genai) тянет @GwtCompatible и
# аннотации, которых нет в Android-classpath.
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**

# Наш локальный слой: LocalModelSpec конфигурирует модель по имени файла.
-keep class com.jarvis.assistant.agent.localai.LocalModelSpec { *; }
