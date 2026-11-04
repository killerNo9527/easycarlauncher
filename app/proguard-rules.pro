# 本项目没有反射（全项目 grep 无 Class.forName / getMethod），
# 但有几处「名字被系统或自己按字符串用到」的地方，必须显式保住。

# 崩溃兜底靠读真实类名，混淆后日志里全是 a.b.c 就没法定位了
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 桌面/页面的 public 组件由 Intent 按类名启动（含 component 显式启动）
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 布局里以全限定名直接引用的自绘 View（WeatherGlyphView 等）
-keep class com.lf.launcher.weather.** { *; }

# Media3 session 的 aidl/Binder 代理需要保留
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**
