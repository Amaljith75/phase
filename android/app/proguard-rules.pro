# JNI entry points are looked up by name from native code.
-keep class app.phase.stem.audio.NativeEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
