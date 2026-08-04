/**
 * Senior IL2CPP Dumper - CSR2 Edition (Fixed)
 */

'use strict';

const IL2CPP_SO = 'libil2cpp.so';
const TAG = "IL2CPP_DUMPER";

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
    warn: (msg) => logToLogcat(`[!] ${msg}`),
    error: (msg) => logToLogcat(`[✗] ${msg}`)
};

function dumpIl2Cpp() {
    Logger.info("Starting IL2CPP Dump...");

    const il2cpp = Process.findModuleByName(IL2CPP_SO);
    if (!il2cpp) {
        Logger.error('libil2cpp.so not found');
        return;
    }

    Logger.success(`Base: ${il2cpp.base}`);

    const resolve = (name, ret, args) => {
        let addr = il2cpp.findExportByName(name);
        if (!addr || addr.isNull()) {
            // Manual fallback
            const exports = il2cpp.enumerateExports();
            for (const exp of exports) {
                if (exp.name === name) {
                    addr = exp.address;
                    break;
                }
            }
        }

        if (!addr || addr.isNull()) {
            Logger.warn(`Symbol ${name} not found.`);
            return null;
        }
        return new NativeFunction(addr, ret, args);
    };

    const api = {
        domain_get: resolve('il2cpp_domain_get', 'pointer', []),
        domain_get_assemblies: resolve('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']),
        assembly_get_image: resolve('il2cpp_assembly_get_image', 'pointer', ['pointer']),
        image_get_class_count: resolve('il2cpp_image_get_class_count', 'size_t', ['pointer']),
        image_get_class: resolve('il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']),
        class_get_name: resolve('il2cpp_class_get_name', 'pointer', ['pointer']),
        class_get_namespace: resolve('il2cpp_class_get_namespace', 'pointer', ['pointer']),
        class_get_methods: resolve('il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer']),
        method_get_name: resolve('il2cpp_method_get_name', 'pointer', ['pointer']),
        method_get_return_type: resolve('il2cpp_method_get_return_type', 'pointer', ['pointer']),
        type_get_name: resolve('il2cpp_type_get_name', 'pointer', ['pointer'])
    };

    if (!api.domain_get) return;

    try {
        const domain = api.domain_get();
        const sizePtr = Memory.alloc(Process.pointerSize);
        const assemblies = api.domain_get_assemblies(domain, sizePtr);
        const count = sizePtr.readUInt();

        Logger.info(`Assemblies: ${count}`);

        const keywords = ['Fuel', 'Cash', 'Gear', 'Race', 'Player', 'Snake', 'Cheat'];

        for (let i = 0; i < count; i++) {
            const assembly = assemblies.add(i * Process.pointerSize).readPointer();
            const image = api.assembly_get_image(assembly);
            const classCount = api.image_get_class_count(image);

            for (let j = 0; j < classCount; j++) {
                const klass = api.image_get_class(image, j);
                if (klass.isNull()) continue;

                const name = api.class_get_name(klass).readUtf8String();
                const ns = api.class_get_namespace(klass).readUtf8String();

                let match = false;
                for (const kw of keywords) {
                    if (name.toLowerCase().includes(kw.toLowerCase())) {
                        match = true;
                        break;
                    }
                }

                if (match) {
                    logToLogcat(`[CLASS] ${ns}.${name}`);
                    const iter = Memory.alloc(Process.pointerSize);
                    iter.writePointer(NULL);
                    let method;
                    while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                        const mName = api.method_get_name(method).readUtf8String();
                        logToLogcat(`  -> ${mName}`);
                    }
                }
            }
        }
        Logger.success("Dump Finished.");
    } catch (e) {
        Logger.error(`Error: ${e}`);
    }
}

setImmediate(() => {
    Logger.info("DUMPER: Waiting 35s for the game to stabilize...");
    setTimeout(dumpIl2Cpp, 35000);
});
