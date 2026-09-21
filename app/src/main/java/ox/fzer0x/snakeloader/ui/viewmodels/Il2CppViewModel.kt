package ox.fzer0x.snakeloader.ui.viewmodels

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.DownloadedScript
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.LogManager
import ox.fzer0x.snakeloader.OverlayService
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ai.AiGoalPreset
import ox.fzer0x.snakeloader.ai.AdaptiveLlmRouter
import ox.fzer0x.snakeloader.ai.AgentExecutionMode
import ox.fzer0x.snakeloader.ai.AutonomousAgentEngine
import ox.fzer0x.snakeloader.ai.Il2CppAiAssistant
import ox.fzer0x.snakeloader.ai.OnDeviceLlmEngine
import ox.fzer0x.snakeloader.data.Il2CppRepository
import ox.fzer0x.snakeloader.db.Il2CppDatabaseHelper
import ox.fzer0x.snakeloader.network.GeminiApiService
import ox.fzer0x.snakeloader.network.OllamaApiService
import ox.fzer0x.snakeloader.ui.screens.loadInstalledApps
import ox.fzer0x.snakeloader.utils.InputValidator
import ox.fzer0x.snakeloader.utils.ShellExecutor
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Il2CppMethodData(
    @SerializedName("name") val name: String = "",
    @SerializedName("returnType") val returnType: String = "void",
    @SerializedName("paramCount") val paramCount: Int = 0,
    @SerializedName("params") val params: List<String> = emptyList(),
    @SerializedName("offset") val offset: String = "0x0",
    @SerializedName("pointer") val pointer: String = "0x0"
)

data class Il2CppFieldData(
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = "var",
    @SerializedName("offset") val offset: String = "0x0"
)

data class Il2CppPropertyData(
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = "var"
)

data class Il2CppClassData(
    @SerializedName("name") val name: String = "",
    @SerializedName("namespace") val namespace: String = "",
    @SerializedName("fullName") val fullName: String = "",
    @SerializedName("assembly") val assembly: String = "",
    @SerializedName("parent") val parent: String = "",
    @SerializedName("isEnum") val isEnum: Boolean = false,
    @SerializedName("isValueType") val isValueType: Boolean = false,
    @SerializedName("isInterface") val isInterface: Boolean = false,
    @SerializedName("size") val size: Int = 0,
    @SerializedName("methods") val methods: List<Il2CppMethodData> = emptyList(),
    @SerializedName("fields") val fields: List<Il2CppFieldData> = emptyList(),
    @SerializedName("properties") val properties: List<Il2CppPropertyData> = emptyList()
)

data class AiTelemetryInfo(
    val freeRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val cpuThreads: Int = 1,
    val modelSizeMb: Long = 0L,
    val contextWindowTokens: Int = 4096,
    val lastInferenceTimeMs: Long = 0L,
    val useVulkanGpu: Boolean = false,
    val activeBackend: String = "CPU (NEON)",
    val estimatedPowerWatts: Double = 0.8,
    val estimatedThermalImpact: String = "Cool (Idle)"
)

enum class Il2CppFilter(val displayName: String) {
    ALL("All"),
    GAME_ONLY("Game Only"),
    NO_SDK("No Ads/SDKs"),
    HIGH_RELEVANCE("Top Relevant"),
    ENUMS("Enums"),
    STRUCTS("Structs"),
    WITH_METHODS("Has Methods"),
    WITH_FIELDS("Has Fields")
}

enum class AutonomousAgentState(val label: String) {
    IDLE("Idle"),
    GENERATING_INITIAL("Generating Candidate Script"),
    PRE_VALIDATING("Gemini Cloud Static Audit"),
    INJECTING("Injecting into Process Memory"),
    TESTING_AND_MONITORING("Monitoring Live Logs & Hooks"),
    WAITING_USER_EVALUATION("Goal achieved in game? (Waiting for feedback)"),
    ANALYZING_ERROR("Analyzing Crash & Stacktrace"),
    CORRECTING_SCRIPT("Gemini Cloud Auto-Repairing Code"),
    SUCCESS_VALIDATED("Script Validated & Verified Success!"),
    FAILED_MAX_RETRIES("Failed: Exceeded Max Retries"),
    ABORTED("Aborted by User")
}

data class AgentLogStep(
    val id: Long = System.currentTimeMillis(),
    val timestamp: String,
    val stepType: String,
    val message: String,
    val codeSnippet: String? = null
)

