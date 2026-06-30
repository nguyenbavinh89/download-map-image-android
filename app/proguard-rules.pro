# Add project specific ProGuard rules here.

# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep MainActivity
-keep class com.downloadmapimage.app.MainActivity { *; }

# Androidx
-keep class androidx.** { *; }
-dontwarn androidx.**
