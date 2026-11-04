package ox.fzer0x.snakeloader.ai

enum class AgentTargetType(val label: String, val description: String) {
    AUTO_DETECT("Auto Detect", "Automatically detect IL2CPP, Java/DEX, or Native ELF based on loaded process libraries."),
    IL2CPP("Unity IL2CPP", "Target libil2cpp.so and C# Metadata AST."),
    JAVA_DEX("Android Java/DEX", "Target Android Dalvik/ART JVM runtime via Java.perform & Java.use."),
    NATIVE_ELF("Native C/C++ (.so)", "Target native Linux shared libraries via Module.getExportByName & Interceptor.attach.")
}
