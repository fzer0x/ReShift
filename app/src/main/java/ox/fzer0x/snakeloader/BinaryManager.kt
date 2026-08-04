package ox.fzer0x.snakeloader

import android.util.Log
import ox.fzer0x.snakeloader.utils.ProcessMonitor
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

    fun getRunningFridaBinary(): String {
        val serverName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_SERVER)
        val cliName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_CLI)
        val injectName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_INJECT)

        val cliRunning = ProcessMonitor.isProcessRunning(cliName) || ProcessMonitor.isProcessRunning("frida")
        val injectRunning = ProcessMonitor.isProcessRunning(injectName) || ProcessMonitor.isProcessRunning("frida-inject")
        val serverRunning = ProcessMonitor.isProcessRunning(serverName) || 
                           ProcessMonitor.isProcessRunning("frida-server") || 
                           ProcessMonitor.isProcessRunning("nm-service")

        return when {
            cliRunning -> "cli"
            injectRunning -> "inject"
            serverRunning -> "server"
            else -> "none"
        }
    }

    fun getBinaryInfo(path: String): BinaryInfo {
        if (path.isEmpty()) return BinaryInfo("", false)
        
        binaryCapabilityCache[path]?.let { return it }

        val helpOutput = ShellExecutor.executeSimple("$path --help", useRoot = true)
        val isCapable = helpOutput.contains("-f, --file") || 
                        helpOutput.contains("-p, --pid") || 
                        helpOutput.contains("-n, --name")
        
        val versionResult = ShellExecutor.executeSimple("$path --version", useRoot = true).trim()
        val version = versionResult.takeIf { it.isNotEmpty() && !it.contains(" ") } ?: "Unknown"
        
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
            "cli" -> {
                val cliPath = stealthConfig.getActiveCliPath()
                if (ShellExecutor.executeSimple("[ -f $cliPath ] && echo yes", useRoot = true).trim() == "yes") {
                    cliPath
                } else auto
            }
            "inject" -> {
                val injectPath = stealthConfig.getActiveInjectPath()
                if (ShellExecutor.executeSimple("[ -f $injectPath ] && echo yes", useRoot = true).trim() == "yes") {
                    injectPath
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

    fun getFridaVersion(forceCheck: Boolean = false, currentVersion: String?): String {
        if (!forceCheck && currentVersion != null && currentVersion != "Unknown") return currentVersion
        
        val cli = getAvailableFridaCli("auto") ?: stealthConfig.getActiveServerPath()
        return getBinaryInfo(cli).version
    }

    fun isInjectAvailable(): Boolean {
        val path = stealthConfig.getActiveInjectPath()
        return ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/modules/snakeloader_frida/frida-inject ] && echo yes", useRoot = true).trim() == "yes"
    }

    fun isCliAvailable(): Boolean {
        val path = stealthConfig.getActiveCliPath()
        return (ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes" && getBinaryInfo(path).isCliCapable) ||
               getAutoFridaCli() != null
    }

    fun isServerAvailable(): Boolean {
        val path = stealthConfig.getActiveServerPath()
        return ShellExecutor.executeSimple("[ -f $path ] && echo yes", useRoot = true).trim() == "yes" ||
               ShellExecutor.executeSimple("[ -f /data/adb/modules/snakeloader_frida/frida-server ] && echo yes", useRoot = true).trim() == "yes"
    }
}