class Il2CppViewModel(
    private val fridaManager: FridaManager,
    private val scriptManager: ScriptManager
) : ViewModel() {

    companion object {
        private const val TAG = "Il2CppViewModel"
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Boolean::class.java, JsonDeserializer<Boolean> { json, _, _ ->
            if (json.isJsonPrimitive) {
                val prim = json.asJsonPrimitive
                if (prim.isBoolean) prim.asBoolean
                else if (prim.isNumber) prim.asInt != 0
                else prim.asString == "true" || prim.asString == "1"
            } else false
        })
        .registerTypeAdapter(Boolean::class.javaObjectType, JsonDeserializer<Boolean> { json, _, _ ->
            if (json.isJsonPrimitive) {
                val prim = json.asJsonPrimitive
                if (prim.isBoolean) prim.asBoolean
                else if (prim.isNumber) prim.asInt != 0
                else prim.asString == "true" || prim.asString == "1"
            } else false
        })
        .registerTypeAdapter(Il2CppClassData::class.java, JsonDeserializer<Il2CppClassData> { json, _, ctx ->
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                val ns = obj.get("namespace")?.asString ?: ""
                val fullName = obj.get("fullName")?.asString ?: (if (ns.isNotBlank()) "$ns.$name" else name)
                val assembly = obj.get("assembly")?.asString ?: ""
                val parent = obj.get("parent")?.asString ?: ""
                val isEnum = obj.get("isEnum")?.asBoolean ?: false
                val isValueType = obj.get("isValueType")?.asBoolean ?: false
                val isInterface = obj.get("isInterface")?.asBoolean ?: false
                val size = obj.get("size")?.asInt ?: 0

                val methods: List<Il2CppMethodData> = if (obj.has("methods") && obj.get("methods").isJsonArray) {
                    ctx.deserialize(obj.get("methods"), object : TypeToken<List<Il2CppMethodData>>() {}.type) ?: emptyList()
                } else emptyList()

                val fields: List<Il2CppFieldData> = if (obj.has("fields") && obj.get("fields").isJsonArray) {
                    ctx.deserialize(obj.get("fields"), object : TypeToken<List<Il2CppFieldData>>() {}.type) ?: emptyList()
                } else emptyList()

                val properties: List<Il2CppPropertyData> = if (obj.has("properties") && obj.get("properties").isJsonArray) {
                    ctx.deserialize(obj.get("properties"), object : TypeToken<List<Il2CppPropertyData>>() {}.type) ?: emptyList()
                } else emptyList()

                Il2CppClassData(
                    name = name,
                    namespace = ns,
                    fullName = fullName,
                    assembly = assembly,
                    parent = parent,
                    isEnum = isEnum,
                    isValueType = isValueType,
                    isInterface = isInterface,
                    size = size,
                    methods = methods,
                    fields = fields,
                    properties = properties
                )
            } else null
        })
        .create()

    private var dbHelper: Il2CppDatabaseHelper? = null

    fun getDbHelper(context: Context): Il2CppDatabaseHelper {
        if (dbHelper == null) {
            dbHelper = Il2CppDatabaseHelper(context.applicationContext)
        }
        return dbHelper!!
    }

    private val _allApps = mutableStateOf<List<AppInfo>>(emptyList())
    val allApps: State<List<AppInfo>> get() = _allApps

    private val _selectedApp = mutableStateOf<AppInfo?>(null)
    val selectedApp: State<AppInfo?> get() = _selectedApp

    private val _showAppPicker = mutableStateOf(false)
    val showAppPicker: State<Boolean> get() = _showAppPicker

    private val _classes = mutableStateOf<List<Il2CppClassData>>(emptyList())
    val classes: State<List<Il2CppClassData>> get() = _classes

    private val _filteredClasses = mutableStateOf<List<Il2CppClassData>>(emptyList())
    val filteredClasses: State<List<Il2CppClassData>> get() = _filteredClasses

    private val _totalMatchesCount = mutableIntStateOf(0)
    val totalMatchesCount: State<Int> get() = _totalMatchesCount

    private val _statusText = mutableStateOf("Select an app to begin IL2CPP Inspection")
    val statusText: State<String> get() = _statusText

    private val _isDumping = mutableStateOf(false)
    val isDumping: State<Boolean> get() = _isDumping

    private val _dumpProgress = mutableIntStateOf(0)
    val dumpProgress: State<Int> get() = _dumpProgress

    private val _injectionDelaySeconds = mutableIntStateOf(7)
    val injectionDelaySeconds: State<Int> get() = _injectionDelaySeconds

    private val _searchText = mutableStateOf("")
    val searchText: State<String> get() = _searchText

    private val _selectedFilter = mutableStateOf(Il2CppFilter.ALL)
    val selectedFilter: State<Il2CppFilter> get() = _selectedFilter

    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> get() = _errorMessage

    private val _generatedCodeDialog = mutableStateOf<Pair<String, String>?>(null)
    val generatedCodeDialog: State<Pair<String, String>?> get() = _generatedCodeDialog

    // AI Agent State
    private val geminiApiService = GeminiApiService()
    private val ollamaApiService = OllamaApiService()
    private val onDeviceLlmEngine = OnDeviceLlmEngine()
    private val aiAssistant = Il2CppAiAssistant()
    private val adaptiveLlmRouter = AdaptiveLlmRouter(fridaManager, onDeviceLlmEngine, ollamaApiService, geminiApiService)
    val autonomousAgentEngine = AutonomousAgentEngine(fridaManager, adaptiveLlmRouter, aiAssistant)
    private var repository: Il2CppRepository? = null

    private fun getRepo(context: Context): Il2CppRepository {
        if (repository == null) {
            repository = Il2CppRepository(context.applicationContext, getDbHelper(context))
        }
        return repository!!
    }

    private val _selectedAiProvider = mutableStateOf(fridaManager.settings.aiProvider)
    val selectedAiProvider: State<String> get() = _selectedAiProvider

    val isGeminiApiKeyMissing: Boolean get() = fridaManager.settings.geminiApiKey.isBlank()

    private val _isGeneratingAiScript = mutableStateOf(false)
    val isGeneratingAiScript: State<Boolean> get() = _isGeneratingAiScript

    private val _aiAgentStatus = mutableStateOf("")
    val aiAgentStatus: State<String> get() = _aiAgentStatus

    private val _aiAgentStep = mutableIntStateOf(0)
    val aiAgentStep: State<Int> get() = _aiAgentStep

    private val _lastInferenceTimeMs = mutableStateOf(0L)
    val lastInferenceTimeMs: State<Long> get() = _lastInferenceTimeMs

    private val _aiGeneratedScript = mutableStateOf<String?>(null)
    val aiGeneratedScript: State<String?> get() = _aiGeneratedScript

    // FULL AUTONOMOUS AI AGENT STATE & TELEMETRY
    private val _autonomousState = mutableStateOf(AutonomousAgentState.IDLE)
    val autonomousState: State<AutonomousAgentState> get() = _autonomousState

    private val _agentCurrentIteration = mutableIntStateOf(0)
    val agentCurrentIteration: State<Int> get() = _agentCurrentIteration

    private val _agentMaxIterations = mutableIntStateOf(0) // 0 = Unlimited Loop
    val agentMaxIterations: State<Int> get() = _agentMaxIterations

    private val _agentLogSteps = mutableStateOf<List<AgentLogStep>>(emptyList())
    val agentLogSteps: State<List<AgentLogStep>> get() = _agentLogSteps

    private val _isAutonomousRunning = mutableStateOf(false)
    val isAutonomousRunning: State<Boolean> get() = _isAutonomousRunning

    private val _autonomousCloudValidationEnabled = mutableStateOf(false)
    val autonomousCloudValidationEnabled: State<Boolean> get() = _autonomousCloudValidationEnabled

    private val _agentInjectionDelaySeconds = mutableIntStateOf(7)
    val agentInjectionDelaySeconds: State<Int> get() = _agentInjectionDelaySeconds

    fun setAgentInjectionDelay(seconds: Int) {
        _agentInjectionDelaySeconds.intValue = seconds.coerceIn(0, 60)
    }

    private val _agentExecutionMode = mutableStateOf(AgentExecutionMode.SPAWN)
    val agentExecutionMode: State<AgentExecutionMode> get() = _agentExecutionMode

    fun setAgentExecutionMode(mode: AgentExecutionMode) {
        _agentExecutionMode.value = mode
        autonomousAgentEngine.setExecutionMode(mode)
    }

    private val _agentLastConsoleOutput = mutableStateOf("")
    val agentLastConsoleOutput: State<String> get() = _agentLastConsoleOutput

    private val _agentLastSqlAstContext = mutableStateOf("")
    val agentLastSqlAstContext: State<String> get() = _agentLastSqlAstContext

    private val _agentLastRepairDiagnosis = mutableStateOf("")
    val agentLastRepairDiagnosis: State<String> get() = _agentLastRepairDiagnosis

    private val _agentValidatedScript = mutableStateOf<String?>(null)
    val agentValidatedScript: State<String?> get() = _agentValidatedScript

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            autonomousAgentEngine.agentState.collect { state ->
                _autonomousState.value = state
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.agentLogs.collect { logs ->
                _agentLogSteps.value = logs
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.currentIteration.collect { iter ->
                _agentCurrentIteration.intValue = iter
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.isRunning.collect { running ->
                _isAutonomousRunning.value = running
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.executionMode.collect { mode ->
                _agentExecutionMode.value = mode
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.lastConsoleOutput.collect { output ->
                _agentLastConsoleOutput.value = output
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.lastSqlAstContext.collect { sqlCtx ->
                _agentLastSqlAstContext.value = sqlCtx
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.lastRepairDiagnosis.collect { diag ->
                _agentLastRepairDiagnosis.value = diag
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.streamingScript.collect { script ->
                if (script != null) {
                    _aiGeneratedScript.value = script
                }
            }
        }
        viewModelScope.launch {
            autonomousAgentEngine.validatedScript.collect { script ->
                if (script != null) {
                    _agentValidatedScript.value = script
                    _aiGeneratedScript.value = script
                }
            }
        }
    }

    fun setAiProvider(provider: String) {
        _selectedAiProvider.value = provider
        fridaManager.settings.aiProvider = provider
    }

    fun updateOnDeviceModelPath(path: String) {
        fridaManager.settings.onDeviceModelPath = path
    }

    fun getOnDeviceModelPath(): String {
        return fridaManager.settings.onDeviceModelPath
    }

    fun getDownloadedGgufModels(context: Context): List<File> {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }?.toList() ?: emptyList()
    }

    fun getTelemetryInfo(context: Context): AiTelemetryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val freeRam = (memoryInfo?.availMem ?: 0L) / (1024 * 1024)
        val totalRam = (memoryInfo?.totalMem ?: 0L) / (1024 * 1024)
        val threads = Runtime.getRuntime().availableProcessors()
        val ggufModel = getDownloadedGgufModels(context).firstOrNull()
        val modelSize = if (ggufModel != null) ggufModel.length() / (1024 * 1024) else 0L

        val isGpu = fridaManager.settings.useVulkanGpu
        val activeBackend = if (isGpu) "Vulkan GPU (Adreno/Mali)" else "CPU (ARM64 NEON)"
        val estimatedPower = if (_isGeneratingAiScript.value) (if (isGpu) 4.5 else 3.8) else 0.8
        val thermalImpact = if (_isGeneratingAiScript.value) (if (isGpu) "Moderate High" else "Warm") else "Cool (Idle)"

        return AiTelemetryInfo(
            freeRamMb = freeRam,
            totalRamMb = totalRam,
            cpuThreads = threads,
            modelSizeMb = modelSize,
            contextWindowTokens = 4096,
            lastInferenceTimeMs = _lastInferenceTimeMs.value,
            useVulkanGpu = isGpu,
            activeBackend = activeBackend,
            estimatedPowerWatts = estimatedPower,
            estimatedThermalImpact = thermalImpact
        )
    }

    fun setUseVulkanGpu(enabled: Boolean) {
        fridaManager.settings.useVulkanGpu = enabled
    }

    fun resetAiScript() {
        _aiGeneratedScript.value = null
        _aiAgentStatus.value = ""
        _aiAgentStep.intValue = 0
    }

    private suspend fun executeAiPrompt(
        context: Context,
        prompt: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<String> {
        return adaptiveLlmRouter.routeAndExecute(
            context = context,
            prompt = prompt,
            systemInstruction = Il2CppAiAssistant.SYSTEM_INSTRUCTION,
            selectedProviderSetting = _selectedAiProvider.value,
            onProgress = onProgress
        )
    }

    fun setInjectionDelay(seconds: Int) {
        _injectionDelaySeconds.intValue = seconds.coerceIn(0, 60)
    }

    fun loadApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = loadInstalledApps(context, includeSystemApps = false)
            withContext(Dispatchers.Main) {
                _allApps.value = apps
            }
        }
    }

    fun openAppPicker() {
        _showAppPicker.value = true
    }

    fun closeAppPicker() {
        _showAppPicker.value = false
    }

    fun selectApp(app: AppInfo) {
        _selectedApp.value = app
        _showAppPicker.value = false
        _statusText.value = "Selected: ${app.label} (${app.packageName})"
        _errorMessage.value = null
    }

    fun setSearchText(query: String) {
        _searchText.value = query
        applyFilter()
    }

    fun setFilter(filter: Il2CppFilter) {
        _selectedFilter.value = filter
        applyFilter()
    }

    fun dismissGeneratedCodeDialog() {
        _generatedCodeDialog.value = null
        _aiAgentStatus.value = ""
    }

    fun clearAiGeneratedScript() {
        _aiGeneratedScript.value = null
        _aiAgentStatus.value = ""
    }

    fun generateAiScriptWithGoal(
        context: Context,
        klass: Il2CppClassData,
        method: Il2CppMethodData,
        goalPreset: AiGoalPreset,
        customGoal: String,
        onComplete: (String) -> Unit
    ) {
        val provider = _selectedAiProvider.value
        if (provider == "GEMINI" && fridaManager.settings.geminiApiKey.isBlank()) {
            _errorMessage.value = "Gemini API key is not set. Please configure it in Settings."
            return
        }

        _isGeneratingAiScript.value = true
        _aiAgentStatus.value = when (provider) {
            "ON_DEVICE_GGUF" -> "Initializing Native GGUF Model in RAM & Running Local JNI Inference..."
            "OLLAMA" -> "Requesting script from Local Ollama (${fridaManager.settings.ollamaModel})..."
            else -> "Requesting script from Gemini (${fridaManager.settings.geminiModel})..."
        }

        viewModelScope.launch {
            val appPkg = _selectedApp.value?.packageName ?: "target_app"
            val prompt = aiAssistant.buildPrompt(appPkg, klass, method, goalPreset, customGoal)

            val result = executeAiPrompt(context, prompt)

            result.fold(
                onSuccess = { rawResponse ->
                    val cleanCode = aiAssistant.extractJsCode(rawResponse)
                    _isGeneratingAiScript.value = false
                    _aiGeneratedScript.value = cleanCode
                    _aiAgentStatus.value = "Script generated successfully!"
                    onComplete(cleanCode)
                },
                onFailure = { err ->
                    _isGeneratingAiScript.value = false
                    _aiAgentStatus.value = "AI Generation failed: ${err.message}"
                    _errorMessage.value = "AI Error: ${err.message}"
                }
            )
        }
    }

    fun generateGlobalAiScript(
        context: Context,
        userInstruction: String,
        onComplete: (String) -> Unit
    ) {
        val provider = _selectedAiProvider.value
        if (provider == "GEMINI" && fridaManager.settings.geminiApiKey.isBlank()) {
            _errorMessage.value = "Gemini API key is not set. Please configure it in Settings."
            return
        }

        _isGeneratingAiScript.value = true
        _aiAgentStep.intValue = 1
        _aiAgentStatus.value = "Step 1/3: Extracting C# Class AST & Context Dump..."

        viewModelScope.launch(Dispatchers.IO) {
            val appPkg = _selectedApp.value?.packageName ?: "target_app"
            var classesToUse = _classes.value
            if (classesToUse.isEmpty()) {
                classesToUse = getRepo(context).queryClasses(appPkg, "", Il2CppFilter.ALL, limit = 5000)
                withContext(Dispatchers.Main) {
                    _classes.value = classesToUse
                }
            }

            if (classesToUse.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _isGeneratingAiScript.value = false
                    _errorMessage.value = "No analyzed classes found! Please inspect the target app first."
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _aiAgentStatus.value = "Step 1/3: Extracted ${classesToUse.size} C# Classes for AST Prompt..."
            }

            val startTime = System.currentTimeMillis()
            val prompt = aiAssistant.buildGlobalPrompt(appPkg, classesToUse, userInstruction)

            withContext(Dispatchers.Main) {
                _aiAgentStep.intValue = 2
                _aiAgentStatus.value = when (provider) {
                    "ON_DEVICE_GGUF" -> "Step 2/3: Running Native llama.cpp Token Inference on-device..."
                    "OLLAMA" -> "Step 2/3: Communicating with Local Ollama Inference Engine..."
                    else -> "Step 2/3: Requesting Code Generation from Gemini Cloud API..."
                }
            }

            val result = executeAiPrompt(context, prompt) { tokens, partialCode ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (tokens > 0) {
                        _aiAgentStatus.value = "Live Token Inference: $tokens tokens generated..."
                        _aiGeneratedScript.value = partialCode
                    } else {
                        _aiAgentStatus.value = partialCode
                    }
                }
            }
            val duration = System.currentTimeMillis() - startTime
            _lastInferenceTimeMs.value = duration

            result.fold(
                onSuccess = { rawResponse ->
                    _aiAgentStep.intValue = 3
                    _aiAgentStatus.value = "Step 3/3: Verifying Frida script syntax & pointer safety..."
                    val cleanCode = aiAssistant.extractJsCode(rawResponse)
                    _isGeneratingAiScript.value = false
                    _aiAgentStep.intValue = 4
                    _aiGeneratedScript.value = cleanCode
                    _aiAgentStatus.value = "Done! Script generated in ${duration}ms"
                    onComplete(cleanCode)
                },
                onFailure = { err ->
                    _isGeneratingAiScript.value = false
                    _aiAgentStep.intValue = 0
                    _aiAgentStatus.value = "AI Generation failed: ${err.message}"
                    _errorMessage.value = "AI Error: ${err.message}"
                }
            )
        }
    }

    fun deployAndTestAiScript(
        context: Context,
        klass: Il2CppClassData,
        method: Il2CppMethodData,
        scriptCode: String,
        onStatusUpdate: (String) -> Unit
    ) {
        val app = _selectedApp.value
        if (app == null) {
            _errorMessage.value = "No target app selected!"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                onStatusUpdate("Attaching Frida script to target process...")
            }

            val success = fridaManager.attachToRunningProcess(app.packageName, scriptCode)
            if (!success) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Failed to attach Frida script!")
                }
                return@launch
            }

            val autoFixEnabled = fridaManager.settings.aiAutoCorrectionEnabled

            if (!autoFixEnabled) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Script deployed successfully! Monitoring logs...")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                onStatusUpdate("Script deployed! Testing execution and monitoring errors...")
            }

            val startLogId = LogManager.fridaLogs.maxOfOrNull { it.id } ?: 0L
            var detectedError: String? = null

            for (attempt in 1..10) {
                delay(500)
                val newLogs = LogManager.fridaLogs.filter { it.id > startLogId }
                val errEntry = newLogs.find {
                    it.message.contains("Error", ignoreCase = true) ||
                    it.message.contains("Exception", ignoreCase = true) ||
                    it.message.contains("TypeError", ignoreCase = true) ||
                    it.message.contains("Access violation", ignoreCase = true)
                }

                if (errEntry != null) {
                    detectedError = errEntry.message
                    break
                }
            }

            if (detectedError != null) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Error detected in log! Initiating Closed-Loop Auto-Fix...")
                }

                val correctionPrompt = aiAssistant.buildCorrectionPrompt(
                    originalScript = scriptCode,
                    errorLog = detectedError,
                    targetPackage = app.packageName,
                    klass = klass,
                    method = method
                )

                val fixResult = executeAiPrompt(context, correctionPrompt)

                fixResult.fold(
                    onSuccess = { rawFix ->
                        val fixedCode = aiAssistant.extractJsCode(rawFix)
                        withContext(Dispatchers.Main) {
                            _aiGeneratedScript.value = fixedCode
                            onStatusUpdate("Auto-Fix generated! Re-injecting corrected script...")
                        }
                        fridaManager.executeScriptContent(app.packageName, fixedCode)
                    },
                    onFailure = { err ->
                        withContext(Dispatchers.Main) {
                            onStatusUpdate("Auto-Fix failed: ${err.message}")
                        }
                    }
                )
            } else {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Script verified! No runtime errors detected.")
                }
            }
        }
    }

    fun setAutonomousMaxIterations(max: Int) {
        _agentMaxIterations.intValue = max.coerceAtLeast(0)
    }

    fun setAutonomousCloudValidation(enabled: Boolean) {
        _autonomousCloudValidationEnabled.value = enabled
    }

    private fun addAgentLogStep(stepType: String, message: String, codeSnippet: String? = null) {
        val timeStr = try {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            "00:00:00"
        }
        val newStep = AgentLogStep(
            timestamp = timeStr,
            stepType = stepType,
            message = message,
            codeSnippet = codeSnippet
        )
        _agentLogSteps.value = _agentLogSteps.value + newStep
    }

    fun clearAgentLogs() {
        autonomousAgentEngine.reset()
    }

    fun resetAutonomousAgent() {
        autonomousAgentEngine.reset()
    }

    fun abortAutonomousAgent() {
        autonomousAgentEngine.abort()
    }

    fun pruneDatabaseNoise(
        context: Context,
        removeCompilerGenerated: Boolean = true,
        removeFrameworkSystem: Boolean = true,
        removeThirdPartySdks: Boolean = false,
        removeEmptyStubs: Boolean = true,
        onComplete: ((Int) -> Unit)? = null
    ) {
        val app = _selectedApp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val db = getDbHelper(context)
            val prunedCount = db.pruneUnimportantClasses(
                packageName = app.packageName,
                removeCompilerGenerated = removeCompilerGenerated,
                removeFrameworkSystem = removeFrameworkSystem,
                removeThirdPartySdks = removeThirdPartySdks,
                removeEmptyStubs = removeEmptyStubs
            )
            val updatedClasses = getRepo(context).queryClasses(app.packageName, _searchText.value, _selectedFilter.value, limit = 5000)
            withContext(Dispatchers.Main) {
                _classes.value = updatedClasses
                _totalMatchesCount.intValue = updatedClasses.size
                _statusText.value = "Pruned $prunedCount unimportant entries from database."
                onComplete?.invoke(prunedCount)
            }
        }
    }

    fun extendAgentMonitoringWindow(additionalMs: Long = 15000L) {
        autonomousAgentEngine.extendMonitoringWindow(additionalMs)
    }

    fun submitAgentUserEvaluation(goalAchieved: Boolean, refinedPrompt: String? = null) {
        autonomousAgentEngine.submitUserEvaluation(goalAchieved, refinedPrompt)
    }

    fun startAutonomousAgent(
        context: Context,
        userInstruction: String,
        targetClass: Il2CppClassData? = null,
        targetMethod: Il2CppMethodData? = null
    ) {
        val app = _selectedApp.value
        if (app == null) {
            _errorMessage.value = "Please select a target application first!"
            return
        }

        if (_selectedAiProvider.value == "GEMINI" && fridaManager.settings.geminiApiKey.isBlank()) {
            _errorMessage.value = "Gemini API key is missing! Please configure it in Settings."
            return
        }

        try {
            if (Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OverlayService start warning: ${e.message}")
        }

        viewModelScope.launch(Dispatchers.IO) {
            var classesToUse = _classes.value
            if (classesToUse.isEmpty()) {
                classesToUse = getRepo(context).queryClasses(app.packageName, query = "", filter = Il2CppFilter.ALL, limit = 5000)
                withContext(Dispatchers.Main) {
                    _classes.value = classesToUse
                }
            }

            if (classesToUse.isEmpty() && targetClass == null) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "No dumped classes found for ${app.label}! Please inspect the target app first."
                }
                return@launch
            }

            autonomousAgentEngine.startLoop(
                context = context,
                scope = viewModelScope,
                targetPackage = app.packageName,
                userInstruction = userInstruction,
                targetClass = targetClass,
                targetMethod = targetMethod,
                allClasses = classesToUse,
                maxIterations = _agentMaxIterations.intValue,
                cloudValidationEnabled = _autonomousCloudValidationEnabled.value,
                injectionDelaySeconds = _agentInjectionDelaySeconds.intValue,
                selectedProvider = _selectedAiProvider.value,
                mode = _agentExecutionMode.value,
                dbHelper = getDbHelper(context),
                onTokenProgress = { tokens, partialCode ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _aiAgentStatus.value = "Live Token Inference: $tokens tokens generated..."
                        _aiGeneratedScript.value = partialCode
                    }
                },
                onScriptSaved = null
            )
        }
    }

    private fun getPossibleDumpPaths(pkg: String): List<String> {
        return listOf(
            "/data/data/$pkg/cache/il2cpp_dump.json",
            "/data/user/0/$pkg/cache/il2cpp_dump.json",
            "/data/local/tmp/il2cpp_dump.json"
        )
    }

    private fun getPossibleDonePaths(pkg: String): List<String> {
        return listOf(
            "/data/data/$pkg/cache/il2cpp_done.flag",
            "/data/user/0/$pkg/cache/il2cpp_done.flag",
            "/data/local/tmp/il2cpp_done.flag"
        )
    }

    fun startInspection(context: Context) {
        val app = _selectedApp.value
        if (app == null) {
            _errorMessage.value = "Please select a target application first!"
            return
        }

        val db = getDbHelper(context)
        val pkg = app.packageName
        db.clearPackage(pkg)

        _isDumping.value = true
        _errorMessage.value = null
        _classes.value = emptyList()
        _filteredClasses.value = emptyList()
        _totalMatchesCount.intValue = 0
        _dumpProgress.intValue = 0
        val delaySec = _injectionDelaySeconds.intValue
        _statusText.value = if (delaySec > 0) "Deploying dumper (Delay: ${delaySec}s)..." else "Initializing Frida and deploying dumper..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safePkg = InputValidator.escapeShellInput(pkg)
                ShellExecutor.execute("mkdir -p /data/data/$safePkg/cache /data/local/tmp && chmod 777 /data/data/$safePkg/cache /data/local/tmp", useRoot = true)
                ShellExecutor.execute("rm -f /data/data/$safePkg/cache/il2cpp_* /data/user/0/$safePkg/cache/il2cpp_* /data/local/tmp/il2cpp_*", useRoot = true)

                val rawScript = try {
                    context.assets.open("il2cpp_inspector_dumper.js").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load script asset", e)
                    withContext(Dispatchers.Main) {
                        _isDumping.value = false
                        _errorMessage.value = "Failed to load dumper asset: ${e.message}"
                    }
                    return@launch
                }

                val scriptHeader = """
                    const TARGET_PACKAGE = "$pkg";
                    const INJECTION_DELAY_SEC = $delaySec;
                    
                """.trimIndent()

                val scriptContent = scriptHeader + rawScript

                withContext(Dispatchers.Main) {
                    _statusText.value = "Launching ${app.label} with Frida..."
                }

                val success = fridaManager.executeScriptContent(app.packageName, scriptContent)
                if (!success) {
                    withContext(Dispatchers.Main) {
                        _isDumping.value = false
                        _errorMessage.value = "Failed to start Frida injection for ${app.packageName}"
                    }
                    return@launch
                }

                startLogPolling(context, app.packageName)

            } catch (e: Exception) {
                Log.e(TAG, "Error in startInspection", e)
                withContext(Dispatchers.Main) {
                    _isDumping.value = false
                    _errorMessage.value = "Inspection error: ${e.message}"
                }
            }
        }
    }

    private fun startLogPolling(context: Context, packageName: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val db = getDbHelper(context)
            var lastProcessedLogId = LogManager.fridaLogs.maxOfOrNull { it.id } ?: 0L
            var attempts = 0
            var finished = false

            val maxAttempts = 360

            while (isActive && attempts < maxAttempts && !finished) {
                delay(500)
                attempts++

                val currentFridaLogs = LogManager.fridaLogs
                val newEntries = currentFridaLogs.filter { it.id > lastProcessedLogId }
                if (newEntries.isNotEmpty()) {
                    lastProcessedLogId = newEntries.maxOf { it.id }

                    for (entry in newEntries) {
                        val msg = entry.message

                        // 1. Process status messages
                        if (msg.contains("[IL2CPP_STATUS]")) {
                            val status = msg.substringAfter("[IL2CPP_STATUS]").trim()
                            withContext(Dispatchers.Main) {
                                _statusText.value = "Status: $status"
                            }
                            if (status.startsWith("FINISHED") || status.startsWith("SAVED")) {
                                finished = true
                            }
                        }

                        // 2. Direct Frida RPC Stream insertion via Repository
                        if (msg.contains("IL2CPP_RPC_STREAM_BATCH")) {
                            getRepo(context).handleFridaRpcStream(packageName, msg)
                            val currentCount = getRepo(context).getClassesCount(packageName)
                            withContext(Dispatchers.Main) {
                                _dumpProgress.intValue = currentCount
                                _statusText.value = "Live Streamed $currentCount classes to SQLite..."
                                applyFilter()
                            }
                        }
                    }
                }

                if (attempts % 2 == 0) {
                    val donePaths = getPossibleDonePaths(packageName)
                    val isDone = donePaths.any { path ->
                        val checkRes = ShellExecutor.execute("[ -f $path ] && echo yes", useRoot = true)
                        checkRes.stdout.contains("yes")
                    }

                    if (isDone) {
                        finished = true
                    }
                    loadPartialOrFullDump(context, packageName, isFinal = finished)
                }
            }

            loadPartialOrFullDump(context, packageName, isFinal = true)
        }
    }

    private suspend fun loadPartialOrFullDump(context: Context, packageName: String, isFinal: Boolean = false) {
        withContext(Dispatchers.IO) {
            val db = getDbHelper(context)
            val possiblePaths = getPossibleDumpPaths(packageName)
            val tempLocalFile = File("/data/data/ox.fzer0x.snakeloader/cache/il2cpp_temp_dump.json")
            tempLocalFile.parentFile?.mkdirs()

            var copied = false
            for (path in possiblePaths) {
                val tempPath = tempLocalFile.absolutePath
                val copyCmd = "cp -f '$path' '$tempPath' 2>/dev/null && chmod 666 '$tempPath' 2>/dev/null"
                ShellExecutor.execute(copyCmd, useRoot = true)

                if (tempLocalFile.exists() && tempLocalFile.length() > 5) {
                    copied = true
                    Log.d(TAG, "Copied dump file from $path (${tempLocalFile.length()} bytes)")
                    break
                }
            }

            if (copied && tempLocalFile.exists() && tempLocalFile.length() > 5) {
                try {
                    val fileLength = tempLocalFile.length()
                    Log.d(TAG, "Streaming JSON dump file into SQLite DB from ${tempLocalFile.absolutePath} (${fileLength / 1024 / 1024} MB)...")

                    val batch = mutableListOf<Il2CppClassData>()
                    var parseCount = 0

                    try {
                        FileReader(tempLocalFile).use { fileReader ->
                            JsonReader(fileReader).use { jsonReader ->
                                @Suppress("DEPRECATION")
                                jsonReader.isLenient = true
                                jsonReader.beginArray()
                                while (jsonReader.hasNext()) {
                                    try {
                                        val classObj = gson.fromJson<Il2CppClassData>(jsonReader, Il2CppClassData::class.java)
                                        if (classObj != null && (classObj.name.isNotBlank() || classObj.fullName.isNotBlank())) {
                                            batch.add(classObj)
                                            parseCount++
                                            if (batch.size >= 500) {
                                                db.insertClassesBatch(packageName, batch)
                                                batch.clear()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Skipping class item in JsonReader: ${e.message}")
                                        try { jsonReader.skipValue() } catch (ex: Exception) {}
                                    }
                                }
                                try { jsonReader.endArray() } catch (e: Exception) {}
                            }
                        }
                    } catch (readErr: Exception) {
                        Log.w(TAG, "JsonReader stream finished/interrupted: ${readErr.message}")
                    } finally {
                        if (batch.isNotEmpty()) {
                            db.insertClassesBatch(packageName, batch)
                            batch.clear()
                        }
                    }

                    val currentDbCount = db.getClassesCount(packageName)
                    Log.d(TAG, "Successfully indexed $currentDbCount classes into SQLite DB! (Parsed: $parseCount)")

                    if (currentDbCount > 0) {
                        withContext(Dispatchers.Main) {
                            _dumpProgress.intValue = currentDbCount
                            if (isFinal) {
                                _statusText.value = "Done! Indexed $currentDbCount classes in SQLite DB."
                                _isDumping.value = false
                                _errorMessage.value = null
                            } else {
                                _statusText.value = "Indexed $currentDbCount classes so far..."
                            }
                            applyFilter()
                        }

                        if (isFinal) {
                            // CLEANUP 39MB JSON files to save storage space!
                            try {
                                if (tempLocalFile.exists()) tempLocalFile.delete()
                                ShellExecutor.execute("rm -f /data/data/$packageName/cache/il2cpp_* /data/local/tmp/il2cpp_*", useRoot = true)
                                Log.d(TAG, "Cleaned up temporary 39MB JSON files after SQLite indexing.")
                            } catch (e: Exception) {
                                Log.w(TAG, "Cleanup temp files error", e)
                            }
                        }

                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming JSON import to SQLite failed", e)
                }
            }

            val dbCount = db.getClassesCount(packageName)
            if (dbCount > 0) {
                withContext(Dispatchers.Main) {
                    _dumpProgress.intValue = dbCount
                    if (isFinal) {
                        _statusText.value = "Done! Loaded $dbCount classes from SQLite DB."
                        _isDumping.value = false
                        _errorMessage.value = null
                    } else {
                        _statusText.value = "Indexed $dbCount classes so far..."
                    }
                    applyFilter()
                }
            } else if (isFinal) {
                withContext(Dispatchers.Main) {
                    _isDumping.value = false
                    _errorMessage.value = "No dump file found or DB is empty. (Ensure target app is running with libil2cpp.so loaded)"
                    _statusText.value = "No dump file found."
                }
            }
        }
    }

    fun loadSavedDump(context: Context? = null) {
        val app = _selectedApp.value
        if (app == null) {
            _errorMessage.value = "Please select a target application first!"
            return
        }

        val appPkg = app.packageName
        val db = context?.let { getDbHelper(it) } ?: dbHelper
        if (db == null) {
            _errorMessage.value = "Database helper not initialized"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _isDumping.value = true
                _statusText.value = "Checking SQLite Database for ${app.label}..."
                _errorMessage.value = null
            }

            val countInDb = db.getClassesCount(appPkg)
            if (countInDb > 0) {
                withContext(Dispatchers.Main) {
                    _dumpProgress.intValue = countInDb
                    _statusText.value = "Loaded $countInDb classes from SQLite Database!"
                    _isDumping.value = false
                    _errorMessage.value = null
                    applyFilter()
                }
            } else if (context != null) {
                withContext(Dispatchers.Main) {
                    _statusText.value = "No DB data. Searching for dump file on device..."
                }
                loadPartialOrFullDump(context, appPkg, isFinal = true)
            } else {
                withContext(Dispatchers.Main) {
                    _isDumping.value = false
                    _statusText.value = "No saved SQLite dump found."
                    _errorMessage.value = "No saved dump found in SQLite DB or device storage."
                }
            }
        }
    }

    private fun applyFilter() {
        val appPkg = _selectedApp.value?.packageName ?: return
        val db = dbHelper ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val query = _searchText.value
            val filter = _selectedFilter.value

            val filtered = db.queryClasses(appPkg, query, filter, limit = 1000)
            val totalCount = db.getClassesCount(appPkg, query, filter)
            val allClasses = if (_classes.value.isEmpty() || _classes.value.size != totalCount) {
                db.queryClasses(appPkg, "", Il2CppFilter.ALL, limit = 5000)
            } else {
                _classes.value
            }

            withContext(Dispatchers.Main) {
                _totalMatchesCount.intValue = totalCount
                _filteredClasses.value = filtered
                if (allClasses.isNotEmpty()) {
                    _classes.value = allClasses
                }
            }
        }
    }

    fun generateHookCode(klass: Il2CppClassData, method: Il2CppMethodData) {
        val snippet = """
            // Frida Hook for ${klass.fullName}::${method.name}
            // Return Type: ${method.returnType}
            // Parameters: ${if (method.params.isEmpty()) "None" else method.params.joinToString(", ")}
            // Relative Offset: ${method.offset} | Absolute Pointer: ${method.pointer}

            const il2cppBase = Module.findBaseAddress("libil2cpp.so");
            if (!il2cppBase) {
                console.log("[-] Error: libil2cpp.so not found in memory!");
            } else {
                const relativeOffset = "${method.offset}";
                const absolutePtrStr = "${method.pointer}";

                let targetAddress = null;
                if (relativeOffset && relativeOffset !== "0x0" && relativeOffset !== "0x00") {
                    targetAddress = il2cppBase.add(ptr(relativeOffset));
                } else if (absolutePtrStr && absolutePtrStr !== "0x0" && absolutePtrStr !== "0x00") {
                    targetAddress = ptr(absolutePtrStr);
                }

                if (!targetAddress) {
                    console.log("[-] Invalid target address/offset for ${method.name}");
                } else {
                    console.log("[+] Hooking ${klass.name}::${method.name} at " + targetAddress);

                    Interceptor.attach(targetAddress, {
                        onEnter: function (args) {
                            console.log("[+] Intercepted ${klass.name}::${method.name}");
                            console.log("    this (args[0]): " + args[0]);
                            ${method.params.mapIndexed { idx, p -> "console.log('    arg${idx + 1} ($p): ' + args[${idx + 1}]);" }.joinToString("\n                            ")}
                        },
                        onLeave: function (retval) {
                            console.log("[+] ${method.name} returned: " + retval);
                        }
                    });
                }
            }
        """.trimIndent()

        _generatedCodeDialog.value = Pair("Hook: ${klass.name}::${method.name}", snippet)
    }

    fun generateClassHookCode(klass: Il2CppClassData) {
        val validMethods = klass.methods.filter { it.offset != "0x0" && it.offset != "0x00" && it.offset.isNotBlank() }
        val sb = StringBuilder()
        sb.append("// ReShift Frida Hook Suite for Class: ${klass.fullName}\n")
        sb.append("// Assembly: ${klass.assembly} | Total Methods: ${klass.methods.size} | Hookable Offsets: ${validMethods.size}\n\n")
        sb.append("const il2cppBase = Module.findBaseAddress('libil2cpp.so');\n")
        sb.append("if (!il2cppBase) {\n")
        sb.append("    console.error('[-] libil2cpp.so not loaded in process!');\n")
        sb.append("} else {\n")
        sb.append("    console.log('[+] Initializing Class Hooks for ${klass.name}...');\n\n")

        validMethods.forEachIndexed { index, method ->
            sb.append("    // [Method $index] ${method.returnType} ${method.name}(${method.params.joinToString(", ")})\n")
            sb.append("    try {\n")
            sb.append("        const target_${index} = il2cppBase.add(ptr('${method.offset}'));\n")
            sb.append("        Interceptor.attach(target_${index}, {\n")
            sb.append("            onEnter(args) {\n")
            sb.append("                console.log('[+] [${klass.name}] ${method.name}() called');\n")
            sb.append("            },\n")
            sb.append("            onLeave(retval) {\n")
            sb.append("                // console.log('    Return: ' + retval);\n")
            sb.append("            }\n")
            sb.append("        });\n")
            sb.append("    } catch(err) {\n")
            sb.append("        console.error('[-] Failed to hook ${method.name} at ${method.offset}: ' + err);\n")
            sb.append("    }\n\n")
        }
        sb.append("}\n")

        _generatedCodeDialog.value = Pair("Class Hook: ${klass.name} (${validMethods.size} methods)", sb.toString())
    }

    fun generateCSharpPseudocode(klass: Il2CppClassData): String {
        val sb = StringBuilder()
        sb.append("// Assembly: ").append(klass.assembly).append("\n")
        if (klass.size > 0) sb.append("// Memory Size: ").append(klass.size).append(" Bytes\n")
        if (klass.namespace.isNotEmpty()) {
            sb.append("namespace ").append(klass.namespace).append(" {\n")
        }

        val typeKind = when {
            klass.isEnum -> "enum"
            klass.isInterface -> "interface"
            klass.isValueType -> "struct"
            else -> "class"
        }

        val parentStr = if (klass.parent.isNotEmpty()) " : ${klass.parent}" else ""
        sb.append("    public ").append(typeKind).append(" ").append(klass.name).append(parentStr).append(" {\n")

        if (klass.fields.isNotEmpty()) {
            sb.append("        // === FIELDS ===\n")
            for (field in klass.fields) {
                sb.append("        // Offset: ").append(field.offset).append("\n")
                sb.append("        public ").append(field.type).append(" ").append(field.name).append(";\n")
            }
            sb.append("\n")
        }

        if (klass.properties.isNotEmpty()) {
            sb.append("        // === PROPERTIES ===\n")
            for (prop in klass.properties) {
                sb.append("        public ").append(prop.type).append(" ").append(prop.name).append(" { get; set; }\n")
            }
            sb.append("\n")
        }

        if (klass.methods.isNotEmpty()) {
            sb.append("        // === METHODS ===\n")
            for (method in klass.methods) {
                sb.append("        // RVA: ").append(method.offset).append(" | Pointer: ").append(method.pointer).append("\n")
                val paramsStr = method.params.joinToString(", ")
                sb.append("        public ").append(method.returnType).append(" ").append(method.name).append("(").append(paramsStr).append(");\n")
            }
        }

        sb.append("    }\n")
        if (klass.namespace.isNotEmpty()) {
            sb.append("}\n")
        }
        return sb.toString()
    }

    fun exportDumpAsJson(context: Context): String? {
        val appPkg = _selectedApp.value?.packageName ?: return null
        val db = getDbHelper(context)
        return try {
            val allClasses = db.queryClasses(appPkg, limit = 100000)
            if (allClasses.isEmpty()) return null
            val file = File(context.getExternalFilesDir(null), "il2cpp_${appPkg}_dump.json")
            file.writeText(gson.toJson(allClasses))
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export JSON error", e)
            null
        }
    }

    fun exportDumpAsCSharp(context: Context): String? {
        val appPkg = _selectedApp.value?.packageName ?: return null
        val db = getDbHelper(context)
        return try {
            val allClasses = db.queryClasses(appPkg, limit = 100000)
            if (allClasses.isEmpty()) return null
            val sb = StringBuilder()
            sb.append("// IL2CPP C# Header Dump generated by ReShift PowerUser Toolbox\n\n")

            for (klass in allClasses) {
                if (klass.namespace.isNotEmpty()) {
                    sb.append("namespace ").append(klass.namespace).append(" {\n")
                }

                val typeKind = when {
                    klass.isEnum -> "enum"
                    klass.isInterface -> "interface"
                    klass.isValueType -> "struct"
                    else -> "class"
                }

                val parentStr = if (klass.parent.isNotEmpty()) " : ${klass.parent}" else ""
                sb.append("    public ").append(typeKind).append(" ").append(klass.name).append(parentStr).append(" {\n")

                for (field in klass.fields) {
                    sb.append("        // Offset: ").append(field.offset).append("\n")
                    sb.append("        public ").append(field.type).append(" ").append(field.name).append(";\n")
                }

                if (klass.fields.isNotEmpty() && klass.methods.isNotEmpty()) sb.append("\n")

                for (method in klass.methods) {
                    sb.append("        // RVA: ").append(method.offset).append(" | Pointer: ").append(method.pointer).append("\n")
                    val paramsStr = method.params.joinToString(", ")
                    sb.append("        public ").append(method.returnType).append(" ").append(method.name).append("(").append(paramsStr).append(");\n")
                }

                sb.append("    }\n")
                if (klass.namespace.isNotEmpty()) sb.append("}\n")
                sb.append("\n")
            }

            val file = File(context.getExternalFilesDir(null), "il2cpp_${appPkg}_dump.cs")
            file.writeText(sb.toString())
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export C# error", e)
            null
        }
    }

    fun saveGeneratedScriptAsJs(context: Context, scriptCode: String): String? {
        if (scriptCode.isBlank()) return null
        return try {
            val appPkg = _selectedApp.value?.packageName ?: "app"
            val fileName = "il2cpp_ai_${appPkg}_hook.js"
            val file = File(context.getExternalFilesDir(null), fileName)
            file.writeText(scriptCode)

            scriptManager.initialize()

            val downloadedScript = DownloadedScript(
                id = "ai/${System.currentTimeMillis()}/$fileName",
                name = "IL2CPP AI Patch ($appPkg)",
                content = scriptCode,
                repository = "IL2CPP AI Generator",
                path = file.absolutePath,
                description = "AI-generated Frida hook for $appPkg",
                isEnabled = true
            )
            scriptManager.addScript(downloadedScript)
            scriptManager.assignScriptToApp(appPkg, downloadedScript.id)

            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Save JS file error", e)
            null
        }
    }

    fun shareGeneratedScript(context: Context, scriptCode: String) {
        if (scriptCode.isBlank()) return
        try {
            val cacheFile = File(context.cacheDir, "shared_frida_script.js")
            cacheFile.writeText(scriptCode)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                cacheFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Frida JS Script")
                putExtra(Intent.EXTRA_TEXT, scriptCode)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Frida JS Script"))
        } catch (e: Exception) {
            Log.e(TAG, "Share script error", e)
        }
    }
}
