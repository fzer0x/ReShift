/**
 * Universal IL2CPP Dumper v2 (ReShift PowerUser High-Performance Engine)
 * High-Speed Frida Native File I/O + Immediate File Creation for 20,000+ Classes
 */

'use strict';

const IL2CPP_SO = 'libil2cpp.so';
const TAG = "ReShift_IL2CPP";

function emitStatus(status, details) {
    console.log("[IL2CPP_STATUS] " + status + (details ? " " + details : ""));
}

function safeReadCString(ptr) {
    if (!ptr || ptr.isNull()) return "";
    try {
        return ptr.readUtf8String() || ptr.readCString() || "";
    } catch (e) {
        return "";
    }
}

class NativeFileWriter {
    constructor(filePath) {
        this.path = filePath;
        this.file = null;
        try {
            this.file = new File(filePath, "w");
        } catch (e) {
            emitStatus("FILE_INIT_ERR", e.message);
        }
    }

    write(text) {
        if (!this.file) return false;
        try {
            this.file.write(text);
            return true;
        } catch (e) {
            return false;
        }
    }

    flush() {
        if (this.file) {
            try { this.file.flush(); } catch(e) {}
        }
    }

    close() {
        if (this.file) {
            try {
                this.file.flush();
                this.file.close();
                this.file = null;
            } catch(e) {}
        }
    }
}

class SymbolResolver {
    constructor(module) {
        this.module = module;
        this.cache = new Map();
        this.exports = null;
    }

    findAddr(name) {
        let addr = this.module.findExportByName(name);
        if (!addr || addr.isNull()) {
            addr = this._findExportByName(name);
        }
        return (addr && !addr.isNull()) ? addr : null;
    }

    resolve(name, ret, args) {
        if (this.cache.has(name)) return this.cache.get(name);

        const addr = this.findAddr(name);
        if (!addr) {
            this.cache.set(name, null);
            return null;
        }

        const func = new NativeFunction(addr, ret || 'pointer', args || []);
        this.cache.set(name, func);
        return func;
    }

    _findExportByName(name) {
        if (!this.exports) {
            try {
                this.exports = this.module.enumerateExports();
            } catch(e) {
                this.exports = [];
            }
        }

        for (let i = 0; i < this.exports.length; i++) {
            if (this.exports[i].name === name) return this.exports[i].address;
        }
        return null;
    }
}

function getOutputPaths() {
    let pkg = (typeof TARGET_PACKAGE !== 'undefined') ? TARGET_PACKAGE : "";
    let baseDir = pkg ? ("/data/data/" + pkg + "/cache") : "/data/local/tmp";

    return {
        dumpPath: baseDir + "/il2cpp_dump.json",
        donePath: baseDir + "/il2cpp_done.flag"
    };
}

class UniversalIL2CPPDumper {
    constructor(module) {
        this.module = module;
        this.baseAddr = module.base;
        this.resolver = new SymbolResolver(module);
        this.api = {};
        this.initialized = false;
    }

