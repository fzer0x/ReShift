package ox.fzer0x.snakeloader.spoofer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Data container representing the persisted configuration of the ReShift Hardware Spoofer.
 */
data class SpooferConfigData(
    @SerializedName("profiles") val profiles: List<DeviceProfile> = emptyList(),
    @SerializedName("packageMappings") val packageMappings: Map<String, String> = emptyMap(), // packageName -> profileId
    @SerializedName("globalProfileId") val globalProfileId: String? = null,
    @SerializedName("isEngineEnabled") val isEngineEnabled: Boolean = false
)

/**
 * Central Manager handling persistence, application mapping, and profile lookup for the ReShift Spoofer Engine.
 */
class DeviceSpooferManager(
    private val context: Context
) {
    private val gson = Gson()
    private val configFile = File(context.filesDir, "spoofer_config.json")
    private val rootConfigPath = "/data/adb/reshift/spoofer_config.json"

    private var configData = SpooferConfigData()
    private val cachedProfiles = ConcurrentHashMap<String, DeviceProfile>()

    init {
        loadConfig()
    }

    @Synchronized
    fun loadConfig(): SpooferConfigData {
        try {
            if (configFile.exists()) {
                val json = configFile.readText()
                val loaded = gson.fromJson(json, SpooferConfigData::class.java)
                if (loaded != null && loaded.profiles.isNotEmpty() && loaded.profiles.all { DeviceProfile::class.java.isInstance(it) }) {
                    val sanitizedProfiles = loaded.profiles.map { profile ->
                        val cleanName = profile.name.replace(Regex("""(\s*\(Randomized\))+""", RegexOption.IGNORE_CASE), "").trim()
                        val baseName = cleanName.ifEmpty { profile.model }
                        val newName = if (profile.name.contains("(Randomized)", ignoreCase = true)) "$baseName (Randomized)" else baseName
                        if (newName != profile.name) profile.copy(name = newName) else profile
                    }
                    configData = loaded.copy(profiles = sanitizedProfiles)
                } else {
                    val defaultProfiles = DeviceSpooferPresets.ALL_PRESETS
                    val defaultGlobalId = DeviceSpooferPresets.PIXEL_8_PRO.profileId
                    configData = SpooferConfigData(profiles = defaultProfiles, globalProfileId = defaultGlobalId, isEngineEnabled = false)
                    saveConfigInternal()
                }
            } else {
                // Initialize default presets
                val defaultProfiles = DeviceSpooferPresets.ALL_PRESETS
                val defaultGlobalId = DeviceSpooferPresets.PIXEL_8_PRO.profileId
                configData = SpooferConfigData(profiles = defaultProfiles, globalProfileId = defaultGlobalId, isEngineEnabled = false)
                saveConfigInternal()
            }

            cachedProfiles.clear()
            configData.profiles.forEach { cachedProfiles[it.profileId] = it }
        } catch (e: Throwable) {
            e.printStackTrace()
            val defaultProfiles = DeviceSpooferPresets.ALL_PRESETS
            val defaultGlobalId = DeviceSpooferPresets.PIXEL_8_PRO.profileId
            configData = SpooferConfigData(profiles = defaultProfiles, globalProfileId = defaultGlobalId, isEngineEnabled = false)
            cachedProfiles.clear()
            try {
                configData.profiles.forEach { cachedProfiles[it.profileId] = it }
                saveConfigInternal()
            } catch (ex: Throwable) {
                ex.printStackTrace()
            }
        }

        return configData
    }

    @Synchronized
    fun saveConfig(newConfig: SpooferConfigData) {
        configData = newConfig
        saveConfigInternal()
    }

    private fun saveConfigInternal() {
        try {
            // Update cachedProfiles FIRST so lookups in current process are immediately consistent
            cachedProfiles.clear()
            configData.profiles.forEach { cachedProfiles[it.profileId] = it }

            val json = gson.toJson(configData)
            configFile.writeText(json)

            // Save to SharedPreferences for LSPosed XSharedPreferences IPC
            try {
                val prefs = context.getSharedPreferences("spoofer_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("config_json", json)
                    .remove("active_profile_json") // Remove stale legacy key
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (ShellExecutor.isRootAvailable()) {
                ShellExecutor.execute("chmod 777 /data/local/tmp", useRoot = true)
                ShellExecutor.execute(
                    "cp ${configFile.absolutePath} /data/local/tmp/reshift_spoofer.json && chmod 666 /data/local/tmp/reshift_spoofer.json",
                    useRoot = true
                )
                ShellExecutor.execute(
                    "mkdir -p /data/adb/reshift && chmod 755 /data/adb/reshift && cp ${configFile.absolutePath} $rootConfigPath && chmod 666 $rootConfigPath",
                    useRoot = true
                )
                val fallbackPath = "/data/data/ox.fzer0x.snakeloader/files/spoofer_config.json"
                ShellExecutor.execute(
                    "cp ${configFile.absolutePath} $fallbackPath && chmod 666 $fallbackPath",
                    useRoot = true
                )
                val activeState = if (configData.isEngineEnabled) "1" else "0"
                val globalId = configData.globalProfileId ?: ""
                ShellExecutor.execute("setprop persist.reshift.spoofer_active $activeState", useRoot = true)
                ShellExecutor.execute("setprop persist.reshift.global_profile_id \"$globalId\"", useRoot = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllProfiles(): List<DeviceProfile> {
        return configData.profiles
    }

    fun getProfileById(profileId: String): DeviceProfile? {
        return cachedProfiles[profileId] ?: configData.profiles.firstOrNull { it.profileId == profileId }
    }

    fun getProfileForPackage(packageName: String): DeviceProfile? {
        if (!configData.isEngineEnabled) return null

        // Check per-app mapping first
        val mappedProfileId = configData.packageMappings[packageName]
        if (!mappedProfileId.isNullOrEmpty()) {
            if (mappedProfileId == "DISABLED" || mappedProfileId == "NONE") return null
            val profile = cachedProfiles[mappedProfileId] ?: configData.profiles.firstOrNull { it.profileId == mappedProfileId }
            if (profile != null) return profile
        }

        // Check global fallback
        val globalId = configData.globalProfileId
        if (!globalId.isNullOrEmpty()) {
            val globalProfile = cachedProfiles[globalId] ?: configData.profiles.firstOrNull { it.profileId == globalId }
            if (globalProfile != null) return globalProfile
        }

        // Fallback: Default to Pixel 8 Pro preset
        return configData.profiles.firstOrNull { it.profileId == DeviceSpooferPresets.PIXEL_8_PRO.profileId }
            ?: configData.profiles.firstOrNull()
            ?: DeviceSpooferPresets.PIXEL_8_PRO
    }

    @Synchronized
    fun mapPackageToProfile(packageName: String, profileId: String?) {
        val updatedMappings = configData.packageMappings.toMutableMap()
        if (profileId != null) {
            updatedMappings[packageName] = profileId
        } else {
            updatedMappings.remove(packageName)
        }
        saveConfig(configData.copy(packageMappings = updatedMappings))

        val profile = profileId?.let { getProfileById(it) } ?: getProfileForPackage(packageName)
        if (configData.isEngineEnabled && profile != null) {
            SpooferNotificationHelper.notifyTargetAppSelected(
                context = context,
                packageName = packageName,
                appLabel = null,
                profile = profile
            )
        }
    }

    fun onTargetAppSelected(packageName: String, appLabel: String? = null) {
        if (!configData.isEngineEnabled) return

        val profile = getProfileForPackage(packageName)
            ?: (configData.globalProfileId?.let { cachedProfiles[it] })
            ?: configData.profiles.firstOrNull()
            ?: DeviceSpooferPresets.PIXEL_8_PRO

        SpooferNotificationHelper.notifyTargetAppSelected(
            context = context,
            packageName = packageName,
            appLabel = appLabel,
            profile = profile
        )
    }

    @Synchronized
    fun addOrUpdateProfile(profile: DeviceProfile) {
        val updatedList = configData.profiles.filter { it.profileId != profile.profileId }.toMutableList()
        updatedList.add(0, profile) // Put active profile at position 0
        saveConfig(configData.copy(profiles = updatedList, globalProfileId = profile.profileId))
    }

    @Synchronized
    fun deleteProfile(profileId: String) {
        val updatedList = configData.profiles.filter { it.profileId != profileId }
        val updatedMappings = configData.packageMappings.filterValues { it != profileId }
        var globalId = configData.globalProfileId
        if (globalId == profileId) globalId = updatedList.firstOrNull()?.profileId

        saveConfig(configData.copy(
            profiles = updatedList,
            packageMappings = updatedMappings,
            globalProfileId = globalId
        ))
    }

    @Synchronized
    fun setGlobalProfile(profileId: String?) {
        var updatedProfiles = configData.profiles
        if (profileId != null) {
            val selected = cachedProfiles[profileId] ?: configData.profiles.firstOrNull { it.profileId == profileId }
            if (selected != null) {
                val list = configData.profiles.filter { it.profileId != profileId }.toMutableList()
                list.add(0, selected)
                updatedProfiles = list
            }
        }
        saveConfig(configData.copy(globalProfileId = profileId, profiles = updatedProfiles))
        triggerActivationNotification(profileId?.let { getProfileById(it) })
        killTargetApps()
    }

    fun killTargetApps() {
        try {
            if (ShellExecutor.isRootAvailable()) {
                ShellExecutor.execute("am force-stop com.toralabs.deviceinfo", useRoot = true)
                ShellExecutor.execute("am force-stop com.finalwire.aida64", useRoot = true)
                ShellExecutor.execute("am force-stop com.cpuid.cpu_z", useRoot = true)
                configData.packageMappings.keys.forEach { pkg ->
                    ShellExecutor.execute("am force-stop $pkg", useRoot = true)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun setEngineEnabled(enabled: Boolean) {
        saveConfig(configData.copy(isEngineEnabled = enabled))
        triggerActivationNotification()
    }

    fun triggerActivationNotification(selectedProfile: DeviceProfile? = null) {
        val activeProfile = selectedProfile
            ?: (configData.globalProfileId?.let { cachedProfiles[it] })
            ?: configData.profiles.firstOrNull()
            ?: DeviceSpooferPresets.PIXEL_8_PRO

        SpooferNotificationHelper.notifyProfileActivated(
            context = context,
            profileName = activeProfile.name,
            isEngineEnabled = configData.isEngineEnabled,
            brand = activeProfile.brand,
            model = activeProfile.model
        )
    }

    fun isEngineEnabled(): Boolean = configData.isEngineEnabled

    fun getPackageMappings(): Map<String, String> = configData.packageMappings

    fun getGlobalProfileId(): String? = configData.globalProfileId
}
