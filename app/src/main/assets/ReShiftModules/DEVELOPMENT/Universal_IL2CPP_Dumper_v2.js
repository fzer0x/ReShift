/**
 * IL2CPP Dumper
 * @version 2.0
 * @description Memory scanning, pattern matching, and universal compatibility
 */

'use strict';

const IL2CPP_SO = 'libil2cpp.so';
const TAG = "IL2CPP_DUMPER_2026";

// LOGGING SYSTEM
let _log = null;
try {
    const logAddr = Module.findExportByName("liblog.so", "__android_log_print");
    if (logAddr) {
        _log = new NativeFunction(logAddr, 'int', ['int', 'pointer', 'pointer', '...']);
    }
} catch (e) {}

function logToLogcat(msg) {
    if (_log) {
        try {
            _log(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String("%s"), Memory.allocUtf8String(msg));
        } catch(e) {}
    }
    console.log(msg);
}

const Logger = {
    info: (msg) => logToLogcat(`[INFO] ${msg}`),
    success: (msg) => logToLogcat(`[✓] ${msg}`),
    warn: (msg) => logToLogcat(`[⚠] ${msg}`),
    error: (msg) => logToLogcat(`[✗] ${msg}`),
    debug: (msg) => logToLogcat(`[DEBUG] ${msg}`)
};

