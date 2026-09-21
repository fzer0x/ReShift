package ox.fzer0x.snakeloader.service

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class OllamaPullProgress(
    val status: String? = null,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
    val error: String? = null
)

class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        const val CHANNEL_ID = "ollama_model_download_channel"
        const val NOTIFICATION_ID = 9982

        const val ACTION_PULL_MODEL = "ox.fzer0x.snakeloader.PULL_MODEL"
        const val ACTION_DOWNLOAD_GGUF = "ox.fzer0x.snakeloader.DOWNLOAD_GGUF"

        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_MODEL_NAME = "extra_model_name"
        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_FILENAME = "extra_filename"
    }

    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        createNotificationChannel()

        if (action == ACTION_DOWNLOAD_GGUF) {
            val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) 
                ?: "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
            val fileName = intent.getStringExtra(EXTRA_FILENAME) ?: "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"

            val initialNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading Local LLM GGUF")
                .setContentText("Downloading $fileName...")
                .setSmallIcon(R.drawable.stat_sys_download)
                .setOngoing(true)
                .setProgress(100, 0, true)
                .build()

            startForeground(NOTIFICATION_ID, initialNotification)

            serviceScope.launch {
                downloadDirectGguf(downloadUrl, fileName)
            }

        } else if (action == ACTION_PULL_MODEL) {
            val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: "http://127.0.0.1:11434"
            val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "richardyoung/qwen2.5-7b-instruct-abliterated:latest"

            val initialNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading Ollama Model")
                .setContentText("Initializing pull for $modelName...")
                .setSmallIcon(R.drawable.stat_sys_download)
                .setOngoing(true)
                .setProgress(100, 0, true)
                .build()

            startForeground(NOTIFICATION_ID, initialNotification)

            serviceScope.launch {
                pullModelStream(baseUrl, modelName)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Download Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private suspend fun downloadDirectGguf(downloadUrl: String, fileName: String) = withContext(Dispatchers.IO) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notifBuilder = NotificationCompat.Builder(this@ModelDownloadService, CHANNEL_ID)
            .setContentTitle("Downloading GGUF Model")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setOngoing(true)

        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val outputFile = File(modelsDir, fileName)

        var currentUrl = downloadUrl
        var connection: HttpURLConnection? = null
        var redirects = 0
        val maxRedirects = 10

        try {
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; ReShift)")

                if (connection is HttpsURLConnection) {
                    connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                }

                val responseCode = connection.responseCode
                if (responseCode in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        Log.i(TAG, "Following redirect ($responseCode) -> $location")
                        currentUrl = location
                        connection.disconnect()
                        redirects++
                        continue
                    }
                }

                if (responseCode !in 200..299) {
                    updateFinishedNotification(notificationManager, notifBuilder, "HTTP Error $responseCode", success = false)
                    return@withContext
                }

                break
            }

            val nonNullConn = connection ?: run {
                updateFinishedNotification(notificationManager, notifBuilder, "Connection failed", success = false)
                return@withContext
            }

            val fileLength = nonNullConn.contentLengthLong
            val input = nonNullConn.inputStream
            val output = FileOutputStream(outputFile)

            val buffer = ByteArray(32768)
            var totalRead = 0L
            var count: Int
            var lastUpdate = 0L

            while (input.read(buffer).also { count = it } != -1) {
                output.write(buffer, 0, count)
                totalRead += count

                val now = System.currentTimeMillis()
                if (now - lastUpdate > 800) {
                    lastUpdate = now
                    val downloadedMb = totalRead / (1024 * 1024)
                    if (fileLength > 0) {
                        val totalMb = fileLength / (1024 * 1024)
                        val percent = ((totalRead.toDouble() / fileLength.toDouble()) * 100).toInt()
                        notifBuilder.setContentText("$fileName ($downloadedMb / $totalMb MB - $percent%)")
                        notifBuilder.setProgress(100, percent, false)
                    } else {
                        notifBuilder.setContentText("$fileName ($downloadedMb MB downloaded)")
                        notifBuilder.setProgress(100, 0, true)
                    }
                    notificationManager?.notify(NOTIFICATION_ID, notifBuilder.build())
                }
            }

            output.flush()
            output.close()
            input.close()

            updateFinishedNotification(notificationManager, notifBuilder, "Saved to ${outputFile.name} (${outputFile.length() / (1024*1024)} MB)", success = true)

        } catch (e: Exception) {
            Log.e(TAG, "GGUF download failed", e)
            updateFinishedNotification(notificationManager, notifBuilder, "Download Error: ${e.message}", success = false)
        } finally {
            connection?.disconnect()
            stopForegroundCompat()
        }
    }

    private suspend fun pullModelStream(baseUrl: String, modelName: String) = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trim().removeSuffix("/").removeSuffix("/v1")
        val targetUrl = "$cleanBaseUrl/api/pull"

        val notificationManager = getSystemService(NotificationManager::class.java)
        val notifBuilder = NotificationCompat.Builder(this@ModelDownloadService, CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setOngoing(true)

        var connection: HttpURLConnection? = null
        try {
            val jsonPayload = gson.toJson(mapOf("name" to modelName, "stream" to true))
            val url = URL(targetUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = 30000
            connection.readTimeout = 0 // Stream until complete
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                updateFinishedNotification(notificationManager, notifBuilder, "Download Failed: $errText", success = false)
                return@withContext
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isBlank()) continue

                try {
                    val progressObj = gson.fromJson(currentLine, OllamaPullProgress::class.java)
                    if (progressObj.error != null) {
                        updateFinishedNotification(notificationManager, notifBuilder, "Error: ${progressObj.error}", success = false)
                        return@withContext
                    }

                    val statusMsg = progressObj.status ?: "Pulling layers..."
                    val totalBytes = progressObj.total ?: 0L
                    val completedBytes = progressObj.completed ?: 0L

                    if (totalBytes > 0) {
                        val progressPercent = ((completedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt()
                        val completedMb = completedBytes / (1024 * 1024)
                        val totalMb = totalBytes / (1024 * 1024)

                        notifBuilder.setContentText("$statusMsg ($completedMb / $totalMb MB - $progressPercent%)")
                        notifBuilder.setProgress(100, progressPercent, false)
                    } else {
                        notifBuilder.setContentText(statusMsg)
                        notifBuilder.setProgress(100, 0, true)
                    }

                    notificationManager?.notify(NOTIFICATION_ID, notifBuilder.build())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing progress line: $currentLine", e)
                }
            }

            updateFinishedNotification(notificationManager, notifBuilder, "Model $modelName downloaded successfully!", success = true)

        } catch (e: Exception) {
            Log.e(TAG, "Model download error", e)
            updateFinishedNotification(notificationManager, notifBuilder, "Download error: ${e.message}", success = false)
        } finally {
            connection?.disconnect()
            stopForegroundCompat()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateFinishedNotification(
        manager: NotificationManager?,
        builder: NotificationCompat.Builder,
        message: String,
        success: Boolean
    ) {
        val finishedNotif = builder
            .setContentTitle(if (success) "Download Complete" else "Download Failed")
            .setContentText(message)
            .setSmallIcon(if (success) R.drawable.stat_sys_download_done else R.drawable.stat_notify_error)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()

        manager?.notify(NOTIFICATION_ID, finishedNotif)
    }
}
