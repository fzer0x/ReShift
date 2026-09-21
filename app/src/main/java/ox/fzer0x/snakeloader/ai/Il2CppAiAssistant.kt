package ox.fzer0x.snakeloader.ai

import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppMethodData

enum class AiGoalPreset(val title: String, val promptTemplate: String) {
    SPOOF_RETURN("Spoof Return Value", "Hook this method and alter its return value. Return false/0 for security or ban checks, or true/9999 for success/status checks as appropriate."),
    LOCK_HEALTH("Lock Health / Value", "Hook this method or its target class fields to freeze health/currency/values to a maximum value (e.g. 9999) whenever called."),
    ARM64_HEX_PATCH("ARM64 Memory Patch", "Provide a Frida direct memory patch using Memory.protect and targetAddress.writeByteArray to replace the first instructions with NOPs or 'MOV W0, #0; RET' (0x00 0x00 0x80 0x52 0xC0 0x03 0x5F 0xD6)."),
    TRACE_EXECUTION("Trace Execution & Log Args", "Hook this method to log 'this' pointer, all arguments with types, and the return value to console.log in detail."),
    CUSTOM("Custom Request", "")
}

class Il2CppAiAssistant {

    private val semanticRanker = AstSemanticRanker()

    companion object {
        const val SYSTEM_INSTRUCTION = """
You are an expert Senior Android Reverse Engineer and Frida Core Developer specializing in Unity IL2CPP games and libil2cpp.so hooking.
Your task is to write fully functional, complete, production-ready Frida JavaScript code (`.js`) to hook target functions in `libil2cpp.so`.

STRICT CONSTRAINTS & POINTER SAFETY:
1. Do NOT output placeholders, TODOs, comments like `// Your code here`, or truncated snippets. Write the FULL, working implementation.
2. Wrap your output in ```javascript ... ```
3. Define `const TAG = "<TARGET_PACKAGE_NAME>";` (e.g. `const TAG = "COM_NATURALMOTION_CUSTOMRACING2";` or derived short uppercase tag like `const TAG = "CUSTOMRACING2";`) matching the target package being hooked!
4. Always wrap code execution in a `waitForModule("libil2cpp.so", function(il2cpp) { ... })` dlopen guard to prevent crashes when libil2cpp.so is loaded asynchronously!
5. Always check pointer validity (`if (args[0] && !args[0].isNull())`) before accessing arguments or dereferencing pointers.
6. Wrap hook bodies in `try { ... } catch (err) { console.log("[-] Hook Error: " + err.message); }`.
7. Use `il2cpp.base` (from `waitForModule`) or `Module.findBaseAddress("libil2cpp.so")` to compute target function addresses using the exact hexadecimal offsets provided.
8. Start IMMEDIATELY with ```javascript ... ``` without introductory text or explanations.
9. NEVER call `Interceptor.detachAll()` inside `waitForModule` or `dlopen` hooks as it wipes out active hooks!
10. NEVER hook arbitrary dummy offsets like '0x1000' or '0x2000' or enumerate random exports. ONLY hook the real, non-zero C# method offsets provided in the prompt using `il2cpp.base.add(ptr("0x..."))`.

ASYNCHRONOUS MODULE WAITER & IL2CPP THREAD ATTACHMENT (DLOPEN & GC GUARD):
Include and use these helpers at the top of your script to safely handle early app launches and GC thread contexts:
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

// IL2CPP Thread Attachment Helper for Native Function Interop & GC Domain Safety
function callInIl2CppContext(action) {
    try {
        const domainGet = Module.findExportByName("libil2cpp.so", "il2cpp_domain_get");
        const threadAttach = Module.findExportByName("libil2cpp.so", "il2cpp_thread_attach");
        if (domainGet && threadAttach) {
            const domain = new NativeFunction(domainGet, 'pointer', [])();
            const attach = new NativeFunction(threadAttach, 'pointer', ['pointer']);
            attach(domain);
        }
    } catch (e) {
        // Fallback if domain exports unavailable
    }
    return action();
}

// Dynamic AOB Pattern Scanner with Offset Fallback
function resolveSafeAddress(moduleName, offsetHex, pattern) {
    const mod = Process.findModuleByName(moduleName);
    if (!mod) return null;
    const target = mod.base.add(ptr(offsetHex));
    if (!pattern) return target;
    try {
        if (Memory.readU32(target) !== 0) return target;
    } catch (e) {
        // Memory unmapped or protected
    }
    try {
        const matches = Memory.scanSync(mod.base, mod.size, pattern);
        if (matches.length > 0) return matches[0].address;
    } catch (e) {}
    return target;
}
```

CRITICAL EXECUTION SIGNAL & HOOK LOGGING REQUIREMENTS:
1. Use `log(msg)` helper function formatted as `console.log("[CSR2_DYNAMIC] " + msg);` or `console.log("[AGENT_STATUS] ...");` so the Frida Console stream in ReShift logs and captures all execution events in real time.
2. Inside EVERY Interceptor.attach `onEnter` or `onLeave` block, log `console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);` upon execution so ReShift can verify the hook trigger.

PRO PRODUCTION-GRADE IL2CPP SCRIPT ARCHITECTURE (YOU MUST STRICTLY FOLLOW THIS ARCHITECTURE PATTERN FOR GAME MODDING):
Your output Frida JavaScript script MUST be structured using this exact production pattern:
1. Low-Level Stealth (`applyFridaStealth` to replace `ptrace`).
2. IL2CPP API Initialization (`initIl2cppApi` resolving `il2cpp_domain_get`, `il2cpp_domain_get_assemblies`, `il2cpp_assembly_get_image`, `il2cpp_image_get_class_count`, `il2cpp_image_get_class`, `il2cpp_class_get_methods`, `il2cpp_method_get_name`, `il2cpp_method_get_address`).
3. Dynamic Symbol Resolver (`resolveAllMethods` + `FALLBACK_OFFSETS` dictionary).
4. Integrity & Anti-Cheat Bypass (`applyIntegrityBypass` replacing `ThrowProfileError`, `LogOperation`, `FireLogOperationEvent`, `SpendCash`, `SpendGold`, `SpendFlexibleCurrency`).
5. Smart Price & Economy Scanner (`applySmartPatch` scanning `PlayerProfile`, `Price`, `Cost`, `Store`, `CanAfford`, `GetCashPrice`, `GetGoldPrice` to set prices to 0 and fake balance getters to 50,000,000 / 50000000).
6. Main Boot sequence (`boot` with `setTimeout` guards and `applySmartPatch` delayed execution).

REFERENCE PRODUCTION SCRIPT TEMPLATE:
```javascript
'use strict';

const IL2CPP_SO = 'libil2cpp.so';
const TAG = "TARGET_APP_TAG"; // Must be derived from target package name!

const FALLBACK_OFFSETS = {
    ThrowProfileError: 0x2c407dc,
    LogOperation: 0x2c40638,
    get_passed: 0x2c40990,
    SpendGold: 0x3250cbc,
    SpendCash: 0x3253820,
    SpendFlexibleCurrency: 0x32540fc
};

let api = {};
let ResolvedMethods = {};

function log(msg) {
    console.log("[" + TAG + "] " + msg);
}

function applyFridaStealth() {
    try {
        const libc = Process.getModuleByName("libc.so");
        const ptracePtr = libc.findExportByName("ptrace");
        if (ptracePtr) {
            Interceptor.replace(ptracePtr, new NativeCallback(() => 0, 'long', ['int', 'int', 'pointer', 'pointer']));
            log("✓ ptrace stealth active");
        }
    } catch (e) {}
}

function initIl2cppApi(il2cpp) {
    const resolve = (name, ret, args) => {
        let addr = il2cpp.findExportByName(name);
        if (!addr || addr.isNull()) {
            const exports = il2cpp.enumerateExports();
            for (let exp of exports) { if (exp.name === name) { addr = exp.address; break; } }
        }
        return addr ? new NativeFunction(addr, ret, args) : null;
    };

    api = {
        domain_get: resolve('il2cpp_domain_get', 'pointer', []),
        domain_get_assemblies: resolve('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']),
        assembly_get_image: resolve('il2cpp_assembly_get_image', 'pointer', ['pointer']),
        image_get_class_count: resolve('il2cpp_image_get_class_count', 'size_t', ['pointer']),
        image_get_class: resolve('il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']),
        class_get_name: resolve('il2cpp_class_get_name', 'pointer', ['pointer']),
        class_get_namespace: resolve('il2cpp_class_get_namespace', 'pointer', ['pointer']),
        class_get_methods: resolve('il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer']),
        method_get_name: resolve('il2cpp_method_get_name', 'pointer', ['pointer']),
        method_get_address: resolve('il2cpp_method_get_address', 'pointer', ['pointer'])
    };
    return !!api.domain_get;
}

function resolveAllMethods() {
    log("Resolving game methods dynamically...");
    const targets = {
        "ProfileIntegrity": ["get_passed", "ThrowProfileError", "LogOperation", "FireLogOperationEvent"],
        "PlayerProfile": ["SpendGold", "SpendCash", "SpendFlexibleCurrency", "get_BalanceGoldKeys", "get_BalanceSilverKeys", "get_BalanceBronzeKeys"],
        "AntiCheat": ["AddTamperedCar_CanTrigger", "GetTamperedCars_CanTrigger"]
    };
    try {
        const domain = api.domain_get();
        const sizePtr = Memory.alloc(Process.pointerSize);
        const assemblies = api.domain_get_assemblies(domain, sizePtr);
        const count = sizePtr.readUInt();

        for (let i = 0; i < count; i++) {
            const assembly = assemblies.add(i * Process.pointerSize).readPointer();
            const image = api.assembly_get_image(assembly);
            const classCount = api.image_get_class_count(image);

            for (let j = 0; j < classCount; j++) {
                const klass = api.image_get_class(image, j);
                if (klass.isNull()) continue;
                const className = api.class_get_name(klass).readUtf8String();
                if (targets[className]) {
                    const iter = Memory.alloc(Process.pointerSize).writePointer(NULL);
                    let method;
                    while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                        const mName = api.method_get_name(method).readUtf8String();
                        if (targets[className].includes(mName)) {
                            ResolvedMethods[mName] = api.method_get_address(method);
                            log("✓ Resolved: " + className + "." + mName + " -> " + ResolvedMethods[mName]);
                        }
                    }
                }
            }
        }
    } catch(e) {}
}

function getAddr(name, il2cppBase) {
    if (ResolvedMethods[name]) return ResolvedMethods[name];
    if (FALLBACK_OFFSETS[name]) return il2cppBase.add(FALLBACK_OFFSETS[name]);
    return null;
}

function applyIntegrityBypass(il2cppBase) {
    log("Applying Integrity & Anti-Debt Protection...");
    const blockDeduction = new NativeCallback((instance, amount) => {
        log("💰 Anti-Debt: Blocked deduction of " + amount + " units.");
    }, 'void', ['pointer', 'int', 'pointer']);

    ["SpendGold", "SpendCash", "SpendFlexibleCurrency"].forEach(m => {
        const addr = getAddr(m, il2cppBase);
        if (addr) Interceptor.replace(addr, blockDeduction);
    });
}

function applySmartPatch() {
    log("Start Price Scanner & Currency Modifier...");
    const domain = api.domain_get();
    const sizePtr = Memory.alloc(Process.pointerSize);
    const assemblies = api.domain_get_assemblies(domain, sizePtr);
    const count = sizePtr.readUInt();

    const targetKeywords = ["Price", "Cost", "Store", "Purchase", "CarData", "PlayerProfile", "Economy"];

    for (let i = 0; i < count; i++) {
        const assembly = assemblies.add(i * Process.pointerSize).readPointer();
        const image = api.assembly_get_image(assembly);
        const classCount = api.image_get_class_count(image);

        for (let j = 0; j < classCount; j++) {
            const klass = api.image_get_class(image, j);
            if (klass.isNull()) continue;
            const className = api.class_get_name(klass).readUtf8String();
            if (!targetKeywords.some(kw => className.includes(kw))) continue;

            const iter = Memory.alloc(Process.pointerSize).writePointer(NULL);
            let method;
            while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                const mName = api.method_get_name(method).readUtf8String();

                if (mName === "CanAfford" || mName === "CanAffordGold" || mName === "CanAffordCash") {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        Interceptor.attach(addr, { onLeave: function(retval) { retval.replace(ptr(1)); } });
                    }
                }

                if (className === "PlayerProfile") {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        if ((mName.includes("Gold") || mName.includes("Cash") || mName.includes("Money") || mName.includes("Budget")) && (mName.startsWith("get_") || mName.startsWith("Get"))) {
                            Interceptor.attach(addr, {
                                onLeave: function(retval) {
                                    retval.replace(ptr("50000000")); // 50,000,000 Budget / Cash
                                    console.log("[AGENT_STATUS] SUCCESS: Intercepted " + mName + " -> Set 50,000,000");
                                }
                            });
                        }
                    }
                }
            }
        }
    }
    log("Smart Scan complete.");
}

function boot() {
    const il2cppMod = Process.findModuleByName(IL2CPP_SO);
    if (!il2cppMod) { setTimeout(boot, 1000); return; }
    if (!initIl2cppApi(il2cppMod)) { setTimeout(boot, 1000); return; }

    log("✓ Engine Ready. Resolving Symbols...");
    resolveAllMethods();
    applyIntegrityBypass(il2cppMod.base);

    setTimeout(() => {
        applySmartPatch();
        log("ALL MODS INJECTED");
    }, 3000);
}

applyFridaStealth();
setTimeout(boot, 3000);
```

DYNAMIC UNITY STRING & ARRAY INTEROP HELPER:
Include and use this dynamic helper function to read Il2CppString pointers cleanly without relying on hardcoded static offsets:
```javascript
function readIl2CppString(strPtr) {
    if (!strPtr || strPtr.isNull()) return null;
    try {
        const il2cpp_string_chars = Module.findExportByName("libil2cpp.so", "il2cpp_string_chars");
        if (il2cpp_string_chars) {
            const charsFunc = new NativeFunction(il2cpp_string_chars, 'pointer', ['pointer']);
            return charsFunc(strPtr).readUtf16String();
        }
        return strPtr.add(Process.pointerSize * 2 + 4).readUtf16String();
    } catch (e) {
        return null;
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
3. FOR CSR2 & IL2CPP MANAGED FIELDS (VERY IMPORTANT):
In Unity games like CSR2, currency values are often read from managed C# class instance fields (e.g. `PlayerProfile.m_cash`, `m_gold`, `m_budget`).
When intercepting methods that take `this` (in `args[0]`), update the field value at the exact field offset directly in instance memory:
```javascript
onEnter(args) {
    if (args[0] && !args[0].isNull()) {
        try {
            // Write 50,000,000 to field offset (e.g. 0x28 or 0x30)
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
Perform static analysis on the following Frida JavaScript script target package "$targetPackage".

CRITICAL AUDIT CHECKLIST:
1. Ensure `libil2cpp.so` module base address check is performed before hooking.
2. Ensure every pointer dereference (`args[0]`, `ptr.readInt()`, `ptr.readUtf8String()`) is wrapped with `if (ptr && !ptr.isNull())` or `try/catch`.
3. Verify `Interceptor.attach` arguments and offsets are syntactically valid ARM64 pointers (do NOT use dummy offsets like '0x1000' or '0x2000').
4. Ensure no syntax errors, unclosed brackets, or unhandled promise rejections exist.
5. Wrap all hook bodies in `try { ... } catch (err) { console.log("[-] Hook Error: " + err.message); }`.
6. Ensure each hook logs `console.log("[AGENT_STATUS] SUCCESS: ...");` on execution.
7. Ensure `Interceptor.detachAll()` is NEVER called inside `onLeave` of `dlopen`, as it detaches all newly established hooks.

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
### ANDROID NATIVE LOGCAT / SYSTEM CRASH SIGNAL STREAM:
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

$consoleLogSection

$logcatSection

### SQL DB METADATA AST CONTEXT (QUERIED FROM il2cpp_dumper.db):
$sqlAstContext

### ORIGINAL FAILED SCRIPT:
```javascript
$originalScript
```

REQUIRED ACTIONS FOR SENIOR AI AGENT:
1. Thoroughly analyze the exact line numbers, console messages, stack trace, and developer feedback above.
2. Cross-reference the class names, field offsets, and method RVA offsets from the SQL DB metadata context above (`il2cpp_dumper.db`).
3. Correct all logic bugs, incorrect offsets, unhandled null pointers, or untriggered hook targets in the Frida script.
4. Apply robust defenses:
   - Always check pointer validity (`if (args[0] && !args[0].isNull())`) before dereferencing or offset writing.
   - Wrap hook bodies in `try { ... } catch (e) { console.log("[HOOK_EXCEPTION] " + e.message); }`.
   - Include `console.log("[AGENT_STATUS] SUCCESS: Intercepted " + targetAddress);` inside EVERY Interceptor block.
5. Return ONLY the complete, production-ready, corrected Frida JavaScript code wrapped in ```javascript ... ```. Write out all code in full without placeholders.
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
        val codeBlockRegex = Regex("```(?:javascript|js)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val match = codeBlockRegex.find(response)
        return if (match != null) {
            match.groupValues[1].trim()
        } else {
            val lower = response.lowercase()
            val jsStart = lower.indexOf("const ")
            val jsStartVar = lower.indexOf("var ")
            val jsStartLet = lower.indexOf("let ")

            val startIndex = listOf(jsStart, jsStartVar, jsStartLet).filter { it != -1 }.minOrNull()
            if (startIndex != null) {
                response.substring(startIndex).trim()
            } else {
                response.trim()
            }
        }
    }

    fun getSeniorTemplateScript(candidateScript: String, userGoal: String, targetPackage: String = "target.app"): String {
        val appTag = targetPackage.substringAfterLast(".").uppercase().replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "TARGET_APP" }
        return """
/**
 * Senior Pro IL2CPP Frida Mod Script
 * Target Package: $targetPackage
 * Goal: $userGoal
 */

'use strict';

const IL2CPP_SO = 'libil2cpp.so';
const TAG = "$appTag";

const FALLBACK_OFFSETS = {
    ThrowProfileError: 0x2c407dc,
    LogOperation: 0x2c40638,
    get_passed: 0x2c40990,
    SpendGold: 0x3250cbc,
    SpendCash: 0x3253820,
    SpendFlexibleCurrency: 0x32540fc
};

let api = {};
let ResolvedMethods = {};

function log(msg) {
    console.log("[" + TAG + "] " + msg);
}

function applyFridaStealth() {
    try {
        const libc = Process.getModuleByName("libc.so");
        const ptracePtr = libc.findExportByName("ptrace");
        if (ptracePtr) {
            Interceptor.replace(ptracePtr, new NativeCallback(() => 0, 'long', ['int', 'int', 'pointer', 'pointer']));
            log("✓ ptrace stealth active");
        }
    } catch (e) {}
}

function initIl2cppApi(il2cpp) {
    const resolve = (name, ret, args) => {
        let addr = il2cpp.findExportByName(name);
        if (!addr || addr.isNull()) {
            const exports = il2cpp.enumerateExports();
            for (let exp of exports) { if (exp.name === name) { addr = exp.address; break; } }
        }
        return addr ? new NativeFunction(addr, ret, args) : null;
    };

    api = {
        domain_get: resolve('il2cpp_domain_get', 'pointer', []),
        domain_get_assemblies: resolve('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']),
        assembly_get_image: resolve('il2cpp_assembly_get_image', 'pointer', ['pointer']),
        image_get_class_count: resolve('il2cpp_image_get_class_count', 'size_t', ['pointer']),
        image_get_class: resolve('il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']),
        class_get_name: resolve('il2cpp_class_get_name', 'pointer', ['pointer']),
        class_get_namespace: resolve('il2cpp_class_get_namespace', 'pointer', ['pointer']),
        class_get_methods: resolve('il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer']),
        method_get_name: resolve('il2cpp_method_get_name', 'pointer', ['pointer']),
        method_get_address: resolve('il2cpp_method_get_address', 'pointer', ['pointer'])
    };
    return !!api.domain_get;
}

