# App-owned R8 rules. Dependencies are expected to ship consumer rules; add any
# further keep/dontwarn rule only in response to a reproduced release failure.

# Preserve runtime-visible annotations used by generated serializers and DI.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# MediaPipe LLM's public Java wrappers are reached from its native/JNI layer.
# Keep only that API package, not all MediaPipe/protobuf/Guava classes.
-keep class com.google.mediapipe.tasks.genai.llminference.LlmInference { *; }
-keep class com.google.mediapipe.tasks.genai.llminference.LlmInference$* { *; }
-keep class com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession { *; }
-keep class com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession$* { *; }

# Generic JNI entry points may be invoked by symbol/name from native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# tasks-genai references compile-only AutoValue annotations and its optional
# multimodal MPImage API. This app uses text-only inference and never calls
# addImage/createImage, so these exact absent types are safe to ignore. Keep the
# suppression narrow: a new missing class must still fail the release build.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor

# No blanket DTO/entity/domain keeps and no broad warning suppression:
# kotlinx.serialization, Room, OkHttp and AndroidX publish generated references or
# consumer rules. A release build must expose any genuinely missing rule.
