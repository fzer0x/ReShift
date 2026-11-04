package ox.fzer0x.snakeloader.ai

import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppMethodData
import java.util.Locale

enum class AiGoalPreset(val title: String, val promptTemplate: String) {
    SPOOF_RETURN("Spoof Return Value", "Hook this method and alter its return value. Return false/0 for security or ban checks, or true/9999 for success/status checks as appropriate."),
    LOCK_HEALTH("Lock Health / Value", "Hook this method or its target class fields to freeze health/currency/values to a maximum value (e.g. 9999) whenever called."),
    ARM64_HEX_PATCH("ARM/ARM64 Memory Patch", "Provide a Frida direct memory patch using Memory.protect and targetAddress.writeByteArray. Check Process.arch: for ARM64 use 'MOV W0, #0; RET' (0x00 0x00 0x80 0x52 0xC0 0x03 0x5F 0xD6) or NOPs; for ARM/Thumb 32-bit use 'MOV R0, #0; BX LR' (0x00 0x20 0x70 0x47) or NOPs."),
    TRACE_EXECUTION("Trace Execution & Log Args", "Hook this method to log 'this' pointer, all arguments with types, and the return value to console.log in detail."),
    CUSTOM("Custom Request", "")
}

class Il2CppAiAssistant {

    private val semanticRanker = AstSemanticRanker()

    companion object {
        const val SYSTEM_INSTRUCTION = """
You are an expert Senior Android Reverse Engineer and Frida Core Developer specializing in Unity IL2CPP games, Android Java/DEX runtime, and Native C/C++ (.so) hooking.
Your task is to write fully functional, complete, production-ready Frida JavaScript code (`.js`) to hook target applications.

STRICT CONSTRAINTS & POINTER SAFETY:
1. Output ONLY valid, executable JavaScript wrapped in a single ```javascript ... ``` code block. Do NOT include markdown explanations outside the code block.
2. Define `const TAG = "<TARGET_PACKAGE_NAME>";` exactly ONCE at the top.
3. For IL2CPP / Native libs, always wrap execution in `waitForModule("libil2cpp.so" / "libname.so", function(mod) { ... })` dlopen guard to prevent early-launch crashes.
4. For Java/DEX apps, wrap code in `Java.perform(function() { ... })` and check for class existence.
5. Always check pointer validity (`if (args[0] && !args[0].isNull())`) before accessing arguments or dereferencing pointers.
6. Wrap hook bodies in `try { ... } catch (err) { console.log("[-] Hook Error: " + err.message); }`.
7. DYNAMIC RPC CONTROLS: Expose `rpc.exports` functions (e.g. `rpc.exports = { getValue: ..., setValue: ... }`) when prompt requests interactive parameter modifications.
8. NEVER call `Interceptor.detachAll()` inside hooks as it wipes out active hooks.
9. Inside EVERY `Interceptor.attach` or `Java.use` hook, log `console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetIdentifier);` upon execution so ReShift can verify the hook trigger.

ASYNCHRONOUS MODULE WAITER HELPER:
Include this helper at the top of your script for native hooks:
```javascript
function waitForModule(moduleName, callback) {
    const mod = Process.findModuleByName(moduleName);
    if (mod) {
        callback(mod);
    } else {
        let listener = null;
        const customDlopen = Module.findExportByName(null, "android_dlopen_ext") || Module.findExportByName(null, "dlopen");
        if (customDlopen) {
            listener = Interceptor.attach(customDlopen, {
                onLeave(retval) {
                    const loaded = Process.findModuleByName(moduleName);
                    if (loaded) {
                        if (listener) {
                            listener.detach();
                            listener = null;
                        }
                        callback(loaded);
                    }
                }
            });
        }
    }
}
```
        """
    }

    fun buildPrompt(
        targetPackage: String,
        klass: Il2CppClassData,
        method: Il2CppMethodData,
        goalPreset: AiGoalPreset,
        customGoalText: String
    ): String {
        val goalDescription = if (goalPreset == AiGoalPreset.CUSTOM) customGoalText else goalPreset.promptTemplate
        val validOffset = if (method.offset.isNotBlank() && method.offset != "0x0" && method.offset != "0x00") method.offset else method.pointer
        val derivedTag = targetPackage.substringAfterLast(".").uppercase().replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "TARGET_APP" }

