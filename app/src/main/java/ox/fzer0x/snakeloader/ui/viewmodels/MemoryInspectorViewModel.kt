package ox.fzer0x.snakeloader.ui.viewmodels

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import ox.fzer0x.snakeloader.HookInfo
import ox.fzer0x.snakeloader.RpcManager

class MemoryInspectorViewModel(
    private val rpcManager: RpcManager? = null,
    private val fridaManager: ox.fzer0x.snakeloader.FridaManager? = null
) : ViewModel() {
    companion object {
        private const val TAG = "MemoryInspectorViewModel"
    }

    val hooks = mutableStateOf<List<HookInfo>>(emptyList())
    val isLoading = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val memoryDump = mutableStateOf<Map<String, String>>(emptyMap())
    val registryInfo = mutableStateOf<Map<String, Any>?>(null)
    val isRpcAvailable = mutableStateOf(false)
    
    val scanResults = mutableStateOf<List<Map<String, Any>>>(emptyList())
    val lastScanPattern = mutableStateOf("")

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val detectedHooks = mutableStateOf<MutableSet<String>>(mutableSetOf())

    fun refreshHooks() {
        isLoading.value = true
        errorMessage.value = null

        scope.launch {
            val shouldUseRpc = checkRpcAvailability()

            if (shouldUseRpc && rpcManager != null) {
                rpcManager.listHooksAsync { hookList ->
                    hooks.value = hookList
                    isLoading.value = false
                    isRpcAvailable.value = true
                    Log.d(TAG, "Loaded ${hookList.size} hooks via RPC")
                }
            } else {
                isRpcAvailable.value = false
                loadHooksFromLogs()
            }
        }
    }

    private suspend fun checkRpcAvailability(): Boolean {
        if (rpcManager == null) {
            Log.d(TAG, "RPC manager is null")
            return false
        }

        if (fridaManager == null) {
            Log.d(TAG, "FridaManager is null")
            return false
        }

        val status = fridaManager.getFridaStatus()
        val runningBinary = status["running_binary"] as? String ?: "none"
        val selectedMode = status["selected_mode"] as? String ?: "auto"

        val isCliRunning = runningBinary == "cli" || runningBinary == "inject"

        Log.d(TAG, "RPC availability check: runningBinary=$runningBinary, selectedMode=$selectedMode, isCliRunning=$isCliRunning")

        return isCliRunning
    }

    private fun loadHooksFromLogs() {
        scope.launch {
            try {
                val hookList = mutableListOf<HookInfo>()

                hookList.add(HookInfo(name = "__rpc_handler__", active = true))
                hookList.add(HookInfo(name = "__memory_dumper__", active = true))
                hookList.add(HookInfo(name = "__test_hook__", active = true))
                hookList.add(HookInfo(name = "__memory_test__", active = true))

                detectedHooks.value.forEach { hookName ->
                    if (!hookList.any { it.name == hookName }) {
                        hookList.add(HookInfo(name = hookName, active = true))
                    }
                }

                hooks.value = hookList
                isLoading.value = false
                Log.d(TAG, "Loaded ${hookList.size} hooks from logs")
                
                getRegistryStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load hooks from logs", e)
                errorMessage.value = "Failed to load hooks: ${e.message}"
                isLoading.value = false
            }
        }
    }

    fun addDetectedHook(hookName: String) {
        if (!detectedHooks.value.contains(hookName)) {
            detectedHooks.value.add(hookName)
            Log.d(TAG, "Detected hook: $hookName")
        }
    }

    fun toggleHook(hookName: String) {
        if (isRpcAvailable.value) {
            rpcManager?.toggleHookAsync(hookName) { success ->
                if (success) {
                    hooks.value = hooks.value.map {
                        if (it.name == hookName) it.copy(active = !it.active) else it
                    }
                    Log.d(TAG, "Hook toggled: $hookName")
                } else {
                    errorMessage.value = "Failed to toggle hook: $hookName"
                }
            }
        } else {
            errorMessage.value = "RPC not available - cannot toggle hooks in inject mode"
        }
    }

    fun dumpMemoryRegion(address: String, size: Int) {
        isLoading.value = true
        errorMessage.value = null

        if (isRpcAvailable.value) {
            rpcManager?.dumpMemoryAsync(address, size) { data ->
                isLoading.value = false
                if (data != null) {
                    memoryDump.value = parseMemoryDump(address, data)
                    Log.d(TAG, "Memory dumped: $address (${data.size} bytes)")
                } else {
                    errorMessage.value = "Failed to dump memory at $address"
                }
            }
        } else {
            isLoading.value = false
            errorMessage.value = "RPC not available - memory dump requires RPC connection"
        }
    }

    private fun parseMemoryDump(address: String, data: ByteArray): Map<String, String> {
        val hex = data.joinToString(" ") { "%02x".format(it) }
        val ascii = data.map { 
            if (it in 32..126) it.toInt().toChar() else '.'
        }.joinToString("")

        return mapOf(
            "address" to address,
            "size" to "${data.size} bytes",
            "hex" to hex,
            "ascii" to ascii
        )
    }

    fun performScan(pattern: String, moduleName: String = "") {
        if (!isRpcAvailable.value) {
            errorMessage.value = "RPC not available for scanning"
            return
        }

        isLoading.value = true
        errorMessage.value = null
        lastScanPattern.value = pattern

        rpcManager?.findPatternAsync(moduleName, pattern) { results ->
            isLoading.value = false
            scanResults.value = results
            Log.d(TAG, "Scan found ${results.size} matches")
        }
    }

    fun writeMemory(address: String, hexValue: String) {
        if (!isRpcAvailable.value) {
            errorMessage.value = "RPC not available for memory write"
            return
        }

        isLoading.value = true
        rpcManager?.writeMemoryAsync(address, hexValue) { success ->
            isLoading.value = false
            if (success) {
                Log.d(TAG, "Successfully wrote $hexValue to $address")
                if (memoryDump.value["address"] == address) {
                    dumpMemoryRegion(address, memoryDump.value["size"]?.replace(" bytes", "")?.toIntOrNull() ?: 256)
                }
            } else {
                errorMessage.value = "Failed to write memory at $address"
            }
        }
    }

    fun getRegistryStatus() {
        if (isRpcAvailable.value) {
            rpcManager?.getDiagnosticsAsync { registry ->
                registryInfo.value = registry
                Log.d(TAG, "Registry: $registry")
            }
        } else {
            registryInfo.value = mapOf(
                "hooks" to hooks.value.map { it.name },
                "scripts" to listOf("rpc_handler.js", "Memory_Dumper.js", "Godmod_Snake.io.js", "test_basic.js"),
                "mode" to "Discovery Mode (RPC connecting...)",
                "message" to "Attempting log-based detection while RPC bridge initializes"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}
