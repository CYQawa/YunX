# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 迅雷应用内验证 WebView：保留所有 @JavascriptInterface 方法（防止 release 混淆后页面调不到桥）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepnames class com.yunx.app.ui.login.XunleiVerifyWebViewScreen*
-keepnames class com.yunx.app.ui.login.XunleiLoginScreen*

# Room：保留 @Entity / @Dao / @Database 类及成员（KSP 实现依赖反射读字段名）
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keepclassmembers @androidx.room.Entity class * { <fields>; }

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile