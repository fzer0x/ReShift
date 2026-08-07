package ox.fzer0x.snakeloader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import ox.fzer0x.snakeloader.utils.ShellExecutor
import ox.fzer0x.snakeloader.utils.InputValidator
import ox.fzer0x.snakeloader.utils.ProcessMonitor
import ox.fzer0x.snakeloader.utils.JsonMessageProcessor
import ox.fzer0x.snakeloader.security.SecurityStateManager
import android.content.Intent
import android.app.ActivityManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class FridaManager(
    val context: Context,
    val stealthConfig: StealthConfigManager,
    val binaryManager: BinaryManager
) : DefaultLifecycleObserver {

    private val prefs: SharedPreferences = context.getSharedPreferences("frida_settings", Context.MODE_PRIVATE)
    val settings = SettingsManager(context)

    companion object {
        private const val TAG = "FridaManager"
        private const val PREF_SELECTED_BINARY = "selected_frida_binary"
    }

    private var scriptProcess: Process? = null
    private var isStopping = false
    
    private val _isScriptRunning = MutableStateFlow(false)
    val isScriptRunning = _isScriptRunning.asStateFlow()

    private var cachedHasRoot: Boolean? = null
    private var cachedFridaVersion: String? = null
    private var multiScriptLoader: MultiScriptLoader? = null
    private var rpcManager: RpcManager? = null
    val dynamicUiManager = DynamicUiManager()
    val stalkerManager = StalkerManager()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var _currentPackageName = mutableStateOf("")
    val currentPackageName: State<String> get() = _currentPackageName

    var selectedFridaBinary: String
        get() = prefs.getString(PREF_SELECTED_BINARY, "auto") ?: "auto"
        set(value) = prefs.edit().putString(PREF_SELECTED_BINARY, value).apply()

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cleanup()
    }

    fun getCurrentFridaBinarySelection(): String = selectedFridaBinary

    suspend fun getAvailableBinaries(): Map<String, Boolean> {
        val status = getFridaStatus()
        return mapOf(
            "cli" to (status["available_cli"] == true),
            "inject" to (status["available_inject"] == true)
        )
    }

    fun getRunningFridaBinary(): String = binaryManager.getRunningFridaBinary()

    suspend fun isFridaRunningSmoothly(): Boolean {
        val runningBinary = getRunningFridaBinary()
        if (runningBinary == "none") return false

        val serverName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_SERVER)
        val cliName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_CLI)
        val injectName = stealthConfig.getBinaryName(StealthConfigManager.DEFAULT_FRIDA_INJECT)
        
        val pgrepPattern = "$serverName|$cliName|$injectName|frida-server|nm-service|frida-inject"
        val pidResult = executeRootCommand("pgrep -f '$pgrepPattern'").trim()

        if (pidResult.isEmpty()) return false

        if (runningBinary == "cli" && !isFridaServerRunning()) {
            Log.w(TAG, "CLI mode but frida-server not running")
            return false
        }

        return true
    }

    suspend fun getFridaStatus(): Map<String, Any> = withContext(Dispatchers.IO) {
        ProcessMonitor.refreshAllProcesses(force = true)
        
        val runningBinary = getRunningFridaBinary()
        val serverRunning = isFridaServerRunning()

        val isSmooth = when (runningBinary) {
            "cli" -> serverRunning
            "inject", "server" -> true
            else -> false
        }

        val script = """
            [ -f ${stealthConfig.getActiveInjectPath()} ] && echo "I:1" || echo "I:0"
            [ -f ${stealthConfig.getActiveCliPath()} ] && echo "C:1" || echo "C:0"
            [ -f /data/adb/modules/snakeloader_frida/frida-inject ] && echo "M:1" || echo "M:0"
            getenforce
            cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo "1"
        """.trimIndent()

        val batchResult = executeRootCommand(script)
        val lines = batchResult.lines().filter { it.isNotEmpty() }
        
        val availableInject = lines.any { it == "I:1" || it == "M:1" }
        val availableCli = lines.any { it == "C:1" } || binaryManager.getAvailableFridaCli("cli") != null
        
        val selinuxOutput = lines.find { it == "Permissive" || it == "Enforcing" } ?: "Enforcing"
        val isSelinuxPermissive = selinuxOutput.equals("Permissive", ignoreCase = true)
        val ptraceVal = lines.lastOrNull()?.trim() ?: "1"
        val isPtraceScopeDisabled = ptraceVal == "0"

        mapOf(
            "running_binary" to runningBinary,
            "is_smooth" to isSmooth,
            "server_running" to serverRunning,
            "selected_mode" to selectedFridaBinary,
            "stealth_enabled" to settings.isStealthModeEnabled,
            "available_cli" to availableCli,
            "available_inject" to availableInject,
            "selinux_permissive" to isSelinuxPermissive,
            "ptrace_scope_disabled" to isPtraceScopeDisabled,
            "frida_version" to getFridaVersion()
        )
    }

    fun getModuleVersion(): String = ModuleManager(context, settings).getModuleVersion()

    fun hasRootAccess(forceCheck: Boolean = false): Boolean {
        if (!forceCheck && cachedHasRoot != null) return cachedHasRoot!!
        val result = ShellExecutor.execute("id", useRoot = true)
        val hasRoot = result.isSuccess && result.stdout.contains("uid=0")
        cachedHasRoot = hasRoot
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
                
                val command = "setsid nohup $availableServer -l 0.0.0.0:$port --origin=https://google.com > /dev/null 2>&1 &"
                Log.d(TAG, "Starting frida-server: $command")
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                
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

    private fun deployScriptInternal(content: String): Boolean {
        return try {
            val tempFile = File(context.cacheDir, "temp_script.js")
            tempFile.writeText(content)
            tempFile.setReadable(true, false)
            
            val scriptPath = stealthConfig.getActiveScriptPath()
            if (settings.isStealthModeEnabled) {
                executeRootCommand("mkdir -p ${stealthConfig.getBasePath()} && chmod 777 ${stealthConfig.getBasePath()}")
            }
            executeRootCommand("cat ${tempFile.absolutePath} > $scriptPath && chmod 644 $scriptPath")
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

            stopScript(keepServer = true)

            if (!deployScriptInternal(scriptContent)) {
                LogManager.addLog(packageName, "Script deployment failed", isError = true)
                return@withContext false
            }

            if (selectedFridaBinary == "cli" || !binaryInfo.isCliCapable) {
                if (!isFridaServerRunning()) {
                    LogManager.addLog(packageName, "Starting Frida server for CLI mode...")
                    startFridaServer()
                }
            }

            SecurityStateManager.setSecurityModified()
            
            if (!isSelinuxPermissive()) setSelinuxPermissive(true)
            executeRootCommand("echo 0 > /proc/sys/kernel/yama/ptrace_scope")

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
                Thread.sleep(1000)

                val flags = mutableListOf<String>()
                if (settings.useRuntimeV8) flags.add("--runtime=v8")
                if (settings.useNoPause) flags.add("--no-pause")
                if (settings.useExceptorOff) {
                    flags.add("--exceptor")
                    flags.add("off")
                }
                if (settings.useDebugLog) flags.add("--debug")

                val helpOutput = ShellExecutor.executeSimple("$cliPath --help", useRoot = true)
                val scriptFlag = if (helpOutput.contains("-l, --load")) "-l" else "-s"
                val scriptPath = stealthConfig.getActiveScriptPath()

                val commandParts = mutableListOf(cliPath)
                
                if (binaryInfo.isCliCapable) {
                    commandParts.add("-f")
                    commandParts.add(packageName)
                } else {
                    if (helpOutput.contains("-f, --file")) {
                        commandParts.add("-f")
                        commandParts.add(packageName)
                    } else {
                        LogManager.addLog(packageName, "Binary doesn't support -f, attempting spawn manually...", isError = false)
                        executeRootCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                        Thread.sleep(2000)
                        commandParts.add("-n")
                        commandParts.add(packageName)
                    }
                }

                commandParts.add(scriptFlag)
                commandParts.add(scriptPath)
                commandParts.addAll(flags)

                val command = commandParts.joinToString(" ")
                Log.d(TAG, "Executing: $command")
                
                LogManager.addLog(packageName, "Starting FridaService and executing injection...")
                val serviceIntent = Intent(context, FridaService::class.java).apply {
                    putExtra("package_name", packageName)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                scriptProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                captureProcessOutput(packageName)
                _isScriptRunning.value = true
                
                if (scriptContent.contains("rpc.exports") || scriptContent.contains("EXPORTS")) {
                    scope.launch {
                        delay(5000)
                        if (initializeRpc()) LogManager.addLog(packageName, "RPC bridge ready")
                    }
                }
                
                Thread {
                    scriptProcess?.waitFor()
                    if (!isStopping) stopScript(keepServer = true)
                }.start()
                
                true
            } catch (e: Exception) {
                LogManager.addLog(packageName, "Execution failed: ${e.message}", isError = true)
                false
            }
        }
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
                                if (hookName.isNotEmpty()) detectedHooks.add(hookName)
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
                            LogManager.addLog(packageName, it, isError = true)
                            LogManager.addFridaLog("[ERROR] $line")
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private val detectedHooks = mutableSetOf<String>()
    fun getDetectedHooks(): Set<String> = detectedHooks.toSet()
    
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

    fun killApp(packageName: String) {
        if (InputValidator.validatePackageName(packageName)) executeRootCommand("am force-stop $packageName")
    }

    fun clearAppData(packageName: String) {
        if (InputValidator.validatePackageName(packageName)) executeRootCommand("pm clear $packageName")
    }

    fun getAvailableMemory(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024) 
    }

    fun clearBackgroundApps() {
        Log.d(TAG, "Clearing background apps to free memory...")
        executeRootCommand("am kill-all")
    }

    fun setSelinuxPermissive(permissive: Boolean) = executeRootCommand(if (permissive) "setenforce 0" else "setenforce 1")

    fun isSelinuxPermissive(): Boolean = executeRootCommand("getenforce").trim().equals("Permissive", ignoreCase = true)

    fun disablePtraceScope(): Boolean = ShellExecutor.execute("echo 0 > /proc/sys/kernel/yama/ptrace_scope", useRoot = true).isSuccess

    fun enablePtraceScope(): Boolean = ShellExecutor.execute("echo 1 > /proc/sys/kernel/yama/ptrace_scope", useRoot = true).isSuccess

    fun isPtraceScopeDisabled(): Boolean {
        val result = ShellExecutor.execute("cat /proc/sys/kernel/yama/ptrace_scope", useRoot = true)
        return result.isSuccess && result.stdout.trim() == "0"
    }

    fun isYamaSupported(): Boolean {
        val result = ShellExecutor.execute("ls /proc/sys/kernel/yama/ptrace_scope", useRoot = true)
        return result.isSuccess
    }

    fun updateStealthConfig() {
        stealthConfig.writeStealthConfig()
    }

    fun getRunningProcesses(): List<Pair<String, String>> {
        val output = executeRootCommand("ps -A -o PID,NAME")
        return output.lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    fun getModulesForPid(pid: String): List<String> {
        val output = executeRootCommand("cat /proc/$pid/maps | awk '{print \$6}' | sort | uniq")
        return output.lines().filter { it.startsWith("/") && it.contains(".so") }.map { it.substringAfterLast("/") }.distinct().sorted()
    }

    fun executeRootCommand(command: String): String = ShellExecutor.executeSimple(command, useRoot = true)
    
    fun cleanup() {
        stopScript(keepServer = false)
        rpcManager?.cleanup()
    }

    suspend fun executeMultipleScripts(scripts: List<FridaScript>, packageName: String, mode: String = "combined"): Boolean {
        if (multiScriptLoader == null) multiScriptLoader = MultiScriptLoader(context, this)
        return multiScriptLoader!!.executeScripts(scripts, packageName, mode)
    }

    suspend fun executeFullToolchain(packageName: String): Boolean {
        return try {
            val scripts = listOf(
                FridaScript("rpc_handler", loadAssetScript("rpc_handler.js"), 3),
                FridaScript("memory_dumper", loadAssetScript("memory_dumper.js"), 2)
            )
            val success = executeMultipleScripts(scripts, packageName, "combined")
            if (success) {
                delay(4000)
                if (initializeRpc()) LogManager.addLog(packageName, "RPC bridge ready")
                else scope.launch {
                    repeat(15) {
                        delay(1000)
                        if (initializeRpc()) return@launch
                    }
                }
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    suspend fun initializeRpc(): Boolean {
        if (rpcManager == null) rpcManager = RpcManager(context)
        return rpcManager!!.startRpc()
    }

    fun getRpcManager(): RpcManager? = rpcManager

    private fun loadAssetScript(filename: String): String {
        return try {
            context.assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }
    }
}
