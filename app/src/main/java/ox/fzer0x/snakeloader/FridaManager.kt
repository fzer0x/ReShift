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
        get() = prefs.getString(PREF_SELECTED_BINARY, "inject") ?: "inject"
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
        val runningBinary = selectedFridaBinary
        val isSmooth = isFridaRunningSmoothly()
        val serverRunning = isFridaServerRunning()

        val batchCmd = "which frida-inject frida-server frida 2>/dev/null; getenforce 2>/dev/null; cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null"
        val batchResult = executeRootCommand(batchCmd)
        val lines = batchResult.lines().filter { it.isNotEmpty() }
        
        val availableInject = lines.any { it == "I:1" || it == "M:1" }
        val availableCli = lines.any { it == "C:1" } || binaryManager.getAvailableFridaCli("cli") != null
        
        val selinuxOutput = lines.find { it == "Permissive" || it == "Enforcing" } ?: "Enforcing"
        val isSelinuxPermissive = selinuxOutput.equals("Permissive", ignoreCase = true)
        val ptraceVal = lines.lastOrNull()?.trim() ?: "1"
        val isPtraceScopeDisabled = ptraceVal == "0"

        return mapOf(
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

    fun isFridaRunningSmoothly(): Boolean {
        return isFridaServerRunning()
    }

    fun getCurrentFridaBinarySelection(): String = selectedFridaBinary
    fun getRunningFridaBinary(): String = selectedFridaBinary
    fun getAvailableBinaries(): List<String> = listOf("inject", "cli")
    fun isYamaSupported(): Boolean = true
    fun enablePtraceScope() = executeRootCommand("echo 1 > /proc/sys/kernel/yama/ptrace_scope")
    fun disablePtraceScope() = executeRootCommand("echo 0 > /proc/sys/kernel/yama/ptrace_scope")
    fun updateStealthConfig() = stealthConfig.writeStealthConfig()
    fun getRpcManager(): RpcBridge? = rpcBridge

    fun executeMultipleScripts(scripts: List<Any>, pkg: String, mode: String = "auto"): Boolean {
        return true
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
        if (InputValidator.validatePackageName(packageName)) executeRootCommand("am force-stop $packageName")
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
                if (settings.useNoPause) flags.add("--no-pause")
                if (settings.useRuntimeV8) flags.add("--runtime=v8")
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                val scriptFlag = if (helpOutput.contains("-l, --load")) "-l" else "-s"
                val scriptPath = stealthConfig.getActiveScriptPath()

                val flags = mutableListOf<String>()
                if (settings.useNoPause) flags.add("--no-pause")
                if (settings.useRuntimeV8) flags.add("--runtime=v8")

                // Robust PID Resolution across Android process trees
                val pidOutput = executeRootCommand("pidof $packageName || pgrep -f $packageName || ps -A | grep $packageName | awk '{print $2}'").trim()
                val pids = pidOutput.lines().map { it.trim() }.filter { it.isNotBlank() && it.all { c -> c.isDigit() } }

                val commandParts = mutableListOf(cliPath)

                if (pids.isNotEmpty()) {
                    val targetPid = pids.first()
                    Log.d(TAG, "Resolved target PID $targetPid for $packageName")
                    if (helpOutput.contains("-n, --attach-name") || helpOutput.contains("-n")) {
                        commandParts.add("-n")
                        commandParts.add(packageName)
                    } else if (helpOutput.contains("-p, --attach-pid") || helpOutput.contains("-p")) {
                        commandParts.add("-p")
                        commandParts.add(targetPid)
                    } else {
                        commandParts.add("-n")
                        commandParts.add(packageName)
                    }
                } else {
                    Log.w(TAG, "PID not found for $packageName. Spawning via -f...")
                    if (binaryInfo.isCliCapable || helpOutput.contains("-f, --file")) {
                        commandParts.add("-f")
                        commandParts.add(packageName)
                    } else {
                        executeRootCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                        Thread.sleep(1500)
                        commandParts.add("-n")
                        commandParts.add(packageName)
                    }
                }

                commandParts.add(scriptFlag)
                commandParts.add(scriptPath)
                commandParts.addAll(flags)

                val command = commandParts.joinToString(" ")
                Log.d(TAG, "Attach Executing: $command")

                scriptProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                captureProcessOutput(packageName)
                _isScriptRunning.value = true

                Thread {
                    scriptProcess?.waitFor()
                    if (!isStopping) stopScript(keepServer = true)
                }.start()

                true
            } catch (e: Exception) {
                LogManager.addLog(packageName, "Attach failed: ${e.message}", isError = true)
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
                            LogManager.addLog(packageName, it, isError = true)
                            LogManager.addFridaLog("[ERROR] $line")
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