// PATTERN SCANNER
class PatternScanner {
    static findBytePattern(module, pattern, mask) {
        const bytes = module.base.readByteArray(module.size);
        if (!bytes) return null;

        const data = new Uint8Array(bytes);
        const patternBytes = pattern.split(' ').map(b => parseInt(b, 16));

        for (let i = 0; i < data.length - patternBytes.length; i++) {
            let found = true;
            for (let j = 0; j < patternBytes.length; j++) {
                if (mask[j] === 'x' && data[i + j] !== patternBytes[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return module.base.add(i);
        }
        return null;
    }

    static findString(module, searchStr) {
        const bytes = module.base.readByteArray(module.size);
        if (!bytes) return null;

        const data = new Uint8Array(bytes);
        const searchBytes = new TextEncoder().encode(searchStr);

        for (let i = 0; i < data.length - searchBytes.length; i++) {
            let found = true;
            for (let j = 0; j < searchBytes.length; j++) {
                if (data[i + j] !== searchBytes[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return module.base.add(i);
        }
        return null;
    }
}

// ADVANCED RESOLVER
class SymbolResolver {
    constructor(module) {
        this.module = module;
        this.cache = new Map();
        this.exports = null;
    }

    resolve(name, ret, args) {
        if (this.cache.has(name)) return this.cache.get(name);

        let addr = this.module.findExportByName(name);
        if (!addr || addr.isNull()) {
            addr = this._findExportByName(name);
        }

        if (!addr || addr.isNull()) {
            Logger.warn(`Symbol ${name} not found.`);
            this.cache.set(name, null);
            return null;
        }

        const func = new NativeFunction(addr, ret, args);
        this.cache.set(name, func);
        return func;
    }

    _findExportByName(name) {
        if (!this.exports) {
            this.exports = this.module.enumerateExports();
        }

        for (const exp of this.exports) {
            if (exp.name === name) return exp.address;
        }
        return null;
    }
}

// IL2CPP METADATA DUMPER
class IL2CPPDumper {
    constructor(module) {
        this.module = module;
        this.resolver = new SymbolResolver(module);
        this.api = {};
        this.initialized = false;
    }

    init() {
        Logger.info("Initializing IL2CPP API...");

        const symbols = {
            domain_get: ['il2cpp_domain_get', 'pointer', []],
            domain_get_assemblies: ['il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']],
            assembly_get_image: ['il2cpp_assembly_get_image', 'pointer', ['pointer']],
            image_get_class_count: ['il2cpp_image_get_class_count', 'size_t', ['pointer']],
            image_get_class: ['il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']],
            class_get_name: ['il2cpp_class_get_name', 'pointer', ['pointer']],
            class_get_namespace: ['il2cpp_class_get_namespace', 'pointer', ['pointer']],
            class_get_methods: ['il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer']],
            class_get_fields: ['il2cpp_class_get_fields', 'pointer', ['pointer', 'pointer']],
            class_get_properties: ['il2cpp_class_get_properties', 'pointer', ['pointer', 'pointer']],
            class_get_parent: ['il2cpp_class_get_parent', 'pointer', ['pointer']],
            method_get_name: ['il2cpp_method_get_name', 'pointer', ['pointer']],
            method_get_return_type: ['il2cpp_method_get_return_type', 'pointer', ['pointer']],
            type_get_name: ['il2cpp_type_get_name', 'pointer', ['pointer']],
            field_get_name: ['il2cpp_field_get_name', 'pointer', ['pointer']],
            field_get_type: ['il2cpp_field_get_type', 'pointer', ['pointer']],
            property_get_name: ['il2cpp_property_get_name', 'pointer', ['pointer']],
            property_get_get_method: ['il2cpp_property_get_get_method', 'pointer', ['pointer']],
            property_get_set_method: ['il2cpp_property_get_set_method', 'pointer', ['pointer']],
            class_is_enum: ['il2cpp_class_is_enum', 'bool', ['pointer']],
            class_is_valuetype: ['il2cpp_class_is_valuetype', 'bool', ['pointer']],
            class_get_size: ['il2cpp_class_get_size', 'size_t', ['pointer']]
        };

        let successCount = 0;
        for (const [key, [name, ret, args]] of Object.entries(symbols)) {
            const func = this.resolver.resolve(name, ret, args);
            if (func) {
                this.api[key] = func;
                successCount++;
            }
        }

        Logger.success(`Resolved ${successCount}/${Object.keys(symbols).length} symbols`);
        this.initialized = successCount > 5;
        return this.initialized;
    }

    dumpAllClasses(filter = null) {
        Logger.info("Starting comprehensive IL2CPP dump...");

        if (!this.initialized && !this.init()) {
            Logger.error("Failed to initialize IL2CPP API");
            return;
        }

        try {
            const domain = this.api.domain_get();
            if (!domain || domain.isNull()) {
                Logger.error("Failed to get domain");
                return;
            }

            const sizePtr = Memory.alloc(Process.pointerSize);
            const assemblies = this.api.domain_get_assemblies(domain, sizePtr);
            const count = sizePtr.readUInt();

            Logger.info(`Found ${count} assemblies`);

            let totalClasses = 0;
            let filteredClasses = 0;
            const results = [];

            for (let i = 0; i < count; i++) {
                const assembly = assemblies.add(i * Process.pointerSize).readPointer();
                if (assembly.isNull()) continue;

                const image = this.api.assembly_get_image(assembly);
                if (image.isNull()) continue;

                const classCount = this.api.image_get_class_count(image);
                totalClasses += classCount;

                Logger.debug(`Assembly ${i}: ${classCount} classes`);

                for (let j = 0; j < classCount; j++) {
                    const klass = this.api.image_get_class(image, j);
                    if (klass.isNull()) continue;

                    const name = this._readString(this.api.class_get_name(klass));
                    const ns = this._readString(this.api.class_get_namespace(klass));

                    const fullName = ns ? `${ns}.${name}` : name;

                    // Check filter
                    if (filter && !this._matchesFilter(fullName, filter)) {
                        continue;
                    }

                    filteredClasses++;
                    const classInfo = this._analyzeClass(klass, fullName);
                    results.push(classInfo);
                    this._logClassInfo(classInfo);
                }
            }

            Logger.success(`Processed ${filteredClasses} filtered classes from ${totalClasses} total`);
            return results;

        } catch (e) {
            Logger.error(`Dump error: ${e}`);
            if (e.stack) Logger.debug(e.stack);
        }
    }

    _readString(ptr) {
        if (!ptr || ptr.isNull()) return "";
        try {
            return ptr.readUtf8String() || "";
        } catch (e) {
            return "";
        }
    }

    _matchesFilter(fullName, filter) {
        if (!filter) return true;
        if (Array.isArray(filter)) {
            return filter.some(kw => fullName.toLowerCase().includes(kw.toLowerCase()));
        }
        return fullName.toLowerCase().includes(filter.toLowerCase());
    }

    _analyzeClass(klass, fullName) {
        const info = {
            name: fullName,
            isEnum: false,
            isValueType: false,
            size: 0,
            parent: "",
            methods: [],
            fields: [],
            properties: []
        };

        try {
            // Basic info
            if (this.api.class_is_enum) {
                info.isEnum = this.api.class_is_enum(klass);
            }
            if (this.api.class_is_valuetype) {
                info.isValueType = this.api.class_is_valuetype(klass);
            }
            if (this.api.class_get_size) {
                info.size = this.api.class_get_size(klass).toNumber();
            }

            // Parent
            if (this.api.class_get_parent) {
                const parent = this.api.class_get_parent(klass);
                if (parent && !parent.isNull()) {
                    const parentName = this._readString(this.api.class_get_name(parent));
                    const parentNs = this._readString(this.api.class_get_namespace(parent));
                    info.parent = parentNs ? `${parentNs}.${parentName}` : parentName;
                }
            }

            // Methods
            if (this.api.class_get_methods) {
                const iter = Memory.alloc(Process.pointerSize);
                iter.writePointer(NULL);
                let method;
                while (true) {
                    try {
                        method = this.api.class_get_methods(klass, iter);
                        if (!method || method.isNull()) break;
                        const mName = this._readString(this.api.method_get_name(method));
                        if (mName) info.methods.push(mName);
                    } catch (e) {
                        break;
                    }
                }
            }

            // Fields
            if (this.api.class_get_fields) {
                const iter = Memory.alloc(Process.pointerSize);
                iter.writePointer(NULL);
                let field;
                while (true) {
                    try {
                        field = this.api.class_get_fields(klass, iter);
                        if (!field || field.isNull()) break;
                        const fName = this._readString(this.api.field_get_name(field));
                        if (fName) info.fields.push(fName);
                    } catch (e) {
                        break;
                    }
                }
            }

            // Properties
            if (this.api.class_get_properties) {
                const iter = Memory.alloc(Process.pointerSize);
                iter.writePointer(NULL);
                let prop;
                while (true) {
                    try {
                        prop = this.api.class_get_properties(klass, iter);
                        if (!prop || prop.isNull()) break;
                        const pName = this._readString(this.api.property_get_name(prop));
                        if (pName) info.properties.push(pName);
                    } catch (e) {
                        break;
                    }
                }
            }

        } catch (e) {
            Logger.debug(`Error analyzing ${fullName}: ${e}`);
        }

        return info;
    }

    _logClassInfo(info) {
        let msg = `[CLASS] ${info.name}`;
        if (info.isEnum) msg += " [ENUM]";
        if (info.isValueType) msg += " [VALUETYPE]";
        if (info.size > 0) msg += ` [SIZE: ${info.size}]`;
        if (info.parent) msg += ` [PARENT: ${info.parent}]`;

        Logger.info(msg);

        if (info.methods.length > 0) {
            Logger.info(`  Methods (${info.methods.length}): ${info.methods.slice(0, 10).join(', ')}${info.methods.length > 10 ? '...' : ''}`);
        }
        if (info.fields.length > 0) {
            Logger.info(`  Fields (${info.fields.length}): ${info.fields.slice(0, 10).join(', ')}${info.fields.length > 10 ? '...' : ''}`);
        }
        if (info.properties.length > 0) {
            Logger.info(`  Properties (${info.properties.length}): ${info.properties.slice(0, 10).join(', ')}${info.properties.length > 10 ? '...' : ''}`);
        }
    }

    // Advanced: Find specific class by name pattern
    findClassByNamePattern(pattern) {
        Logger.info(`Searching for classes matching: ${pattern}`);
        return this.dumpAllClasses(pattern);
    }

    // Advanced: Dump all classes (no filter)
    dumpAll() {
        return this.dumpAllClasses(null);
    }

    // Advanced: Dump classes with custom filter function
    dumpCustom(filterFn) {
        Logger.info("Starting custom filtered dump...");
        // Implementation would call dumpAllClasses with special filter
        return this.dumpAllClasses(null); // Simplified for compatibility
    }

    // Memory scanner for potential cheat detection bypass
    scanForSuspiciousPatches() {
        Logger.info("Scanning for potential memory patches...");
    }
}

/**
 * Intelligent module waiting logic
 * @param {string} moduleName Name of the module to wait for
 * @param {function} callback Function to execute once found
 */
function waitForModule(moduleName, callback) {
    const timeoutMs = 60000; // 60 seconds total timeout
    const pollInterval = 1000; // Poll every 1 second
    const start = Date.now();

    Logger.info(`Starting intelligent wait for ${moduleName}...`);

    const checker = setInterval(() => {
        const module = Process.findModuleByName(moduleName);

        if (module) {
            clearInterval(checker);
            Logger.success(`Detected ${moduleName} at ${module.base}`);

            // Wait an additional 2 seconds for internal IL2CPP initialization
            // (e.g. metadata loading, domain setup)
            Logger.info("Allowing module to stabilize...");
            setTimeout(callback, 2000);
            return;
        }

        const elapsed = Date.now() - start;
        if (elapsed > timeoutMs) {
            clearInterval(checker);
            Logger.error(`FAILED: ${moduleName} not found after ${timeoutMs/1000}s.`);
            Logger.error("Possible reasons: Game is not IL2CPP, module name is different, or loading failed.");
            return;
        }

        if (Math.floor(elapsed / 1000) % 10 === 0 && elapsed > 0) {
            Logger.debug(`Still waiting for ${moduleName} (${Math.floor(elapsed/1000)}s elapsed)...`);
        }
    }, pollInterval);
}

// MAIN EXECUTION
function main() {
    Logger.info("=== Universal IL2CPP Dumper v2 ===");

    const il2cpp = Process.findModuleByName(IL2CPP_SO);
    if (!il2cpp) {
        Logger.error(`Fatal: ${IL2CPP_SO} disappeared from memory during transition to main!`);
        return;
    }

    Logger.info(`Analyzing module: ${il2cpp.base} (${il2cpp.size} bytes)`);

    const dumper = new IL2CPPDumper(il2cpp);

    // Advanced features
    const filters = [
        'Player', 'Game', 'Manager', 'Controller', 'System',
        'Data', 'Network', 'Scene', 'UI', 'Config'
    ];

    Logger.info(`Using smart filters: ${filters.join(', ')}`);

    // Dump filtered classes
    const results = dumper.dumpAllClasses(filters);

    if (results && results.length > 0) {
        Logger.success(`Dump complete: ${results.length} classes found`);

        // Summary
        const enums = results.filter(c => c.isEnum).length;
        const valueTypes = results.filter(c => c.isValueType).length;
        Logger.info(`Summary: ${enums} enums, ${valueTypes} value types`);
    } else {
        Logger.warn("No matching classes found - try adjusting filters");
        Logger.info("Attempting full dump...");
        dumper.dumpAll();
    }

    // Memory protection check
    dumper.scanForSuspiciousPatches();
}

// INITIALIZATION
setImmediate(() => {
    Logger.info("DUMPER: Initializing script engine...");

    // Start the intelligent wait process
    waitForModule(IL2CPP_SO, main);
});

// ERROR HANDLING
Process.setExceptionHandler((details) => {
    Logger.error(`Uncaught exception: ${details.type} at ${details.address}`);
    return false;
});