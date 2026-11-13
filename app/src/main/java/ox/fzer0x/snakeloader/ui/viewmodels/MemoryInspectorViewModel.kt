package ox.fzer0x.snakeloader.ui.viewmodels

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ox.fzer0x.snakeloader.HookInfo
import ox.fzer0x.snakeloader.RpcBridge
import ox.fzer0x.snakeloader.data.*

class MemoryInspectorViewModel(
    val rpcBridge: RpcBridge? = null,
    val fridaManager: ox.fzer0x.snakeloader.FridaManager? = null,
    val patchRepository: PatchRepository? = null,
    val transactionManager: PatchTransactionManager? = null
) : ViewModel() {
    companion object {
        private const val TAG = "MemoryInspectorViewModel"
    }

    val hooks = mutableStateOf<List<HookInfo>>(emptyList())
    val isLoading = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val memoryDump = mutableStateOf<Map<String, String>>(emptyMap())
    val isRpcAvailable = mutableStateOf(false)
    
    val scanResults = mutableStateOf<List<AobScanResult>>(emptyList())
    val activeScanId = mutableStateOf<String?>(null)
    val isAobScanning = mutableStateOf(false)
    val aobStatusMessage = mutableStateOf<String?>(null)

    private val detectedHooks = mutableStateOf<MutableSet<String>>(mutableSetOf())
    private var scanJob: Job? = null

    val patches: StateFlow<List<MemoryPatch>> = patchRepository?.patches ?: MutableStateFlow<List<MemoryPatch>>(emptyList()).asStateFlow()

    init {
        checkRpcStatus()
    }

    fun refreshHooks() {
        checkRpcStatus()
    }

    fun checkRpcStatus() {
        viewModelScope.launch {
            if (fridaManager != null) {
                val status = fridaManager.getFridaStatus()
                val serverRunning = status["server_running"] as? Boolean ?: false
                isRpcAvailable.value = serverRunning
            }
        }
    }

    fun startAobScan(moduleName: String?, patternHex: String, minFixedBytes: Int = 2, maxResults: Int = 100) {
        if (rpcBridge == null) {
            errorMessage.value = "RPC Bridge not initialized"
            return
        }

        scanJob?.cancel()
        isAobScanning.value = true
        errorMessage.value = null
        aobStatusMessage.value = "Initiating AOB scan..."

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = rpcBridge.startAobScan(moduleName, patternHex, minFixedBytes, maxResults)
                if (response.error != null) {
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "AOB Scan failed: ${response.error}"
                        isAobScanning.value = false
                    }
                    return@launch
                }

                val resultMap = response.result as? Map<*, *>
                val scanId = resultMap?.get("scanId") as? String
                val initStatus = resultMap?.get("status") as? String

                if (scanId == null || initStatus == "ERROR") {
                    withContext(Dispatchers.Main) {
                        errorMessage.value = resultMap?.get("error") as? String ?: "Failed to start AOB scan"
                        isAobScanning.value = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    activeScanId.value = scanId
                    aobStatusMessage.value = "Scanning memory..."
                }

                // Poll scan status until complete or cancelled
                while (isActive) {
                    delay(500)
                    val statusResp = rpcBridge.getAobScanStatus(scanId)
                    val statusMap = statusResp.result as? Map<*, *> ?: break
                    val statusStr = statusMap["status"] as? String ?: "ERROR"

                    val resultsListRaw = statusMap["results"] as? List<*> ?: emptyList<Any>()
                    val parsedResults = resultsListRaw.mapNotNull { item ->
                        val map = item as? Map<*, *> ?: return@mapNotNull null
                        AobScanResult(
                            addressHex = map["addressHex"] as? String ?: "",
                            moduleName = map["moduleName"] as? String ?: "",
                            rvaOffsetHex = map["rvaOffsetHex"] as? String ?: "",
                            matchedBytesHex = map["matchedBytesHex"] as? String ?: "",
                            size = (map["size"] as? Number)?.toInt() ?: 4
                        )
                    }

                    withContext(Dispatchers.Main) {
                        scanResults.value = parsedResults
                        aobStatusMessage.value = "Scanning... Found ${parsedResults.size} match(es)"
                    }

                    if (statusStr == "COMPLETED" || statusStr == "CANCELLED" || statusStr == "ERROR") {
                        withContext(Dispatchers.Main) {
                            isAobScanning.value = false
                            activeScanId.value = null
                            aobStatusMessage.value = if (statusStr == "COMPLETED") "Scan completed. ${parsedResults.size} match(es)." else "Scan $statusStr."
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "AOB Scan error: ${e.message}"
                        isAobScanning.value = false
                    }
                }
            }
        }
    }

    fun cancelAobScan() {
        val scanId = activeScanId.value
        scanJob?.cancel()
        scanJob = null
        isAobScanning.value = false
        aobStatusMessage.value = "Scan cancelled"

        if (scanId != null && rpcBridge != null) {
            viewModelScope.launch(Dispatchers.IO) {
                rpcBridge.cancelAobScan(scanId)
            }
        }
    }

    fun applyPatch(patch: MemoryPatch) {
        if (patchRepository == null) return
        viewModelScope.launch(Dispatchers.IO) {
            patchRepository.savePatch(patch)
            patchRepository.togglePatch(patch.id)
        }
    }

    fun togglePatch(patchId: String) {
        if (patchRepository == null) return
        viewModelScope.launch(Dispatchers.IO) {
            patchRepository.togglePatch(patchId)
        }
    }

    fun removePatch(patchId: String) {
        if (patchRepository == null) return
        viewModelScope.launch(Dispatchers.IO) {
            patchRepository.removePatch(patchId)
        }
    }

    fun convertAobResultToPatch(result: AobScanResult, packageName: String, patchName: String, preset: PatchPreset, customHex: String = "") {
        val hexToWrite = if (preset == PatchPreset.CUSTOM) customHex else preset.hexBytes
        if (hexToWrite.isBlank()) return

        val newPatch = MemoryPatch(
            id = "patch_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = patchName.ifBlank { "Patch @ ${result.rvaOffsetHex}" },
            packageName = packageName,
            moduleName = result.moduleName,
            rvaOffsetHex = result.rvaOffsetHex,
            lastRuntimeAddressHex = result.addressHex,
            originalBytesHex = result.matchedBytesHex,
            patchBytesHex = hexToWrite,
            status = PatchStatus.UNVERIFIED,
            targetArch = TargetArchitecture.ARM64,
            preset = preset
        )

        applyPatch(newPatch)
    }

    fun addDetectedHook(hookName: String) {
        if (!detectedHooks.value.contains(hookName)) {
            detectedHooks.value.add(hookName)
        }
    }
}
