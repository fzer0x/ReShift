package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import java.io.File

data class FridaScript(
    val name: String,
    val content: String,
    val priority: Int = 0,
    val dependencies: List<String> = emptyList()
)

class MultiScriptLoader(private val context: Context, private val fridaManager: FridaManager) {
    companion object {
        private const val TAG = "MultiScriptLoader"
        private const val SCRIPTS_DIR = "/data/local/tmp/reshift_scripts"
        private const val REGISTRY_FILE = "/data/local/tmp/reshift_registry.json"
    }

    suspend fun executeScripts(
        scripts: List<FridaScript>,
        packageName: String,
        mode: String = "sequential"
    ): Boolean {
        return when (mode) {
            "combined" -> executeCombined(scripts, packageName)
            "sequential" -> executeSequential(scripts, packageName)
            else -> false
        }
    }

    private suspend fun executeCombined(scripts: List<FridaScript>, packageName: String): Boolean {
        return try {
            val sorted = scripts.sortedByDescending { it.priority }
            val combined = buildCombinedScript(sorted)

            LogManager.addLog(packageName, "Executing ${scripts.size} scripts in COMBINED mode")
            LogManager.addLog(packageName, "Combined script length: ${combined.length} characters")
            
            val success = fridaManager.executeScriptContent(packageName, combined)
            
            if (success) {
                LogManager.addLog(packageName, "Combined script executed successfully")
            } else {
                LogManager.addLog(packageName, "Combined script execution failed", isError = true)
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Combined execution failed", e)
            LogManager.addLog(packageName, "Combined script error: ${e.message}", isError = true)
            false
        }
    }

    private suspend fun executeSequential(scripts: List<FridaScript>, packageName: String): Boolean {
        return try {
            val sorted = scripts.sortedByDescending { it.priority }
            val combinedScript = buildCombinedScript(sorted)

            LogManager.addLog(packageName, "Executing ${scripts.size} scripts in SEQUENTIAL mode (with shared registry)")
            
            if (!fridaManager.executeScriptContent(packageName, combinedScript)) {
                LogManager.addLog(packageName, "Failed to execute combined script", isError = true)
                return false
            }

            LogManager.addLog(packageName, "All ${scripts.size} scripts executed successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sequential execution failed", e)
            LogManager.addLog(packageName, "Sequential error: ${e.message}", isError = true)
            false
        }
    }

    private fun buildCombinedScript(scripts: List<FridaScript>): String {
        val sb = StringBuilder()
        
        val stealthBase = fridaManager.settings.isStealthModeEnabled
        val basePath = if (stealthBase) StealthConfigManager.BASE_STEALTH_PATH else StealthConfigManager.LEGACY_BASE_PATH

        sb.append("console.log('[Snakeloader] === SCRIPT LOADER STARTING ===');\n")
        sb.append("console.log('[Snakeloader] Loading ${scripts.size} scripts');\n")
        sb.append("const RE_SHIFT_BASE = '$basePath';\n\n")

        sb.append(getGlobalUtilities())
        sb.append("\n\n")

        for ((index, script) in scripts.withIndex()) {
            sb.append("console.log('[Snakeloader] Loading script: ${script.name}');\n")
            sb.append("(function() {\n")
            sb.append("const scriptName = '${script.name}';\n")
            sb.append("console.log('[Snakeloader] Registering script: ' + scriptName);\n")
            sb.append("SCRIPT_REGISTRY.scripts[scriptName] = { name: scriptName, loaded: true, timestamp: Date.now() };\n")
            sb.append("console.log('[Snakeloader] Script registered successfully');\n")
            sb.append(script.content)
            sb.append("\n})();\n\n")
        }

        sb.append(getEnhancedRpcExports())

        return sb.toString()
    }

    private fun getGlobalUtilities(): String = """
'use strict';

console.log('[Snakeloader] === GLOBAL UTILITIES LOADING ===');

if (typeof Process.SCRIPT_REGISTRY === 'undefined') {
    Process.SCRIPT_REGISTRY = {
        scripts: {},
        hooks: {},
        memory: {},
        interceptors: []
    };
}
const SCRIPT_REGISTRY = Process.SCRIPT_REGISTRY;

console.log('[Snakeloader] SCRIPT_REGISTRY initialized/retrieved');

class GlobalLogger {
    static info(msg, script = "CORE") {
        console.log("[Snakeloader] [" + script + "] [INFO] " + msg);
    }
    static success(msg, script = "CORE") {
        console.log("[Snakeloader] [" + script + "] [✓] " + msg);
    }
    static warn(msg, script = "CORE") {
        console.log("[Snakeloader] [" + script + "] [!] " + msg);
    }
    static error(msg, script = "CORE") {
        console.error("[Snakeloader] [" + script + "] [✗] " + msg);
    }
}

console.log('GlobalLogger initialized');

const MemoryUtils = {
    readString(addr) {
        try {
            return addr.readUtf8String();
        } catch (e) {
            return "[Invalid Address]";
        }
    },
    readInt(addr) {
        try {
            return addr.readInt();
        } catch (e) {
            return 0;
        }
    },
    readLong(addr) {
        try {
            return addr.readLong();
        } catch (e) {
            return 0n;
        }
    },
    readPtr(addr) {
        try {
            return addr.readPointer();
        } catch (e) {
            return NULL;
        }
    },
    writeInt(addr, value) {
        try {
            addr.writeInt(value);
            return true;
        } catch (e) {
            return false;
        }
    }
};

console.log('MemoryUtils initialized');

function registerHook(name, target, callback) {
    SCRIPT_REGISTRY.hooks[name] = { target, callback, active: true };
    GlobalLogger.success("Hook registered: " + name);
}

let MODULE_CACHE = {};
function findModule(name) {
    if (MODULE_CACHE[name]) return MODULE_CACHE[name];
    try {
        const mod = Process.getModuleByName(name);
        MODULE_CACHE[name] = mod;
        return mod;
    } catch (e) {
        GlobalLogger.warn("Module not found: " + name);
        return null;
    }
}

console.log('=== GLOBAL UTILITIES READY ===');
""".trimIndent()

    private fun getEnhancedRpcExports(): String = """


console.log('[Snakeloader] === ENHANCED RPC BRIDGE INITIALIZING ===');

SCRIPT_REGISTRY.hooks['__test_hook__'] = { name: '__test_hook__', active: true, target: '0x0', description: 'RPC Connection Test Hook' };
SCRIPT_REGISTRY.hooks['__memory_test__'] = { name: '__memory_test__', active: true, target: '0x0', description: 'Memory Inspector Test Hook' };

console.log('[Snakeloader] Test hooks registered: __test_hook__, __memory_test__');
console.log('[Snakeloader] Total hooks in registry: ' + Object.keys(SCRIPT_REGISTRY.hooks).length);

rpc.exports.listRegisteredHooks = function() {
    console.log('[Snakeloader] listRegisteredHooks called');
    const hooks = Object.keys(SCRIPT_REGISTRY.hooks);
    console.log('[Snakeloader] Returning hooks: ' + JSON.stringify(hooks));
    return hooks;
};
rpc.exports.listregisteredhooks = rpc.exports.listRegisteredHooks;
rpc.exports.list_registered_hooks = rpc.exports.listRegisteredHooks;

rpc.exports.getHookStatus = function(hookName) {
    console.log('[Snakeloader] getHookStatus called for: ' + hookName);
    const hook = SCRIPT_REGISTRY.hooks[hookName];
    if (!hook) {
        console.log('[Snakeloader] Hook not found in global registry, checking scripts...');
        for (const scriptName in SCRIPT_REGISTRY.scripts) {
            const script = SCRIPT_REGISTRY.scripts[scriptName];
            if (script.hooks && script.hooks[hookName]) {
                console.log('[Snakeloader] Found hook in script: ' + scriptName);
                return { name: hookName, active: script.hooks[hookName].active, source: scriptName };
            }
        }
        console.log('[Snakeloader] Hook not found anywhere');
        return null;
    }
    console.log('[Snakeloader] Hook found: ' + JSON.stringify(hook));
    return { name: hookName, active: hook.active, target: hook.target, description: hook.description };
};
rpc.exports.gethookstatus = rpc.exports.getHookStatus;
rpc.exports.get_hook_status = rpc.exports.getHookStatus;

rpc.exports.toggleHook = function(hookName) {
    console.log('[Snakeloader] toggleHook called for: ' + hookName);
    const hook = SCRIPT_REGISTRY.hooks[hookName];
    if (!hook) {
        console.log('[Snakeloader] Hook not found in global registry, checking scripts...');
        for (const scriptName in SCRIPT_REGISTRY.scripts) {
            const script = SCRIPT_REGISTRY.scripts[scriptName];
            if (script.hooks && script.hooks[hookName]) {
                const h = script.hooks[hookName];
                h.active = !h.active;
                GlobalLogger.info("Hook toggled: " + hookName + " (from " + scriptName + ") -> " + (h.active ? "ON" : "OFF"));
                return h.active;
            }
        }
        console.log('[Snakeloader] Hook not found anywhere');
        return false;
    }
    hook.active = !hook.active;
    GlobalLogger.info("Hook toggled: " + hookName + " -> " + (hook.active ? "ON" : "OFF"));
    return hook.active;
};
rpc.exports.togglehook = rpc.exports.toggleHook;
rpc.exports.toggle_hook = rpc.exports.toggleHook;

rpc.exports.getMemoryDump = function(address, size) {
    console.log('[Snakeloader] getMemoryDump called: address=' + address + ', size=' + size);
    try {
        const addr = ptr(address);
        if (addr.isNull()) {
            console.log('[Snakeloader] Address is null');
            return null;
        }
        const bytes = addr.readByteArray(size);
        GlobalLogger.success("Memory dumped: " + address + " (" + size + " bytes)");
        return bytes;
    } catch (e) {
        GlobalLogger.error("Memory dump failed: " + e.message);
        return null;
    }
};
rpc.exports.getmemorydump = rpc.exports.getMemoryDump;
rpc.exports.get_memory_dump = rpc.exports.getMemoryDump;

rpc.exports.readMemoryString = function(address, maxLen) {
    console.log('[Snakeloader] readMemoryString called: address=' + address);
    try {
        const addr = ptr(address);
        const str = addr.readUtf8String(maxLen || 256);
        GlobalLogger.success('String read: "' + str + '"');
        return str;
    } catch (e) {
        GlobalLogger.error("String read failed: " + e.message);
        return null;
    }
};
rpc.exports.readmemorystring = rpc.exports.readMemoryString;
rpc.exports.read_memory_string = rpc.exports.readMemoryString;

rpc.exports.writeMemoryInt = function(address, value) {
    console.log('[Snakeloader] writeMemoryInt called: address=' + address + ', value=' + value);
    try {
        const addr = ptr(address);
        addr.writeInt(value);
        GlobalLogger.success("Wrote int to " + address + ": " + value);
        return true;
    } catch (e) {
        GlobalLogger.error("Write failed: " + e.message);
        return false;
    }
};
rpc.exports.writememoryint = rpc.exports.writeMemoryInt;
rpc.exports.write_memory_int = rpc.exports.writeMemoryInt;

rpc.exports.getScriptRegistry = function() {
    console.log('[Snakeloader] getScriptRegistry called');
    const registry = {
        scripts: Object.keys(SCRIPT_REGISTRY.scripts),
        hooks: Object.keys(SCRIPT_REGISTRY.hooks),
        interceptors: SCRIPT_REGISTRY.interceptors.length,
        timestamp: Date.now()
    };
    
    registry.hookDetails = {};
    for (const hookName in SCRIPT_REGISTRY.hooks) {
        registry.hookDetails[hookName] = {
            active: SCRIPT_REGISTRY.hooks[hookName].active,
            target: SCRIPT_REGISTRY.hooks[hookName].target,
            description: SCRIPT_REGISTRY.hooks[hookName].description || ''
        };
    }
    
    console.log('[Snakeloader] Registry: ' + JSON.stringify(registry));
    GlobalLogger.success("Registry status: " + JSON.stringify(registry));
    return registry;
};
rpc.exports.getscriptregistry = rpc.exports.getScriptRegistry;
rpc.exports.get_script_registry = rpc.exports.getScriptRegistry;

rpc.exports.listModules = function() {
    console.log('[Snakeloader] listModules called');
    try {
        const modules = Process.enumerateModules();
        console.log('[Snakeloader] Found ' + modules.length + ' modules');
        return modules.map(m => ({
            name: m.name,
            base: m.base.toString(),
            size: m.size.toString(),
            path: m.path
        }));
    } catch (e) {
        GlobalLogger.error("Module enumeration failed: " + e.message);
        return [];
    }
};
rpc.exports.listmodules = rpc.exports.listModules;
rpc.exports.list_modules = rpc.exports.listModules;

rpc.exports.getProcessInfo = function() {
    console.log('[Snakeloader] getProcessInfo called');
    try {
        const info = {
            pid: Process.id,
            arch: Process.arch,
            platform: Process.platform,
            pointerSize: Process.pointerSize,
            isDebuggerAttached: Process.isDebuggerAttached()
        };
        GlobalLogger.success("Process info: PID=" + info.pid + ", Arch=" + info.arch);
        return info;
    } catch (e) {
        GlobalLogger.error("Failed to get process info: " + e.message);
        return null;
    }
};
rpc.exports.getprocessinfo = rpc.exports.getProcessInfo;
rpc.exports.get_process_info = rpc.exports.getProcessInfo;

rpc.exports.testConnection = function() {
    console.log('[Snakeloader] testConnection called');
    GlobalLogger.success("RPC Connection Test: SUCCESS");
    return { status: "connected", timestamp: Date.now() };
};
rpc.exports.testconnection = rpc.exports.testConnection;
rpc.exports.test_connection = rpc.exports.testConnection;


SCRIPT_REGISTRY.scripts['combined_toolchain'] = { name: 'combined_toolchain', loaded: true, timestamp: Date.now() };
GlobalLogger.success('=== ENHANCED RPC BRIDGE READY ===');
GlobalLogger.success('Available RPC methods: listRegisteredHooks, getHookStatus, toggleHook, getMemoryDump, readMemoryString, writeMemoryInt, getScriptRegistry, listModules, getProcessInfo, testConnection');
GlobalLogger.success('Test hooks registered: __test_hook__, __memory_test__');

if (typeof Frida !== 'undefined') {
    console.log('[Snakeloader] Frida Environment: ' + Frida.version + ' (' + Script.runtime + ')');
}

console.log('[Snakeloader] === RPC BRIDGE INITIALIZATION COMPLETE ===');
console.log('[Snakeloader] RPC server is expected to be managed by the host (CLI or ZygiskFrida)');

console.log('[Snakeloader] === SCRIPT EXECUTION COMPLETE ===');
console.log('[Snakeloader] Total scripts loaded: ' + Object.keys(SCRIPT_REGISTRY.scripts).length);
console.log('[Snakeloader] Total hooks registered: ' + Object.keys(SCRIPT_REGISTRY.hooks).length);
console.log('[Snakeloader] Hook names: ' + Object.keys(SCRIPT_REGISTRY.hooks).join(', '));
""".trimIndent()

    private fun updateRegistry(script: FridaScript) {
        try {
            val registryFile = File(context.cacheDir, "script_registry.json")
            val entry = """{"script":"${script.name}","timestamp":${System.currentTimeMillis()},"status":"executed"}"""
            registryFile.appendText(entry + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Registry update failed", e)
        }
    }

    fun loadScriptFromAssets(fileName: String): FridaScript? {
        return try {
            val content = context.assets.open(fileName).bufferedReader().use { it.readText() }
            FridaScript(
                name = fileName,
                content = content,
                priority = if (fileName.contains("analyzer") || fileName.contains("tracer")) 10 else 5
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load script from assets: $fileName", e)
            null
        }
    }

    fun getIL2CppHelpers(): String = """
const IL2CppHelpers = {
    getModule() {
        return findModule('libil2cpp.so');
    },
    
    defineNativeFunction(exportName, returnType, argTypes) {
        const il2cpp = this.getModule();
        if (!il2cpp) return null;
        try {
            const addr = il2cpp.findExportByName(exportName);
            return new NativeFunction(addr, returnType, argTypes);
        } catch (e) {
            GlobalLogger.warn(`Failed to define: ${'$'}{exportName}`);
            return null;
        }
    },
    
    findClassByName(className) {
        const il2cpp_domain_get = this.defineNativeFunction('il2cpp_domain_get', 'pointer', []);
        const il2cpp_domain_get_assemblies = this.defineNativeFunction('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']);
        const il2cpp_assembly_get_image = this.defineNativeFunction('il2cpp_assembly_get_image', 'pointer', ['pointer']);
        const il2cpp_image_get_class_count = this.defineNativeFunction('il2cpp_image_get_class_count', 'size_t', ['pointer']);
        const il2cpp_image_get_class = this.defineNativeFunction('il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']);
        const il2cpp_class_get_name = this.defineNativeFunction('il2cpp_class_get_name', 'pointer', ['pointer']);
        
        const domain = il2cpp_domain_get();
        const sizePtr = Memory.alloc(Process.pointerSize);
        const assemblies = il2cpp_domain_get_assemblies(domain, sizePtr);
        const count = sizePtr.readUInt();
        
        for (let i = 0; i < count; i++) {
            const assembly = assemblies.add(i * Process.pointerSize).readPointer();
            const image = il2cpp_assembly_get_image(assembly);
            const classCount = il2cpp_image_get_class_count(image);
            
            for (let j = 0; j < classCount; j++) {
                const klass = il2cpp_image_get_class(image, j);
                const name = il2cpp_class_get_name(klass).readUtf8String();
                if (name === className) return klass;
            }
        }
        return null;
    }
};
""".trimIndent()
}
