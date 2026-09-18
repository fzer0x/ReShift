/**
 * Universal Class Dumper for Android (Java & IL2CPP/Mono/Lua)
 * Target: ReShift Project
 * Version: 2.0 (Final)
 */

console.log("[-] Universal Class Dumper v2.0 loaded.");

// Global state
var il2cppBase = null;
var monoBase = null;
var luaBase = null;
var unityBase = null;
var mainBase = null;
var nativeHookActive = false;

/**
 * Local helper to safely find exports without global polyfills.
 */
function safeFindExport(moduleName, exportName) {
    try {
        var addr = Module.findExportByName(moduleName, exportName);
        if (addr && !addr.isNull()) return addr;
    } catch (e) {}
    return null;
}

/**
 * Robust string reading with UTF-8 safety and error handling.
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

function dumpModules() {
    console.log("[*] Dumping Loaded Modules...");
    Process.enumerateModules().forEach(function(m) {
        console.log(m.name + " -> " + m.base + " (" + m.size + " bytes) " + m.path);
    });
}

/**
 * Enhanced Java Class Dumping with Regex and improved crash protection.
 */
function dumpJavaClasses(filterRegex) {
    var regex = null;
    if (filterRegex) {
        try {
            regex = new RegExp(filterRegex, 'i');
        } catch (e) {
            console.log("[!] Invalid regex: " + filterRegex + ". Using literal match.");
        }
    }

    console.log("[*] Dumping Java Classes (Filter: " + (filterRegex || "none") + ")...");

    Java.perform(function() {
        try {
            Java.enumerateLoadedClasses({
                onMatch: function(className) {
                    var match = false;
                    if (!regex) {
                        match = true;
                    } else {
                        match = regex.test(className);
                    }

                    if (match) {
                        console.log("[Java Class] " + className);
                        try {
                            // Deep wrap to prevent "Class not found" crashes
                            var cls = null;
                            try {
                                cls = Java.use(className);
                            } catch (e1) {
                                // Some classes (like proxies or generated ones) can't be 'use'd directly
                                return;
                            }

                            if (cls) {
                                var methods = cls.class.getDeclaredMethods();
                                methods.forEach(function(m) {
                                    console.log("  [Method] " + m.toString());
                                });
                            }
                        } catch (e) {
                            // Suppress errors for inaccessible class members
                        }
                    }
                },
                onComplete: function() {
                    console.log("[*] Java Class Dump Complete.");
                }
            });
        } catch (e) {
            console.log("[!] Java enumeration failed: " + e);
        }
    });
}

function detectNativeEngines() {
    var il2cpp = Process.findModuleByName("libil2cpp.so");
    var mono = Process.findModuleByName("libmono.so");
    var xlua = Process.findModuleByName("libxlua.so");
    var unity = Process.findModuleByName("libunity.so");
    var main = Process.findModuleByName("libmain.so");

    if (il2cpp && !il2cppBase) {
        il2cppBase = il2cpp.base;
        console.log("[+] libil2cpp.so detected at " + il2cppBase);
    }
    if (mono && !monoBase) {
        monoBase = mono.base;
        console.log("[+] libmono.so detected at " + monoBase);
    }
    if (xlua && !luaBase) {
        luaBase = xlua.base;
        console.log("[+] libxlua.so (Lua) detected at " + luaBase);
    }
    if (unity && !unityBase) {
        unityBase = unity.base;
        console.log("[+] libunity.so detected at " + unityBase);
    }
    if (main && !mainBase) {
        mainBase = main.base;
        console.log("[+] libmain.so (Unity Entry) detected at " + mainBase);
    }
}

/**
 * Hook System.loadLibrary and System.load to track native library loading.
 */
function hookJavaLibraryLoading() {
    Java.perform(function() {
        var System = Java.use('java.lang.System');

        System.loadLibrary.overload('java.lang.String').implementation = function(library) {
            if (nativeHookActive) console.log("[Java] System.loadLibrary('" + library + "')");
            try {
                this.loadLibrary(library);
                checkInterestingLibrary(library);
            } catch (e) {
                if (nativeHookActive) console.log("[!] Error in loadLibrary(" + library + "): " + e);
            }
        };

        System.load.overload('java.lang.String').implementation = function(library) {
            if (nativeHookActive) console.log("[Java] System.load('" + library + "')");
            try {
                this.load(library);
                checkInterestingLibrary(library);
            } catch (e) {
                if (nativeHookActive) console.log("[!] Error in load(" + library + "): " + e);
            }
        };
    });
}

function checkInterestingLibrary(libName) {
    var interesting = ["il2cpp", "unity", "xlua", "mono", "main"];
    interesting.forEach(function(item) {
        if (libName.toLowerCase().indexOf(item) !== -1) {
            if (nativeHookActive) console.log("[!] Interesting library loading activity: " + libName);
            // Re-scan engines after a short delay
            setTimeout(detectNativeEngines, 500);
        }
    });
}

