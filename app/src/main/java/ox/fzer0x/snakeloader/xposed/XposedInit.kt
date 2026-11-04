package ox.fzer0x.snakeloader.xposed

import android.app.Application
import android.net.Uri
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.libxposed.api.XposedModule
import ox.fzer0x.snakeloader.spoofer.DeviceProfile
import ox.fzer0x.snakeloader.spoofer.DeviceSpooferPresets
import ox.fzer0x.snakeloader.spoofer.SpooferActivationReceiver
import ox.fzer0x.snakeloader.spoofer.SpooferConfigData
import java.io.File

/**
 * Xposed / LSPosed Framework Module entry point for ReShift.
 * Standard implementation matching libxposed API 102 module specification.
 */
class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        val processName = lpparam.processName ?: ""

        // CRITICAL: Skip system framework, system server, zygote, system ui, telephony, webview, and self process!
        // Hooking system processes causes Zygote/Zygisk boot crashes due to ART offset & framework dependency mismatches.
        if (packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "ox.fzer0x.snakeloader" ||
            packageName == "com.android.phone" ||
            packageName == "com.android.providers.settings" ||
            packageName == "com.google.android.webview" ||
            packageName == "com.android.webview" ||
            processName == "system_server" ||
            processName.contains("zygote") ||
            processName.contains("system")
        ) {
            return
        }

        try {
            val profile = loadProfileForPackage(packageName) ?: return
            XposedBridge.log("[ReShift Vector Spoofer] [API 102] Applying Hardware Spoofer profile '${profile.name}' to $packageName")

            FingerprintSpooferHook.applyHooks(lpparam, profile)
            sendActivationBroadcast(packageName, profile)
        } catch (e: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] [API 102] Error applying hooks to $packageName: " + e.message)
        }
    }

    companion object {
        private val gson = Gson()
        private const val ROOT_CONFIG_PATH = "/data/adb/reshift/spoofer_config.json"
        private const val FALLBACK_CONFIG_PATH = "/data/data/ox.fzer0x.snakeloader/files/spoofer_config.json"

        fun sendActivationBroadcast(packageName: String, profile: DeviceProfile) {
            try {
                val app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication"
                ) as? Application

                if (app != null) {
                    SpooferActivationReceiver.sendActivationBroadcast(app, packageName, profile)
                }
            } catch (_: Throwable) { }
        }

        fun getProfileFromConfig(config: SpooferConfigData?, packageName: String): DeviceProfile? {
            if (config == null || !config.isEngineEnabled) return null

            // 1. Per-app package mapping check
            val mappedId = config.packageMappings[packageName]
            if (!mappedId.isNullOrEmpty()) {
                if (mappedId == "DISABLED" || mappedId == "NONE") return null
                val mappedProfile = config.profiles.firstOrNull { it.profileId == mappedId }
                if (mappedProfile != null) return mappedProfile
            }

            // 2. Global active profile check
            val globalId = config.globalProfileId
            if (!globalId.isNullOrEmpty()) {
                val globalProfile = config.profiles.firstOrNull { it.profileId == globalId }
                if (globalProfile != null) return globalProfile
            }

            // 3. Fallback: Default to Pixel 8 Pro preset
            return config.profiles.firstOrNull { it.profileId == DeviceSpooferPresets.PIXEL_8_PRO.profileId }
                ?: config.profiles.firstOrNull()
                ?: DeviceSpooferPresets.PIXEL_8_PRO
        }

        fun loadProfileForPackage(packageName: String, module: XposedModule? = null): DeviceProfile? {
            // 1. Try libxposed API 102 RemotePreferences if running inside a libxposed module context
            if (module != null) {
                try {
                    val remotePrefs = module.getRemotePreferences("spoofer_prefs")
                    val configJson = remotePrefs.getString("config_json", null)
                    if (!configJson.isNullOrEmpty()) {
                        val config = gson.fromJson(configJson, SpooferConfigData::class.java)
                        if (config != null) {
                            if (!config.isEngineEnabled) return null
                            val profile = getProfileFromConfig(config, packageName)
                            if (profile != null) return profile
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("[ReShift Vector Spoofer] [API 102] RemotePreferences read error: ${t.message}")
                }
            }

            // 2. Try XSharedPreferences (Native LSPosed / Xposed Module IPC)
            try {
                val xPref = XSharedPreferences("ox.fzer0x.snakeloader", "spoofer_prefs")
                xPref.makeWorldReadable()
                xPref.reload()

                val configJson = xPref.getString("config_json", null)
                if (!configJson.isNullOrEmpty()) {
                    val config = gson.fromJson(configJson, SpooferConfigData::class.java)
                    if (config != null) {
                        if (!config.isEngineEnabled) return null
                        val profile = getProfileFromConfig(config, packageName)
                        if (profile != null) return profile
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[ReShift Vector Spoofer] XSharedPreferences error: ${t.message}")
            }

            // 3. Try direct file reads (for Root / SELinux permissive file IPC)
            val pathsToTry = listOf(
                ROOT_CONFIG_PATH,
                "/data/local/tmp/reshift_spoofer.json",
                "/data/local/tmp/spoofer_config.json",
                FALLBACK_CONFIG_PATH
            )

            for (path in pathsToTry) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val json = file.readText()
                        if (json.isNotEmpty()) {
                            val config = gson.fromJson(json, SpooferConfigData::class.java)
                            if (config != null) {
                                if (!config.isEngineEnabled) return null
                                val profile = getProfileFromConfig(config, packageName)
                                if (profile != null) return profile
                            }
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("[ReShift Vector Spoofer] Error reading $path: ${t.message}")
                }
            }

            // 4. Fallback: Query ContentProvider if currentApplication context is initialized
            try {
                val app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication"
                ) as? Application

                if (app != null) {
                    val cursor = app.contentResolver.query(
                        Uri.parse("content://ox.fzer0x.snakeloader.spoofer.provider/profile"),
                        null,
                        null,
                        arrayOf(packageName),
                        null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val jsonIndex = it.getColumnIndex("profile_json")
                            if (jsonIndex >= 0) {
                                val jsonStr = it.getString(jsonIndex)
                                if (!jsonStr.isNullOrEmpty()) {
                                    val profile = gson.fromJson(jsonStr, DeviceProfile::class.java)
                                    if (profile != null) return profile
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) { }

            // 5. SELinux-proof SystemProperties Fallback
            try {
                val sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", null)
                val enabled = XposedHelpers.callStaticMethod(sysPropClass, "get", "persist.reshift.spoofer_active", "0") as? String
                if (enabled == "1") {
                    val globalId = XposedHelpers.callStaticMethod(sysPropClass, "get", "persist.reshift.global_profile_id", "") as? String
                    if (!globalId.isNullOrEmpty()) {
                        val matchedPreset = DeviceSpooferPresets.ALL_PRESETS.firstOrNull { it.profileId == globalId }
                        if (matchedPreset != null) return matchedPreset
                    }
                    return DeviceSpooferPresets.PIXEL_8_PRO
                }
            } catch (_: Throwable) { }

            return null
        }
    }
}
