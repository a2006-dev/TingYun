# 停云 · ProGuard 规则
#
# 9.5：Bridge 方法靠 @JavascriptInterface 反射调用，
# 名字一旦被混淆网页就找不到，必须整类保留。

# Bridge：整类与方法名都不能动
-keep class com.tingyun.app.bridge.TingYunBridge { *; }
-keepclassmembers class com.tingyun.app.bridge.TingYunBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 数据类字段名就是 JSON 的 key，不能混淆
-keep class com.tingyun.app.data.Track { *; }

# media3 整包保留（ExoPlayer 内部大量反射）
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# androidx.webkit 的 WebViewAssetLoader 反射
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# WebView 相关
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# 保留行号，崩溃日志才可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile