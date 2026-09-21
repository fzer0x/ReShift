package ox.fzer0x.snakeloader.ui.viewmodels

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.FridaBinaryDownloader

class AdvancedFridaViewModel(
    private val settingsManager: SettingsManager
) : ViewModel() {
    companion object {
        private const val TAG = "AdvancedFridaViewModel"
    }

    val selectedMode = mutableStateOf("combined")
    val selectedScripts = mutableStateOf<List<String>>(emptyList())
    val currentPackage = mutableStateOf("")
    val customScriptContents = mutableStateOf<Map<String, String>>(emptyMap())
    val isExecuting = mutableStateOf(false)
    val selectedFridaBinary = mutableStateOf("auto")
    val availableBinaries = mutableStateOf<Map<String, Boolean>>(emptyMap())
    val fridaStatus = mutableStateOf<Map<String, Any>>(emptyMap())
    val downloadProgress = mutableStateOf("")
    val isDownloading = mutableStateOf(false)

    val useExceptorOff = mutableStateOf(settingsManager.useExceptorOff)
    val useRuntimeV8 = mutableStateOf(settingsManager.useRuntimeV8)
    val useNoPause = mutableStateOf(settingsManager.useNoPause)
    val useDebugLog = mutableStateOf(settingsManager.useDebugLog)

    fun toggleExceptorOff(enabled: Boolean) {
        useExceptorOff.value = enabled
        settingsManager.useExceptorOff = enabled
    }

    fun toggleRuntimeV8(enabled: Boolean) {
        useRuntimeV8.value = enabled
        settingsManager.useRuntimeV8 = enabled
    }

    fun toggleNoPause(enabled: Boolean) {
        useNoPause.value = enabled
        settingsManager.useNoPause = enabled
    }

    fun toggleDebugLog(enabled: Boolean) {
        useDebugLog.value = enabled
        settingsManager.useDebugLog = enabled
    }

    fun setSelectedMode(mode: String) {
        selectedMode.value = mode
    }

    fun setSelectedScripts(scripts: List<String>) {
        selectedScripts.value = scripts
    }

    fun setCurrentPackage(packageName: String) {
        currentPackage.value = packageName
    }

    fun addCustomScript(fileName: String, content: String) {
        customScriptContents.value = customScriptContents.value + (fileName to content)
    }

    fun setExecuting(executing: Boolean) {
        isExecuting.value = executing
    }

    fun setSelectedFridaBinary(binary: String) {
        selectedFridaBinary.value = binary
    }

    fun setAvailableBinaries(binaries: Map<String, Boolean>) {
        availableBinaries.value = binaries
    }

    fun setFridaStatus(status: Map<String, Any>) {
        fridaStatus.value = status
    }

    fun setAvailableBinaries(binaries: List<String>) {
        availableBinaries.value = binaries.associateWith { true }
    }

    fun clearCustomScripts() {
        customScriptContents.value = emptyMap()
    }

    fun downloadBinary(fridaManager: FridaManager, type: String) {
        viewModelScope.launch {
            isDownloading.value = true
            downloadProgress.value = "Fetching latest version..."
            
            val downloader = FridaBinaryDownloader(fridaManager.context, fridaManager.stealthConfig)
            val latestVersion = downloader.getLatestVersion()
            
            if (latestVersion == null) {
                downloadProgress.value = "Failed to fetch version info"
                isDownloading.value = false
                return@launch
            }

            downloadProgress.value = "Downloading $type ($latestVersion)..."
            val success = downloader.downloadAndInstall(latestVersion, type) { progress: String ->
                downloadProgress.value = progress
            }

            if (success) {
                downloadProgress.value = "Installed successfully!"
                setFridaStatus(fridaManager.getFridaStatus())
                setAvailableBinaries(fridaManager.getAvailableBinaries())
            } else {
                downloadProgress.value = "Installation failed"
            }
            
            isDownloading.value = false
        }
    }
}
