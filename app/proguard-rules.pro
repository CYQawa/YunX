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

# Gopeed（gomobile 桥接）：libgojni.so 内部用 FindClass 按「类名 + 方法名」反查这些类
# （go/Seq、go/Universe$proxyerror、com/gopeed/libgopeed/*），R8 改名或裁剪会让 .so
# dlopen 之后找不到类而崩溃。规则与 AAR 自带 proguard.txt 一致。
-keep class go.** { *; }
-keep class com.gopeed.** { *; }

# Room：保留 @Entity / @Dao / @Database 类及成员（KSP 实现依赖反射读字段名）
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# Room 实体字段名即列名，禁止混淆/重命名字段
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
}

# multiplatform-markdown-renderer（mikepenz）：保留其内部模型/解析类，避免 R8 裁剪 GFM 节点
-keep class com.mikepenz.** { *; }
-dontwarn com.mikepenz.**

# 反注入自检依赖入口类名（entryMismatch），显式保留
-keepnames class com.yunx.app.YunXApp
-keepnames class com.yunx.app.MainActivity
-keepnames class com.yunx.app.MainActivityIcon2

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile