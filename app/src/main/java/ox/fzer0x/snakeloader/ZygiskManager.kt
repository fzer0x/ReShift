package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import ox.fzer0x.snakeloader.utils.ShellExecutor
import ox.fzer0x.snakeloader.utils.InputValidator
import java.io.File

data class ZygiskAppEntry(
    val packageName: String,
    val scriptIds: List<String> = emptyList(),
    val scriptPath: String? = null,
    val scriptId: String? = null
)

data class ZygiskConfig(
    val enabled: Boolean = false,
    val apps: List<ZygiskAppEntry> = emptyList(),
    val globalScriptId: String? = null
)

class ZygiskManager(
    private val context: Context, 
    private val scriptManager: ScriptManager,
    private val settings: SettingsManager
) {
    companion object {
        private const val TAG = "ZygiskManager"
        private const val CONFIG_PATH = "/data/adb/reshift/zygisk_config.json"
        private const val SCRIPTS_ROOT = "/data/adb/reshift/scripts"
        private const val PREFS_NAME = "zygisk_settings"
        private const val KEY_CONFIG = "zygisk_config_json"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getConfig(): ZygiskConfig {
        val json = prefs.getString(KEY_CONFIG, null)
        val config = if (json != null) {
            try {
                gson.fromJson(json, ZygiskConfig::class.java).let { cfg ->
                    val migratedApps = cfg.apps.map { app ->
                        if (app.scriptId != null && app.scriptIds.isEmpty()) {
                            app.copy(scriptIds = listOf(app.scriptId), scriptId = null)
                        } else app
                    }
                    cfg.copy(apps = migratedApps)
                }
            } catch (e: Exception) {
                ZygiskConfig()
            }
        } else {
            ZygiskConfig()
        }
        
        return if (config.enabled != settings.isZygiskEnabled) {
            config.copy(enabled = settings.isZygiskEnabled)
        } else {
            config
        }
    }

    fun saveConfig(config: ZygiskConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString(KEY_CONFIG, json).apply()
        exportToRoot(json)
    }

    private fun exportToRoot(configJson: String) {
        try {
            ShellExecutor.execute("mkdir -p $SCRIPTS_ROOT && chmod 755 $SCRIPTS_ROOT", useRoot = true)

            val config = getConfig()
            Log.d(TAG, "Exporting Zygisk config (Enabled: ${config.enabled}) for ${config.apps.size} apps")
            
            val appsWithResolvedPaths = config.apps.mapNotNull { entry ->
                if (!InputValidator.validatePackageName(entry.packageName)) {
                    Log.w(TAG, "Skipping export for invalid package name: ${entry.packageName}")
                    return@mapNotNull null
                }
                
                if (entry.scriptIds.isNotEmpty()) {
                    val assignedScripts = scriptManager.getScripts().filter { it.id in entry.scriptIds }
                    
                    if (assignedScripts.isNotEmpty()) {
                        Log.d(TAG, "Syncing ${assignedScripts.size} scripts for ${entry.packageName}")
                        
                        val fullContent = StringBuilder()
                        assignedScripts.forEach { script ->
                            var content = script.content.replace("\r\n", "\n").trim()
                            if (content.startsWith("*") && !content.startsWith("/*")) {
                                content = "/*" + content
                            }
                            fullContent.append("// --- Script: ${script.name} ---\n")
                            fullContent.append(content)
                            fullContent.append("\n\n")
                        }
                        
                        val fileName = "${entry.packageName}_boot.js"
                        val remotePath = "$SCRIPTS_ROOT/$fileName"
                        
                        val tempFile = File(context.cacheDir, fileName)
                        tempFile.writeText(fullContent.toString())
                        
                        ShellExecutor.execute("cp ${tempFile.absolutePath} $remotePath && chmod 644 $remotePath", useRoot = true)
                        
                        val localScriptPath = "/data/data/${entry.packageName}/cache/re_script.js"
                        val gadgetConfig = """
                            {
                              "interaction": {
                                "type": "script",
                                "path": "$localScriptPath",
                                "on_change": "reload"
                              }
                            }
                        """.trimIndent()
                        
                        val configFileName = "${entry.packageName}_boot.config.json"
                        val remoteConfigPath = "$SCRIPTS_ROOT/$configFileName"
                        val tempConfigFile = File(context.cacheDir, configFileName)
                        tempConfigFile.writeText(gadgetConfig)
                        
                        ShellExecutor.execute("cp ${tempConfigFile.absolutePath} $remoteConfigPath && chmod 644 $remoteConfigPath", useRoot = true)
                        
                        entry.copy(scriptPath = remotePath)
                    } else {
                        Log.w(TAG, "No scripts found for ${entry.packageName}")
                        entry
                    }
                } else entry
            }

            config.globalScriptId?.let { gid ->
                val script = scriptManager.getScripts().find { it.id == gid }
                script?.let {
                    val remotePath = "$SCRIPTS_ROOT/global_boot.js"
                    val tempFile = File(context.cacheDir, "global_boot.js")
                    tempFile.writeText(it.content)
                    ShellExecutor.execute("cat ${tempFile.absolutePath} > $remotePath && chmod 644 $remotePath", useRoot = true)
                    tempFile.delete()
                }
            }

            val finalConfig = config.copy(apps = appsWithResolvedPaths, enabled = settings.isZygiskEnabled)
            val finalJson = gson.toJson(finalConfig)
            
            val configTempFile = File(context.cacheDir, "zygisk_config.json")
            configTempFile.writeText(finalJson)
            
            ShellExecutor.execute("cat ${configTempFile.absolutePath} > $CONFIG_PATH && chmod 644 $CONFIG_PATH", useRoot = true)
            Log.d(TAG, "Zygisk config and scripts exported successfully (Global Enabled: ${finalConfig.enabled})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export Zygisk config", e)
        }
    }

    fun toggleApp(packageName: String, enabled: Boolean) {
        val config = getConfig()
        val newList = if (enabled) {
            if (config.apps.any { it.packageName == packageName }) config.apps
            else config.apps + ZygiskAppEntry(packageName)
        } else {
            config.apps.filter { it.packageName != packageName }
        }
        saveConfig(config.copy(apps = newList))
    }

    fun toggleAppScript(packageName: String, scriptId: String) {
        val config = getConfig()
        val newList = config.apps.map { 
            if (it.packageName == packageName) {
                val newScripts = if (scriptId in it.scriptIds) {
                    it.scriptIds.filter { id -> id != scriptId }
                } else {
                    it.scriptIds + scriptId
                }
                it.copy(scriptIds = newScripts)
            } else it
        }
        saveConfig(config.copy(apps = newList))
    }

    fun isAppEnabled(packageName: String): Boolean {
        return getConfig().apps.any { it.packageName == packageName }
    }

    fun setZygiskEnabled(enabled: Boolean) {
        val config = getConfig()
        saveConfig(config.copy(enabled = enabled))
    }
}
