package ox.fzer0x.snakeloader

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import ox.fzer0x.snakeloader.utils.JsonMessageProcessor

object LogcatReader {
    private var job: Job? = null
    private const val TAG = "LogcatReader"

    fun start(dynamicUiManager: DynamicUiManager? = null, stalkerManager: StalkerManager? = null) {
        if (job != null && job?.isActive == true) return

        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                LogManager.addLogcatEntry("Logcat monitoring starting...", "System", isScriptRelated = true)
                var process: Process? = null
                try {
                    val command = "logcat -v threadtime"
                    process = try {
                        Log.d(TAG, "Starting logcat via su")
                        Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                    } catch (e: Exception) {
                        Log.d(TAG, "Starting logcat via normal exec")
                        Runtime.getRuntime().exec(command)
                    }

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    LogManager.addLogcatEntry("Logcat stream connected.", "System", isScriptRelated = true)

                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        
                        if (dynamicUiManager != null && stalkerManager != null) {
                            JsonMessageProcessor.processLine(line, dynamicUiManager, stalkerManager)
                        }

                        val parsed = LogcatParser.parse(line)
                        LogManager.addLogcatEntry(
                            message = parsed.message,
                            packageName = parsed.scriptName ?: parsed.tag,
                            isScriptRelated = parsed.isScriptRelated,
                            priority = parsed.priority
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading logcat", e)
                    LogManager.addLogcatEntry("Logcat error: ${e.message}", "Error", isScriptRelated = true, priority = "E")
                } finally {
                    process?.destroy()
                }
                
                if (isActive) {
                    Log.w(TAG, "Logcat process died, restarting in 2 seconds...")
                    delay(2000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
