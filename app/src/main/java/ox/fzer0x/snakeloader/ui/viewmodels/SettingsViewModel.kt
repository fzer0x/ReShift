package ox.fzer0x.snakeloader.ui.viewmodels

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ModuleManager
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.UpdateManager
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File

class SettingsViewModel(
    val fridaManager: FridaManager,
    private val settings: SettingsManager,
    val updateManager: UpdateManager
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
        ShellExecutor.execute("reboot", useRoot = true)
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

    var geminiApiKey by mutableStateOf(settings.geminiApiKey)
        private set

    var geminiModel by mutableStateOf(settings.geminiModel)
        private set

    var aiAutoCorrectionEnabled by mutableStateOf(settings.aiAutoCorrectionEnabled)
        private set

    var aiProvider by mutableStateOf(settings.aiProvider)
        private set

    var ollamaBaseUrl by mutableStateOf(settings.ollamaBaseUrl)
        private set

    var ollamaModel by mutableStateOf(settings.ollamaModel)
        private set

    var onDeviceModelPath by mutableStateOf(settings.onDeviceModelPath)
        private set

    fun updateGeminiApiKey(key: String) {
        geminiApiKey = key
        settings.geminiApiKey = key
    }

    fun updateGeminiModel(model: String) {
        geminiModel = model
        settings.geminiModel = model
    }

    fun updateAiAutoCorrectionEnabled(enabled: Boolean) {
        aiAutoCorrectionEnabled = enabled
        settings.aiAutoCorrectionEnabled = enabled
    }

    fun updateAiProvider(provider: String) {
        aiProvider = provider
        settings.aiProvider = provider
    }

    fun updateOllamaBaseUrl(url: String) {
        ollamaBaseUrl = url
        settings.ollamaBaseUrl = url
    }

    fun updateOllamaModel(model: String) {
        ollamaModel = model
        settings.ollamaModel = model
    }

    fun updateOnDeviceModelPath(path: String) {
        onDeviceModelPath = path
        settings.onDeviceModelPath = path
    }

    fun getDownloadedGgufModels(context: Context): List<File> {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }?.toList() ?: emptyList()
    }

    fun backupGgufModelsToDownloads(context: Context, onComplete: (String) -> Unit) {
        launchWithLoading {
            withContext(Dispatchers.IO) {
                try {
                    val internalModelsDir = File(context.filesDir, "models")
                    val existingFiles = internalModelsDir.listFiles { _, n -> n.endsWith(".gguf", ignoreCase = true) }
                    if (!internalModelsDir.exists() || existingFiles.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            onComplete("No local GGUF models found in app storage to backup!")
                        }
                        return@withContext
                    }

                    val downloadsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "ReShift_LLM_Models"
                    )
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    ShellExecutor.executeSimple("mkdir -p '${downloadsDir.absolutePath}'", useRoot = true)

                    var copiedCount = 0
                    var totalBytes = 0L

                    for (srcFile in existingFiles) {
                        val destFile = File(downloadsDir, srcFile.name)
                        var copied = false
                        try {
                            if (!destFile.exists() || destFile.length() != srcFile.length()) {
                                srcFile.copyTo(destFile, overwrite = true)
                            }
                            if (destFile.exists() && destFile.length() > 0) {
                                copied = true
                            }
                        } catch (e: Exception) {
                            Log.w("SettingsViewModel", "Java backup copy failed for ${srcFile.name}, trying root...", e)
                        }

                        if (!copied) {
                            val srcPath = srcFile.absolutePath
                            val destPath = destFile.absolutePath
                            ShellExecutor.executeSimple("cp '$srcPath' '$destPath' && chmod 666 '$destPath'", useRoot = true)
                            if (destFile.exists()) {
                                copied = true
                            }
                        }

                        if (copied) {
                            copiedCount++
                            totalBytes += srcFile.length()
                        }
                    }

                    val totalMb = totalBytes / (1024 * 1024)
                    val msg = "Successfully backed up $copiedCount model(s) ($totalMb MB) to /Download/ReShift_LLM_Models!"
                    withContext(Dispatchers.Main) {
                        onComplete(msg)
                    }
                } catch (e: Exception) {
                    Log.e("SettingsViewModel", "Backup GGUF failed", e)
                    withContext(Dispatchers.Main) {
                        onComplete("Backup error: ${e.message}")
                    }
                }
            }
        }
    }

    fun restoreGgufModelsFromDownloads(context: Context, onComplete: (String) -> Unit) {
        launchWithLoading {
            withContext(Dispatchers.IO) {
                try {
                    val candidateFiles = mutableSetOf<File>()

                    // 1. Base Download directories to scan using Environment APIs
                    val externalDir = Environment.getExternalStorageDirectory()
                    val downloadsPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                    val baseDownloadDirs = listOfNotNull(
                        downloadsPublic,
                        File(externalDir, "Download"),
                        File(externalDir, "Downloads"),
                        File("/storage/emulated/0/Download"),
                        File("/storage/emulated/0/Downloads")
                    ).distinct()

                    for (baseDir in baseDownloadDirs) {
                        if (baseDir.exists() && baseDir.isDirectory) {
                            baseDir.walkTopDown().maxDepth(3).forEach { file ->
                                if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true)) {
                                    candidateFiles.add(file)
                                }
                            }
                        }
                    }

                    // 2. Root Fallback Search (using find command via su if Java walk missed something or permissions prevented listing)
                    try {
                        val searchPaths = baseDownloadDirs.map { "'${it.absolutePath}'" }.joinToString(" ")
                        val rootFindOutput = ShellExecutor.executeSimple(
                            "find $searchPaths -iname '*.gguf' 2>/dev/null",
                            useRoot = true
                        )
                        rootFindOutput.lines().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotBlank() && trimmed.endsWith(".gguf", ignoreCase = true)) {
                                val rootFile = File(trimmed)
                                if (rootFile.exists() || ShellExecutor.executeSimple("[ -f '$trimmed' ] && echo yes", useRoot = true).trim() == "yes") {
                                    candidateFiles.add(rootFile)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SettingsViewModel", "Root find fallback error", e)
                    }

                    val uniqueFiles = candidateFiles.distinctBy { it.name }

                    if (uniqueFiles.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            onComplete("No .gguf model files found in /Download or its subfolders!")
                        }
                        return@withContext
                    }

                    val internalModelsDir = File(context.filesDir, "models")
                    if (!internalModelsDir.exists()) {
                        internalModelsDir.mkdirs()
                    }

                    var restoredCount = 0
                    var totalBytes = 0L
                    var firstRestoredPath: String? = null

                    for (srcFile in uniqueFiles) {
                        val destFile = File(internalModelsDir, srcFile.name)
                        var copiedSuccessfully = false

                        // Try standard Java file copy first
                        try {
                            if (!destFile.exists() || destFile.length() != srcFile.length()) {
                                srcFile.copyTo(destFile, overwrite = true)
                            }
                            if (destFile.exists() && destFile.length() > 0) {
                                copiedSuccessfully = true
                            }
                        } catch (e: Exception) {
                            Log.w("SettingsViewModel", "Java copy failed for ${srcFile.name}, trying root copy...", e)
                        }

                        // If Java copy failed or file is incomplete, use Root Shell copy
                        if (!copiedSuccessfully) {
                            try {
                                val srcPath = srcFile.absolutePath
                                val destPath = destFile.absolutePath
                                ShellExecutor.executeSimple(
                                    "cp '$srcPath' '$destPath' && chmod 666 '$destPath'",
                                    useRoot = true
                                )
                                if (destFile.exists() && destFile.length() > 0) {
                                    copiedSuccessfully = true
                                }
                            } catch (e: Exception) {
                                Log.e("SettingsViewModel", "Root copy failed for ${srcFile.name}", e)
                            }
                        }

                        if (copiedSuccessfully) {
                            restoredCount++
                            totalBytes += destFile.length()
                            if (firstRestoredPath == null) {
                                firstRestoredPath = destFile.absolutePath
                            }
                        }
                    }

                    if (restoredCount > 0 && firstRestoredPath != null) {
                        updateOnDeviceModelPath(firstRestoredPath)
                    }

                    val totalMb = totalBytes / (1024 * 1024)
                    val msg = if (restoredCount > 0) {
                        "Successfully restored $restoredCount GGUF model(s) ($totalMb MB) from /Download!"
                    } else {
                        "Error copying .gguf files from /Download to app storage."
                    }

                    withContext(Dispatchers.Main) {
                        onComplete(msg)
                    }
                } catch (e: Exception) {
                    Log.e("SettingsViewModel", "Restore GGUF failed", e)
                    withContext(Dispatchers.Main) {
                        onComplete("Restore error: ${e.message}")
                    }
                }
            }
        }
    }
}