function resolveAllMethods() {
    log("Resolving game methods dynamically...");
    const targets = {
        "ProfileIntegrity": ["get_passed", "ThrowProfileError", "LogOperation", "FireLogOperationEvent"],
        "PlayerProfile": ["SpendGold", "SpendCash", "SpendFlexibleCurrency", "get_BalanceGoldKeys", "get_BalanceSilverKeys", "get_BalanceBronzeKeys"],
        "AntiCheat": ["AddTamperedCar_CanTrigger", "GetTamperedCars_CanTrigger"]
    };
    try {
        const domain = api.domain_get();
        const sizePtr = Memory.alloc(Process.pointerSize);
        const assemblies = api.domain_get_assemblies(domain, sizePtr);
        const count = sizePtr.readUInt();

        for (let i = 0; i < count; i++) {
            const assembly = assemblies.add(i * Process.pointerSize).readPointer();
            const image = api.assembly_get_image(assembly);
            const classCount = api.image_get_class_count(image);

            for (let j = 0; j < classCount; j++) {
                const klass = api.image_get_class(image, j);
                if (klass.isNull()) continue;
                const className = api.class_get_name(klass).readUtf8String();
                if (targets[className]) {
                    const iter = Memory.alloc(Process.pointerSize).writePointer(NULL);
                    let method;
                    while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                        const mName = api.method_get_name(method).readUtf8String();
                        if (targets[className].includes(mName)) {
                            ResolvedMethods[mName] = api.method_get_address(method);
                            log("✓ Resolved: " + className + "." + mName + " -> " + ResolvedMethods[mName]);
                        }
                    }
                }
            }
        }
    } catch(e) {}
}

