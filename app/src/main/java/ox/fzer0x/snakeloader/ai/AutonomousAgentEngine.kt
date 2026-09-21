package ox.fzer0x.snakeloader.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.LogManager
import ox.fzer0x.snakeloader.LogcatReader
import ox.fzer0x.snakeloader.db.Il2CppDatabaseHelper
import ox.fzer0x.snakeloader.ui.viewmodels.AgentLogStep
import ox.fzer0x.snakeloader.ui.viewmodels.AutonomousAgentState
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppMethodData
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UserEvaluationOutcome(
    val goalAchieved: Boolean,
    val refinedPrompt: String? = null
)

data class MonitoringResult(
    val error: String? = null,
    val hasSuccessLog: Boolean = false,
    val successLogMessage: String? = null,
    val fullConsoleLogs: String = "",
    val logcatLogs: String = ""
)

enum class AgentExecutionMode(val label: String) {
    AUTO("Auto Select (Smart Spawn/Attach)"),
    SPAWN("Spawn Process (-f)"),
    ATTACH("Attach to Process (-n)")
}

class AutonomousAgentEngine(
    private val fridaManager: FridaManager,
    private val adaptiveLlmRouter: AdaptiveLlmRouter,
    private val aiAssistant: Il2CppAiAssistant
) {

    companion object {
        private const val TAG = "AutonomousAgentEngine"
        var activeEngineInstance: WeakReference<AutonomousAgentEngine>? = null
    }

    private var agentJob: Job? = null

    private val _agentState = MutableStateFlow(AutonomousAgentState.IDLE)
    val agentState: StateFlow<AutonomousAgentState> = _agentState.asStateFlow()

    private val _agentLogs = MutableStateFlow<List<AgentLogStep>>(emptyList())
    val agentLogs: StateFlow<List<AgentLogStep>> = _agentLogs.asStateFlow()

    private val _currentIteration = MutableStateFlow(0)
    val currentIteration: StateFlow<Int> = _currentIteration.asStateFlow()

    private val _validatedScript = MutableStateFlow<String?>(null)
    val validatedScript: StateFlow<String?> = _validatedScript.asStateFlow()

    private val _streamingScript = MutableStateFlow<String?>(null)
    val streamingScript: StateFlow<String?> = _streamingScript.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _executionMode = MutableStateFlow(AgentExecutionMode.AUTO)
    val executionMode: StateFlow<AgentExecutionMode> = _executionMode.asStateFlow()

    private val _lastConsoleOutput = MutableStateFlow("")
    val lastConsoleOutput: StateFlow<String> = _lastConsoleOutput.asStateFlow()

    private val _lastSqlAstContext = MutableStateFlow("")
    val lastSqlAstContext: StateFlow<String> = _lastSqlAstContext.asStateFlow()

    private val _lastRepairDiagnosis = MutableStateFlow("")
    val lastRepairDiagnosis: StateFlow<String> = _lastRepairDiagnosis.asStateFlow()

    private var extensionTimeMs = 0L
    private var userEvaluationDeferred: CompletableDeferred<UserEvaluationOutcome>? = null

    fun setExecutionMode(mode: AgentExecutionMode) {
        _executionMode.value = mode
    }

    fun extendMonitoringWindow(additionalMs: Long = 15000L) {
        extensionTimeMs += additionalMs
        addLogStep("MONITOR", "Monitoring window extended by user (+${additionalMs / 1000}s). Please interact with target app...")
    }

    fun submitUserEvaluation(goalAchieved: Boolean, refinedPrompt: String? = null) {
        userEvaluationDeferred?.complete(UserEvaluationOutcome(goalAchieved, refinedPrompt))
    }

    fun reset() {
        agentJob?.cancel()
        userEvaluationDeferred?.cancel()
        userEvaluationDeferred = null
        fridaManager.activeAgentEngine = null
        activeEngineInstance = null
        extensionTimeMs = 0L
        _isRunning.value = false
        _agentState.value = AutonomousAgentState.IDLE
        _currentIteration.value = 0
        _validatedScript.value = null
        _streamingScript.value = null
        _agentLogs.value = emptyList()
        _lastConsoleOutput.value = ""
        _lastSqlAstContext.value = ""
        _lastRepairDiagnosis.value = ""
    }

    fun abort() {
        agentJob?.cancel()
        userEvaluationDeferred?.cancel()
        userEvaluationDeferred = null
        fridaManager.activeAgentEngine = null
        activeEngineInstance = null
        _isRunning.value = false
        _agentState.value = AutonomousAgentState.ABORTED
        addLogStep("ABORT", "Autonomous AI Agent execution aborted by user.")
    }

    fun startLoop(
        context: Context,
        scope: CoroutineScope,
        targetPackage: String,
        userInstruction: String,
        targetClass: Il2CppClassData?,
        targetMethod: Il2CppMethodData?,
        allClasses: List<Il2CppClassData>,
        maxIterations: Int,
        cloudValidationEnabled: Boolean,
        injectionDelaySeconds: Int = 0,
        selectedProvider: String = "GEMINI",
        mode: AgentExecutionMode = AgentExecutionMode.AUTO,
        dbHelper: Il2CppDatabaseHelper? = null,
        onTokenProgress: ((Int, String) -> Unit)? = null,
        onScriptSaved: ((String) -> Unit)? = null
    ) {
        reset()
        _executionMode.value = mode
        fridaManager.activeAgentEngine = this
        activeEngineInstance = WeakReference(this)
        _isRunning.value = true
        _agentState.value = AutonomousAgentState.GENERATING_INITIAL

        try {
            LogcatReader.start()
        } catch (e: Exception) {
            Log.w(TAG, "LogcatReader start warning: ${e.message}")
        }

        agentJob = scope.launch(Dispatchers.IO) {
            runAutonomousPipeline(
                context = context,
                targetPackage = targetPackage,
                userInstruction = userInstruction,
                targetClass = targetClass,
                targetMethod = targetMethod,
                allClasses = allClasses,
                maxIterations = maxIterations,
                cloudValidationEnabled = cloudValidationEnabled,
                injectionDelaySeconds = injectionDelaySeconds,
                selectedProvider = selectedProvider,
                dbHelper = dbHelper,
                onTokenProgress = onTokenProgress,
                onScriptSaved = onScriptSaved
            )
        }
    }

    private suspend fun runAutonomousPipeline(
        context: Context,
        targetPackage: String,
        userInstruction: String,
        targetClass: Il2CppClassData?,
        targetMethod: Il2CppMethodData?,
        allClasses: List<Il2CppClassData>,
        maxIterations: Int,
        cloudValidationEnabled: Boolean,
        injectionDelaySeconds: Int,
        selectedProvider: String,
        dbHelper: Il2CppDatabaseHelper?,
        onTokenProgress: ((Int, String) -> Unit)?,
        onScriptSaved: ((String) -> Unit)?
    ) {
        val modelLabel = when (selectedProvider) {
            "GEMINI" -> "Gemini Cloud API"
            "OLLAMA" -> "Local Ollama Engine"
            else -> "On-Device Qwen GGUF Engine"
        }

        // Deep symbol search in sql.db if allClasses list is empty
        val effectiveClasses = if (allClasses.isEmpty() && dbHelper != null && dbHelper.hasSavedDump(targetPackage)) {
            val (initialKeywords, initialOffsets) = extractDeepSearchKeywordsAndOffsets(userInstruction)
            val queried = dbHelper.searchSymbolsDeep(targetPackage, initialKeywords, initialOffsets, limit = 35)
            addLogStep("SQL_DB", "Queried sql.db (il2cpp_dumper.db) for '$targetPackage' matching keywords [${initialKeywords.take(5).joinToString(", ")}]: Found ${queried.size} AST classes.")
            queried
        } else {
            allClasses
        }

        val formattedAstInitial = formatSqlAstContext(effectiveClasses)
        _lastSqlAstContext.value = formattedAstInitial

        val classContextSummary = if (targetClass != null && targetMethod != null) {
            "Target: ${targetClass.fullName}::${targetMethod.name} at offset ${targetMethod.offset}"
        } else {
            "Target Classes: ${effectiveClasses.size} extracted C# Metadata AST"
        }
        addLogStep("INIT", "Initializing Autonomous Agent ($modelLabel) for target package: $targetPackage. Mode: ${_executionMode.value.label}. Context: $classContextSummary")

        // STEP 1: REAL-TIME STREAMING CANDIDATE GENERATION
        withContext(Dispatchers.Main) {
            _agentState.value = AutonomousAgentState.GENERATING_INITIAL
        }
        addLogStep("GENERATE", "Requesting real-time streaming candidate Frida script from $modelLabel...")

        val initialPrompt = if (targetClass != null && targetMethod != null) {
            aiAssistant.buildPrompt(targetPackage, targetClass, targetMethod, AiGoalPreset.CUSTOM, userInstruction)
        } else {
            aiAssistant.buildGlobalPrompt(targetPackage, effectiveClasses, userInstruction)
        }

        val genResult = if (selectedProvider == "ON_DEVICE_GGUF") {
            adaptiveLlmRouter.executeQwenForScriptGeneration(
                context = context,
                prompt = initialPrompt,
                systemInstruction = Il2CppAiAssistant.SYSTEM_INSTRUCTION,
                onProgress = { tokens, text ->
                    _streamingScript.value = text
                    onTokenProgress?.invoke(tokens, text)
                }
            )
        } else {
            adaptiveLlmRouter.routeAndExecute(
                context = context,
                prompt = initialPrompt,
                systemInstruction = Il2CppAiAssistant.SYSTEM_INSTRUCTION,
                selectedProviderSetting = selectedProvider,
                onProgress = { tokens, text ->
                    _streamingScript.value = text
                    onTokenProgress?.invoke(tokens, text)
                }
            )
        }

        if (genResult.isFailure) {
            val err = genResult.exceptionOrNull()?.message ?: "Script generation failed"
            addLogStep("ERROR", "Script generation via $modelLabel failed: $err")
            withContext(Dispatchers.Main) {
                _agentState.value = AutonomousAgentState.FAILED_MAX_RETRIES
                _isRunning.value = false
            }
            return
        }

        var currentScript = aiAssistant.extractJsCode(genResult.getOrThrow())

        // Post-validation: If target is CSR2 or budget request, auto-upgrade template architecture
        if (targetPackage.contains("csr", ignoreCase = true) || userInstruction.contains("50", ignoreCase = true) || userInstruction.contains("budget", ignoreCase = true)) {
            if (!currentScript.contains("initIl2cppApi") || !currentScript.contains("applySmartPatch")) {
                addLogStep("UPGRADE", "Auto-upgrading Frida script to Senior Pro Mod Architecture for $targetPackage...")
                currentScript = aiAssistant.getSeniorTemplateScript(currentScript, userInstruction, targetPackage)
            }
        }

        _streamingScript.value = currentScript
        addLogStep("GENERATE", "Candidate script generated by $modelLabel successfully.", currentScript)

        // STEP 2: CLOUD PRE-VALIDATION STATIC ANALYSIS
        if (cloudValidationEnabled) {
            withContext(Dispatchers.Main) {
                _agentState.value = AutonomousAgentState.PRE_VALIDATING
            }
            addLogStep("VALIDATE", "Gemini Cloud performing static analysis & pointer guard validation...")

            val cloudValPrompt = aiAssistant.buildCloudValidationPrompt(currentScript, targetPackage)
            val valResult = adaptiveLlmRouter.routeAndExecute(
                context = context,
                prompt = cloudValPrompt,
                systemInstruction = Il2CppAiAssistant.SYSTEM_INSTRUCTION,
                selectedProviderSetting = "GEMINI",
                onProgress = { tokens, text ->
                    _streamingScript.value = text
                    onTokenProgress?.invoke(tokens, text)
                }
            )

            if (valResult.isSuccess) {
                currentScript = aiAssistant.extractJsCode(valResult.getOrThrow())
                _streamingScript.value = currentScript
                addLogStep("VALIDATE", "Gemini Cloud static analysis completed cleanly.", currentScript)
            } else {
                addLogStep("VALIDATE", "Cloud pre-validation skipped due to network, continuing with candidate.")
            }
        }

        // STEP 3: CLOSED-LOOP INJECTION, DUAL-STREAM MONITORING & SENIOR AUTO-REPAIR
        var iteration = 1
        while (_isRunning.value) {
            if (maxIterations in 1 until iteration) {
                withContext(Dispatchers.Main) {
                    _agentState.value = AutonomousAgentState.FAILED_MAX_RETRIES
                    _isRunning.value = false
                }
                addLogStep("ERROR", "Maximum number of attempts ($maxIterations) reached. Loop stopped.")
                break
            }

            withContext(Dispatchers.Main) {
                _currentIteration.value = iteration
                _agentState.value = AutonomousAgentState.INJECTING
            }

            val iterTag = if (maxIterations > 0) "#$iteration / $maxIterations" else "#$iteration (Unlimited)"

            var scriptToInject = currentScript
            if (injectionDelaySeconds > 0) {
                val delayMs = injectionDelaySeconds * 1000
                addLogStep("TIMER", "[Iteration $iterTag] Delayed Injection Active: Delaying hook activation for $injectionDelaySeconds second(s)...")
                scriptToInject = """
console.log("[AGENT_STATUS] SCRIPT_LOADED: Frida injected. Waiting ${injectionDelaySeconds}s timer before hooking...");
setTimeout(function() {
    console.log("[TIMER] Injection timer expired (${injectionDelaySeconds}s). Activating IL2CPP hooks now...");
    $currentScript
}, $delayMs);
                """.trimIndent()
            }

            // Determine execution strategy: SPAWN (-f) or ATTACH (-n)
            val activeMode = _executionMode.value
            val useAttach = when (activeMode) {
                AgentExecutionMode.ATTACH -> true
                AgentExecutionMode.SPAWN -> false
                AgentExecutionMode.AUTO -> (iteration > 1) // Switch to attach if retry occurs after initial spawn
            }

            val modeName = if (useAttach) "ATTACH (-n)" else "SPAWN (-f)"
            addLogStep("INJECT", "[Iteration $iterTag] Mode $modeName: Injecting Frida script into $targetPackage...")

            val startFridaLogId = LogManager.fridaLogs.maxOfOrNull { it.id } ?: 0L
            val startLogcatId = LogManager.logcatEntries.maxOfOrNull { it.id } ?: 0L

            val attachSuccess = if (useAttach) {
                fridaManager.attachToRunningProcess(targetPackage, scriptToInject)
            } else {
                fridaManager.executeScriptContent(targetPackage, scriptToInject)
            }

            if (!attachSuccess) {
                addLogStep("WARN", "[Iteration $iterTag] $modeName injection failed. Attempting fallback mode...")
                val fallbackSuccess = if (useAttach) {
                    fridaManager.executeScriptContent(targetPackage, scriptToInject)
                } else {
                    fridaManager.attachToRunningProcess(targetPackage, scriptToInject)
                }
                if (!fallbackSuccess) {
                    addLogStep("ERROR", "[Iteration $iterTag] Both SPAWN and ATTACH injection attempts failed.")
                }
            } else {
                addLogStep("INJECT", "[Iteration $iterTag] Injection successful via $modeName.")
            }

            // MONITORING PHASE (Dynamic observation window with Dual Stream Log Fusion)
            withContext(Dispatchers.Main) {
                _agentState.value = AutonomousAgentState.TESTING_AND_MONITORING
            }
            addLogStep("MONITOR", "[Iteration $iterTag] Monitoring live Frida console & Logcat streams for `[AGENT_STATUS] SUCCESS` (15s window). Please interact with target app...")

            val monitorRes = monitorDualStreamLogs(startFridaLogId, startLogcatId, durationMs = 15000)

            _lastConsoleOutput.value = monitorRes.fullConsoleLogs
            val consoleLineCount = monitorRes.fullConsoleLogs.lines().filter { it.isNotBlank() }.size
            addLogStep("CONSOLE", "[Iteration $iterTag] Captured $consoleLineCount lines of Frida Console Output for AI Agent analysis.")

            // AUTO-RECOVERY CLEANUP ON SESSION DETACH / PROCESS CRASH
            if (monitorRes.error != null && (monitorRes.error.contains("failed", ignoreCase = true) || monitorRes.error.contains("crashed", ignoreCase = true) || monitorRes.error.contains("SIGSEGV", ignoreCase = true))) {
                addLogStep("RECOVERY", "Session crash detected (${monitorRes.error}). Cleaning up Frida session...")
                fridaManager.stopScript(keepServer = true)
            }

            // INTERACTIVE USER EVALUATION STEP (YES / NO + OPTIONAL REFINED PROMPT)
            withContext(Dispatchers.Main) {
                _agentState.value = AutonomousAgentState.WAITING_USER_EVALUATION
            }
            addLogStep("EVALUATE", "[Attempt $iterTag] Injection completed. Waiting for developer evaluation: Was your goal in the game achieved?")

            val evalDeferred = CompletableDeferred<UserEvaluationOutcome>()
            userEvaluationDeferred = evalDeferred

            val userOutcome = evalDeferred.await()
            userEvaluationDeferred = null

            if (userOutcome.goalAchieved) {
                withContext(Dispatchers.Main) {
                    _agentState.value = AutonomousAgentState.SUCCESS_VALIDATED
                    _validatedScript.value = currentScript
                    _streamingScript.value = currentScript
                    _isRunning.value = false
                }
                addLogStep("SUCCESS", "[SUCCESS] Script confirmed & validated as successful by developer!", currentScript)
                onScriptSaved?.invoke(currentScript)
                break
            } else {
                addLogStep("WARN", "[Attempt $iterTag] Developer feedback: Game goal not yet achieved.")

                if (maxIterations in 1..iteration) {
                    withContext(Dispatchers.Main) {
                        _agentState.value = AutonomousAgentState.FAILED_MAX_RETRIES
                        _isRunning.value = false
                    }
                    addLogStep("ERROR", "Maximum number of attempts ($maxIterations) reached. Loop stopped.")
                    break
                }

                // SENIOR REPAIR WITH USER REFINED PROMPT, FULL CONSOLE OUTPUT & DYNAMIC SQL DB SYMBOL SEARCH
                withContext(Dispatchers.Main) {
                    _agentState.value = AutonomousAgentState.CORRECTING_SCRIPT
                }

                // Extract keywords AND hex offsets from instruction, feedback, error, console output, and original script
                val (repairKeywords, repairOffsets) = extractDeepSearchKeywordsAndOffsets(
                    userInstruction,
                    userOutcome.refinedPrompt,
                    monitorRes.error,
                    monitorRes.fullConsoleLogs,
                    currentScript
                )

                // Query sql.db for matching C# classes, methods, and field offsets
                val sqlAstClasses = if (dbHelper != null && dbHelper.hasSavedDump(targetPackage)) {
                    val dbResults = dbHelper.searchSymbolsDeep(targetPackage, repairKeywords, repairOffsets, limit = 25)
                    addLogStep("SQL_DB", "[Attempt #$iteration] Queried sql.db (il2cpp_dumper.db) for symbols [${repairKeywords.take(4).joinToString(", ")}] & offsets [${repairOffsets.take(3).joinToString(", ")}] -> Loaded ${dbResults.size} AST classes.")
                    dbResults
                } else {
                    effectiveClasses
                }

                val sqlAstContext = formatSqlAstContext(sqlAstClasses)
                _lastSqlAstContext.value = sqlAstContext

                val feedbackSummary = if (!userOutcome.refinedPrompt.isNullOrBlank()) {
                    "DEVELOPER FEEDBACK: ${userOutcome.refinedPrompt}"
                } else {
                    "Script injected, but game value/budget has not changed in app UI."
                }

                val diagnosisSummary = "Attempt #$iteration Analysis: ${monitorRes.error ?: "Hook executed without altering game state"}. Repairing script via $modelLabel..."
                _lastRepairDiagnosis.value = diagnosisSummary
                addLogStep("FIX", "[Attempt #$iteration -> #${iteration + 1}] Senior AI Agent analyzing Frida Console Output & sql.db to repair script...")

                val fixPrompt = aiAssistant.buildAutonomousCorrectionPrompt(
                    originalScript = currentScript,
                    errorLog = monitorRes.error ?: feedbackSummary,
                    fullConsoleLogs = monitorRes.fullConsoleLogs,
                    logcatLogs = monitorRes.logcatLogs,
                    sqlAstContext = sqlAstContext,
                    targetPackage = targetPackage,
                    iteration = iteration,
                    maxIterations = maxIterations,
                    userFeedback = userOutcome.refinedPrompt
                )

                val fixResult = adaptiveLlmRouter.routeAndExecute(
                    context = context,
                    prompt = fixPrompt,
                    systemInstruction = Il2CppAiAssistant.SYSTEM_INSTRUCTION,
                    selectedProviderSetting = selectedProvider,
                    onProgress = { tokens, text ->
                        _streamingScript.value = text
                        onTokenProgress?.invoke(tokens, text)
                    }
                )

                if (fixResult.isSuccess) {
                    currentScript = aiAssistant.extractJsCode(fixResult.getOrThrow())
                    _streamingScript.value = currentScript
                    addLogStep("FIX", "[Attempt #$iteration] Revised Senior Frida script generated cleanly.", currentScript)
                } else {
                    val fixErr = fixResult.exceptionOrNull()?.message ?: "Revision request failed"
                    addLogStep("ERROR", "$modelLabel revision request failed: $fixErr")
                }
                iteration++
            }
        }
    }

    private suspend fun monitorDualStreamLogs(
        startFridaLogId: Long,
        startLogcatId: Long,
        durationMs: Long
    ): MonitoringResult = withContext(Dispatchers.IO) {
        var endTime = System.currentTimeMillis() + durationMs
        var detectedSuccessLog: String? = null
        var detectedError: String? = null

        while (System.currentTimeMillis() < endTime) {
            if (extensionTimeMs > 0L) {
                endTime += extensionTimeMs
                extensionTimeMs = 0L
            }

            // Stream 1: Frida JS Console Logs
            val newFridaLogs = LogManager.fridaLogs.filter { it.id > startFridaLogId }

            // Check for Exceptions / Syntax Errors / Crash Signals in Frida Console
            val fridaError = newFridaLogs.find { entry ->
                val msg = entry.message
                msg.contains("SyntaxError", ignoreCase = true) ||
                msg.contains("Unexpected token", ignoreCase = true) ||
                msg.contains("Unexpected identifier", ignoreCase = true) ||
                msg.contains("ReferenceError", ignoreCase = true) ||
                msg.contains("TypeError", ignoreCase = true) ||
                msg.contains("Access violation", ignoreCase = true) ||
                msg.contains("Null pointer", ignoreCase = true) ||
                msg.contains("Error:", ignoreCase = true) ||
                msg.contains("Exception", ignoreCase = true) ||
                msg.contains("Error in Interceptor", ignoreCase = true) ||
                msg.contains("crashed", ignoreCase = true) ||
                msg.contains("SIGSEGV", ignoreCase = true) ||
                msg.contains("cannot read property", ignoreCase = true) ||
                msg.contains("is not a function", ignoreCase = true) ||
                msg.contains("Invalid address", ignoreCase = true) ||
                msg.contains("Process terminated", ignoreCase = true)
            }
            if (fridaError != null && detectedError == null) {
                detectedError = "Frida JS Exception/SyntaxError: ${fridaError.message}"
            }

            // Check for Execution Intercept Signals
            val successEntry = newFridaLogs.find { entry ->
                val msg = entry.message
                val isInterceptSignal = msg.contains("[AGENT_STATUS] SUCCESS", ignoreCase = true) ||
                                        msg.contains("[+] Intercepted", ignoreCase = true) ||
                                        msg.contains("✓ Resolved:", ignoreCase = true) ||
                                        msg.contains("ALL MODS INJECTED", ignoreCase = true) ||
                                        msg.contains("Smart Scan complete", ignoreCase = true) ||
                                        msg.contains("[+] Hooked", ignoreCase = true) ||
                                        msg.contains("returned:", ignoreCase = true)
                val isScriptLoadSignal = msg.contains("Script Loaded", ignoreCase = true) ||
                                         msg.contains("Guard Ready", ignoreCase = true)
                isInterceptSignal && !isScriptLoadSignal
            }
            if (successEntry != null && detectedSuccessLog == null) {
                detectedSuccessLog = successEntry.message
            }

            // Stream 2: System Logcat / Native Crash Signals
            val newLogcat = LogManager.logcatEntries.filter { it.id > startLogcatId }
            val nativeCrash = newLogcat.find { entry ->
                val msg = entry.message
                msg.contains("FATAL EXCEPTION", ignoreCase = true) ||
                msg.contains("SIGSEGV", ignoreCase = true) ||
                msg.contains("SIGBUS", ignoreCase = true) ||
                (msg.contains("libil2cpp", ignoreCase = true) && msg.contains("crash", ignoreCase = true))
            }
            if (nativeCrash != null && detectedError == null) {
                detectedError = "Native Android Logcat Crash: ${nativeCrash.message}"
            }

            delay(300)
        }

        // Capture full Frida console log stream (up to 300 entries, chronologically ordered)
        val allFridaLogs = LogManager.fridaLogs.filter { it.id > startFridaLogId }
            .take(300)
            .reversed()
            .joinToString("\n") { entry ->
                val tagStr = if (!entry.tag.isNullOrBlank()) "[${entry.tag}] " else ""
                "$tagStr${entry.message}"
            }

        val allLogcatLogs = LogManager.logcatEntries.filter { it.id > startLogcatId }
            .take(100)
            .reversed()
            .joinToString("\n") { "[${it.packageName}] ${it.message}" }

        return@withContext MonitoringResult(
            error = detectedError,
            hasSuccessLog = detectedSuccessLog != null,
            successLogMessage = detectedSuccessLog,
            fullConsoleLogs = allFridaLogs,
            logcatLogs = allLogcatLogs
        )
    }

    private fun formatSqlAstContext(classes: List<Il2CppClassData>): String {
        if (classes.isEmpty()) return "No matching AST classes found in il2cpp_dumper.db"
        val sb = StringBuilder()
        sb.append("=== IL2CPP DUMPER SQL.DB METADATA AST (SEARCH RESULTS: ${classes.size} CLASSES) ===\n\n")

        for (klass in classes.take(20)) {
            sb.append("Class: ").append(klass.fullName)
            if (klass.parent.isNotBlank()) sb.append(" : ").append(klass.parent)
            sb.append(" (Assembly: ").append(klass.assembly)
            if (klass.size > 0) sb.append(", Size: ").append(klass.size).append(" bytes")
            sb.append(")\n")

            if (klass.methods.isNotEmpty()) {
                sb.append("  [Methods: ${klass.methods.size}]\n")
                for (m in klass.methods) {
                    val validOffset = if (m.offset.isNotBlank() && m.offset != "0x0" && m.offset != "0x00") m.offset else m.pointer
                    sb.append("    - ").append(m.returnType).append(" ").append(m.name)
                        .append("(").append(m.params.joinToString(", ")).append(") -> RVA OFFSET: ").append(validOffset).append("\n")
                }
            }

            if (klass.fields.isNotEmpty()) {
                sb.append("  [Fields: ${klass.fields.size}]\n")
                for (f in klass.fields) {
                    sb.append("    * ").append(f.type).append(" ").append(f.name)
                        .append(" -> FIELD OFFSET: ").append(f.offset).append("\n")
                }
            }

            if (klass.properties.isNotEmpty()) {
                sb.append("  [Properties: ${klass.properties.size}]\n")
                for (p in klass.properties) {
                    sb.append("    # Property: ").append(p.type).append(" ").append(p.name).append("\n")
                }
            }

            sb.append("\n")
        }
        return sb.toString()
    }

    private fun extractDeepSearchKeywordsAndOffsets(vararg texts: String?): Pair<List<String>, List<String>> {
        val keywords = mutableSetOf<String>()
        val offsets = mutableSetOf<String>()

        val hexRegex = Regex("0x[0-9a-fA-F]+")
        val symbolRegex = Regex("[a-zA-Z_][a-zA-Z0-9_]{2,}")

        val ignoreList = setOf(
            "the", "and", "for", "that", "this", "with", "from", "have", "will", "your",
            "script", "failed", "error", "null", "true", "false", "void", "status",
            "interrupted", "intercepted", "frida", "agent", "return", "function", "const", "var", "let"
        )

        for (text in texts) {
            if (text.isNullOrBlank()) continue

            hexRegex.findAll(text).forEach { match ->
                val off = match.value.lowercase()
                if (off != "0x0" && off != "0x00") {
                    offsets.add(off)
                }
            }

            symbolRegex.findAll(text).forEach { match ->
                val word = match.value
                val lower = word.lowercase()
                if (!ignoreList.contains(lower) && word.length >= 3) {
                    keywords.add(word)
                }
            }
        }

        return Pair(keywords.toList(), offsets.toList())
    }

    private fun addLogStep(stepType: String, message: String, codeSnippet: String? = null) {
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
        _agentLogs.value = _agentLogs.value + newStep
    }
}
