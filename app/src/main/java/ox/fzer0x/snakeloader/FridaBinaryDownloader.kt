package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class FridaBinaryDownloader(
    private val context: Context,
    private val stealthConfig: StealthConfigManager
) {
    companion object {
        private const val TAG = "FridaBinaryDownloader"
        private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/frida/frida/releases/latest"
    }

    suspend fun getLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_RELEASES_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "ReShift/1.0")
            connection.connectTimeout = 5000
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                return@withContext json.getString("tag_name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch latest version", e)
        }
        null
    }

    suspend fun downloadAndInstall(version: String, type: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val arch = getFridaArch()
            val binaryName = if (type == "server") "frida-server" else "frida-inject"
            val fileName = "$binaryName-$version-android-$arch.xz"
            val downloadUrl = "https://github.com/frida/frida/releases/download/$version/$fileName"
            
            onProgress("Starting download: $fileName")
            
            val tempDir = "/data/local/tmp"
            val tempPath = "$tempDir/$fileName"
            val decompressedPath = tempPath.removeSuffix(".xz")
            
            val downloadCmd = "curl -L -o $tempPath $downloadUrl"
            val downloadResult = ShellExecutor.execute(downloadCmd, useRoot = true)
            
            if (!downloadResult.isSuccess) {
                onProgress("Download failed: ${downloadResult.stderr}")
                return@withContext false
            }
            
            onProgress("Decompressing...")
            val xzResult = ShellExecutor.execute("busybox xz -d -f $tempPath", useRoot = true)
            if (!xzResult.isSuccess) {
                val fallbackXz = ShellExecutor.execute("xz -d -f $tempPath", useRoot = true)
                if (!fallbackXz.isSuccess) {
                    onProgress("Decompression failed. Ensure busybox with xz is installed.")
                    return@withContext false
                }
            }
            
            val targetPath = when (type) {
                "server" -> stealthConfig.getActiveServerPath()
                "inject", "cli" -> stealthConfig.getActiveInjectPath()
                else -> "$tempDir/$binaryName"
            }
            
            onProgress("Installing to $targetPath...")
            ShellExecutor.execute("mkdir -p ${stealthConfig.getBasePath()}", useRoot = true)
            ShellExecutor.execute("mv $decompressedPath $targetPath", useRoot = true)
            ShellExecutor.execute("chmod 755 $targetPath", useRoot = true)
            
            if (type == "cli") {
                val cliPath = stealthConfig.getActiveCliPath()
                ShellExecutor.execute("cp $targetPath $cliPath && chmod 755 $cliPath", useRoot = true)
            }
            
            onProgress("Installation successful!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download and install failed", e)
            onProgress("Error: ${e.message}")
            false
        }
    }

    private fun getFridaArch(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi-v7a") || abi.contains("armv7") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64"
        }
    }
}
