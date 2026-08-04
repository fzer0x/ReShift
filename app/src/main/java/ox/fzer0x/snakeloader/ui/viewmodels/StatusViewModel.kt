package ox.fzer0x.snakeloader.ui.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.ui.screens.loadInstalledApps

class StatusViewModel(
    private val fridaManager: FridaManager,
    private val scriptManager: ScriptManager,
    private val settings: SettingsManager
) : BaseViewModel() {

    var hasRoot by mutableStateOf(false)
        private set

    var fridaRunning by mutableStateOf(false)
        private set

    var fridaVersion by mutableStateOf("...")
        private set

    var busyboxVersion by mutableStateOf("...")
        private set

    var recentApps by mutableStateOf<List<AppInfo>>(emptyList())
        private set

    var selectedFridaBinary by mutableStateOf("auto")
        private set

    var selectedFridaSource by mutableStateOf("zygisk")
        private set

    var runningFridaBinary by mutableStateOf("none")
        private set

    var isStealthModeEnabled by mutableStateOf(false)
        private set

    var isSelinuxPermissive by mutableStateOf(false)
        private set

    var isPtraceScopeDisabled by mutableStateOf(false)
        private set

    var moduleVersion by mutableStateOf("...")
        private set

    private var lastRefreshTime = 0L

    fun refreshStatus(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRefreshTime < 1000) return
        lastRefreshTime = currentTime

        launchWithLoading {
            val statusMap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val hasRootAccess = fridaManager.hasRootAccess(forceCheck = true)
                if (hasRootAccess) {
                    fridaManager.getFridaStatus()
                } else {
                    null
                }
            }

            hasRoot = statusMap != null
            statusMap?.let { status ->
                fridaRunning = status["server_running"] as? Boolean ?: false
                fridaVersion = fridaManager.getFridaVersion(forceCheck = false)
                busyboxVersion = fridaManager.getBusyBoxVersion()
                selectedFridaBinary = status["selected_mode"] as? String ?: "auto"
                selectedFridaSource = "Module"
                runningFridaBinary = status["running_binary"] as? String ?: "none"
                isStealthModeEnabled = status["stealth_enabled"] as? Boolean ?: false
                
                isSelinuxPermissive = status["selinux_permissive"] as? Boolean ?: false
                isPtraceScopeDisabled = status["ptrace_scope_disabled"] as? Boolean ?: false
                
                moduleVersion = fridaManager.getModuleVersion()
            }

            val appsWithActiveScripts = scriptManager.getAppsWithActiveScripts()
            val recentPackageNames = scriptManager.getRecentApps().filter { it in appsWithActiveScripts }
            
            val combinedPackageNames = (recentPackageNames + appsWithActiveScripts.toList()).distinct()

            val allApps = loadInstalledApps(context)
            recentApps = combinedPackageNames.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
        }
    }

    fun startFridaServer(force: Boolean = false) {
        launchWithLoading {
            if (hasRoot) {
                fridaRunning = fridaManager.startFridaServer(force)
                fridaVersion = fridaManager.getFridaVersion(forceCheck = true)
            }
        }
    }
}
