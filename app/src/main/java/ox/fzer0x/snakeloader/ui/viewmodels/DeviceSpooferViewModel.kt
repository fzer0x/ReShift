package ox.fzer0x.snakeloader.ui.viewmodels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.spoofer.DeviceProfile
import ox.fzer0x.snakeloader.spoofer.DeviceSpooferManager
import ox.fzer0x.snakeloader.spoofer.DeviceSpooferPresets

data class SpooferUiState(
    val isEngineEnabled: Boolean = false,
    val profiles: List<DeviceProfile> = emptyList(),
    val packageMappings: Map<String, String> = emptyMap(),
    val globalProfileId: String? = null,
    val selectedProfile: DeviceProfile? = null
)

class DeviceSpooferViewModel(
    private val spooferManager: DeviceSpooferManager,
    private val scriptManager: ScriptManager
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(SpooferUiState())
    val uiState: StateFlow<SpooferUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        launchWithLoading {
            val config = spooferManager.loadConfig()
            val profiles = config.profiles.ifEmpty { DeviceSpooferPresets.ALL_PRESETS }
            val activeGlobal = config.globalProfileId?.let { id -> profiles.firstOrNull { it.profileId == id } }
            val selected = activeGlobal ?: profiles.firstOrNull()

            _uiState.value = SpooferUiState(
                isEngineEnabled = config.isEngineEnabled,
                profiles = profiles,
                packageMappings = config.packageMappings,
                globalProfileId = config.globalProfileId ?: selected?.profileId,
                selectedProfile = selected
            )
        }
    }

    fun toggleEngine(enabled: Boolean) {
        spooferManager.setEngineEnabled(enabled)
        _uiState.value = _uiState.value.copy(isEngineEnabled = enabled)
    }

    fun selectProfile(profile: DeviceProfile) {
        spooferManager.setGlobalProfile(profile.profileId)
        loadData()
    }

    fun saveProfile(profile: DeviceProfile) {
        spooferManager.addOrUpdateProfile(profile)
        loadData()
    }

    fun deleteProfile(profileId: String) {
        spooferManager.deleteProfile(profileId)
        loadData()
    }

    fun mapPackageToProfile(packageName: String, profileId: String?) {
        spooferManager.mapPackageToProfile(packageName, profileId)
        loadData()
    }

    fun setGlobalProfile(profileId: String?) {
        spooferManager.setGlobalProfile(profileId)
        loadData()
    }

    fun applyPreset(preset: DeviceProfile) {
        spooferManager.addOrUpdateProfile(preset)
        spooferManager.setGlobalProfile(preset.profileId)
        loadData()
    }

    fun randomizeCurrentProfile() {
        val selected = _uiState.value.selectedProfile
        val randomized = DeviceSpooferPresets.generateRandomProfile(base = selected)
        spooferManager.addOrUpdateProfile(randomized)
        spooferManager.setGlobalProfile(randomized.profileId)
        loadData()
    }
}