        return """
Target Package: $targetPackage
Target Tag: $derivedTag
Target Class: ${klass.fullName}
Target Method: ${method.returnType} ${method.name}(${method.params.joinToString(", ")})
Method Offset: $validOffset
User Goal: $goalDescription

Instructions:
Write a complete, production-grade Frida JavaScript script (`Interceptor.attach`) that hooks offset $validOffset.
Define `const TAG = "$derivedTag";` at the top.
Implement the full logic to fulfill the goal (e.g. log arguments, replace return values, or freeze field values).
Include `console.log("[" + TAG + "] SUCCESS: Intercepted ${method.name}");` inside `onEnter` or `onLeave`.
Do NOT leave placeholders.
        """.trimIndent()
    }

    fun buildGlobalPrompt(
        targetPackage: String,
        allClasses: List<Il2CppClassData>,
        userInstruction: String
    ): String {
        val derivedTag = targetPackage.substringAfterLast(".").uppercase().replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "TARGET_APP" }
        val rankedMethods = semanticRanker.rankAndFilterMethods(allClasses, userInstruction, maxResults = 15)

        val dumpedMethodsSummary = StringBuilder()
        for (item in rankedMethods) {
            val klass = item.klass
            val method = item.method
            dumpedMethodsSummary.append("Class: ").append(klass.fullName).append("\n")
            dumpedMethodsSummary.append("  - Method: ").append(method.returnType).append(" ").append(method.name)
                .append("(").append(method.params.joinToString(", ")).append(") -> EXACT OFFSET: ").append(method.offset).append("\n")
            for (f in klass.fields.take(3)) {
                dumpedMethodsSummary.append("    * Field: ").append(f.type).append(" ").append(f.name).append(" -> Offset: ").append(f.offset).append("\n")
            }
            dumpedMethodsSummary.append("\n")
        }

        return """
Target Package: $targetPackage
Script Logging Tag: const TAG = "$derivedTag";
User Goal: "$userInstruction"

EXACT USER INTENT & CONSTRAINTS:
1. Strict Fidelity Constraint: Fulfill ONLY and EXACTLY the user's goal ("$userInstruction").
2. Do NOT add unrequested modifications, generic cheat features, or unrelated hooks unless explicitly specified in the user goal.

ACTUAL RANKED C# METHODS & REAL NON-ZERO OFFSETS IN MEMORY:
$dumpedMethodsSummary

Instructions:
Write a complete, ready-to-run Frida JavaScript script (`Interceptor.attach`) hooking the EXACT game methods listed above to satisfy "$userInstruction".

HOW TO MODIFY CURRENCY / BUDGET / NUMERIC VALUES:
1. For getter methods (e.g. `get_Coins`, `get_Money`, `get_Budget`, `GetBalance`), replace return value in `onLeave`:
```javascript
onLeave(retval) {
    try {
        retval.replace(50000000);
    } catch (e) {
        try { this.context.x0 = ptr("50000000"); } catch (e2) {}
    }
    console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);
}
```
2. For setter / adder methods (e.g. `set_Money(int val)`, `AddCoins(int amount)`), overwrite parameter `args[1]` in `onEnter`:
```javascript
onEnter(args) {
    try {
        args[1] = ptr("50000000");
    } catch (e) {}
    console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);
}
```
3. FOR MANAGED FIELDS:
When intercepting methods that take `this` (in `args[0]`), update field values directly in instance memory:
```javascript
onEnter(args) {
    if (args[0] && !args[0].isNull()) {
        try {
            args[0].add(0x28).writeInt(50000000);
        } catch (e) {}
    }
    console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);
}
```

Write out EVERY `Interceptor.attach` block completely with full logic.
In EVERY `Interceptor.attach` block, include `console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);` inside `onEnter` or `onLeave`.
Do NOT use comments like `// Your code here` or leave function bodies empty.
Start IMMEDIATELY with ```javascript ... ```
        """.trimIndent()
    }

    fun buildCloudValidationPrompt(
        rawScript: String,
        targetPackage: String
    ): String {
        return """
You are an AI Frida Code Validator.
Perform static analysis on the following Frida JavaScript script for target package "$targetPackage".

CRITICAL AUDIT CHECKLIST:
1. Ensure `libil2cpp.so` module base address check is performed before hooking.
2. Ensure every pointer dereference (`args[0]`, `ptr.readInt()`, `ptr.readUtf8String()`) is wrapped with `if (ptr && !ptr.isNull())` or `try/catch`.
3. Verify `Interceptor.attach` arguments and offsets are syntactically valid pointers (ARM64/ARM32, do NOT use dummy offsets like '0x1000' or '0x2000').
4. Ensure no syntax errors, unclosed brackets, or unhandled promise rejections exist.
5. Wrap all hook bodies in `try { ... } catch (err) { console.log("[-] Hook Error: " + err.message); }`.
6. Ensure each hook logs `console.log("[AGENT_STATUS] SUCCESS: ...");` on execution.
7. Ensure `Interceptor.detachAll()` is NEVER called inside `onLeave` of `dlopen`.

Original Candidate Script:
$rawScript

Instructions:
If the code is already safe and clean, return it as-is. If any safety checks or error handles are missing, inject them now.
Return ONLY the complete validated Frida JavaScript code wrapped in ```javascript ... ``` without introductory text.
        """.trimIndent()
    }

    fun buildAutonomousCorrectionPrompt(
        originalScript: String,
        errorLog: String,
        fullConsoleLogs: String,
        logcatLogs: String,
        sqlAstContext: String,
        targetPackage: String,
        iteration: Int,
        maxIterations: Int,
        userFeedback: String? = null
    ): String {
        val failureCategory = when {
            errorLog.contains("SyntaxError", ignoreCase = true) || errorLog.contains("Unexpected token", ignoreCase = true) || errorLog.contains("Unexpected identifier", ignoreCase = true) ->
                "CATEGORY: JAVASCRIPT_SYNTAX_ERROR - Fix unclosed brackets, missing commas, improper quotes, or illegal JS syntax reported in line numbers below."
            errorLog.contains("TypeError", ignoreCase = true) || errorLog.contains("null", ignoreCase = true) ->
                "CATEGORY: NULL_POINTER_DEREFERENCE - Ensure every pointer (args[0], ptr.read...) is checked with if (ptr && !ptr.isNull()) before dereferencing."
            errorLog.contains("SIGSEGV", ignoreCase = true) || errorLog.contains("Access violation", ignoreCase = true) ->
                "CATEGORY: NATIVE_ACCESS_VIOLATION - Invalid pointer address or offset. Use resolveSafeAddress(moduleName, offsetHex, pattern) or check pointer alignment."
            errorLog.contains("NO_HOOK_SUCCESS_SIGNAL_ERROR", ignoreCase = true) ->
                "CATEGORY: UNTRIGGERED_HOOK - Script loaded safely but target function was never intercepted. Ensure Interceptor.attach targets valid active functions or caller getters/setters and logs [AGENT_STATUS] SUCCESS on execution."
            else -> "CATEGORY: GENERAL_RUNTIME_EXCEPTION - Analyze Frida console log output & stack trace to fix logic or pointer offset bugs."
        }

        // Format script with 1-based line numbers for LLM precision
        val scriptLines = originalScript.lines()
        val lineNumberedScript = scriptLines.mapIndexed { index, line ->
            String.format(Locale.US, "%3d | %s", index + 1, line)
        }.joinToString("\n")

        // Extract specific line number from error log if present (e.g., "at line 42" or "line 42:")
        val errorLineMatch = Regex("(?:line\\s*|:)(\\d+)", RegexOption.IGNORE_CASE).find(errorLog)
        val errorLineNum = errorLineMatch?.groupValues?.get(1)?.toIntOrNull()

        val boundedContext = if (errorLineNum != null && errorLineNum in 1..scriptLines.size) {
            val start = maxOf(0, errorLineNum - 6)
            val end = minOf(scriptLines.size, errorLineNum + 5)
            val snippet = scriptLines.subList(start, end).mapIndexed { i, l ->
                val actualLine = start + i + 1
                val marker = if (actualLine == errorLineNum) " >>> ERROR LINE $errorLineNum >>> " else "                       "
                String.format(Locale.US, "%3d | %s%s", actualLine, marker, l)
            }.joinToString("\n")
            "\n### BOUNDED CODE CONTEXT AROUND ERROR LINE $errorLineNum:\n```javascript\n$snippet\n```\n"
        } else ""

        val feedbackSection = if (!userFeedback.isNullOrBlank()) {
            """
### DEVELOPER FEEDBACK & REFINED INSTRUCTION:
$userFeedback
            """.trimIndent()
        } else "No additional developer feedback provided."

        val consoleLogSection = if (fullConsoleLogs.isNotBlank()) {
            """
### FULL FRIDA CONSOLE LOG STREAM (RUNTIME EXECUTION & CONSOLE OUTPUT):
$fullConsoleLogs
            """.trimIndent()
        } else "No Frida console output recorded during monitoring."

        val logcatSection = if (logcatLogs.isNotBlank()) {
            """
### ANDROID NATIVE LOGCAT / CRASH SIGNAL STREAM:
$logcatLogs
            """.trimIndent()
        } else "No native logcat crashes recorded."

        val iterHeader = if (maxIterations > 0) "Attempt $iteration of $maxIterations" else "Attempt #$iteration (Unlimited Loop)"

        return """
AUTONOMOUS REPAIR LOOP [$iterHeader]
Target Package: $targetPackage
Failure Diagnosis: $failureCategory

$feedbackSection

### RUNTIME ERROR & SUMMARY:
$errorLog

$boundedContext

$consoleLogSection

$logcatSection

### SQL DB METADATA AST CONTEXT (QUERIED FROM il2cpp_dumper.db):
$sqlAstContext

### ORIGINAL FAILED SCRIPT WITH LINE NUMBERS:
```javascript
$lineNumberedScript
```

REQUIRED ACTIONS FOR SENIOR AI AGENT:
1. Analyze the exact line numbers, console messages, stack trace, and developer feedback above.
2. Apply a MINIMAL TARGETED PATCH to fix the specific error line ($errorLineNum) without rewriting or breaking working parts of the script.
3. Cross-reference class names, field offsets, and method RVA offsets from the SQL DB metadata context above.
4. Correct all syntax errors, incorrect offsets, unhandled null pointers, or untriggered hook targets in the Frida script.
5. Return ONLY the complete, production-ready, corrected Frida JavaScript code wrapped in ```javascript ... ```. Do NOT include line numbers or markdown text outside code fences.
        """.trimIndent()
    }

    fun buildCorrectionPrompt(
        originalScript: String,
        errorLog: String,
        targetPackage: String,
        klass: Il2CppClassData,
        method: Il2CppMethodData
    ): String {
        val validOffset = if (method.offset.isNotBlank() && method.offset != "0x0") method.offset else method.pointer
        return """
The Frida script crashed or threw an error in $targetPackage for ${klass.fullName}::${method.name} at Offset $validOffset.

Original Script:
$originalScript

Runtime Error / Stacktrace:
$errorLog

Fix the error and return ONLY the complete, corrected Frida JavaScript code wrapped in ```javascript ... ```. Write out all code in full without placeholders.
        """.trimIndent()
    }

    fun extractJsCode(response: String): String {
        if (response.isBlank()) return ""

        // 1. Extract code inside ```javascript or ```js or ``` code fences
        val codeBlockRegex = Regex("```(?:javascript|js)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val matches = codeBlockRegex.findAll(response).toList()

        val rawCode = if (matches.isNotEmpty()) {
            matches.joinToString("\n\n") { it.groupValues[1].trim() }
        } else {
            // No code fences found: safely strip leading/trailing code fence markers if partially present
            var cleaned = response.trim()
            cleaned = cleaned.removePrefix("```javascript").removePrefix("```js").removePrefix("```").trim()
            cleaned = cleaned.removeSuffix("```").trim()
            cleaned
        }

        // 2. Filter out top/bottom markdown text lines while preserving comments, template literals, and regexes
        val lines = rawCode.lines()
        val filteredLines = lines.filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("```") ||
            trimmed.matches(Regex("^(Here is|Here's|Below is|This is) the (corrected|updated|frida|javascript) script.*", RegexOption.IGNORE_CASE))
        }

        return filteredLines.joinToString("\n").trim()
    }

    fun getSeniorTemplateScript(candidateScript: String, userGoal: String, targetPackage: String = "target.app"): String {
        val appTag = targetPackage.substringAfterLast(".").uppercase().replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "TARGET_APP" }
        val candidateBlock = if (candidateScript.isNotBlank()) {
            "// --- CANDIDATE BASE SCRIPT INJECTED ---\n$candidateScript\n// --- END CANDIDATE BASE SCRIPT ---\n\n"
        } else ""

        return """
/**
 * Senior Pro Frida Mod Script
 * Target Package: $targetPackage ($appTag)
 * Goal: $userGoal
 */

'use strict';

$candidateBlock
        """.trimIndent()
    }

    fun buildJavaDexGlobalPrompt(
        targetPackage: String,
        targetClassNames: List<String>,
        userInstruction: String
    ): String {
        val derivedTag = targetPackage.substringAfterLast(".").uppercase().replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "TARGET_APP" }
        val classSummary = targetClassNames.joinToString("\n") { "Target Java Class: $it" }

        return """
Target Package: $targetPackage
Script Logging Tag: const TAG = "$derivedTag";
Architecture: Android Java/DEX Runtime (Dalvik/ART)
User Goal: "$userInstruction"

TARGET JAVA CLASSES:
$classSummary

Instructions:
Write a complete Frida JavaScript script using `Java.perform(function() { ... })` targeting the Java classes listed above.
1. Use `Java.use("com.example.TargetClass")` to hook methods or constructors (`${'$'}init`).
2. Handle method overloads explicitly using `.overload(...)` if necessary.
3. To alter return values or fields, replace implementation:
```javascript
Java.perform(function() {
    const TargetClass = Java.use("com.example.TargetClass");
    TargetClass.getCoins.implementation = function() {
        console.log("[AGENT_STATUS] SUCCESS: Intercepted getCoins");
        return 999999;
    };
});
```
4. Expose `rpc.exports` if parameter modifications or live tweaking are requested.
5. In EVERY hooked method, include `console.log("[AGENT_STATUS] SUCCESS: Intercepted " + methodName);`.
Start IMMEDIATELY with ```javascript ... ```
        """.trimIndent()
    }

    fun buildNativeElfGlobalPrompt(
        targetPackage: String,
        targetSoName: String,
        exportedSymbolsOrOffsets: List<String>,
        userInstruction: String
    ): String {
        val derivedTag = targetPackage.substringAfterLast(".").uppercase().replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "TARGET_APP" }
        val symbolSummary = exportedSymbolsOrOffsets.joinToString("\n") { "Target Symbol/Offset: $it" }

        return """
Target Package: $targetPackage
Script Logging Tag: const TAG = "$derivedTag";
Architecture: Native Linux C/C++ Shared Library (.so)
Target Module: $targetSoName
User Goal: "$userInstruction"

TARGET SYMBOLS & OFFSETS:
$symbolSummary

Instructions:
Write a complete Frida JavaScript script hooking native functions or performing opcode patches in `$targetSoName`.
1. Wrap hook in `waitForModule("$targetSoName", function(mod) { ... })`.
2. For exported C functions: `Module.getExportByName("$targetSoName", "symbol_name")`.
3. For unexported functions at RVA offsets: `mod.base.add(0x12345)`.
4. For ARM64 Opcode patches, use `Memory.patchCode`:
```javascript
Memory.patchCode(targetAddr, 8, function(code) {
    const writer = new Arm64Writer(code, { pc: targetAddr });
    writer.putMovRegU64('x0', 0); // MOV X0, #0
    writer.putRet();               // RET
    writer.flush();
});
```
5. Log `console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);` inside hooks.
Start IMMEDIATELY with ```javascript ... ```
        """.trimIndent()
    }

    fun buildCompactAstSummary(classes: List<Il2CppClassData>, limit: Int = 15): String {
        val sb = StringBuilder()
        for (klass in classes.take(limit)) {
            sb.append("C: ").append(klass.fullName).append("\n")
            for (m in klass.methods.take(5)) {
                val off = if (m.offset.isNotBlank() && m.offset != "0x0") m.offset else m.pointer
                sb.append(" M: ").append(m.returnType).append(" ").append(m.name).append("()@").append(off).append("\n")
            }
            for (f in klass.fields.take(3)) {
                sb.append(" F: ").append(f.type).append(" ").append(f.name).append("@").append(f.offset).append("\n")
            }
        }
        return sb.toString()
    }
}
