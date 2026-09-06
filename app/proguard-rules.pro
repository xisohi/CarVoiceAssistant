# Vosk：JNI 绑定需要保留
-keep class org.vosk.** { *; }
-keep class com.alphacephei.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
