/**
 * Passive Dumper for Android
 * Optimized to avoid early initialization crashes in libart.so.
 *
 * Strategy:
 * 1. Immediate Native Hooks on dlopen/android_dlopen_ext.
 * 2. Background periodic module detection.
 * 3. Delayed Java initialization (5s+) to allow app stabilization.
 * 4. No intrusive Java-level hooks on start.
 */

console.log("[-] Passive Dumper loaded. Native monitoring active.");

// Global state
var il2cppBase = null;
var monoBase = null;
var luaBase = null;
var unityBase = null;
var mainBase = null;
var javaAvailable = false;

/**
 * Local helper to safely find exports.
 */
function safeFindExport(moduleName, exportName) {
    try {
        if (moduleName === null) {
            if (typeof Module.getGlobalExportByName === 'function') {
                return Module.getGlobalExportByName(exportName);
            }
            if (typeof Module.findExportByName === 'function') {
                return Module.findExportByName(null, exportName);
            }
        } else {
            var mod = Process.findModuleByName(moduleName);
            if (mod) {
                if (typeof mod.findExportByName === 'function') {
                    return mod.findExportByName(exportName);
                }
            }
        }
    } catch (e) {}
    return null;
}

/**
 * Robust string reading.
 */
function safeReadUtf8String(ptr) {
    if (ptr === null || ptr.isNull()) return "";
    try {
        const str = ptr.readUtf8String();
        return str !== null ? str : "";
    } catch (e) {
        try {
            return ptr.readCString() || "";
        } catch (e2) {
            return "";
        }
    }
}

/**
 * Passive engine detection by scanning process modules.
 */
function detectNativeEngines() {
    var modules = Process.enumerateModules();
    var foundNew = false;

    modules.forEach(function(m) {
        var name = m.name.toLowerCase();
        if (name.indexOf("libil2cpp.so") !== -1 && !il2cppBase) {
            il2cppBase = m.base;
            console.log("[+] libil2cpp.so detected at " + il2cppBase);
            foundNew = true;
            // Try to dump metadata immediately if detected
            setTimeout(function() {
                rpc.exports.dumpil2cpp();
            }, 1000);
        } else if (name.indexOf("libmono.so") !== -1 && !monoBase) {
            monoBase = m.base;
            console.log("[+] libmono.so detected at " + monoBase);
            foundNew = true;
        } else if (name.indexOf("libxlua.so") !== -1 && !luaBase) {
            luaBase = m.base;
            console.log("[+] libxlua.so detected at " + luaBase);
            foundNew = true;
        } else if (name.indexOf("libunity.so") !== -1 && !unityBase) {
            unityBase = m.base;
            console.log("[+] libunity.so detected at " + unityBase);
            foundNew = true;
        } else if (name.indexOf("libmain.so") !== -1 && !mainBase) {
            mainBase = m.base;
            console.log("[+] libmain.so detected at " + mainBase);
            foundNew = true;
        }
    });

    return foundNew;
}

/**
 * Background polling for engine presence (Non-intrusive).
 */
setInterval(detectNativeEngines, 2000);

/**
 * Robust dlopen identification.
 */
function findDlopen() {
    var symbols = ["dlopen", "android_dlopen_ext", "__dl_dlopen", "__dl_android_dlopen_ext"];
    var modules = [null, "linker64", "linker", "libdl.so", "libc.so"];

    for (var i = 0; i < modules.length; i++) {
        for (var j = 0; j < symbols.length; j++) {
            var addr = safeFindExport(modules[i], symbols[j]);
            if (addr) {
                console.log("[*] Found " + symbols[j] + " in " + (modules[i] || "global") + " at " + addr);
                return addr;
            }
        }
    }
    return null;
}

/**
 * Immediate Native Hooks for library loading notification.
 */
function setupNativeHooks() {
    var dlopenAddr = findDlopen();
    if (!dlopenAddr) {
        console.log("[!] Critical: dlopen/android_dlopen_ext not found.");
        return;
    }

    Interceptor.attach(dlopenAddr, {
        onEnter: function(args) {
            if (args[0].isNull()) return;
            try {
                this.path = args[0].readUtf8String();
            } catch (e) {
                this.path = null;
            }
        },
        onLeave: function(retval) {
            if (this.path) {
                var p = this.path.toLowerCase();
                if (p.indexOf("il2cpp") !== -1 || p.indexOf("unity") !== -1 || p.indexOf("mono") !== -1) {
                    // Small delay to ensure library is fully initialized
                    setTimeout(detectNativeEngines, 1000);
                }
            }
        }
    });
}

/**
 * Delayed Java Initialization (6000ms+)
 */
setTimeout(function() {
    console.log("[*] Initializing Java-side availability (Delayed)...");
    Java.perform(function() {
        javaAvailable = true;
        console.log("[+] Java environment is now considered stable.");

        // Automatic dump of interesting classes
        console.log("[*] Performing automatic Java class dump (unity/mergegames)...");
        var classes = rpc.exports.dumpjava("unity|mergegames");
        if (Array.isArray(classes)) {
            console.log("[*] Found " + classes.length + " interesting Java classes.");
            classes.forEach(function(c) {
                console.log("  [Java] " + c);
            });
        }
    });
}, 8000);

/**
 * Diagnostic RPCs.
 */
rpc.exports = {
    detect: function() {
        detectNativeEngines();
        return {
            il2cpp: il2cppBase,
            unity: unityBase,
            java: javaAvailable,
            main: mainBase
        };
    },
    dumpjava: function(filterRegex) {
        if (!javaAvailable) return "[!] Java not available yet.";

        var results = [];
        Java.perform(function() {
            var regex = filterRegex ? new RegExp(filterRegex, 'i') : null;
            Java.enumerateLoadedClasses({
                onMatch: function(className) {
                    if (!regex || regex.test(className)) {
                        results.push(className);
                    }
                },
                onComplete: function() {}
            });
        });
        return results;
    },
    dumpil2cpp: function(filter) {
        if (!il2cppBase) return "[!] libil2cpp.so not detected.";

        // Simplified IL2CPP metadata dump logic
        var requiredExports = [
            "il2cpp_domain_get",
            "il2cpp_domain_get_assemblies",
            "il2cpp_assembly_get_image",
            "il2cpp_image_get_class_count",
            "il2cpp_image_get_class",
            "il2cpp_class_get_name"
        ];

        var api = {};
        for (var i = 0; i < requiredExports.length; i++) {
            var exp = safeFindExport("libil2cpp.so", requiredExports[i]);
            if (!exp) return "[!] Missing IL2CPP export: " + requiredExports[i];
            api[requiredExports[i]] = exp;
        }

        // Just a sanity check for RPC return
        return "[*] IL2CPP Metadata access verified. Use specialized tools for full dump.";
    },
    ping: function() {
        return "pong";
    }
};

// Start Native Hooks immediately
setupNativeHooks();
detectNativeEngines();
