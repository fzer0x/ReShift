package ox.fzer0x.snakeloader.ui.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
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

    var moduleLabel by mutableStateOf("Root Module")
        private set

    var moduleVersion by mutableStateOf("...")
        private set

    var isModuleInstalled by mutableStateOf(false)
        private set

    var isModuleLatest by mutableStateOf(false)
        private set

    var hasModuleUpdate by mutableStateOf(false)
        private set

    private var lastRefreshTime = 0L

    private data class StatusRefreshData(
        val hasRoot: Boolean,
        val fridaRunning: Boolean,
        val fridaVersion: String,
        val busyboxVersion: String,
        val selectedFridaBinary: String,
        val runningFridaBinary: String,
        val isStealthModeEnabled: Boolean,
        val isSelinuxPermissive: Boolean,
        val isPtraceScopeDisabled: Boolean,
        val moduleLabel: String,
        val isModuleInstalled: Boolean,
        val moduleVersion: String,
        val hasModuleUpdate: Boolean,
        val isModuleLatest: Boolean,
        val recentApps: List<AppInfo>
    )

    suspend fun refreshStatus(context: Context, force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (!force && currentTime - lastRefreshTime < 1000) return
        lastRefreshTime = currentTime

        isLoading = true
        try {
            val statusInfo = withContext(Dispatchers.IO) {
                val hasRootAccess = fridaManager.hasRootAccess(forceCheck = true)
                if (!hasRootAccess) {
                    return@withContext null
                }

                val status = fridaManager.getFridaStatus()
                val fVersion = fridaManager.getFridaVersion(forceCheck = true)
                val bVersion = fridaManager.getBusyBoxVersion()

                val mm = fridaManager.getModuleManager()
                val rootType = mm.getRootType()
                val isInstalled = mm.isModuleInstalled()

                val installedVer = mm.getModuleVersion()
                val installedVCode = mm.getModuleVersionCode()
                val assetVCode = mm.getAssetModuleVersionCode()

                val hasUpdate = (isInstalled && installedVCode > 0 && assetVCode > 0 && installedVCode < assetVCode)
                val isLatest = (isInstalled && !hasUpdate)

                val appsWithActiveScripts = scriptManager.getAppsWithActiveScripts()
                val recentPackageNames = scriptManager.getRecentApps().filter { it in appsWithActiveScripts }
                val combinedPackageNames = (recentPackageNames + appsWithActiveScripts.toList()).distinct()

                val allApps = loadInstalledApps(context)
                val recentAppList = combinedPackageNames.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }

                StatusRefreshData(
                    hasRoot = true,
                    fridaRunning = status["server_running"] as? Boolean ?: false,
                    fridaVersion = fVersion,
                    busyboxVersion = bVersion,
                    selectedFridaBinary = status["selected_mode"] as? String ?: "auto",
                    runningFridaBinary = status["running_binary"] as? String ?: "none",
                    isStealthModeEnabled = status["stealth_enabled"] as? Boolean ?: false,
                    isSelinuxPermissive = status["selinux_permissive"] as? Boolean ?: false,
                    isPtraceScopeDisabled = status["ptrace_scope_disabled"] as? Boolean ?: false,
                    moduleLabel = rootType.displayName,
                    isModuleInstalled = isInstalled,
                    moduleVersion = if (isInstalled) installedVer else "Not Installed",
                    hasModuleUpdate = hasUpdate,
                    isModuleLatest = isLatest,
                    recentApps = recentAppList
                )
            }

            if (statusInfo != null) {
                hasRoot = statusInfo.hasRoot
                fridaRunning = statusInfo.fridaRunning
                fridaVersion = statusInfo.fridaVersion
                busyboxVersion = statusInfo.busyboxVersion
                selectedFridaBinary = statusInfo.selectedFridaBinary
                selectedFridaSource = "Module"
                runningFridaBinary = statusInfo.runningFridaBinary
                isStealthModeEnabled = statusInfo.isStealthModeEnabled
                isSelinuxPermissive = statusInfo.isSelinuxPermissive
                isPtraceScopeDisabled = statusInfo.isPtraceScopeDisabled
                moduleLabel = statusInfo.moduleLabel
                isModuleInstalled = statusInfo.isModuleInstalled
                moduleVersion = statusInfo.moduleVersion
                hasModuleUpdate = statusInfo.hasModuleUpdate
                isModuleLatest = statusInfo.isModuleLatest
                recentApps = statusInfo.recentApps
            } else {
                hasRoot = false
                fridaRunning = false
                recentApps = emptyList()
            }
        } finally {
            isLoading = false
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
