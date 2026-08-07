package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API_URL = "https://api.github.com/repos/fzer0x/ReShift/releases/latest"
    }

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpdateAvailable(val versionCode: Int, val versionName: String, val downloadUrl: String) : UpdateState()
        object UpToDate : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        object Installing : UpdateState()
        object Success : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "ReShift/1.0")
            connection.connectTimeout = 5000
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val tagName = json.getString("tag_name") // Format: "100-1.0.0"
                
                val parts = tagName.split("-")
                if (parts.size >= 2) {
                    val latestVersionCode = parts[0].toIntOrNull() ?: 0
                    val latestVersionName = parts[1]
                    
                    val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                    
                    val currentVersionCode = packageInfo.longVersionCode.toInt()
                    
                    if (latestVersionCode > currentVersionCode) {
                        val assets = json.getJSONArray("assets")
                        var downloadUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        
                        if (downloadUrl.isNotEmpty()) {
                            _updateState.value = UpdateState.UpdateAvailable(latestVersionCode, latestVersionName, downloadUrl)
                        }
                    } else {
                        _updateState.value = UpdateState.UpToDate
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed silently", e)
            _updateState.value = UpdateState.Idle
        }
    }

    suspend fun downloadAndInstall(downloadUrl: String) = withContext(Dispatchers.IO) {
        val tempApk = File(context.cacheDir, "reshift_update.apk")
        try {
            _updateState.value = UpdateState.Downloading(0f)
            
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            val fileSize = connection.contentLength
            
            connection.inputStream.use { input ->
                FileOutputStream(tempApk).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (fileSize > 0) {
                            _updateState.value = UpdateState.Downloading(totalRead.toFloat() / fileSize.toFloat())
                        }
                    }
                }
            }
            
            _updateState.value = UpdateState.Installing
            
            val suInstallCmd = "pm install -r ${tempApk.absolutePath}"
            val result = ShellExecutor.execute(suInstallCmd, useRoot = true)
            
            if (result.isSuccess) {
                _updateState.value = UpdateState.Success
            } else {
                Log.e(TAG, "Installation failed: ${result.stderr}")
                _updateState.value = UpdateState.Idle
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            _updateState.value = UpdateState.Idle
        } finally {
            if (tempApk.exists()) tempApk.delete()
        }
    }
}