function dumpIL2CPPMetadata(filter) {
    if (!il2cppBase) {
        console.log("[!] libil2cpp.so not loaded. Cannot dump IL2CPP.");
        return;
    }

    console.log("[*] Dumping IL2CPP Metadata...");

    var requiredExports = [
        "il2cpp_domain_get",
        "il2cpp_domain_get_assemblies",
        "il2cpp_assembly_get_image",
        "il2cpp_image_get_class_count",
        "il2cpp_image_get_class",
        "il2cpp_class_get_name",
        "il2cpp_class_get_namespace",
        "il2cpp_class_get_methods",
        "il2cpp_method_get_name"
    ];

    var api = {};
    for (var i = 0; i < requiredExports.length; i++) {
        var expName = requiredExports[i];
        var addr = safeFindExport("libil2cpp.so", expName);
        if (!addr) {
            console.log("[!] IL2CPP export '" + expName + "' not found. Skipping dump.");
            return;
        }

        switch (expName) {
            case "il2cpp_domain_get": api[expName] = new NativeFunction(addr, 'pointer', []); break;
            case "il2cpp_domain_get_assemblies": api[expName] = new NativeFunction(addr, 'pointer', ['pointer', 'pointer']); break;
            case "il2cpp_assembly_get_image": api[expName] = new NativeFunction(addr, 'pointer', ['pointer']); break;
            case "il2cpp_image_get_class_count": api[expName] = new NativeFunction(addr, 'size_t', ['pointer']); break;
            case "il2cpp_image_get_class": api[expName] = new NativeFunction(addr, 'pointer', ['pointer', 'size_t']); break;
            case "il2cpp_class_get_name": api[expName] = new NativeFunction(addr, 'pointer', ['pointer']); break;
            case "il2cpp_class_get_namespace": api[expName] = new NativeFunction(addr, 'pointer', ['pointer']); break;
            case "il2cpp_class_get_methods": api[expName] = new NativeFunction(addr, 'pointer', ['pointer', 'pointer']); break;
            case "il2cpp_method_get_name": api[expName] = new NativeFunction(addr, 'pointer', ['pointer']); break;
        }
    }

    try {
        var domain = api.il2cpp_domain_get();
        var size_ptr = Memory.alloc(Process.pointerSize);
        var assemblies = api.il2cpp_domain_get_assemblies(domain, size_ptr);
        var count = size_ptr.readInt();

        for (var i = 0; i < count; i++) {
            var assembly = assemblies.add(i * Process.pointerSize).readPointer();
            var image = api.il2cpp_assembly_get_image(assembly);
            var classCount = api.il2cpp_image_get_class_count(image);

            for (var j = 0; j < classCount; j++) {
                var klass = api.il2cpp_image_get_class(image, j);
                var name = safeReadUtf8String(api.il2cpp_class_get_name(klass));
                var ns = safeReadUtf8String(api.il2cpp_class_get_namespace(klass));
                var fullName = ns + "." + name;

                if (!filter || fullName.toLowerCase().includes(filter.toLowerCase())) {
                    console.log("[IL2CPP Class] " + fullName);

                    var iter = Memory.alloc(Process.pointerSize);
                    iter.writePointer(NULL);
                    var method;
                    while (!(method = api.il2cpp_class_get_methods(klass, iter)).isNull()) {
                        var methodName = safeReadUtf8String(api.il2cpp_method_get_name(method));
                        console.log("  [Method] " + methodName + " @ " + method);
                    }
                }
            }
        }
    } catch (e) {
        console.log("[!] Error during IL2CPP dump: " + e);
    }
}

function findDlopen() {
    return safeFindExport(null, "dlopen") ||
           safeFindExport("linker64", "dlopen") ||
           safeFindExport("linker", "dlopen") ||
           safeFindExport("libc.so", "dlopen") ||
           safeFindExport(null, "android_dlopen_ext") ||
           safeFindExport("linker64", "android_dlopen_ext") ||
           safeFindExport("linker", "android_dlopen_ext");
}

function waitForLib(libName, callback) {
    var mod = Process.findModuleByName(libName);
    if (mod) {
        callback(mod.base);
        return;
    }

    var dlopenAddr = findDlopen();
    if (!dlopenAddr) return;

    var interceptor = Interceptor.attach(dlopenAddr, {
        onEnter: function(args) {
            if (args[0].isNull()) return;
            try {
                this.path = args[0].readUtf8String();
            } catch (e) {
                this.path = null;
            }
            if (nativeHookActive && this.path) {
                console.log("[Native] dlopen('" + this.path + "')");
            }
        },
        onLeave: function(retval) {
            if (this.path && this.path.indexOf(libName) !== -1) {
                var loaded = Process.findModuleByName(libName);
                if (loaded) {
                    console.log("[+] " + libName + " loaded dynamically!");
                    callback(loaded.base);
                    interceptor.detach();
                }
            }
        }
    });
}

function enableNativeHooks(enable) {
    nativeHookActive = !!enable;
    console.log("[*] Native Hooking Mode: " + (nativeHookActive ? "ENABLED" : "DISABLED"));
}

rpc.exports = {
    dumpjava: function(filterRegex) {
        dumpJavaClasses(filterRegex);
    },
    dumpil2cpp: function(filter) {
        dumpIL2CPPMetadata(filter);
    },
    dumpmodules: function() {
        dumpModules();
    },
    setNativeHook: function(enable) {
        enableNativeHooks(enable);
    },
    detectEngines: function() {
        detectNativeEngines();
    },
    search: function(name) {
        console.log("[*] Searching for '" + name + "'...");
        dumpJavaClasses(name);
        detectNativeEngines();
        if (il2cppBase) {
            dumpIL2CPPMetadata(name);
        }
    }
};

// Auto-Run
setImmediate(function() {
    detectNativeEngines();
    hookJavaLibraryLoading();

    waitForLib("libil2cpp.so", function(base) {
        il2cppBase = base;
        console.log("[+] IL2CPP Ready.");
    });

    waitForLib("libunity.so", function(base) {
        unityBase = base;
        console.log("[+] Unity Engine Ready.");
    });

    waitForLib("libmain.so", function(base) {
        mainBase = base;
        console.log("[+] libmain.so Ready.");
    });
});