    init() {
        const symbols = {
            domain_get: ['il2cpp_domain_get', 'pointer', []],
            domain_get_assemblies: ['il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']],
            assembly_get_image: ['il2cpp_assembly_get_image', 'pointer', ['pointer']],
            image_get_name: ['il2cpp_image_get_name', 'pointer', ['pointer']],
            image_get_class_count: ['il2cpp_image_get_class_count', 'size_t', ['pointer']],
            image_get_class: ['il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']],
            class_get_name: ['il2cpp_class_get_name', 'pointer', ['pointer']],
            class_get_namespace: ['il2cpp_class_get_namespace', 'pointer', ['pointer']],
            class_get_methods: ['il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer']],
            class_get_fields: ['il2cpp_class_get_fields', 'pointer', ['pointer', 'pointer']],
            class_get_properties: ['il2cpp_class_get_properties', 'pointer', ['pointer', 'pointer']],
            class_get_parent: ['il2cpp_class_get_parent', 'pointer', ['pointer']],
            method_get_name: ['il2cpp_method_get_name', 'pointer', ['pointer']],
            method_get_pointer: ['il2cpp_method_get_pointer', 'pointer', ['pointer']],
            method_get_return_type: ['il2cpp_method_get_return_type', 'pointer', ['pointer']],
            method_get_param_count: ['il2cpp_method_get_param_count', 'uint32', ['pointer']],
            method_get_param: ['il2cpp_method_get_param', 'pointer', ['pointer', 'uint32']],
            method_get_param_name: ['il2cpp_method_get_param_name', 'pointer', ['pointer', 'uint32']],
            type_get_name: ['il2cpp_type_get_name', 'pointer', ['pointer']],
            field_get_name: ['il2cpp_field_get_name', 'pointer', ['pointer']],
            field_get_type: ['il2cpp_field_get_type', 'pointer', ['pointer']],
            field_get_offset: ['il2cpp_field_get_offset', 'size_t', ['pointer']],
            property_get_name: ['il2cpp_property_get_name', 'pointer', ['pointer']],
            class_is_enum: ['il2cpp_class_is_enum', 'bool', ['pointer']],
            class_is_valuetype: ['il2cpp_class_is_valuetype', 'bool', ['pointer']],
            class_is_interface: ['il2cpp_class_is_interface', 'bool', ['pointer']],
            class_get_size: ['il2cpp_class_get_size', 'int32', ['pointer']]
        };

        let successCount = 0;
        for (const [key, [name, ret, args]] of Object.entries(symbols)) {
            const func = this.resolver.resolve(name, ret, args);
            if (func) {
                this.api[key] = func;
                successCount++;
            }
        }

        this.initialized = successCount > 5;
        return this.initialized;
    }

    startDump() {
        emitStatus("STARTING", "Base=" + this.baseAddr + " Size=" + this.module.size);

        if (!this.initialized && !this.init()) {
            emitStatus("ERROR", "Failed to resolve IL2CPP exports");
            return;
        }

        try {
            const domain = this.api.domain_get();
            if (!domain || domain.isNull()) {
                emitStatus("ERROR", "il2cpp_domain_get returned NULL");
                return;
            }

            const sizePtr = Memory.alloc(Process.pointerSize);
            const assemblies = this.api.domain_get_assemblies(domain, sizePtr);
            const count = sizePtr.readUInt();

            emitStatus("EXTRACTING", "Found " + count + " assemblies. Resolving output path...");

            const paths = getOutputPaths();
            let dumpPath = paths.dumpPath;
            let donePath = paths.donePath;

            let outFile = new NativeFileWriter(dumpPath);
            if (!outFile.file) {
                dumpPath = "/data/local/tmp/il2cpp_dump.json";
                donePath = "/data/local/tmp/il2cpp_done.flag";
                outFile = new NativeFileWriter(dumpPath);
            }

            if (outFile.file) {
                emitStatus("FILE_OK", dumpPath);
                outFile.write("[\n");
                outFile.flush();
            } else {
                emitStatus("FILE_ERR", "Cannot open file for writing: " + dumpPath);
            }

            let totalClassCount = 0;
            let isFirstInFile = true;
            let asmIndex = 0;
            let batchBuffer = [];

            const self = this;

            function flushBatchRpc() {
                if (batchBuffer.length > 0) {
                    try {
                        send({ type: "IL2CPP_RPC_STREAM_BATCH", classes: batchBuffer });
                    } catch (e) {}
                    batchBuffer = [];
                }
            }

            function processNextAssembly() {
                if (asmIndex >= count) {
                    flushBatchRpc();
                    if (outFile && outFile.file) {
                        try {
                            outFile.write("\n]");
                            outFile.flush();
                            outFile.close();
                        } catch (e) {}
                    }

                    try {
                        const doneFile = new NativeFileWriter(donePath);
                        doneFile.write("DONE");
                        doneFile.close();

                        const doneFileTmp = new NativeFileWriter("/data/local/tmp/il2cpp_done.flag");
                        doneFileTmp.write("DONE");
                        doneFileTmp.close();
                    } catch (e) {}

                    emitStatus("SAVED", dumpPath);
                    emitStatus("FINISHED", "Count=" + totalClassCount);
                    return;
                }

                try {
                    const assembly = assemblies.add(asmIndex * Process.pointerSize).readPointer();
                    if (!assembly.isNull()) {
                        const image = self.api.assembly_get_image(assembly);
                        if (!image.isNull()) {
                            const imageName = self.api.image_get_name ? safeReadCString(self.api.image_get_name(image)) : ("Assembly_" + asmIndex);
                            const classCount = self.api.image_get_class_count(image);

                            for (let j = 0; j < classCount; j++) {
                                try {
                                    const klass = self.api.image_get_class(image, j);
                                    if (klass.isNull()) continue;

                                    const name = safeReadCString(self.api.class_get_name(klass));
                                    if (!name) continue;

                                    const ns = safeReadCString(self.api.class_get_namespace(klass));
                                    const fullName = ns ? (ns + "." + name) : name;

                                    let parentName = "";
                                    if (self.api.class_get_parent) {
                                        const parentKlass = self.api.class_get_parent(klass);
                                        if (parentKlass && !parentKlass.isNull()) {
                                            const pName = safeReadCString(self.api.class_get_name(parentKlass));
                                            const pNs = safeReadCString(self.api.class_get_namespace(parentKlass));
                                            parentName = pNs ? (pNs + "." + pName) : pName;
                                        }
                                    }

                                    const isEnum = self.api.class_is_enum ? self.api.class_is_enum(klass) : false;
                                    const isValueType = self.api.class_is_valuetype ? self.api.class_is_valuetype(klass) : false;
                                    const isInterface = self.api.class_is_interface ? self.api.class_is_interface(klass) : false;
                                    const classSize = self.api.class_get_size ? self.api.class_get_size(klass) : 0;

                                    // Methods Analysis
                                    const methodsList = [];
                                    if (self.api.class_get_methods && self.api.method_get_name) {
                                        const iter = Memory.alloc(Process.pointerSize);
                                        iter.writePointer(NULL);
                                        let method;
                                        let mSafety = 0;
                                        while (mSafety++ < 2000) {
                                            try {
                                                method = self.api.class_get_methods(klass, iter);
                                                if (!method || method.isNull()) break;

                                                const mName = safeReadCString(self.api.method_get_name(method));
                                                if (!mName) continue;

                                                let retTypeStr = "void";
                                                if (self.api.method_get_return_type && self.api.type_get_name) {
                                                    const retType = self.api.method_get_return_type(method);
                                                    if (retType && !retType.isNull()) {
                                                        retTypeStr = safeReadCString(self.api.type_get_name(retType)) || "void";
                                                    }
                                                }

                                                const paramCount = self.api.method_get_param_count ? self.api.method_get_param_count(method) : 0;
                                                const paramsArr = [];
                                                if (paramCount > 0 && paramCount < 50 && self.api.method_get_param && self.api.type_get_name) {
                                                    for (let pIdx = 0; pIdx < paramCount; pIdx++) {
                                                        const pType = self.api.method_get_param(method, pIdx);
                                                        const pTypeName = pType ? safeReadCString(self.api.type_get_name(pType)) : "var";
                                                        const pName = self.api.method_get_param_name ? safeReadCString(self.api.method_get_param_name(method, pIdx)) : ("arg" + pIdx);
                                                        paramsArr.push(pTypeName + " " + pName);
                                                    }
                                                }

                                                let methodOffset = "0x0";
                                                let methodPtrStr = "0x0";
                                                try {
                                                    let methodPtr = null;
                                                    if (self.api.method_get_pointer) {
                                                        methodPtr = self.api.method_get_pointer(method);
                                                    }

                                                    if (!methodPtr || methodPtr.isNull() || methodPtr.compare(self.baseAddr) <= 0) {
                                                        const candidate0 = method.readPointer();
                                                        if (candidate0 && !candidate0.isNull() && candidate0.compare(self.baseAddr) > 0) {
                                                            methodPtr = candidate0;
                                                        } else {
                                                            const ptrSize = Process.pointerSize;
                                                            for (let off = 1; off <= 4; off++) {
                                                                const cand = method.add(off * ptrSize).readPointer();
                                                                if (cand && !cand.isNull() && cand.compare(self.baseAddr) > 0 && cand.compare(self.baseAddr.add(self.module.size)) < 0) {
                                                                    methodPtr = cand;
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                    }

                                                    if (methodPtr && !methodPtr.isNull()) {
                                                        methodPtrStr = methodPtr.toString();
                                                        if (methodPtr.compare(self.baseAddr) > 0) {
                                                            const rva = methodPtr.sub(self.baseAddr);
                                                            methodOffset = "0x" + rva.toString(16).toUpperCase();
                                                        }
                                                    }
                                                } catch (mErr) {}

                                                methodsList.push({
                                                    name: mName,
                                                    returnType: retTypeStr,
                                                    paramCount: paramCount,
                                                    params: paramsArr,
                                                    offset: methodOffset,
                                                    pointer: methodPtrStr
                                                });
                                            } catch (e) {
                                                break;
                                            }
                                        }
                                    }

                                    // Fields Analysis
                                    const fieldsList = [];
                                    if (self.api.class_get_fields && self.api.field_get_name) {
                                        const iter = Memory.alloc(Process.pointerSize);
                                        iter.writePointer(NULL);
                                        let field;
                                        let fSafety = 0;
                                        while (fSafety++ < 2000) {
                                            try {
                                                field = self.api.class_get_fields(klass, iter);
                                                if (!field || field.isNull()) break;

                                                const fName = safeReadCString(self.api.field_get_name(field));
                                                if (!fName) continue;

                                                let fTypeStr = "var";
                                                if (self.api.field_get_type && self.api.type_get_name) {
                                                    const fType = self.api.field_get_type(field);
                                                    if (fType && !fType.isNull()) {
                                                        fTypeStr = safeReadCString(self.api.type_get_name(fType)) || "var";
                                                    }
                                                }

                                                const fOffsetVal = self.api.field_get_offset ? self.api.field_get_offset(field) : 0;
                                                const fOffsetStr = "0x" + fOffsetVal.toString(16).toUpperCase();

                                                fieldsList.push({
                                                    name: fName,
                                                    type: fTypeStr,
                                                    offset: fOffsetStr
                                                });
                                            } catch (e) {
                                                break;
                                            }
                                        }
                                    }

                                    // Properties Analysis
                                    const propertiesList = [];
                                    if (self.api.class_get_properties && self.api.property_get_name) {
                                        const iter = Memory.alloc(Process.pointerSize);
                                        iter.writePointer(NULL);
                                        let prop;
                                        let prSafety = 0;
                                        while (prSafety++ < 2000) {
                                            try {
                                                prop = self.api.class_get_properties(klass, iter);
                                                if (!prop || prop.isNull()) break;

                                                const prName = safeReadCString(self.api.property_get_name(prop));
                                                if (prName) {
                                                    propertiesList.push({
                                                        name: prName
                                                    });
                                                }
                                            } catch (e) {
                                                break;
                                            }
                                        }
                                    }

                                    const classObj = {
                                        name: name || "",
                                        namespace: ns || "",
                                        fullName: fullName || name || "",
                                        assembly: imageName || ""
                                    };
                                    if (parentName) classObj.parent = parentName;
                                    if (isEnum) classObj.isEnum = true;
                                    if (isValueType) classObj.isValueType = true;
                                    if (isInterface) classObj.isInterface = true;
                                    if (classSize) classObj.size = classSize;
                                    if (methodsList.length > 0) classObj.methods = methodsList;
                                    if (fieldsList.length > 0) classObj.fields = fieldsList;
                                    if (propertiesList.length > 0) classObj.properties = propertiesList;

                                    totalClassCount++;
                                    batchBuffer.push(classObj);

                                    if (batchBuffer.length >= 50) {
                                        flushBatchRpc();
                                    }

                                    if (outFile && outFile.file) {
                                        try {
                                            const jsonChunk = (isFirstInFile ? "" : ",\n") + JSON.stringify(classObj);
                                            outFile.write(jsonChunk);
                                            isFirstInFile = false;
                                        } catch (e) {}
                                    }

                                } catch (cErr) {}
                            }
                        }
                    }
                } catch (aErr) {}

                asmIndex++;
                if (outFile && outFile.file && asmIndex % 2 === 0) {
                    outFile.flush();
                }
                emitStatus("PROGRESS", "Assembly " + asmIndex + "/" + count + " (" + totalClassCount + " classes)...");
                setTimeout(processNextAssembly, 2);
            }

            processNextAssembly();

        } catch (e) {
            emitStatus("ERROR", e.toString());
        }
    }
}

function start() {
    let triggered = false;
    let pollTimer = null;

    const delaySec = (typeof INJECTION_DELAY_SEC !== 'undefined') ? Number(INJECTION_DELAY_SEC) : 0;
    if (delaySec > 0) {
        emitStatus("WAITING_DELAY", "Waiting " + delaySec + " seconds before starting injection...");
    }

    setTimeout(function() {
        function checkAndDump(mod) {
            if (triggered) return;
            triggered = true;
            if (pollTimer) clearInterval(pollTimer);

            emitStatus("INITIALIZING", "Found " + IL2CPP_SO + " at " + mod.base + ". Waiting for IL2CPP domain...");

            const resolver = new SymbolResolver(mod);
            let domainRetries = 0;
            const domainInterval = setInterval(function() {
                domainRetries++;
                const domainGetFunc = resolver.resolve("il2cpp_domain_get", "pointer", []);
                if (domainGetFunc) {
                    try {
                        const domain = domainGetFunc();
                        if (domain && !domain.isNull()) {
                            clearInterval(domainInterval);
                            emitStatus("READY", "Domain initialized after " + domainRetries + "s. Extracting metadata...");
                            setTimeout(function() {
                                const dumper = new UniversalIL2CPPDumper(mod);
                                dumper.startDump();
                            }, 500);
                            return;
                        }
                    } catch(e) {}
                }

                if (domainRetries >= 90) {
                    clearInterval(domainInterval);
                    emitStatus("ERROR", "Domain initialization timed out after 90s");
                } else if (domainRetries % 5 === 0) {
                    emitStatus("WAITING_DOMAIN", "Waiting for il2cpp_domain_get() (" + domainRetries + "s)...");
                }
            }, 1000);
        }

        const targetModule = Process.findModuleByName(IL2CPP_SO);
        if (targetModule) {
            checkAndDump(targetModule);
        } else {
            emitStatus("WAITING", "Waiting for " + IL2CPP_SO + "...");

            pollTimer = setInterval(function() {
                const mod = Process.findModuleByName(IL2CPP_SO);
                if (mod) {
                    checkAndDump(mod);
                }
            }, 1000);

            const dlopenSymbols = ["dlopen", "android_dlopen_ext", "__loader_dlopen"];
            for (let i = 0; i < dlopenSymbols.length; i++) {
                const symbolName = dlopenSymbols[i];
                let ptr = null;
                try {
                    ptr = Module.findExportByName("libc.so", symbolName) || Module.findExportByName("libdl.so", symbolName) || Module.findExportByName(null, symbolName);
                } catch(e) {}
                if (ptr) {
                    try {
                        Interceptor.attach(ptr, {
                            onEnter: function (args) {
                                try {
                                    this.path = safeReadCString(args[0]);
                                } catch (e) {
                                    this.path = null;
                                }
                            },
                            onLeave: function (retval) {
                                if (this.path && this.path.indexOf(IL2CPP_SO) !== -1) {
                                    let mod = Process.findModuleByName(IL2CPP_SO);
                                    if (mod) {
                                        checkAndDump(mod);
                                    }
                                }
                            }
                        });
                    } catch (e) {}
                }
            }
        }
    }, delaySec * 1000);
}

setImmediate(function() {
    start();
});
