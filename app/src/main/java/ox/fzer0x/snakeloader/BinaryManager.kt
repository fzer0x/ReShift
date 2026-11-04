package ox.fzer0x.snakeloader

import android.util.Log
import ox.fzer0x.snakeloader.utils.ShellExecutor

class BinaryManager(
    private val stealthConfig: StealthConfigManager,
    private val settings: SettingsManager
) {
    companion object {
        private const val TAG = "BinaryManager"
    }

    private val binaryCapabilityCache = mutableMapOf<String, BinaryInfo>()
    private var cachedAutoCli: String? = null
    private var lastAutoCliCheck = 0L

    data class BinaryInfo(
        val path: String,
        val isCliCapable: Boolean,
        val version: String = "Unknown",
        val type: String = "unknown"
    )

    fun getRunningFridaBinary(selectedBinary: String = "auto"): String {
        val serverName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_SERVER)
        val cliName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_CLI)
        val injectName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_INJECT)

        val port = stealthConfig.getActivePort()
        val portCheck = ShellExecutor.executeSimple("netstat -tuln | grep :$port", useRoot = true).trim()

        val serverRunning = ShellExecutor.executeSimple("pgrep -x $serverName", useRoot = true).trim().isNotEmpty() ||
                           (serverName != "frida-server" && ShellExecutor.executeSimple("pgrep -x frida-server", useRoot = true).trim().isNotEmpty()) ||
                           (serverName != "nm-service" && ShellExecutor.executeSimple("pgrep -x nm-service", useRoot = true).trim().isNotEmpty()) ||
                           portCheck.isNotEmpty()

        val cliRunning = ShellExecutor.executeSimple("pgrep -x $cliName", useRoot = true).trim().isNotEmpty() ||
                         (cliName != "frida" && ShellExecutor.executeSimple("pgrep -x frida", useRoot = true).trim().isNotEmpty())

        val injectRunning = ShellExecutor.executeSimple("pgrep -x $injectName", useRoot = true).trim().isNotEmpty() ||
                            (injectName != "frida-inject" && ShellExecutor.executeSimple("pgrep -x frida-inject", useRoot = true).trim().isNotEmpty())

        if (selectedBinary == "server" && (serverRunning || isServerAvailable())) return "server"
        if (selectedBinary == "cli" && (cliRunning || serverRunning || isCliAvailable())) return "cli"
        if (selectedBinary == "inject" && (injectRunning || serverRunning || isInjectAvailable())) return "inject"
        if (selectedBinary == "gadget" && (isGadgetAvailable() || serverRunning)) return "gadget"

        return when {
            selectedBinary != "auto" && selectedBinary != "none" -> selectedBinary
            serverRunning -> "server"
            cliRunning -> "cli"
            injectRunning -> "inject"
            else -> "none"
        }
    }

    fun getBinaryInfo(path: String, forceRefresh: Boolean = false): BinaryInfo {
        if (path.isEmpty()) return BinaryInfo("", false)
        
        if (!forceRefresh) {
            binaryCapabilityCache[path]?.let { 
                if (it.version != "Unknown") return it 
            }
        }

        val helpOutput = ShellExecutor.executeSimple("$path --help", useRoot = true)
        val isCapable = helpOutput.contains("-f, --file") || 
                        helpOutput.contains("-p, --pid") || 
                        helpOutput.contains("-n, --name")
        
        val rawVersion = ShellExecutor.executeSimple("$path --version", useRoot = true).trim()
        val version = rawVersion.lines().firstOrNull()?.trim()?.takeIf { 
            it.isNotEmpty() && !it.contains(" ") && it.contains(".") 
        } ?: "Unknown"
        
        val type = when {
            path.contains("server") -> "server"
            path.contains("inject") -> "inject"
            isCapable -> "cli"
            else -> "unknown"
        }

        val info = BinaryInfo(path, isCapable, version, type)
        binaryCapabilityCache[path] = info
        return info
    }

    fun getAvailableFridaCli(selectedBinary: String): String? {
        val auto = getAutoFridaCli()
        val candidate = when (selectedBinary) {
            "server" -> {
                val cliPath = stealthConfig.getActiveCliPath()
                if (ShellExecutor.executeSimple("[ -f $cliPath ] && echo yes", useRoot = true).trim() == "yes") {
                    cliPath
                } else if (ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida ] && echo yes", useRoot = true).trim() == "yes") {
                    "/data/adb/reshift/frida"
                } else {
                    val injectPath = stealthConfig.getActiveInjectPath()
                    if (ShellExecutor.executeSimple("[ -f $injectPath ] && echo yes", useRoot = true).trim() == "yes") {
                        injectPath
                    } else if (ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-inject ] && echo yes", useRoot = true).trim() == "yes") {
                        "/data/adb/reshift/frida-inject"
                    } else auto
                }
            }
            "cli" -> {
                val cliPath = stealthConfig.getActiveCliPath()
                if (ShellExecutor.executeSimple("[ -f $cliPath ] && echo yes", useRoot = true).trim() == "yes") {
                    cliPath
                } else if (ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida ] && echo yes", useRoot = true).trim() == "yes") {
                    "/data/adb/reshift/frida"
                } else {
                    Log.w(TAG, "frida CLI selected but binary missing. Automatically falling back to frida-inject.")
                    val injectPath = stealthConfig.getActiveInjectPath()
                    if (ShellExecutor.executeSimple("[ -f $injectPath ] && echo yes", useRoot = true).trim() == "yes") {
                        injectPath
                    } else if (ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-inject ] && echo yes", useRoot = true).trim() == "yes") {
                        "/data/adb/reshift/frida-inject"
                    } else auto
                }
            }
            "inject" -> {
                val injectPath = stealthConfig.getActiveInjectPath()
                if (ShellExecutor.executeSimple("[ -f $injectPath ] && echo yes", useRoot = true).trim() == "yes") {
                    injectPath
                } else if (ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-inject ] && echo yes", useRoot = true).trim() == "yes") {
                    "/data/adb/reshift/frida-inject"
                } else auto
            }
            "gadget" -> {
                val injectPath = stealthConfig.getActiveInjectPath()
                if (ShellExecutor.executeSimple("[ -f $injectPath ] && echo yes", useRoot = true).trim() == "yes") {
                    injectPath
                } else if (ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-inject ] && echo yes", useRoot = true).trim() == "yes") {
                    "/data/adb/reshift/frida-inject"
                } else auto
            }
            else -> auto
        }

        if (candidate != null) {
            val info = getBinaryInfo(candidate)
            if (selectedBinary == "cli" && !info.isCliCapable) {
                Log.w(TAG, "Selected binary $candidate is NOT CLI-capable. Searching fallback...")
                return findCapableFallback() ?: candidate
            }
        }

        return candidate
    }

    private fun findCapableFallback(): String? {
        val searchPaths = listOf(
            stealthConfig.getActiveCliPath(),
            "/data/adb/modules/snakeloader_frida/frida",
            "/data/local/tmp/frida",
            stealthConfig.getActiveInjectPath(),
            "/data/adb/modules/snakeloader_frida/frida-inject",
            "/data/local/tmp/frida-inject"
        )
        
        return searchPaths.firstOrNull { 
            ShellExecutor.executeSimple("[ -f $it ] && echo yes", useRoot = true).trim() == "yes" && 
            getBinaryInfo(it).isCliCapable 
        }
    }

    private fun getAutoFridaCli(): String? {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAutoCliCheck < 10000 && cachedAutoCli != null) {
            return cachedAutoCli
        }

        val paths = listOf(
            stealthConfig.getActiveCliPath(),
            "/data/adb/modules/snakeloader_frida/frida",
            "/data/local/tmp/frida",
            stealthConfig.getActiveInjectPath(),
            "/data/adb/modules/snakeloader_frida/frida-inject",
            "/data/local/tmp/frida-inject"
        )
        
        for (path in paths) {
            if (ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes") {
                cachedAutoCli = path
                lastAutoCliCheck = currentTime
                return path
            }
        }

        return null
    }

    fun getFridaVersion(forceCheck: Boolean = false, currentVersion: String? = null): String {
        if (!forceCheck && currentVersion != null && currentVersion != "Unknown") return currentVersion
        
        getRunningServerVersion()?.let { return it }

        val searchPaths = listOfNotNull(
            getAutoFridaCli(),
            stealthConfig.getActiveServerPath(),
            stealthConfig.getActiveInjectPath(),
            stealthConfig.getActiveCliPath(),
            "/data/adb/modules/snakeloader_frida/frida-server",
            "/data/adb/modules/snakeloader_frida/frida-inject",
            "/data/local/tmp/frida-server",
            "/data/local/tmp/frida-inject"
        ).distinct()

        for (path in searchPaths) {
            val info = getBinaryInfo(path, forceRefresh = forceCheck)
            if (info.version != "Unknown") {
                return info.version
            }
        }

        return "Unknown"
    }

    private fun getRunningServerVersion(): String? {
        val serverName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_SERVER)
        val pidsResult = ShellExecutor.executeSimple("pidof $serverName frida-server nm-service", useRoot = true).trim()
        val pid = pidsResult.split("\\s+".toRegex()).firstOrNull { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        if (!pid.isNullOrEmpty()) {
            val versionResult = ShellExecutor.executeSimple("/proc/$pid/exe --version 2>/dev/null", useRoot = true).trim()
            val version = versionResult.lines().firstOrNull()?.trim()?.takeIf { 
                it.isNotEmpty() && !it.contains(" ") && it.contains(".") 
            }
            if (version != null) return version
        }
        return null
    }

    fun getAvailableBinaries(): Map<String, Boolean> {
        return mapOf(
            "server" to isServerAvailable(),
            "inject" to isInjectAvailable(),
            "cli" to isCliAvailable(),
            "gadget" to isGadgetAvailable()
        )
    }

    fun getBinaryPath(type: String): String? {
        val paths = when (type) {
            "server" -> listOf(
                stealthConfig.getActiveServerPath(),
                "/data/adb/reshift/frida-server",
                "/data/adb/modules/snakeloader_frida/frida-server",
                "/data/local/tmp/frida-server"
            )
            "inject" -> listOf(
                stealthConfig.getActiveInjectPath(),
                "/data/adb/reshift/frida-inject",
                "/data/adb/modules/snakeloader_frida/frida-inject",
                "/data/local/tmp/frida-inject"
            )
            "cli" -> listOf(
                stealthConfig.getActiveCliPath(),
                "/data/adb/reshift/frida",
                "/data/adb/modules/snakeloader_frida/frida",
                "/data/local/tmp/frida",
                stealthConfig.getActiveInjectPath(),
                "/data/adb/reshift/frida-inject"
            )
            "gadget" -> listOf(
                "/data/adb/reshift/frida-gadget.so",
                "/data/adb/modules/snakeloader_frida/frida-gadget.so",
                "/data/local/tmp/frida-gadget.so"
            )
            else -> emptyList()
        }

        return paths.firstOrNull {
            ShellExecutor.executeSimple("[ -f $it ] && echo yes", useRoot = true).trim() == "yes"
        }
    }

    fun getBinaryVersion(type: String): String {
        val path = getBinaryPath(type) ?: return "Not Installed"
        if (type == "gadget") return "Available"
        val info = getBinaryInfo(path, forceRefresh = true)
        return if (info.version != "Unknown") info.version else "Installed"
    }

    fun isInjectAvailable(): Boolean {
        val path = stealthConfig.getActiveInjectPath()
        return ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-inject ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/modules/snakeloader_frida/frida-inject ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/local/tmp/frida-inject ] && echo yes", useRoot = true).trim() == "yes"
    }

    fun isCliAvailable(): Boolean {
        val path = stealthConfig.getActiveCliPath()
        return ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/modules/snakeloader_frida/frida ] && echo yes", useRoot = true).trim() == "yes" ||
               isInjectAvailable()
    }

    fun isServerAvailable(): Boolean {
        val path = stealthConfig.getActiveServerPath()
        return ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-server ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/modules/snakeloader_frida/frida-server ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/local/tmp/frida-server ] && echo yes", useRoot = true).trim() == "yes"
    }

    fun isGadgetAvailable(): Boolean {
        return ShellExecutor.executeSimple("[ -f /data/adb/reshift/frida-gadget.so ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/modules/snakeloader_frida/frida-gadget.so ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/local/tmp/frida-gadget.so ] && echo yes", useRoot = true).trim() == "yes"
    }
}
