# ReShift ProGuard & R8 Optimization Rules

# Koin Dependency Injection
-keep class org.koin.** { *; }

# Gson Serialization & Annotations
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod,InnerClasses

# Keep Data Models for JSON Serialization/Deserialization
-keep class ox.fzer0x.snakeloader.data.** { *; }
-keep class ox.fzer0x.snakeloader.network.** { *; }
-keep class ox.fzer0x.snakeloader.CodeShareProject { *; }
-keep class ox.fzer0x.snakeloader.DownloadedScript { *; }
-keep class ox.fzer0x.snakeloader.ZygiskConfig { *; }
-keep class ox.fzer0x.snakeloader.ZygiskAppEntry { *; }

# Keep IL2CPP Data Models (declared in ui.viewmodels) for Gson
-keep class ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData { *; }
-keep class ox.fzer0x.snakeloader.ui.viewmodels.Il2CppMethodData { *; }
-keep class ox.fzer0x.snakeloader.ui.viewmodels.Il2CppFieldData { *; }
-keep class ox.fzer0x.snakeloader.ui.viewmodels.Il2CppPropertyData { *; }
-keep class ox.fzer0x.snakeloader.ui.viewmodels.Il2CppFilter { *; }
-keep class ox.fzer0x.snakeloader.ui.viewmodels.AgentLogStep { *; }
-keep class ox.fzer0x.snakeloader.ui.viewmodels.AiTelemetryInfo { *; }

# Native JNI Methods & Engine
-keepclassmembers class ox.fzer0x.snakeloader.ai.OnDeviceLlmEngine {
    native <methods>;
}
