# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class org.fcitx.fcitx5.android.core.* { *; }
-keep class org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase {
    public <init>(...);
}

# Keep dependency magic
-keep class ** extends org.mechdancer.dependency.Component {
    int hashCode();
    boolean equals(java.lang.Object);
}

# Android Test runner is packaged separately but shares the target app classpath. The release
# shrinker must not remove shared runtime entry points while the test APK still references them.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.lifecycle.Lifecycle$State { *; }
-keep class androidx.lifecycle.LifecycleRegistry** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Preserve only the app ABI exercised by the release device gate. Production-only code remains
# shrinkable, while AndroidTest can call these members through the target app classpath.
-keep class org.fcitx.fcitx5.android.core.Fcitx { *; }
-keep class org.fcitx.fcitx5.android.core.FcitxAPI** { *; }
-keep class org.fcitx.fcitx5.android.core.FcitxEvent** { *; }
-keep class org.fcitx.fcitx5.android.core.InputMethodEntry { *; }
-keep class org.fcitx.fcitx5.android.core.RawConfig { *; }
-keep class org.fcitx.fcitx5.android.data.UserDataManager** { *; }

# remove kotlin null checks
-processkotlinnullchecks remove

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
