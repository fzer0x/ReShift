package ox.fzer0x.snakeloader.data

enum class PatchStatus {
    ACTIVE,
    INACTIVE,
    UNVERIFIED,
    CONFLICT,
    ERROR
}

enum class TargetArchitecture {
    ARM64,
    ARM32,
    X86_64,
    UNKNOWN
}

enum class PatchPreset(val hexBytes: String, val label: String, val arch: TargetArchitecture) {
    NOP("1F2003D5", "ARM64 NOP", TargetArchitecture.ARM64),
    RET_TRUE("200080D2C0035FD6", "ARM64 Return True (W0=1)", TargetArchitecture.ARM64),
    RET_FALSE("000080D2C0035FD6", "ARM64 Return False (W0=0)", TargetArchitecture.ARM64),
    RET_VOID("C0035FD6", "ARM64 Return Void", TargetArchitecture.ARM64),
    CUSTOM("", "Custom Hex Bytes", TargetArchitecture.UNKNOWN)
}

data class MemoryPatch(
    val id: String,
    val name: String,
    val packageName: String,
    val moduleName: String,
    val rvaOffsetHex: String,
    val lastRuntimeAddressHex: String? = null,
    val originalBytesHex: String? = null,
    val patchBytesHex: String,
    val status: PatchStatus = PatchStatus.UNVERIFIED,
    val targetArch: TargetArchitecture = TargetArchitecture.ARM64,
    val preset: PatchPreset = PatchPreset.CUSTOM,
    val processId: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object PatchArchitectureValidator {
    fun validateArchitecture(targetArch: TargetArchitecture, systemArch: String): Boolean {
        val sysLower = systemArch.lowercase()
        return when (targetArch) {
            TargetArchitecture.ARM64 -> sysLower.contains("arm64") || sysLower.contains("aarch64")
            TargetArchitecture.ARM32 -> sysLower.contains("arm") && !sysLower.contains("64")
            TargetArchitecture.X86_64 -> sysLower.contains("x86_64") || sysLower.contains("amd64")
            TargetArchitecture.UNKNOWN -> true
        }
    }
}

data class AobScanResult(
    val addressHex: String,
    val moduleName: String,
    val rvaOffsetHex: String,
    val matchedBytesHex: String,
    val size: Int
)

data class AobScanStatus(
    val scanId: String,
    val status: String,
    val pattern: String,
    val resultsCount: Int,
    val results: List<AobScanResult>,
    val error: String? = null
)
