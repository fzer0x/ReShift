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
            val fileName = when (type) {
                "server" -> "frida-server-$version-android-$arch.xz"
                "inject" -> "frida-inject-$version-android-$arch.xz"
                "cli" -> "frida-inject-$version-android-$arch.xz"
                "gadget" -> "frida-gadget-$version-android-$arch.so.xz"
                else -> "frida-server-$version-android-$arch.xz"
            }
            val downloadUrl = "https://github.com/frida/frida/releases/download/$version/$fileName"

            onProgress("Starting download: $fileName")

            val tempDir = "/data/local/tmp"
            val tempPath = "$tempDir/$fileName"
            val decompressedPath = tempPath.removeSuffix(".xz")

            // Create target directory structure
            ShellExecutor.execute("mkdir -p /data/adb/reshift ${stealthConfig.getBasePath()}", useRoot = true)
            ShellExecutor.execute("chmod 755 /data/adb/reshift ${stealthConfig.getBasePath()}", useRoot = true)

            val downloadCmd = "curl -L -o $tempPath $downloadUrl"
            val downloadResult = ShellExecutor.execute(downloadCmd, useRoot = true)

            if (!downloadResult.isSuccess || !File(tempPath).exists() && ShellExecutor.executeSimple("[ -f $tempPath ] && echo yes", useRoot = true).trim() != "yes") {
                onProgress("Download failed: ${downloadResult.stderr.ifEmpty { "Network error" }}")
                return@withContext false
            }

            onProgress("Decompressing $fileName...")
            val xzResult = ShellExecutor.execute("busybox xz -d -f $tempPath", useRoot = true)
            if (!xzResult.isSuccess) {
                val fallbackXz = ShellExecutor.execute("xz -d -f $tempPath", useRoot = true)
                if (!fallbackXz.isSuccess) {
                    onProgress("Decompression failed. Ensure busybox with xz is installed.")
                    return@withContext false
                }
            }

            val isGadget = (type == "gadget")
            val chmodRights = if (isGadget) "0644" else "0755"

            when (type) {
                "server" -> {
                    val activePath = stealthConfig.getActiveServerPath()
                    onProgress("Installing server to $activePath...")
                    ShellExecutor.execute("cp -f $decompressedPath $activePath && chmod $chmodRights $activePath", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/adb/reshift/frida-server && chmod $chmodRights /data/adb/reshift/frida-server", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/local/tmp/frida-server && chmod $chmodRights /data/local/tmp/frida-server", useRoot = true)
                }
                "inject" -> {
                    val activePath = stealthConfig.getActiveInjectPath()
                    onProgress("Installing inject to $activePath...")
                    ShellExecutor.execute("cp -f $decompressedPath $activePath && chmod $chmodRights $activePath", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/adb/reshift/frida-inject && chmod $chmodRights /data/adb/reshift/frida-inject", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/local/tmp/frida-inject && chmod $chmodRights /data/local/tmp/frida-inject", useRoot = true)
                }
                "cli" -> {
                    val activePath = stealthConfig.getActiveCliPath()
                    onProgress("Installing CLI (frida) to $activePath...")
                    ShellExecutor.execute("cp -f $decompressedPath $activePath && chmod $chmodRights $activePath", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/adb/reshift/frida && chmod $chmodRights /data/adb/reshift/frida", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/local/tmp/frida && chmod $chmodRights /data/local/tmp/frida", useRoot = true)
                }
                "gadget" -> {
                    val gadgetPath = "/data/adb/reshift/frida-gadget.so"
                    onProgress("Installing gadget to $gadgetPath...")
                    ShellExecutor.execute("cp -f $decompressedPath $gadgetPath && chmod $chmodRights $gadgetPath", useRoot = true)
                    ShellExecutor.execute("cp -f $decompressedPath /data/local/tmp/frida-gadget.so && chmod $chmodRights /data/local/tmp/frida-gadget.so", useRoot = true)
                }
            }

            // Cleanup temp decompressed binary
            ShellExecutor.execute("rm -f $tempPath $decompressedPath", useRoot = true)
            ShellExecutor.execute("chcon -R u:object_r:system_file:s0 /data/adb/reshift 2>/dev/null || true", useRoot = true)

            onProgress("Installation of $type successful!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download and install failed for $type", e)
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
