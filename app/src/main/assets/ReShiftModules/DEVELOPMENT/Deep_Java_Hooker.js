/**
 * Deep Java Hooker for ReShift
 * Hooks critical Java entry points for Unity games and specific app logic.
 *
 * Includes:
 * 1. SplashActivity method hooking.
 * 2. IUnityAds implementation discovery and hooking.
 * 3. Common Unity Java entry points (UnityPlayer, UnityAds).
 * 4. Passive native library monitoring.
 */

console.log("[-] Deep Java Hooker loaded.");

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

// --- Native Library Monitoring (Passive) ---
var il2cppBase = null;
var unityBase = null;

function detectNativeEngines() {
    var modules = Process.enumerateModules();
    modules.forEach(function(m) {
        var name = m.name.toLowerCase();
        if (name.indexOf("libil2cpp.so") !== -1 && !il2cppBase) {
            il2cppBase = m.base;
            console.log("[+] libil2cpp.so detected at " + il2cppBase);
        } else if (name.indexOf("libunity.so") !== -1 && !unityBase) {
            unityBase = m.base;
            console.log("[+] libunity.so detected at " + unityBase);
        }
    });
}

function setupNativeHooks() {
    var dlopenAddr = safeFindExport(null, "dlopen") ||
                     safeFindExport(null, "android_dlopen_ext") ||
                     safeFindExport("linker64", "dlopen") ||
                     safeFindExport("linker", "dlopen") ||
                     safeFindExport("libdl.so", "dlopen");

    if (dlopenAddr) {
        Interceptor.attach(dlopenAddr, {
            onEnter: function(args) {
                try {
                    this.path = args[0].readUtf8String();
                } catch(e) {
                    this.path = null;
                }
            },
            onLeave: function(retval) {
                if (this.path && (this.path.indexOf("il2cpp") !== -1 || this.path.indexOf("unity") !== -1)) {
                    // Slight delay to allow the library to load
                    setTimeout(detectNativeEngines, 1000);
                }
            }
        });
        console.log("[*] Native dlopen monitoring active.");
    }
}

// Start native monitoring immediately
setupNativeHooks();
detectNativeEngines();
setInterval(detectNativeEngines, 3000);

// --- Java Method Hooking Utils ---

/**
 * Hooks all declared methods of a class.
 */
function hookAllMethods(className) {
    try {
        var targetClass = Java.use(className);
        var methods = targetClass.class.getDeclaredMethods();
        var methodNames = [];

        for (var i = 0; i < methods.length; i++) {
            var name = methods[i].getName();
            if (methodNames.indexOf(name) === -1) {
                methodNames.push(name);
            }
        }

        methodNames.forEach(function(methodName) {
            try {
                var overloads = targetClass[methodName].overloads;
                overloads.forEach(function(overload) {
                    overload.implementation = function() {
                        var args = [];
                        for (var k = 0; k < arguments.length; k++) {
                            args.push(arguments[k]);
                        }

                        var result;
                        try {
                            result = overload.apply(this, arguments);
                        } catch (e) {
                            console.log("[JavaHook] Exception in " + className + "." + methodName + ": " + e);
                            throw e;
                        }

                        // Clear logging format: [JavaHook] Class.Method(args) -> result
                        console.log("[JavaHook] " + className + "." + methodName + "(" + args.join(", ") + ") -> " + result);
                        return result;
                    };
                });
            } catch (e) {
                // Some methods like 'wait', 'notify' etc might fail or we might not want to hook them
            }
        });
    } catch (err) {
        console.log("[!] Error preparing hooks for " + className + ": " + err);
    }
}

// --- Main Java Hook Execution ---

setTimeout(function() {
    Java.perform(function() {
        console.log("[*] Initializing Deep Java Hooks (3s stabilization delay)...");

        // 1. Hook SplashActivity to monitor early transitions
        var splashClass = "com.mergegames.gossipharbor.SplashActivity";
        console.log("[*] Hooking " + splashClass);
        hookAllMethods(splashClass);

        // 2. Search for IUnityAds implementations
        console.log("[*] Searching for com.microfun.IUnityAds implementations...");
        Java.enumerateLoadedClasses({
            onMatch: function(className) {
                // Filter for likely candidates to improve performance
                if (className.startsWith("com.microfun") || className.startsWith("com.mergegames")) {
                    try {
                        var clazz = Java.use(className);
                        var interfaces = clazz.class.getInterfaces();
                        for (var i = 0; i < interfaces.length; i++) {
                            if (interfaces[i].getName() === "com.microfun.IUnityAds") {
                                console.log("[+] Found IUnityAds implementation: " + className);
                                hookAllMethods(className);
                            }
                        }
                    } catch (e) {}
                }
            },
            onComplete: function() {
                console.log("[*] IUnityAds implementation search complete.");
            }
        });

        // 3. Hook common Unity-related Java entry points
        var unityPlayer = "com.unity3d.player.UnityPlayer";
        var unityAds = "com.unity3d.ads.UnityAds";

        try {
            Java.use(unityPlayer);
            console.log("[*] Hooking " + unityPlayer);
            hookAllMethods(unityPlayer);
        } catch(e) { console.log("[!] UnityPlayer class not found yet."); }

        try {
            Java.use(unityAds);
            console.log("[*] Hooking " + unityAds);
            hookAllMethods(unityAds);
        } catch(e) { console.log("[!] UnityAds class not found yet."); }

        console.log("[+] Java Hooks initialization complete.");
    });
}, 3000);
