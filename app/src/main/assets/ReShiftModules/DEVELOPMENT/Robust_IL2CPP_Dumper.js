/**
 * Robust IL2CPP Dumper
 * Target: com.mergegames.gossipharbor
 */

// Polyfill for missing Frida globals in this environment
if (typeof Module.findExportByName === 'undefined') {
    Module.findExportByName = function(moduleName, exportName) {
        try {
            if (moduleName === null) {
                if (typeof Module.getGlobalExportByName === 'function') {
                    return Module.getGlobalExportByName(exportName);
                }
                return null;
            }
            let mod = Process.findModuleByName(moduleName);
            return mod ? mod.findExportByName(exportName) : null;
        } catch (e) {
            return null;
        }
    };
}

function log(msg) {
    console.log("[RobustDumper] " + msg);
}

function safeReadCString(ptr) {
    if (!ptr || ptr.isNull()) return "";
    try {
        return ptr.readCString() || "";
    } catch (e) {
        return "";
    }
}

function resolveExport(moduleName, exportName) {
    let addr = Module.findExportByName(moduleName, exportName);
    if (addr && !addr.isNull()) return addr;

    // Fallback: Manually search exports if findExportByName fails
    try {
        let exports = Module.enumerateExports(moduleName);
        for (let i = 0; i < exports.length; i++) {
            if (exports[i].name === exportName) {
                log("Found " + exportName + " via manual search at " + exports[i].address);
                return exports[i].address;
            }
        }
    } catch (e) {}
    return null;
}

const il2cpp_api = {
    domain_get: null,
    domain_get_assemblies: null,
    assembly_get_image: null,
    image_get_name: null,
    image_get_class_count: null,
    image_get_class: null,
    class_get_name: null,
    class_get_namespace: null,
    class_get_methods: null,
    method_get_name: null,
    method_get_param_count: null
};

function initApi(moduleName) {
    log("Initializing IL2CPP APIs...");
    il2cpp_api.domain_get = resolveExport(moduleName, "il2cpp_domain_get");
    il2cpp_api.domain_get_assemblies = resolveExport(moduleName, "il2cpp_domain_get_assemblies");
    il2cpp_api.assembly_get_image = resolveExport(moduleName, "il2cpp_assembly_get_image");
    il2cpp_api.image_get_name = resolveExport(moduleName, "il2cpp_image_get_name");
    il2cpp_api.image_get_class_count = resolveExport(moduleName, "il2cpp_image_get_class_count");
    il2cpp_api.image_get_class = resolveExport(moduleName, "il2cpp_image_get_class");
    il2cpp_api.class_get_name = resolveExport(moduleName, "il2cpp_class_get_name");
    il2cpp_api.class_get_namespace = resolveExport(moduleName, "il2cpp_class_get_namespace");
    il2cpp_api.class_get_methods = resolveExport(moduleName, "il2cpp_class_get_methods");
    il2cpp_api.method_get_name = resolveExport(moduleName, "il2cpp_method_get_name");
    il2cpp_api.method_get_param_count = resolveExport(moduleName, "il2cpp_method_get_param_count");

    for (let key in il2cpp_api) {
        if (il2cpp_api[key] === null) {
            log("Critical Error: Failed to find export " + "il2cpp_" + key);
            return false;
        }
    }
    return true;
}

function dump() {
    if (!initApi("libil2cpp.so")) return;

    const domain_get = new NativeFunction(il2cpp_api.domain_get, 'pointer', []);
    const domain_get_assemblies = new NativeFunction(il2cpp_api.domain_get_assemblies, 'pointer', ['pointer', 'pointer']);
    const assembly_get_image = new NativeFunction(il2cpp_api.assembly_get_image, 'pointer', ['pointer']);
    const image_get_name = new NativeFunction(il2cpp_api.image_get_name, 'pointer', ['pointer']);
    const image_get_class_count = new NativeFunction(il2cpp_api.image_get_class_count, 'size_t', ['pointer']);
    const image_get_class = new NativeFunction(il2cpp_api.image_get_class, 'pointer', ['pointer', 'size_t']);
    const class_get_name = new NativeFunction(il2cpp_api.class_get_name, 'pointer', ['pointer']);
    const class_get_namespace = new NativeFunction(il2cpp_api.class_get_namespace, 'pointer', ['pointer']);
    const class_get_methods = new NativeFunction(il2cpp_api.class_get_methods, 'pointer', ['pointer', 'pointer']);
    const method_get_name = new NativeFunction(il2cpp_api.method_get_name, 'pointer', ['pointer']);

    log("Starting Metadata Dump...");

    const domain = domain_get();
    let size_ptr = Memory.alloc(Process.pointerSize);
    const assemblies = domain_get_assemblies(domain, size_ptr);
    const count = size_ptr.readInt();

    log("Found " + count + " assemblies.");

    for (let i = 0; i < count; i++) {
        const assembly = assemblies.add(i * Process.pointerSize).readPointer();
        const image = assembly_get_image(assembly);
        const imageName = image_get_name(image).readCString();

        log("Assembly: " + imageName);

        const classCount = image_get_class_count(image);
        for (let j = 0; j < classCount; j++) {
            try {
                const klass = image_get_class(image, j);
                if (klass.isNull()) continue;

                const name = safeReadCString(class_get_name(klass));
                const ns = safeReadCString(class_get_namespace(klass));

                if (name) {
                    console.log("  Class: " + (ns ? ns + "." : "") + name);

                    let iter = Memory.alloc(Process.pointerSize);
                    iter.writePointer(NULL);
                    let method;
                    while (true) {
                        method = class_get_methods(klass, iter);
                        if (!method || method.isNull()) break;
                        const methodName = safeReadCString(method_get_name(method));
                        if (methodName) console.log("    Method: " + methodName);
                    }
                }
            } catch (e) {
                // Skip problematic classes
            }
        }
    }
    log("Dump Complete.");
}

function start() {
    const moduleName = "libil2cpp.so";
    let targetModule = Process.findModuleByName(moduleName);

    if (targetModule) {
        log("libil2cpp.so already loaded at " + targetModule.base);
        // Delay slightly to ensure il2cpp is fully initialized by the game
        setTimeout(dump, 2000);
    } else {
        log("Waiting for libil2cpp.so...");

        const dlopen_ptrs = [
            Module.findExportByName(null, "dlopen"),
            Module.findExportByName(null, "android_dlopen_ext")
        ];

        dlopen_ptrs.forEach(ptr => {
            if (!ptr) return;
            Interceptor.attach(ptr, {
                onEnter: function (args) {
                    try {
                        this.path = safeReadCString(args[0]);
                    } catch (e) {
                        this.path = null;
                    }
                },
                onLeave: function (retval) {
                    if (this.path && this.path.indexOf(moduleName) !== -1) {
                        log("Detected " + moduleName + " loading via dlopen");
                        let mod = Process.findModuleByName(moduleName);
                        if (mod) {
                            // Give it a moment to initialize internally
                            setTimeout(dump, 2000);
                        }
                    }
                }
            });
        });
    }
}

Java.perform(function() {
    start();
});
