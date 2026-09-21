package ox.fzer0x.snakeloader.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL

data class OllamaMessage(
    val role: String,
    val content: String
)

data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val options: Map<String, Any>? = mapOf("temperature" to 0.2)
)

data class OllamaChatChoice(
    val message: OllamaMessage?,
    val delta: OllamaMessage?
)

data class OllamaChatResponse(
    val choices: List<OllamaChatChoice>? = null,
    val message: OllamaMessage? = null,
    val response: String? = null,
    val error: String? = null
)

class OllamaApiService {

    companion object {
        private const val TAG = "OllamaApiService"
    }

    private val gson = Gson()

    suspend fun generateContent(
        baseUrl: String,
        model: String,
        systemInstructionText: String?,
        prompt: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        if (cleanBaseUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Ollama Base URL is empty. Please set it in Settings."))
        }

        val targetUrl = if (cleanBaseUrl.endsWith("/v1")) {
            "$cleanBaseUrl/chat/completions"
        } else {
            "$cleanBaseUrl/v1/chat/completions"
        }

        val messages = mutableListOf<OllamaMessage>()
        if (!systemInstructionText.isNullOrBlank()) {
            messages.add(OllamaMessage(role = "system", content = systemInstructionText))
        }
        messages.add(OllamaMessage(role = "user", content = prompt))

        val effectiveModel = model.trim().ifBlank { "richardyoung/qwen2.5-7b-instruct-abliterated:latest" }
        val isStreaming = onProgress != null

        val requestPayload = OllamaChatRequest(
            model = effectiveModel,
            messages = messages,
            stream = isStreaming
        )

        var connection: HttpURLConnection? = null
        try {
            val jsonBody = gson.toJson(requestPayload)
            val url = URL(targetUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", if (isStreaming) "text/event-stream" else "application/json")
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            if (responseCode !in 200..299) {
                val responseBodyStr = inputStream.bufferedReader().use { it.readText() }
                Log.e(TAG, "Ollama API error (HTTP $responseCode): $responseBodyStr")
                return@withContext Result.failure(Exception("HTTP $responseCode Error: $responseBodyStr"))
            }

            if (isStreaming) {
                val accumulatedText = StringBuilder()
                var tokenCount = 0
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l.isBlank() || l == "data: [DONE]") continue
                    val cleanJson = if (l.startsWith("data:")) l.substring(5).trim() else l
                    try {
                        val responseObj = gson.fromJson(cleanJson, OllamaChatResponse::class.java)
                        val deltaContent = responseObj.choices?.firstOrNull()?.delta?.content
                            ?: responseObj.choices?.firstOrNull()?.message?.content
                            ?: responseObj.message?.content
                            ?: responseObj.response

                        if (!deltaContent.isNullOrEmpty()) {
                            accumulatedText.append(deltaContent)
                            tokenCount++
                            onProgress?.invoke(tokenCount, accumulatedText.toString())
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Parsing Ollama streaming chunk error: ${e.message}")
                    }
                }

                val finalOutput = accumulatedText.toString().trim()
                if (finalOutput.isNotEmpty()) {
                    Result.success(finalOutput)
                } else {
                    Result.failure(Exception("Ollama returned an empty streaming response."))
                }
            } else {
                val responseBodyStr = inputStream.bufferedReader().use { it.readText() }
                val responseObj = gson.fromJson(responseBodyStr, OllamaChatResponse::class.java)
                val outputText = responseObj.choices?.firstOrNull()?.message?.content
                    ?: responseObj.message?.content
                    ?: responseObj.response

                if (!outputText.isNullOrBlank()) {
                    Result.success(outputText)
                } else {
                    Result.failure(Exception("Ollama returned an empty response."))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to communicate with Ollama API at $targetUrl", e)
            val friendlyMsg = if (e is ConnectException || e.message?.contains("Failed to connect") == true) {
                "Kein lokaler Ollama-Server unter '$targetUrl' erreichbar! " +
                "Falls die KI auf dem Handy laufen soll, starte 'ollama serve' in Termux. " +
                "Falls Ollama auf deinem PC läuft, trage die PC-WLAN-IP (z.B. http://192.168.1.X:11434) in den Einstellungen ein."
            } else {
                "Ollama Fehler: ${e.message}"
            }
            Result.failure(Exception(friendlyMsg))
        } finally {
            connection?.disconnect()
        }
    }
}
