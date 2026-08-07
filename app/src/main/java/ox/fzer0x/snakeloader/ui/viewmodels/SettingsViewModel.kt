package ox.fzer0x.snakeloader.ui.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ModuleManager
import ox.fzer0x.snakeloader.SettingsManager

class SettingsViewModel(
    val fridaManager: FridaManager,
    private val settings: SettingsManager
) : BaseViewModel() {

    private var moduleManager: ModuleManager? = null

    private fun getModuleManager(context: Context): ModuleManager {
        if (moduleManager == null) {
            moduleManager = ModuleManager(context, settings)
        }
        return moduleManager!!
    }

    var isModuleInstalled by mutableStateOf(false)
        private set

    var moduleVersion by mutableStateOf("Not Installed")
        private set

    var isModuleServiceRunning by mutableStateOf(false)
        private set

    var installationLog by mutableStateOf("")
        private set

    var isInstalling by mutableStateOf(false)
        private set

    var installSuccess by mutableStateOf(false)
        private set

    fun refreshModuleStatus(context: Context) {
        launchWithLoading {
            val mm = getModuleManager(context)
            isModuleInstalled = mm.isModuleInstalled()
            if (isModuleInstalled) {
                moduleVersion = mm.getModuleVersion()
                isModuleServiceRunning = mm.isServiceRunning()
            } else {
                moduleVersion = "Not Installed"
                isModuleServiceRunning = false
            }
        }
    }

    fun installModule(context: Context) {
        launchWithLoading {
            if (fridaManager.hasRootAccess(forceCheck = true)) {
                installationLog = ""
                isInstalling = true
                installSuccess = false
                val success = getModuleManager(context).installModule { line ->
                    installationLog += "$line\n"
                }
                installSuccess = success
                if (success) {
                    refreshModuleStatus(context)
                }
                delay(1000)
                isInstalling = false
            }
        }
    }

    fun rebootDevice() {
        ox.fzer0x.snakeloader.utils.ShellExecutor.execute("reboot", useRoot = true)
    }

    fun clearInstallationLog() {
        installationLog = ""
    }

    fun restartModuleService(context: Context) {
        launchWithLoading {
            if (fridaManager.hasRootAccess(forceCheck = true)) {
                fridaManager.updateStealthConfig()
                
                val success = getModuleManager(context).restartService()
                if (success) {
                    delay(2000)
                    refreshModuleStatus(context)
                }
            }
        }
    }
}
