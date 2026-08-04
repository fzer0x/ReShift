package ox.fzer0x.snakeloader.ui.viewmodels

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import ox.fzer0x.snakeloader.DownloadedScript
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.SettingsManager

class AppDetailsViewModel(
    private val packageName: String,
    private val scriptManager: ScriptManager,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _assignedScripts = mutableStateOf(scriptManager.getAssignmentsForApp(packageName))
    val assignedScripts: State<List<DownloadedScript>> = _assignedScripts

    private val _allScripts = mutableStateOf(scriptManager.getScripts())
    val allScripts: State<List<DownloadedScript>> = _allScripts

    val useExceptorOff = mutableStateOf(settingsManager.useExceptorOff)
    val useRuntimeV8 = mutableStateOf(settingsManager.useRuntimeV8)
    val useNoPause = mutableStateOf(settingsManager.useNoPause)
    val useDebugLog = mutableStateOf(settingsManager.useDebugLog)

    fun refreshScripts() {
        _assignedScripts.value = scriptManager.getAssignmentsForApp(packageName)
        _allScripts.value = scriptManager.getScripts()
    }

    fun assignScript(scriptId: String) {
        scriptManager.assignScriptToApp(packageName, scriptId)
        refreshScripts()
    }

    fun unassignScript(scriptId: String) {
        scriptManager.unassignScriptFromApp(packageName, scriptId)
        refreshScripts()
    }

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
}
