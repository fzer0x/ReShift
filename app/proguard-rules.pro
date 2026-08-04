# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\sasch\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# For Koin
-keep class org.koin.** { *; }

# For Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep ReShift core classes if needed, though R8 is usually smart enough
-keep class ox.fzer0x.snakeloader.data.** { *; }
-keep class ox.fzer0x.snakeloader.ZygiskConfig { *; }
-keep class ox.fzer0x.snakeloader.ZygiskAppEntry { *; }