function getAddr(name, il2cppBase) {
    if (ResolvedMethods[name]) return ResolvedMethods[name];
    if (FALLBACK_OFFSETS[name]) return il2cppBase.add(FALLBACK_OFFSETS[name]);
    return null;
}

function applyIntegrityBypass(il2cppBase) {
    log("Applying Integrity & Anti-Debt Protection...");
    const blockDeduction = new NativeCallback((instance, amount) => {
        log("💰 Anti-Debt: Blocked deduction of " + amount + " units.");
    }, 'void', ['pointer', 'int', 'pointer']);

    ["SpendGold", "SpendCash", "SpendFlexibleCurrency"].forEach(m => {
        const addr = getAddr(m, il2cppBase);
        if (addr) Interceptor.replace(addr, blockDeduction);
    });
}

function applySmartPatch() {
    log("Start Price Scanner & Currency Modifier...");
    const domain = api.domain_get();
    const sizePtr = Memory.alloc(Process.pointerSize);
    const assemblies = api.domain_get_assemblies(domain, sizePtr);
    const count = sizePtr.readUInt();

    const targetKeywords = ["Price", "Cost", "Store", "Purchase", "CarData", "PlayerProfile", "Economy", "Wallet", "Cash", "Gold", "Budget"];

    for (let i = 0; i < count; i++) {
        const assembly = assemblies.add(i * Process.pointerSize).readPointer();
        const image = api.assembly_get_image(assembly);
        const classCount = api.image_get_class_count(image);

        for (let j = 0; j < classCount; j++) {
            const klass = api.image_get_class(image, j);
            if (klass.isNull()) continue;
            const className = api.class_get_name(klass).readUtf8String();
            if (!targetKeywords.some(kw => className.includes(kw))) continue;

            const iter = Memory.alloc(Process.pointerSize).writePointer(NULL);
            let method;
            while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                const mName = api.method_get_name(method).readUtf8String();

                if (mName === "CanAfford" || mName === "CanAffordGold" || mName === "CanAffordCash") {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        Interceptor.attach(addr, { onLeave: function(retval) { retval.replace(ptr(1)); } });
                    }
                }

                if (className === "PlayerProfile") {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        if ((mName.includes("Gold") || mName.includes("Cash") || mName.includes("Money") || mName.includes("Budget")) && (mName.startsWith("get_") || mName.startsWith("Get"))) {
                            Interceptor.attach(addr, {
                                onLeave: function(retval) {
                                    retval.replace(ptr("50000000")); // 50,000,000 Budget / Cash
                                    console.log("[AGENT_STATUS] SUCCESS: Intercepted " + mName + " -> Set 50,000,000");
                                }
                            });
                        }
                    }
                }
            }
        }
    }
    log("Smart Scan complete.");
}

function boot() {
    const il2cppMod = Process.findModuleByName(IL2CPP_SO);
    if (!il2cppMod) { setTimeout(boot, 1000); return; }
    if (!initIl2cppApi(il2cppMod)) { setTimeout(boot, 1000); return; }

    log("✓ Engine Ready. Resolving Symbols...");
    resolveAllMethods();
    applyIntegrityBypass(il2cppMod.base);

    setTimeout(() => {
        applySmartPatch();
        log("ALL MODS INJECTED");
    }, 3000);
}

applyFridaStealth();
setTimeout(boot, 3000);
        """.trimIndent()
    }
}
