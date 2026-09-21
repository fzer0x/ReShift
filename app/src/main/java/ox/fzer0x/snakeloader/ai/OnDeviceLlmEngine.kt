package ox.fzer0x.snakeloader.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class OnDeviceLlmEngine {

    fun interface NativeProgressCallback {
        fun onProgress(tokensGenerated: Int, partialText: String)
    }

    companion object {
        private const val TAG = "OnDeviceLlmEngine"
        private var nativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("reshift_llama")
                nativeLibraryLoaded = true
                Log.i(TAG, "Successfully loaded native library libreshift_llama.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library libreshift_llama.so", e)
                nativeLibraryLoaded = false
            }
        }
    }

    private external fun nativeInitModel(modelPath: String, useGpu: Boolean): Long

    private external fun nativeGenerate(
        contextPtr: Long,
        prompt: String,
        systemInstruction: String?,
        maxTokens: Int,
        temperature: Float,
        callback: NativeProgressCallback?
    ): String?

    private external fun nativeFreeModel(contextPtr: Long)

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listDownloadedModels(context: Context): List<File> {
        val dir = getModelsDir(context)
        return dir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }?.toList() ?: emptyList()
    }

    fun validateGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                if (read < 4) return false
                // GGUF magic bytes: 'G', 'G', 'U', 'F' (0x47, 0x47, 0x55, 0x46)
                return header[0] == 'G'.code.toByte() &&
                       header[1] == 'G'.code.toByte() &&
                       header[2] == 'U'.code.toByte() &&
                       header[3] == 'F'.code.toByte()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating GGUF header for ${file.name}", e)
            return false
        }
    }

    @Volatile
    private var cachedContextPtr: Long = 0L
    @Volatile
    private var cachedModelPath: String = ""
    @Volatile
    private var cachedUseGpu: Boolean = false

    @Synchronized
    fun getOrInitSession(modelPath: String, useGpu: Boolean): Long {
        if (cachedContextPtr != 0L) {
            if (cachedModelPath == modelPath && cachedUseGpu == useGpu) {
                Log.i(TAG, "Reusing cached native GGUF session at pointer 0x${cachedContextPtr.toString(16)}")
                return cachedContextPtr
            } else {
                Log.i(TAG, "Model path or GPU mode changed. Unloading existing cached session...")
                unloadModelLocked()
            }
        }

        Log.i(TAG, "Initializing new native GGUF session: $modelPath | GPU: $useGpu")
        val ptr = nativeInitModel(modelPath, useGpu)
        if (ptr != 0L) {
            cachedContextPtr = ptr
            cachedModelPath = modelPath
            cachedUseGpu = useGpu
        }
        return ptr
    }

    @Synchronized
    fun unloadModel() {
        unloadModelLocked()
    }

    private fun unloadModelLocked() {
        if (cachedContextPtr != 0L) {
            try {
                Log.i(TAG, "Unloading cached native GGUF session (0x${cachedContextPtr.toString(16)})...")
                nativeFreeModel(cachedContextPtr)
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing native model session", e)
            } finally {
                cachedContextPtr = 0L
                cachedModelPath = ""
                cachedUseGpu = false
            }
        }
    }

    suspend fun generateContent(
        context: Context,
        modelPath: String,
        systemInstruction: String?,
        prompt: String,
        useGpu: Boolean = false,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!nativeLibraryLoaded) {
            return@withContext Result.failure(
                IllegalStateException("Native JNI inference library (libreshift_llama.so) could not be loaded. Please check system architecture (arm64-v8a).")
            )
        }

        val downloadedModels = listDownloadedModels(context)
        val selectedFile = if (modelPath.isNotBlank()) {
            File(modelPath)
        } else {
            downloadedModels.firstOrNull()
        }

        if (selectedFile == null || !selectedFile.exists() || selectedFile.length() < 1000) {
            return@withContext Result.failure(
                IllegalArgumentException("No local GGUF model found on phone. Please download a model (e.g. qwen2.5-coder-1.5b.gguf) in Settings.")
            )
        }

        if (!validateGgufHeader(selectedFile)) {
            return@withContext Result.failure(
                IllegalArgumentException("File '${selectedFile.name}' is not a valid GGUF model or is incomplete. Invalid magic bytes.")
            )
        }

        Log.i(TAG, "Starting Native On-Device GGUF Inference: ${selectedFile.absolutePath} (${selectedFile.length() / (1024 * 1024)} MB) | Backend: ${if (useGpu) "Vulkan GPU" else "CPU NEON"}")

        val contextPtr = getOrInitSession(selectedFile.absolutePath, useGpu)
        if (contextPtr == 0L) {
            return@withContext Result.failure(
                IllegalStateException("Native GGUF model initialization in RAM failed. Insufficient free RAM.")
            )
        }

        try {
            val callback = NativeProgressCallback { tokens, text ->
                onProgress?.invoke(tokens, text)
            }

            val generatedCode = nativeGenerate(
                contextPtr = contextPtr,
                prompt = prompt,
                systemInstruction = systemInstruction,
                maxTokens = 2048,
                temperature = 0.2f,
                callback = callback
            )

            if (!generatedCode.isNullOrBlank()) {
                Result.success(generatedCode.trim())
            } else {
                Result.failure(
                    IllegalStateException("Native GGUF inference produced empty code.")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native GGUF inference error", e)
            unloadModel()
            Result.failure(e)
        }
    }
}
