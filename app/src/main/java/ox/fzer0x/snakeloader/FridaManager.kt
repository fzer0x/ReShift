package ox.fzer0x.snakeloader

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ox.fzer0x.snakeloader.ai.AutonomousAgentEngine
import ox.fzer0x.snakeloader.security.SecurityStateManager
import ox.fzer0x.snakeloader.utils.InputValidator
import ox.fzer0x.snakeloader.utils.JsonMessageProcessor
import ox.fzer0x.snakeloader.utils.ProcessMonitor
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File

class FridaManager(
    val context: Context,
    val binaryManager: BinaryManager,
    val settings: SettingsManager,
    val dynamicUiManager: DynamicUiManager,
    val stalkerManager: StalkerManager,
    val stealthConfig: StealthConfigManager
) {
    companion object {
        private const val TAG = "FridaManager"
        private const val PREF_SELECTED_BINARY = "selected_frida_binary"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scriptProcess: Process? = null
    private var isStopping = false
    private var rpcBridge: RpcBridge? = null
    private var cachedHasRoot: Boolean? = null
    private var cachedFridaVersion: String? = null
    private val detectedHooksSet = mutableSetOf<String>()

    private val prefs = context.getSharedPreferences("frida_prefs", Context.MODE_PRIVATE)

    var selectedFridaBinary: String
        get() = prefs.getString(PREF_SELECTED_BINARY, "auto") ?: "auto"
        set(value) = prefs.edit().putString(PREF_SELECTED_BINARY, value).apply()

    private val _isScriptRunning = MutableStateFlow(false)
    val isScriptRunning: StateFlow<Boolean> = _isScriptRunning.asStateFlow()

    var activeAgentEngine: AutonomousAgentEngine? = null

    fun submitAgentUserEvaluation(goalAchieved: Boolean, refinedPrompt: String? = null) {
        activeAgentEngine?.submitUserEvaluation(goalAchieved, refinedPrompt)
    }

    private val _currentPackageName = MutableStateFlow<String?>(null)
    val currentPackageName: StateFlow<String?> = _currentPackageName.asStateFlow()

    fun getFridaStatus(): Map<String, Any> {
        val batchCmd = "echo 'SERVER='; pidof frida-server nm-service frida-inject frida 2>/dev/null; echo 'SELINUX='; getenforce 2>/dev/null; echo 'YAMA='; cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null"
        val batchResult = executeRootCommand(batchCmd)
        
        var serverRunning = false
        var isSelinuxPermissive = false
        var isPtraceScopeDisabled = false

        val lines = batchResult.lines()
        var currentSection = ""

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("SERVER=")) currentSection = "SERVER"
            else if (trimmed.startsWith("SELINUX=")) currentSection = "SELINUX"
            else if (trimmed.startsWith("YAMA=")) currentSection = "YAMA"
            else if (trimmed.isNotEmpty()) {
                when (currentSection) {
                    "SERVER" -> if (trimmed.split("\\s+".toRegex()).any { it.all { c -> c.isDigit() } }) serverRunning = true
                    "SELINUX" -> if (trimmed.equals("Permissive", ignoreCase = true)) isSelinuxPermissive = true
                    "YAMA" -> if (trimmed == "0") isPtraceScopeDisabled = true
                }
            }
        }

        val runningBinary = binaryManager.getRunningFridaBinary(selectedFridaBinary)

        return mapOf(
            "running_binary" to runningBinary,
            "is_smooth" to serverRunning,
            "server_running" to serverRunning,
            "selected_mode" to selectedFridaBinary,
            "stealth_enabled" to settings.isStealthModeEnabled,
            "available_server" to binaryManager.isServerAvailable(),
            "available_inject" to binaryManager.isInjectAvailable(),
            "available_cli" to binaryManager.isCliAvailable(),
            "available_gadget" to binaryManager.isGadgetAvailable(),
            "binaries_status" to binaryManager.getAvailableBinaries(),
            "selinux_permissive" to isSelinuxPermissive,
            "ptrace_scope_disabled" to isPtraceScopeDisabled,
            "frida_version" to getFridaVersion()
        )
    }

    fun getModuleManager(): ModuleManager = ModuleManager(context, settings)

    fun getModuleVersion(): String = getModuleManager().getModuleVersion()

    fun hasRootAccess(forceCheck: Boolean = false): Boolean {
        if (!forceCheck && cachedHasRoot == true) return true
        val isRoot = ShellExecutor.requestRootAccess()
        if (isRoot) {
            cachedHasRoot = true
            return true
        }
        val result = ShellExecutor.execute("id", useRoot = true)
        val hasRoot = result.isSuccess && result.stdout.contains("uid=0")
        if (hasRoot) {
            cachedHasRoot = true
        } else {
            cachedHasRoot = null
        }
        return hasRoot
    }

    fun getFridaVersion(forceCheck: Boolean = false): String {
        cachedFridaVersion = binaryManager.getFridaVersion(forceCheck, cachedFridaVersion)
        return cachedFridaVersion!!
    }

    fun getBusyBoxVersion(): String {
        val output = executeRootCommand("busybox").trim()
        return if (output.startsWith("BusyBox v")) {
            output.substringBefore("\n").substringAfter("BusyBox ").substringBefore(" ")
        } else {
            "Not Found"
        }
    }

    fun isFridaServerRunning(): Boolean {
        val serverName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_SERVER)
        val port = stealthConfig.getActivePort()
        
        val pidofResult = executeRootCommand("pidof $serverName").trim()
        if (pidofResult.isNotEmpty()) return true
        
        if (serverName != "frida-server") {
            if (executeRootCommand("pidof frida-server").trim().isNotEmpty()) return true
            if (executeRootCommand("pidof nm-service").trim().isNotEmpty()) return true
        }

        val nameRunning = ProcessMonitor.isProcessRunning(serverName) || 
                         ProcessMonitor.isProcessRunning("frida-server") || 
                         ProcessMonitor.isProcessRunning("nm-service")
        
        if (nameRunning) return true
        
        val portCheck = executeRootCommand("netstat -tuln | grep :$port").trim()
        if (portCheck.isNotEmpty()) return true
        
        return executeRootCommand("pgrep -f $serverName").trim().isNotEmpty()
    }

    fun isFridaRunningSmoothly(): Boolean {
        return isFridaServerRunning()
    }

    fun getCurrentFridaBinarySelection(): String = selectedFridaBinary
    fun getRunningFridaBinary(): String = binaryManager.getRunningFridaBinary(selectedFridaBinary)
    fun getAvailableBinaries(): Map<String, Boolean> = binaryManager.getAvailableBinaries()
    fun isYamaSupported(): Boolean = true
    fun enablePtraceScope() = executeRootCommand("echo 1 > /proc/sys/kernel/yama/ptrace_scope")
    fun disablePtraceScope() = executeRootCommand("echo 0 > /proc/sys/kernel/yama/ptrace_scope")
    fun updateStealthConfig() = stealthConfig.writeStealthConfig()
    fun getRpcManager(): RpcBridge? = rpcBridge

    suspend fun executeMultipleScripts(scripts: List<Any>, pkg: String, mode: String = "auto"): Boolean {
        return withContext(Dispatchers.IO) {
            val fridaScripts = scripts.mapNotNull { item ->
                when (item) {
                    is FridaScript -> item
                    is Pair<*, *> -> FridaScript(item.first.toString(), item.second.toString())
                    else -> null
                }
            }
            if (fridaScripts.isEmpty()) return@withContext false
            MultiScriptLoader(context, this@FridaManager).executeScripts(fridaScripts, pkg, mode)
        }
    }

    fun getRunningProcesses(): List<Pair<String, Int>> {
        val output = executeRootCommand("ps -A -o NAME,PID").lines().drop(1)
        return output.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[0]
                val pid = parts[1].toIntOrNull()
                if (pid != null && name.isNotBlank()) Pair(name, pid) else null
            } else null
        }
    }

    fun getModulesForPid(pid: Int): List<String> {
        val output = executeRootCommand("cat /proc/$pid/maps | awk '{print \$6}' | sort | uniq")
        return output.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    fun killApp(packageName: String) {
        if (InputValidator.validatePackageName(packageName)) {
            val safePkg = InputValidator.escapeShellInput(packageName)
            executeRootCommand("am force-stop $safePkg 2>/dev/null; kill -9 \$(pidof $safePkg 2>/dev/null) 2>/dev/null")
        }
    }

    fun clearAppData(packageName: String) {
        if (InputValidator.validatePackageName(packageName)) executeRootCommand("pm clear $packageName")
    }

    fun stopScript(keepServer: Boolean = true): Boolean {
        if (isStopping) return true
        isStopping = true
        _isScriptRunning.value = false
        stalkerManager.isActive.value = false
        
        try {
            scriptProcess?.destroy()
            scriptProcess = null
            fullCleanup(keepServer)
            
            if (!keepServer) {
                setSelinuxPermissive(false)
                executeRootCommand("echo 1 > /proc/sys/kernel/yama/ptrace_scope")
                SecurityStateManager.clearSecurityModified()
            }
            
            context.stopService(Intent(context, FridaService::class.java))
            isStopping = false
            return true
        } catch (e: Exception) {
            isStopping = false
            return false
        }
    }

    fun launchTargetApp(packageName: String) {
        if (InputValidator.validatePackageName(packageName)) {
            LogManager.addLog(packageName, "Launching target app $packageName...")
            executeRootCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        }
    }

    fun closeTargetApp(targetPackage: String? = null): Boolean {
        var pkg = targetPackage?.trim()?.ifEmpty { null }
            ?: _currentPackageName.value?.ifEmpty { null }

        if (pkg.isNullOrEmpty()) {
            val recentFridaLog = LogManager.fridaLogs.lastOrNull { 
                it.packageName.isNotBlank() && 
                it.packageName != context.packageName && 
                it.packageName != "Frida" && 
                it.packageName != "Logcat" &&
                it.packageName != "System" 
            }?.packageName
            if (!recentFridaLog.isNullOrEmpty() && InputValidator.validatePackageName(recentFridaLog)) {
                pkg = recentFridaLog
            }
        }

        if (pkg.isNullOrEmpty()) {
            val recentAppLog = LogManager.logs.lastOrNull { 
                it.packageName.isNotBlank() && 
                it.packageName != context.packageName && 
                it.packageName != "Frida" && 
                it.packageName != "Logcat" &&
                it.packageName != "System" 
            }?.packageName
            if (!recentAppLog.isNullOrEmpty() && InputValidator.validatePackageName(recentAppLog)) {
                pkg = recentAppLog
            }
        }

        if (pkg.isNullOrEmpty()) {
            val dumpsysCmd = "dumpsys window | grep -iE 'mCurrentFocus|mFocusedApp' | grep -oE '[a-zA-Z0-9._]+/[a-zA-Z0-9._]+' | cut -d/ -f1 | grep -v '${context.packageName}' | head -n 1"
            val topPkg = executeRootCommand(dumpsysCmd).trim()
            if (topPkg.isNotEmpty() && InputValidator.validatePackageName(topPkg)) {
                pkg = topPkg
            }
        }

        if (!pkg.isNullOrEmpty() && pkg != context.packageName) {
            LogManager.addLog(pkg, "Force closing target application $pkg via su...", isError = false)
            killApp(pkg)
            stopScript(keepServer = true)
            return true
        } else {
            stopScript(keepServer = true)
            return false
        }
    }

    fun fullCleanup(keepServer: Boolean = true) {
        val injectName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_INJECT)
        val cliName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_CLI)
        val serverName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_SERVER)

        val killList = mutableListOf(injectName, cliName, "frida-inject", "frida")
        if (!keepServer) {
            killList.addAll(listOf(serverName, "frida-server", "nm-service"))
        }

        executeRootCommand("killall -9 ${killList.joinToString(" ")} 2>/dev/null")
        
        val basePath = stealthConfig.getBasePath()
        executeRootCommand("pkill -9 -x $injectName 2>/dev/null")
        executeRootCommand("pkill -9 -x $cliName 2>/dev/null")
        executeRootCommand("pkill -9 -f '$basePath/$injectName' 2>/dev/null")
        executeRootCommand("pkill -9 -f '$basePath/$cliName' 2>/dev/null")
        
        if (!keepServer) {
            executeRootCommand("pkill -9 -x $serverName 2>/dev/null")
            executeRootCommand("pkill -9 -f '$basePath/$serverName' 2>/dev/null")
            executeRootCommand("pkill -9 -f '/data/local/tmp/frida-server' 2>/dev/null")
            executeRootCommand("pkill -9 -f '/data/local/tmp/nm-service' 2>/dev/null")
            
            if (!settings.isStealthModeEnabled) {
                executeRootCommand("rm -f $basePath/config.sh")
            }
        }
    }
    
    private val serverMutex = Mutex()

    suspend fun startFridaServer(force: Boolean = false): Boolean = serverMutex.withLock {
        return withContext(Dispatchers.IO) {
            try {
                if (!hasRootAccess()) return@withContext false
                
                if (!force && isFridaServerRunning()) {
                    Log.d(TAG, "Frida server already running")
                    return@withContext true
                }

                fullCleanup(keepServer = false)
                delay(300)

                val serverPath = stealthConfig.getActiveServerPath()
                val fallbackPaths = listOf("/data/adb/modules/snakeloader_frida/frida-server", "/data/local/tmp/frida-server", "/data/local/tmp/nm-service")
                
                val availableServer = if (executeRootCommand("[ -f $serverPath ] && echo yes").trim() == "yes") {
                    serverPath
                } else {
                    fallbackPaths.find { executeRootCommand("[ -f $it ] && echo yes").trim() == "yes" }
                }

                if (availableServer == null) {
                    Log.e(TAG, "No Frida server binary found")
                    return@withContext false
                }

                stealthConfig.writeStealthConfig()
                executeRootCommand("chmod 755 $availableServer")
                
                val port = stealthConfig.getActivePort()
                
                val command = "setsid nohup $availableServer -l 127.0.0.1:$port --origin=https://google.com > /dev/null 2>&1 &"
                Log.d(TAG, "Starting frida-server: $command")
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "0", "-c", command))
                } catch (_: Exception) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                }
                
                ProcessMonitor.clearCache()
                
                var started = false
                repeat(12) {
                    if (isFridaServerRunning()) {
                        started = true
                        return@repeat
                    }
                    delay(500)
                }
                
                if (started) {
                    Log.d(TAG, "Frida server successfully started on port $port")
                    true
                } else {
                    Log.e(TAG, "Frida server failed to start or died immediately")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start frida-server", e)
                false
            }
        }
    }

    private fun sanitizeScriptForJavaReady(content: String): String {
        var processed = content

        val moduleFindExportPolyfill = """
(function() {
    if (typeof Module !== 'undefined' && typeof Module.findExportByName !== 'function') {
        Module.findExportByName = function(moduleName, exportName) {
            if (typeof exportName === 'undefined') {
                exportName = moduleName;
                moduleName = null;
            }
            if (typeof Module.getExportByName === 'function') {
                try {
                    return Module.getExportByName(moduleName, exportName);
                } catch (_) {}
            }
            if (moduleName) {
                try {
                    const mod = Process.findModuleByName(moduleName);
                    if (mod) {
                        if (typeof mod.findExportByName === 'function') {
                            return mod.findExportByName(exportName);
                        }
                        if (typeof mod.getExportByName === 'function') {
                            try { return mod.getExportByName(exportName); } catch (_) {}
                        }
                    }
                } catch (_) {}
            } else {
                try {
                    if (typeof Process.getExportByName === 'function') {
                        return Process.getExportByName(exportName);
                    }
                } catch (_) {}
                try {
                    if (typeof Process.findExportByName === 'function') {
                        return Process.findExportByName(exportName);
                    }
                } catch (_) {}
            }
            return null;
        };
    }
})();
""".trimIndent()

        val javaPerformOverride = """
(function() {
    if (typeof Java !== 'undefined') {
        if (!Java.__reshift_overridden) {
            Java.__reshift_overridden = true;
            const origPerform = Java.perform;
            Java.perform = function(fn) {
                function safeRun() {
                    if (Java.available) {
                        try {
                            origPerform.call(Java, fn);
                        } catch (e) {
                            try { fn(); } catch (err) {}
                        }
                    } else {
                        setTimeout(safeRun, 25);
                    }
                }
                safeRun();
            };
        }
    }
})();
""".trimIndent()

        if (!processed.contains("Module.findExportByName =")) {
            processed = "$moduleFindExportPolyfill\n$processed"
        }

        if (!processed.contains("Java.__reshift_overridden")) {
            processed = "$javaPerformOverride\n$processed"
        }

        processed = processed.replace(
            Regex("""if\s*\(\s*typeof\s+Java\s*!==\s*['"]undefined['"]\s*&&\s*Java\.available\s*\)"""),
            "if (typeof Java !== 'undefined')"
        )

        return processed
    }

    private fun deployScriptInternal(content: String): Boolean {
        return try {
            val sanitized = sanitizeScriptForJavaReady(content)
            val tempFile = File(context.cacheDir, "temp_script.js")
            tempFile.writeText(sanitized)
            tempFile.setReadable(true, false)
            
            val scriptPath = stealthConfig.getActiveScriptPath()
            if (settings.isStealthModeEnabled) {
                executeRootCommand("mkdir -p ${stealthConfig.getBasePath()} && chmod 777 ${stealthConfig.getBasePath()}")
            }
            executeRootCommand("cp ${tempFile.absolutePath} $scriptPath && chmod 644 $scriptPath")
            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Script deployment failed", e)
            false
        }
    }
    
    suspend fun executeScriptContent(packageName: String, scriptContent: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (!InputValidator.validatePackageName(packageName)) {
                LogManager.addLog(packageName, "Error: Invalid package name format.", isError = true)
                return@withContext false
            }

            if (!hasRootAccess()) return@withContext false
            
            val freeMem = getAvailableMemory()
            Log.d(TAG, "Free memory: ${freeMem}MB")
            if (freeMem < 500) {
                LogManager.addLog(packageName, "Warning: Low RAM (${freeMem}MB). This may cause crashes.", isError = false)
                if (freeMem < 300) {
                    LogManager.addLog(packageName, "Critical memory level. Attempting to clear background apps...")
                    clearBackgroundApps()
                }
            }

            val cliPath = binaryManager.getAvailableFridaCli(selectedFridaBinary)
            if (cliPath == null) {
                LogManager.addLog(packageName, "Error: Frida binary not found.", isError = true)
                return@withContext false
            }

            val binaryInfo = binaryManager.getBinaryInfo(cliPath)
            LogManager.addLog(packageName, "Using binary: ${binaryInfo.path} (${binaryInfo.version})")

            stopScript(keepServer = (selectedFridaBinary == "server" || isFridaServerRunning()))

            if (!deployScriptInternal(scriptContent)) {
                LogManager.addLog(packageName, "Script deployment failed", isError = true)
                return@withContext false
            }

            if (selectedFridaBinary == "server" || selectedFridaBinary == "cli" || !binaryInfo.isCliCapable) {
                if (!isFridaServerRunning()) {
                    LogManager.addLog(packageName, "Server mode active ($selectedFridaBinary). Starting Frida server daemon...")
                    startFridaServer()
                } else {
                    LogManager.addLog(packageName, "Server mode active ($selectedFridaBinary). Frida server daemon running on port ${stealthConfig.getActivePort()}.")
                }
            }

            SecurityStateManager.setSecurityModified()
            
            if (!isSelinuxPermissive()) setSelinuxPermissive(true)
            executeRootCommand("echo 0 > /proc/sys/kernel/yama/ptrace_scope")
            executeRootCommand("device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null")
            executeRootCommand("settings put global settings_enable_monitor_phantom_procs false 2>/dev/null")

            try {
                _currentPackageName.value = packageName
                dynamicUiManager.reset()
                stalkerManager.reset()
                stalkerManager.isActive.value = false

                LogManager.clearFridaLogs()
                LogManager.addFridaLog("     ____")
                LogManager.addFridaLog("    / _  |   Frida ${binaryInfo.version} - SnakeLoader Overhaul")
                LogManager.addFridaLog("   | (_| |")
                LogManager.addFridaLog("    > _  |   Target: $packageName")
                
                executeRootCommand("am force-stop $packageName")
                delay(1000)

                val helpOutput = ShellExecutor.executeSimple("$cliPath --help", useRoot = true)
                val isFridaInject = (selectedFridaBinary == "inject") ||
                                    (selectedFridaBinary != "server" && selectedFridaBinary != "cli" && cliPath.contains("frida-inject"))

                val flags = mutableListOf<String>()
                if ((settings.useNoPause || !isFridaInject) && helpOutput.contains("--no-pause")) flags.add("--no-pause")
                if (settings.useRuntimeV8 && helpOutput.contains("--runtime")) flags.add("--runtime=v8")
                if (settings.useExceptorOff && helpOutput.contains("--exceptor")) {
                    flags.add("--exceptor")
                    flags.add("off")
                }
                if (settings.useDebugLog && helpOutput.contains("--debug")) flags.add("--debug")
                if (helpOutput.contains("-e, --eternalize") || helpOutput.contains("-e") || cliPath.contains("frida-inject")) {
                    flags.add("-e")
                }

                val scriptFlag = if (helpOutput.contains("-l, --load")) "-l" else "-s"
                val scriptPath = stealthConfig.getActiveScriptPath()

                val commandParts = mutableListOf(cliPath)
                if (selectedFridaBinary == "server" && (helpOutput.contains("-H, --host") || helpOutput.contains("-H"))) {
                    commandParts.add("-H")
                    commandParts.add("127.0.0.1:${stealthConfig.getActivePort()}")
                }

                if (!isFridaInject && (binaryInfo.isCliCapable || helpOutput.contains("-f, --file") || helpOutput.contains("-f"))) {
                    LogManager.addLog(packageName, "Early-spawning process $packageName via -f...", isError = false)
                    commandParts.add("-f")
                    commandParts.add(packageName)
                } else {
                    LogManager.addLog(packageName, "Launching target $packageName via launcher...", isError = false)
                    executeRootCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                    delay(1000)

                    val targetPid = resolveTargetPid(packageName)
                    if (targetPid != null) {
                        LogManager.addLog(packageName, "Target PID resolved: $targetPid", isError = false)
                        commandParts.add("-p")
                        commandParts.add(targetPid)
                    } else {
                        commandParts.add("-n")
                        commandParts.add(packageName)
                    }
                }

                commandParts.add(scriptFlag)
                commandParts.add(scriptPath)
                commandParts.addAll(flags)

                val command = commandParts.joinToString(" ")
                Log.d(TAG, "Executing: $command")
                LogManager.addLog(packageName, "Executing command: $command", isError = false)
                
                LogManager.addLog(packageName, "Starting FridaService and executing injection...")
                val serviceIntent = Intent(context, FridaService::class.java).apply {
                    putExtra("package_name", packageName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                val execCmd = if (isFridaInject) "setsid $command" else command
                scriptProcess = try {
                    Runtime.getRuntime().exec(arrayOf("su", "0", "-c", execCmd))
                } catch (_: Exception) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", execCmd))
                }
                captureProcessOutput(packageName)
                _isScriptRunning.value = true

                if (scriptContent.contains("rpc.exports") || scriptContent.contains("EXPORTS")) {
                    scope.launch {
                        delay(5000)
                        if (initializeRpc()) LogManager.addLog(packageName, "RPC bridge ready")
                    }
                }
                
                scope.launch(Dispatchers.IO) {
                    val exitCode = scriptProcess?.waitFor() ?: -1
                    if (exitCode == 0) {
                        LogManager.addLog(packageName, "Injection completed successfully! Script active in $packageName.", isError = false)
                    } else {
                        LogManager.addLog(packageName, "Injection process exited with code $exitCode", isError = true)
                    }
                    if (!isStopping && exitCode != 0) stopScript(keepServer = true)
                }
                
                true
            } catch (e: Exception) {
                LogManager.addLog(packageName, "Execution failed: ${e.message}", isError = true)
                false
            }
        }
    }

    suspend fun attachToRunningProcess(packageName: String, scriptContent: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (!InputValidator.validatePackageName(packageName)) return@withContext false
            if (!hasRootAccess()) return@withContext false

            val cliPath = binaryManager.getAvailableFridaCli(selectedFridaBinary) ?: return@withContext false
            val binaryInfo = binaryManager.getBinaryInfo(cliPath)

            stopScript(keepServer = true)

            if (!deployScriptInternal(scriptContent)) {
                LogManager.addLog(packageName, "Script deployment failed", isError = true)
                return@withContext false
            }

            if (!isFridaServerRunning()) {
                startFridaServer()
            }

            SecurityStateManager.setSecurityModified()
            if (!isSelinuxPermissive()) setSelinuxPermissive(true)
            executeRootCommand("echo 0 > /proc/sys/kernel/yama/ptrace_scope")

            try {
                _currentPackageName.value = packageName
                LogManager.addLog(packageName, "Attaching Frida script to running process $packageName...")

                val helpOutput = ShellExecutor.executeSimple("$cliPath --help", useRoot = true)
                val isFridaInject = (selectedFridaBinary == "inject") ||
                                    (selectedFridaBinary != "server" && selectedFridaBinary != "cli" && cliPath.contains("frida-inject"))
                val scriptFlag = if (helpOutput.contains("-l, --load")) "-l" else "-s"
                val scriptPath = stealthConfig.getActiveScriptPath()

                val flags = mutableListOf<String>()
                if ((settings.useNoPause || !isFridaInject) && helpOutput.contains("--no-pause")) flags.add("--no-pause")
                if (settings.useRuntimeV8 && helpOutput.contains("--runtime")) flags.add("--runtime=v8")
                if (helpOutput.contains("-e, --eternalize") || helpOutput.contains("-e") || cliPath.contains("frida-inject")) flags.add("-e")

                val targetPid = resolveTargetPid(packageName)
                val commandParts = mutableListOf(cliPath)
                if (selectedFridaBinary == "server" && (helpOutput.contains("-H, --host") || helpOutput.contains("-H"))) {
                    commandParts.add("-H")
                    commandParts.add("127.0.0.1:${stealthConfig.getActivePort()}")
                }

                if (targetPid != null) {
                    LogManager.addLog(packageName, "Target PID resolved: $targetPid", isError = false)
                    if (helpOutput.contains("-p, --attach-pid") || helpOutput.contains("-p") || isFridaInject) {
                        commandParts.add("-p")
                        commandParts.add(targetPid)
                    } else if (helpOutput.contains("-n, --attach-name") || helpOutput.contains("-n")) {
                        commandParts.add("-n")
                        commandParts.add(packageName)
                    } else {
                        commandParts.add("-p")
                        commandParts.add(targetPid)
                    }
                } else {
                    LogManager.addLog(packageName, "PID not found for $packageName. Spawning via -f...", isError = false)
                    if (!isFridaInject && (binaryInfo.isCliCapable || helpOutput.contains("-f, --file"))) {
                        commandParts.add("-f")
                        commandParts.add(packageName)
                    } else {
                        executeRootCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                        delay(1500)
                        val retryPid = resolveTargetPid(packageName)
                        if (retryPid != null) {
                            commandParts.add("-p")
                            commandParts.add(retryPid)
                        } else {
                            commandParts.add("-n")
                            commandParts.add(packageName)
                        }
                    }
                }

                commandParts.add(scriptFlag)
                commandParts.add(scriptPath)
                commandParts.addAll(flags)

                val command = commandParts.joinToString(" ")
                Log.d(TAG, "Attach Executing: $command")
                LogManager.addLog(packageName, "Executing command: $command", isError = false)

                val execCmd = if (isFridaInject) "setsid $command" else command
                scriptProcess = try {
                    Runtime.getRuntime().exec(arrayOf("su", "0", "-c", execCmd))
                } catch (_: Exception) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", execCmd))
                }
                captureProcessOutput(packageName)
                _isScriptRunning.value = true

                Thread {
                    val exitCode = scriptProcess?.waitFor() ?: -1
                    if (exitCode == 0) {
                        LogManager.addLog(packageName, "Attach completed successfully! Script active in $packageName.", isError = false)
                    } else {
                        LogManager.addLog(packageName, "Attach process exited with code $exitCode", isError = true)
                    }
                    if (!isStopping && exitCode != 0) stopScript(keepServer = true)
                }.start()

                true
            } catch (e: Exception) {
                LogManager.addLog(packageName, "Attach failed: ${e.message}", isError = true)
                false
            }
        }
    }

    private fun resolveTargetPid(packageName: String): String? {
        val pidCmd = "pidof $packageName 2>/dev/null || pgrep -f $packageName 2>/dev/null || ps -A 2>/dev/null | grep $packageName | awk '{print \$2}'"
        val output = executeRootCommand(pidCmd).trim()
        val tokens = output.split("\\s+".toRegex()).map { it.trim() }
        for (token in tokens) {
            if (token.isNotEmpty() && token.all { it.isDigit() }) {
                val exists = executeRootCommand("[ -d /proc/$token ] && echo yes").trim()
                if (exists == "yes") {
                    return token
                }
            }
        }
        return null
    }

    private fun captureProcessOutput(packageName: String) {
        val process = scriptProcess ?: return
        
        Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            JsonMessageProcessor.processLine(it, dynamicUiManager, stalkerManager)
                            if (it.contains("Hooking") || it.contains("Blocking") || it.contains("Hooked")) {
                                val hookName = it.substringAfter("Hooking ").substringAfter("Blocking ").substringAfter("Hooked ").substringBefore(" at").trim()
                                if (hookName.isNotEmpty()) detectedHooksSet.add(hookName)
                            }
                            LogManager.addLog(packageName, it)
                            LogManager.addFridaLog(it)
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()

        Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            if (it.contains("ioctl(22)")) {
                                LogManager.addLog(packageName, "Binder error detected (ioctl 22). Check Zygisk/Magisk conflicts.", isError = true)
                            }

                            val isSpawnTimeout = it.contains("timed out", ignoreCase = true) ||
                                               it.contains("waiting for app to launch", ignoreCase = true) ||
                                               it.contains("unable to launch", ignoreCase = true) ||
                                               it.contains("Failed to spawn", ignoreCase = true)

                            if (isSpawnTimeout) {
                                LogManager.addLog(packageName, "[Snakeloader] Spawn timeout detected ($it). Verifying process and auto-attaching...", isError = false)
                                scope.launch(Dispatchers.IO) {
                                    delay(1200)
                                    val pidOutput = executeRootCommand("pidof $packageName || pgrep -f $packageName").trim()
                                    val pids = pidOutput.lines().map { l -> l.trim() }.filter { l -> l.isNotBlank() && l.all { c -> c.isDigit() } }
                                    if (pids.isNotEmpty()) {
                                        LogManager.addLog(packageName, "[Snakeloader] App process running (PID ${pids.first()}). Auto-attaching Frida...", isError = false)
                                        val scriptPath = stealthConfig.getActiveScriptPath()
                                        val activeContent = try { File(scriptPath).readText() } catch (e: Exception) { "" }
                                        if (activeContent.isNotEmpty()) {
                                            attachToRunningProcess(packageName, activeContent)
                                        }
                                    } else {
                                        LogManager.addLog(packageName, "[Snakeloader] App not running. Launching app and re-attaching...", isError = false)
                                        executeRootCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                                        delay(2000)
                                        val recheckPids = executeRootCommand("pidof $packageName || pgrep -f $packageName").trim()
                                            .lines().map { l -> l.trim() }.filter { l -> l.isNotBlank() && l.all { c -> c.isDigit() } }
                                        if (recheckPids.isNotEmpty()) {
                                            val activeContent = try { File(stealthConfig.getActiveScriptPath()).readText() } catch (e: Exception) { "" }
                                            if (activeContent.isNotEmpty()) {
                                                attachToRunningProcess(packageName, activeContent)
                                            }
                                        }
                                    }
                                }
                            } else {
                                LogManager.addLog(packageName, it, isError = true)
                                LogManager.addFridaLog("[ERROR] $line")
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    fun executeRootCommand(command: String): String {
        return ShellExecutor.executeSimple(command, useRoot = true)
    }

    fun setSelinuxPermissive(permissive: Boolean) {
        val state = if (permissive) "0" else "1"
        executeRootCommand("setenforce $state")
    }

    private fun isSelinuxPermissive(): Boolean {
        val output = executeRootCommand("getenforce").trim()
        return output.equals("Permissive", ignoreCase = true)
    }

    private fun clearBackgroundApps() {
        executeRootCommand("sync && echo 3 > /proc/sys/vm/drop_caches")
    }

    private fun getAvailableMemory(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    suspend fun initializeRpc(): Boolean {
        rpcBridge = RpcBridge(context)
        return rpcBridge?.startRpcServer() ?: false
    }

    fun getDetectedHooks(): Set<String> = detectedHooksSet.toSet()
}
