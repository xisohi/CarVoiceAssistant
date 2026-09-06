# Vosk：JNI 绑定需要保留
-keep class org.vosk.** { *; }
-keep class com.alphacephei.** { *; }

# Porcupine：JNI 绑定需要保留
-keep class ai.picovoice.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
