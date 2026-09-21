package ox.fzer0x.snakeloader.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.network.GeminiApiService
import ox.fzer0x.snakeloader.network.OllamaApiService

class AdaptiveLlmRouter(
    private val fridaManager: FridaManager,
    private val onDeviceLlmEngine: OnDeviceLlmEngine,
    private val ollamaApiService: OllamaApiService,
    private val geminiApiService: GeminiApiService
) {

    companion object {
        private const val TAG = "AdaptiveLlmRouter"
    }

    private fun getAvailableRamMb(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 2000L
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    suspend fun routeAndExecute(
        context: Context,
        prompt: String,
        systemInstruction: String?,
        selectedProviderSetting: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> {
        val freeRam = getAvailableRamMb(context)
        Log.i(TAG, "Routing LLM Request. Provider Setting: $selectedProviderSetting | Free RAM: $freeRam MB | Prompt length: ${prompt.length}")

        if (selectedProviderSetting == "GEMINI") {
            return executeGemini(prompt, systemInstruction, onProgress)
        }

        if (selectedProviderSetting == "OLLAMA") {
            return executeOllama(prompt, systemInstruction, onProgress)
        }

        if (selectedProviderSetting == "ON_DEVICE_GGUF") {
            val hasLocalGguf = onDeviceLlmEngine.listDownloadedModels(context).isNotEmpty()

            if (!hasLocalGguf) {
                Log.w(TAG, "No local GGUF model found on device. Trying fallback to Ollama/Gemini...")
                return fallbackToOllamaOrGemini(prompt, systemInstruction, onProgress)
            }

            val selectedModelPath = fridaManager.settings.onDeviceModelPath.lowercase()
            val minRamMb = when {
                selectedModelPath.contains("7b") -> 5500L
                selectedModelPath.contains("3b") -> 2500L
                else -> 1200L
            }

            if (freeRam < minRamMb) {
                Log.w(TAG, "Low RAM ($freeRam MB free, required: $minRamMb MB for $selectedModelPath). Executing inference carefully...")
            }

            val effectivePrompt = if (prompt.length > 12000) {
                val truncated = truncatePromptSafely(prompt, 12000)
                Log.i(TAG, "Prompt truncated safely (${prompt.length} -> ${truncated.length} chars) for 4096-token On-Device GGUF Context Window.")
                truncated
            } else {
                prompt
            }

            Log.i(TAG, "Executing native On-Device GGUF inference via llama.cpp C++ Engine (Vulkan GPU: ${fridaManager.settings.useVulkanGpu})...")
            val result = onDeviceLlmEngine.generateContent(
                context = context,
                modelPath = fridaManager.settings.onDeviceModelPath,
                systemInstruction = systemInstruction,
                prompt = effectivePrompt,
                useGpu = fridaManager.settings.useVulkanGpu,
                onProgress = onProgress
            )

            if (result.isSuccess) {
                return result
            }

            Log.e(TAG, "On-Device inference failed (${result.exceptionOrNull()?.message}). Trying fallback...")
            return fallbackToOllamaOrGemini(prompt, systemInstruction, onProgress)
        }

        return executeGemini(prompt, systemInstruction, onProgress)
    }

    suspend fun executeQwenForScriptGeneration(
        context: Context,
        prompt: String,
        systemInstruction: String?,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> {
        val freeRam = getAvailableRamMb(context)
        Log.i(TAG, "Executing Qwen for Script Generation. Free RAM: $freeRam MB")

        val downloadedModels = onDeviceLlmEngine.listDownloadedModels(context)
        val qwenModel = downloadedModels.find { it.name.contains("qwen", ignoreCase = true) } ?: downloadedModels.firstOrNull()

        if (qwenModel != null) {
            val effectivePrompt = if (prompt.length > 12000) truncatePromptSafely(prompt, 12000) else prompt
            Log.i(TAG, "Executing On-Device Qwen GGUF model (${qwenModel.name})...")
            val onDeviceRes = onDeviceLlmEngine.generateContent(
                context = context,
                modelPath = qwenModel.absolutePath,
                systemInstruction = systemInstruction,
                prompt = effectivePrompt,
                useGpu = fridaManager.settings.useVulkanGpu,
                onProgress = onProgress
            )
            if (onDeviceRes.isSuccess) {
                return onDeviceRes
            }
        }

        if (fridaManager.settings.ollamaBaseUrl.isNotBlank()) {
            Log.i(TAG, "Executing Ollama (Qwen) fallback...")
            val ollamaRes = executeOllama(prompt, systemInstruction, onProgress)
            if (ollamaRes.isSuccess) return ollamaRes
        }

        if (fridaManager.settings.geminiApiKey.isNotBlank()) {
            Log.i(TAG, "Fallback to Gemini for generation since Qwen is unavailable...")
            return executeGemini(prompt, systemInstruction, onProgress)
        }

        return Result.failure(IllegalStateException("No Qwen model or fallback LLM provider available."))
    }

    private suspend fun fallbackToOllamaOrGemini(
        prompt: String,
        systemInstruction: String?,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> {
        if (fridaManager.settings.ollamaBaseUrl.isNotBlank()) {
            val ollamaRes = executeOllama(prompt, systemInstruction, onProgress)
            if (ollamaRes.isSuccess) return ollamaRes
        }

        if (fridaManager.settings.geminiApiKey.isNotBlank()) {
            return executeGemini(prompt, systemInstruction, onProgress)
        }

        return Result.failure(
            IllegalStateException("On-Device GGUF could not be executed and neither Ollama nor a Gemini API Key is configured in settings.")
        )
    }

    private suspend fun executeGemini(
        prompt: String,
        systemInstruction: String?,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> {
        if (fridaManager.settings.geminiApiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Gemini API Key is not configured in settings."))
        }
        return geminiApiService.generateContent(
            apiKey = fridaManager.settings.geminiApiKey,
            model = fridaManager.settings.geminiModel,
            systemInstructionText = systemInstruction ?: Il2CppAiAssistant.SYSTEM_INSTRUCTION,
            prompt = prompt,
            onProgress = onProgress
        )
    }

    private suspend fun executeOllama(
        prompt: String,
        systemInstruction: String?,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> {
        return ollamaApiService.generateContent(
            baseUrl = fridaManager.settings.ollamaBaseUrl,
            model = fridaManager.settings.ollamaModel,
            systemInstructionText = systemInstruction ?: Il2CppAiAssistant.SYSTEM_INSTRUCTION,
            prompt = prompt,
            onProgress = onProgress
        )
    }

    private fun truncatePromptSafely(prompt: String, maxChars: Int = 12000): String {
        if (prompt.length <= maxChars) return prompt

        val cutCandidate = prompt.substring(0, maxChars)
        val lastDoubleNewline = cutCandidate.lastIndexOf("\n\n")
        val lastNewline = cutCandidate.lastIndexOf('\n')

        val cleanCutIndex = when {
            lastDoubleNewline > maxChars - 1500 -> lastDoubleNewline
            lastNewline > maxChars - 500 -> lastNewline
            else -> maxChars
        }

        val truncatedPart = prompt.substring(0, cleanCutIndex).trimEnd()
        return "$truncatedPart\n\n[... Remaining class dump truncated cleanly at boundary for local context window ...]"
    }
}
