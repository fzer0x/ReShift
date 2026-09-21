package ox.fzer0x.snakeloader.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class GeminiPart(
    val text: String
)

data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

data class GeminiSystemInstruction(
    val parts: List<GeminiPart>
)

data class GeminiGenerationConfig(
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 4096
)

data class GeminiRequest(
    @SerializedName("system_instruction") val systemInstruction: GeminiSystemInstruction? = null,
    val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)

data class GeminiCandidate(
    val content: GeminiContent?
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

class GeminiApiService {

    companion object {
        private const val TAG = "GeminiApiService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    private val gson = Gson()

    suspend fun generateContent(
        apiKey: String,
        model: String,
        systemInstructionText: String?,
        prompt: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API Key is empty. Please configure it in Settings."))
        }

        val userModelChoice = model.trim()
        val candidateModels = if (userModelChoice.isNotBlank()) {
            listOf(userModelChoice, "gemini-3.6-flash", "gemini-3.5-flash-lite")
        } else {
            listOf("gemini-3.6-flash", "gemini-3.5-flash-lite")
        }.distinct()

        var lastException: Exception? = null

        for (targetModel in candidateModels) {
            val isStreaming = onProgress != null
            val endpointAction = if (isStreaming) "streamGenerateContent" else "generateContent"
            val urlString = "$BASE_URL$targetModel:$endpointAction${if (isStreaming) "?alt=sse" else ""}"

            val systemInstruction = systemInstructionText?.let {
                GeminiSystemInstruction(parts = listOf(GeminiPart(text = it)))
            }

            val requestPayload = GeminiRequest(
                systemInstruction = systemInstruction,
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = prompt))
                    )
                ),
                generationConfig = GeminiGenerationConfig(temperature = 0.2f, maxOutputTokens = 4096)
            )

            var connection: HttpURLConnection? = null
            var retryAttempts = 0
            val maxRetries = 3

            while (retryAttempts < maxRetries) {
                try {
                    val jsonBody = gson.toJson(requestPayload)
                    val url = URL(urlString)
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("x-goog-api-key", apiKey)
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", if (isStreaming) "text/event-stream" else "application/json")
                    connection.connectTimeout = 30000
                    connection.readTimeout = 90000
                    connection.doOutput = true

                    if (connection is HttpsURLConnection) {
                        connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    }

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
                        Log.w(TAG, "Gemini API error for $targetModel (HTTP $responseCode): $responseBodyStr")
                        val errObj = try { gson.fromJson(responseBodyStr, GeminiResponse::class.java)?.error } catch (e: Exception) { null }
                        val msg = errObj?.message ?: "HTTP $responseCode Error"

                        if (responseCode == 404) {
                            lastException = Exception("[$targetModel] Model not found (HTTP 404)")
                            break
                        }

                        if (responseCode == 503 || responseCode == 429 || msg.contains("high demand", ignoreCase = true) || msg.contains("quota", ignoreCase = true)) {
                            retryAttempts++
                            if (retryAttempts < maxRetries) {
                                delay(2000L * retryAttempts)
                                continue
                            }
                        }

                        lastException = Exception("[$targetModel] $msg")
                        break
                    }

                    if (isStreaming) {
                        val fullAccumulatedText = StringBuilder()
                        var tokenCount = 0
                        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line?.trim() ?: continue
                            if (l.startsWith("data:")) {
                                val jsonChunk = l.substring(5).trim()
                                if (jsonChunk.isBlank() || jsonChunk == "[DONE]") continue
                                try {
                                    val chunkResp = gson.fromJson(jsonChunk, GeminiResponse::class.java)
                                    val chunkText = chunkResp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                                    if (!chunkText.isNullOrEmpty()) {
                                        fullAccumulatedText.append(chunkText)
                                        tokenCount++
                                        onProgress?.invoke(tokenCount, fullAccumulatedText.toString())
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing SSE JSON chunk: ${e.message}")
                                }
                            }
                        }

                        val resultText = fullAccumulatedText.toString().trim()
                        if (resultText.isNotEmpty()) {
                            return@withContext Result.success(resultText)
                        } else {
                            lastException = Exception("Empty streaming response from $targetModel")
                            break
                        }
                    } else {
                        val responseBodyStr = inputStream.bufferedReader().use { it.readText() }
                        val geminiResp = gson.fromJson(responseBodyStr, GeminiResponse::class.java)
                        val candidateText = geminiResp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        if (!candidateText.isNullOrBlank()) {
                            return@withContext Result.success(candidateText)
                        } else {
                            lastException = Exception("Empty candidate response from $targetModel")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed communication with Gemini model $targetModel", e)
                    lastException = e
                    if (e is SocketException && e.message?.contains("Software caused connection abort") == true) {
                        break
                    }
                    retryAttempts++
                    if (retryAttempts < maxRetries) {
                        delay(1500L * retryAttempts)
                        continue
                    } else {
                        break
                    }
                } finally {
                    connection?.disconnect()
                }
            }
        }

        return@withContext Result.failure(lastException ?: Exception("All Gemini models are currently experiencing high demand. Please retry in a moment."))
    }
}
