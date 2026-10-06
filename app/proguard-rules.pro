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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- PDF Studio
# Shrink unused code but keep names: readable crash traces and no reflection surprises.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# PDFBox loads filters, fonts and encodings dynamically; BouncyCastle is looked up as a JCA provider.
-keep class com.tom_roush.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.apache.commons.logging.**

# Home-screen widgets are created by the launcher.
-keep class com.example.widget.** { *; }
