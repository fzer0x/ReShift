package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File
import java.io.FileOutputStream

class ModuleManager(private val context: Context, private val settings: SettingsManager) {
    companion object {
        private const val TAG = "ModuleManager"
        private const val MODULE_ID = "snakeloader_frida"
        private const val MODULE_PATH = "/data/adb/modules/$MODULE_ID"
        private const val LOG_PATH = "/data/adb/reshift/frida_boot.log"
        private const val MODULE_ASSET_NAME = "ReShift.zip"
    }

    enum class RootType {
        MAGISK, KERNEL_SU, APATCH, UNKNOWN
    }

    fun getRootType(): RootType {
        if (ShellExecutor.execute("command -v magisk", useRoot = true).isSuccess) return RootType.MAGISK
        if (ShellExecutor.execute("command -v ksu", useRoot = true).isSuccess) return RootType.KERNEL_SU
        if (ShellExecutor.execute("command -v apatch", useRoot = true).isSuccess) return RootType.APATCH
        return RootType.UNKNOWN
    }

    fun isModuleInstalled(): Boolean {
        val result = ShellExecutor.execute("[ -d $MODULE_PATH ] && echo yes", useRoot = true)
        return result.stdout.trim() == "yes"
    }

    fun getModuleVersion(): String {
        val result = ShellExecutor.execute("grep '^version=' $MODULE_PATH/module.prop | cut -d= -f2", useRoot = true)
        return result.stdout.trim().ifEmpty { "0" }
    }

    fun getModuleVersionCode(): Int {
        val result = ShellExecutor.execute("grep '^versionCode=' $MODULE_PATH/module.prop | cut -d= -f2", useRoot = true)
        return result.stdout.trim().toIntOrNull() ?: 0
    }

    fun getAssetModuleVersion(): String {
        return readPropFromAsset("version") ?: "1.0.0"
    }

    fun getAssetModuleVersionCode(): Int {
        return readPropFromAsset("versionCode")?.toIntOrNull() ?: 100
    }

    private fun readPropFromAsset(key: String): String? {
        try {
            context.assets.open(MODULE_ASSET_NAME).use { inputStream ->
                val zipInputStream = java.util.zip.ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (entry.name == "module.prop") {
                        val reader = zipInputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line?.startsWith("$key=") == true) {
                                return line.substringAfter('=').trim()
                            }
                        }
                        break
                    }
                    entry = zipInputStream.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $key from asset", e)
        }
        return null
    }

    fun isServiceRunning(): Boolean {
        val result = ShellExecutor.execute("pgrep -f 'frida-server|nm-service'", useRoot = true)
        return result.stdout.trim().isNotEmpty()
    }

    suspend fun installModule(onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            onLog("--- Starting Module Installation ---")
            onLog("Preparing assets...")
            val tempZip = File(context.cacheDir, MODULE_ASSET_NAME)
            
            context.assets.open(MODULE_ASSET_NAME).use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }

            val remoteZip = "/data/local/tmp/$MODULE_ASSET_NAME"
            onLog("Copying module to: $remoteZip")
            ShellExecutor.execute("cp ${tempZip.absolutePath} $remoteZip", useRoot = true)
            ShellExecutor.execute("chmod 644 $remoteZip", useRoot = true)

            val rootType = getRootType()
            onLog("Detected Root: $rootType")
            
            val installCmd = when (rootType) {
                RootType.MAGISK -> "magisk --install-module $remoteZip"
                RootType.KERNEL_SU -> "ksu module install $remoteZip"
                RootType.APATCH -> "apatch module install $remoteZip"
                else -> {
                    onLog("Warning: Unknown root type, falling back to magisk")
                    "magisk --install-module $remoteZip"
                }
            }

            onLog("Executing: $installCmd")
            val installResult = ShellExecutor.executeStreaming(installCmd, useRoot = true) { line ->
                onLog(line)
            }

            if (installResult.isSuccess) {
                onLog("Installation successful!")
                if (settings.isZygiskEnabled) {
                    onLog("Synchronizing Zygisk state...")
                    setZygiskEnabled(true)
                }
            } else {
                onLog("Installation failed with exit code: ${installResult.exitCode}")
            }

            onLog("Cleaning up...")
            ShellExecutor.execute("rm $remoteZip", useRoot = true)
            tempZip.delete()
            onLog("--- Installation Finished ---")

            installResult.isSuccess
        } catch (e: Exception) {
            onLog("FATAL ERROR: ${e.message}")
            Log.e(TAG, "Failed to install module", e)
            false
        }
    }

    fun getBootLogs(): String {
        val result = ShellExecutor.execute("cat $LOG_PATH", useRoot = true)
        return if (result.isSuccess) result.stdout else "No logs found at $LOG_PATH"
    }

    fun restartService(): Boolean {
        ShellExecutor.execute("pkill -9 -f 'frida-server|nm-service'", useRoot = true)
        
        val result = ShellExecutor.execute("nohup sh $MODULE_PATH/service.sh > /dev/null 2>&1 &", useRoot = true)
        return result.isSuccess
    }

    fun setZygiskEnabled(enabled: Boolean): Boolean {
        settings.isZygiskEnabled = enabled
        val value = if (enabled) "yes" else "no"
        val cmd = "if [ -f $MODULE_PATH/module.prop ]; then if grep -q '^zygisk=' $MODULE_PATH/module.prop; then sed -i 's/^zygisk=.*/zygisk=$value/' $MODULE_PATH/module.prop; else echo 'zygisk=$value' >> $MODULE_PATH/module.prop; fi; fi"
        val result = ShellExecutor.execute(cmd, useRoot = true)
        return result.isSuccess
    }

    fun isZygiskEnabled(): Boolean {
        val fileEnabled = ShellExecutor.execute("grep '^zygisk=yes' $MODULE_PATH/module.prop", useRoot = true).stdout.trim().isNotEmpty()
        
        val hasNativeLoader = ShellExecutor.execute("[ -d $MODULE_PATH/zygisk ] && echo yes", useRoot = true).stdout.trim() == "yes"
        if (settings.isZygiskEnabled && !hasNativeLoader) {
            Log.e(TAG, "Zygisk is enabled but native loader is missing in module!")
        }

        if (fileEnabled != settings.isZygiskEnabled) {
            Log.w(TAG, "Zygisk state mismatch: File=$fileEnabled, Settings=${settings.isZygiskEnabled}. Syncing...")
            setZygiskEnabled(settings.isZygiskEnabled)
        }
        return settings.isZygiskEnabled
    }

    fun deleteModule(): Boolean {
        val result = ShellExecutor.execute("rm -rf $MODULE_PATH && touch /data/adb/modules/.active_update", useRoot = true)
        return result.isSuccess
    }
}
