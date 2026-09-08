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

# Protobuf, kept rather than merely un-warned-about.
#
# MediaPipe builds its calculator graph from protobuf messages, and the lite
# runtime resolves message fields reflectively *by name*. Obfuscating them turns
# graph construction into "Field typeUrl_ for com.google.protobuf.e not found",
# which surfaces as the face detector failing to start -- in release only.
-keep class com.google.protobuf.** { *; }
-keep class com.google.mediapipe.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
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

# MediaPipe logs through Google Flogger, and Flogger works out which class is
# logging by walking the call stack. R8 inlines the caller away, the walk finds
# nothing, and FluentLogger throws from inside the static initialiser of
# com.google.mediapipe.framework.Graph -- which takes the face detector with it.
#
# It fails only in release, and only on the camera path, so it survived every
# debug run and every unit test. The symptom on screen was "Camera unavailable:
# no delegate could run the face model"; the cause in the log was
# "IllegalStateException: no caller found on the stack for: o3.d", o3.d being
# Flogger after obfuscation.
#
# R8 does not honour -optimizations, so inlining is turned off wholesale. The
# cost is a slightly larger DEX in an APK that is 90 MB of native libraries and
# models; shrinking and obfuscation still run.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
-dontoptimize
