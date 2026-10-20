# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class com.wienerlinien.app.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
