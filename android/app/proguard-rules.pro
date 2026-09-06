# TFLite and its delegates resolve classes reflectively from native code, so R8
# cannot see the references and would strip them.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# MediaPipe Tasks: the JNI layer instantiates these by name.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }

# Protobuf classes MediaPipe references only for profiling and graph templates.
# Neither is used here, so warning about their absence is noise.
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.protobuf.**

# AutoValue's annotation processor is a compile-time dependency that leaks into
# the runtime classpath via ML Kit. javax.lang.model does not exist on Android
# and is never reached at runtime.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**


# Room generates implementations that are looked up by name.
-keep class com.nila.data.** { *; }

# ONNX Runtime reaches into these from JNI, so the shrinker cannot see the
# references. Without this the release build strips the classes the native
# library looks up by name and PP-OCR dies with a NoSuchMethodError on first use
# -- in release only, which is the worst place to find it.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**

# LlmBridge loads MediaPipe GenAI reflectively so the app builds without it.
-dontwarn com.google.mediapipe.tasks.genai.**
