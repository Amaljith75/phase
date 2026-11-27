# ---- Required keeps ----------------------------------------------------
# JNI entry points are looked up by name from native code.
-keep class app.phase.stem.audio.NativeEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
# ONNX Runtime: classes are instantiated from native code.
-keep class ai.onnxruntime.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Hardening against reverse engineering ----------------------------
# Flatten every obfuscated class into one anonymous package and drop
# source-file names and line numbers from the shipped bytecode.
-repackageclasses ''
-allowaccessmodification
-renamesourcefileattribute ''
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# Strip debug/verbose/info logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
