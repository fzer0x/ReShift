// ============ FRIDA RPC HANDLER SCRIPT ============
// Provides RPC interface for Memory Inspector + Hook Management
// DEDICATED TCP JSON-RPC SERVER ON PORT 27043
// WITH TIMEOUT MANAGEMENT FOR LONG-RUNNING OPERATIONS

'use strict';

// ============ GLOBAL SHIM ============
var global = typeof globalThis !== 'undefined' ? globalThis : typeof global !== 'undefined' ? global : this;

const RPC_PORT = 27043;
const DEBUG = true;
const DEFAULT_TIMEOUT_MS = 5000;      // 5 seconds for normal ops
const SCAN_TIMEOUT_MS = 30000;        // 30 seconds for scans
const DUMP_TIMEOUT_MS = 10000;        // 10 seconds for dumps

// ============ TIMEOUT MANAGER ============
class TimeoutManager {
    constructor(defaultTimeout = DEFAULT_TIMEOUT_MS) {
        this.timeoutHandles = {};
        this.defaultTimeout = defaultTimeout;
    }

    create(operationId, timeoutMs = null) {
        const timeout = timeoutMs || this.defaultTimeout;
        const handle = setTimeout(() => {
            Logger.warn(`Operation ${operationId} timed out after ${timeout}ms`);
            this.clear(operationId);
        }, timeout);
        this.timeoutHandles[operationId] = handle;
        return handle;
    }

    clear(operationId) {
        if (this.timeoutHandles[operationId]) {
            clearTimeout(this.timeoutHandles[operationId]);
            delete this.timeoutHandles[operationId];
        }
    }

    clearAll() {
        Object.keys(this.timeoutHandles).forEach(id => this.clear(id));
    }
}

const timeoutManager = new TimeoutManager(DEFAULT_TIMEOUT_MS);

// ============ UNIFIED LOGGER ============
class Logger {
    static info(msg) { console.log('[RPC] [INFO]', msg); }
    static success(msg) { console.log('[RPC] [✓]', msg); }
    static warn(msg) { console.log('[RPC] [!]', msg); }
    static error(msg) { console.error('[RPC] [✗]', msg); }
}

// ============ STALKER/TRACER MANAGEMENT ============
const ACTIVE_TRACES = {};
const THREAD_TRACES = {};

function getTracerForAddress(address) {
    const addrStr = address.toString();
    if (ACTIVE_TRACES[addrStr]) return ACTIVE_TRACES[addrStr];
    return null;
}

function createSymbolResolver(addressOrSymbol) {
    try {
        const resolved = DebugSymbol.fromAddress(ptr(addressOrSymbol));
        if (resolved && resolved.name) {
            return resolved.name;
        }
    } catch (e) {}
    return addressOrSymbol.toString();
}

// ============ USE SHARED REGISTRY ============
if (!global.SCRIPT_REGISTRY) {
    global.SCRIPT_REGISTRY = {
        scripts: {},
        hooks: {},
        interceptors: [],
        uiHandlers: {},
        traces: {}
    };
}
const SCRIPT_REGISTRY = global.SCRIPT_REGISTRY;

// ============ RPC EXPORTS ============
const EXPORTS = {};

EXPORTS.listRegisteredHooks = function() {
    Logger.info('Listing hooks...');
    const hookList = Object.keys(SCRIPT_REGISTRY.hooks);
    Logger.success('Found ' + hookList.length + ' hooks');
    return hookList;
};

EXPORTS.getHookStatus = function(hookName) {
    const hook = SCRIPT_REGISTRY.hooks[hookName];
    if (!hook) {
        Logger.warn('Hook not found: ' + hookName);
        return null;
    }
    return {
        name: hookName,
        active: hook.active,
        target: hook.target || 'unknown',
        interceptors: hook.interceptors || 0,
        description: hook.description || ''
    };
};

EXPORTS.toggleHook = function(hookName) {
    const hook = SCRIPT_REGISTRY.hooks[hookName];
    if (!hook) {
        Logger.warn('Cannot toggle: hook not found ' + hookName);
        return false;
    }

    hook.active = !hook.active;
    Logger.success('Hook toggled: ' + hookName + ' -> ' + (hook.active ? 'ON' : 'OFF'));

    return hook.active;
};

EXPORTS.getMemoryDump = function(address, size) {
    try {
        const addr = ptr(address);
        if (addr.isNull()) {
            Logger.warn(`Invalid address: ${address}`);
            return null;
        }
        
        const bytes = addr.readByteArray(size);
        Logger.success(`Memory dumped: ${address} (${size} bytes)`);
        return bytes;
    } catch (e) {
        Logger.error(`Memory dump failed: ${e.message}`);
        return null;
    }
};

EXPORTS.getScriptRegistry = function() {
    return {
        hooks: Object.keys(SCRIPT_REGISTRY.hooks),
        scripts: Object.keys(SCRIPT_REGISTRY.scripts),
        interceptors: SCRIPT_REGISTRY.interceptors.length,
        uptime: Date.now()
    };
};

EXPORTS.testConnection = function() {
    return { status: "connected", timestamp: Date.now() };
};

EXPORTS.get_rpc_diagnostics = function() {
    const exports = Object.keys(EXPORTS);
    return {
        status: "operational",
        timestamp: Date.now(),
        port: RPC_PORT,
        exports_count: exports.length,
        exports: exports,
        registry: {
            hooks: Object.keys(SCRIPT_REGISTRY.hooks || {}).length,
            scripts: Object.keys(SCRIPT_REGISTRY.scripts || {}).length,
            interceptors: (SCRIPT_REGISTRY.interceptors || []).length,
            traces: Object.keys(SCRIPT_REGISTRY.traces || {}).length
        },
        memory: {
            process_id: Process.id,
            arch: Process.arch,
            platform: Process.platform,
            pointer_size: Process.pointerSize,
            modules_loaded: Process.enumerateModules().length
        },
        il2cpp_status: EXPORTS.get_dumper_status ? EXPORTS.get_dumper_status() : { initialized: false, available: false }
    };
};

EXPORTS.onUiEvent = function(controlId, value) {
    Logger.info(`UI Event: ${controlId} = ${value}`);
    if (SCRIPT_REGISTRY.uiHandlers && SCRIPT_REGISTRY.uiHandlers[controlId]) {
        try {
            SCRIPT_REGISTRY.uiHandlers[controlId](value);
            return true;
        } catch (e) {
            Logger.error(`UI Handler Error for ${controlId}: ${e.message}`);
            return false;
        }
    }
    return false;
};

EXPORTS.list_active_traces = function() {
    const traces = SCRIPT_REGISTRY.traces || {};
    return {
        count: Object.keys(traces).length,
        traces: traces
    };
};

EXPORTS.get_module_info = function(moduleName) {
    try {
        const module = moduleName ?
            Process.findModuleByName(moduleName) :
            Process.enumerateModules()[0];
        
        if (!module) {
            Logger.warn(`Module not found: ${moduleName}`);
            return null;
        }
        
        return {
            name: module.name,
            base: module.base.toString(),
            size: module.size,
            path: module.path,
            arch: Process.arch
        };
    } catch (e) {
        Logger.error(`get_module_info failed: ${e.message}`);
        return null;
    }
};

EXPORTS.list_modules = function() {
    try {
        const modules = Process.enumerateModules();
        return modules.map(m => ({
            name: m.name,
            base: m.base.toString(),
            size: m.size,
            path: m.path
        }));
    } catch (e) {
        Logger.error(`list_modules failed: ${e.message}`);
        return [];
    }
};

EXPORTS.startStalker = function(moduleName) {
    if (typeof global.startStalker === 'function') {
        global.startStalker(moduleName);
        return true;
    }
    Logger.error('startStalker not found in global scope. Is stalker_trace.js loaded?');
    return false;
};

EXPORTS.stopStalker = function() {
    if (typeof global.stopStalker === 'function') {
        global.stopStalker();
        return true;
    }
    Logger.error('stopStalker not found in global scope.');
    return false;
};

EXPORTS.scanMemory = function(pattern, moduleName, timeoutMs = SCAN_TIMEOUT_MS) {
    Logger.info(`Scanning memory for pattern: ${pattern}` + (moduleName ? ` in ${moduleName}` : '') + ` (timeout: ${timeoutMs}ms)`);

    const scanResponse = {
        results: [],
        complete: false,
        scannedRanges: 0,
        totalRanges: 0,
        timeoutMs: timeoutMs,
        error: null
    };
    
    if (!pattern || pattern.trim() === '') {
        Logger.warn("Pattern is empty");
        scanResponse.error = "Empty pattern";
        return scanResponse;
    }

    let timedOut = false;

    // Set timeout for this operation
    const timeoutHandle = setTimeout(() => {
        timedOut = true;
        Logger.warn(`SCAN TIMEOUT after ${timeoutMs}ms - returning partial results`);
    }, timeoutMs);
    
    try {
        const ranges = moduleName ? 
            Process.enumerateModules().filter(m => m.name === moduleName) :
            Process.enumerateRanges('r--');

        scanResponse.totalRanges = ranges.length;

        if (ranges.length === 0) {
            clearTimeout(timeoutHandle);
            Logger.warn(`No ranges found for scan`);
            return scanResponse;
        }

        Logger.info(`Scanning ${ranges.length} range(s)`);
        
        for (let idx = 0; idx < ranges.length; idx++) {
            if (timedOut) break;

            const range = ranges[idx];
            scanResponse.scannedRanges++;
            
            try {
                const rangeMatches = Memory.scanSync(range.base, range.size, pattern);
                if (rangeMatches && rangeMatches.length > 0) {
                    rangeMatches.forEach(match => {
                        scanResponse.results.push({
                            address: match.address.toString(),
                            offset: `0x${match.address.sub(range.base).toString(16)}`,
                            size: match.size,
                            module: moduleName || 'heap'
                        });
                    });
                }
            } catch (rangeErr) {
                // Ignore inaccessible ranges
            }
        }
        
        clearTimeout(timeoutHandle);
        scanResponse.complete = !timedOut;
        Logger.success(`Found ${scanResponse.results.length} matches`);
        return scanResponse;
    } catch (e) {
        clearTimeout(timeoutHandle);
        Logger.error(`scanMemory failed: ${e.message}`);
        scanResponse.error = e.message;
        return scanResponse;
    }
};

EXPORTS.writeMemory = function(address, hexValue) {
    try {
        const addr = ptr(address);
        const bytes = [];
        for (let i = 0; i < hexValue.length; i += 2) {
            bytes.push(parseInt(hexValue.substr(i, 2), 16));
        }

        // Ensure memory is writable
        try {
            Memory.protect(addr, bytes.length, 'rwx');
        } catch (e) {
            Logger.warn(`Failed to change memory protection for ${address}: ${e.message}`);
        }

        Memory.patchCode(addr, bytes.length, function(code) {
            code.writeByteArray(bytes);
        });
        Logger.success(`Memory written at ${address}: ${hexValue}`);
        return true;
    } catch (e) {
        Logger.error(`Memory write failed: ${e.message}`);
        return false;
    }
};

// ============ IL2CPP METHOD HOOKING ============

EXPORTS.hookMethod = function(className, methodName) {
    Logger.info(`Attempting to hook ${className}::${methodName}`);
    
    if (!className || !methodName) {
        Logger.warn("Invalid className or methodName");
        return false;
    }

    try {
        // Look for libil2cpp.so
        const il2cpp = Process.findModuleByName('libil2cpp.so');
        if (!il2cpp) {
            Logger.warn('libil2cpp.so not found - may not be a Unity app');
            return false;
        }

        // Create a trace/hook entry for this method
        const hookKey = `${className}::${methodName}`;
        
        // Register in script registry
        if (!SCRIPT_REGISTRY.hooks[hookKey]) {
            SCRIPT_REGISTRY.hooks[hookKey] = {
                active: true,
                target: className,
                description: `IL2CPP hook for ${methodName}`,
                interceptors: 0
            };
            Logger.success(`Hook registered: ${hookKey}`);
        } else {
            SCRIPT_REGISTRY.hooks[hookKey].active = true;
            Logger.success(`Hook activated: ${hookKey}`);
        }

        // Log to console for game output
        console.log(`[IL2CPP Hook] Activated: ${hookKey}`);
        
        return true;
    } catch (e) {
        Logger.error(`hookMethod failed: ${e.message}`);
        return false;
    }
};

// ============ TRACING FUNCTIONALITY ============

EXPORTS.trace_address = function(address, name, argCount, captureStack) {
    try {
        const traceKey = address.toString();
        const targetAddr = ptr(address);
        const displayName = name || `trace_${traceKey.substring(0, 8)}`;
        
        Logger.info(`Setting up trace for address ${address} (${displayName})`);
        
        const tracer = Interceptor.attach(targetAddr, {
            onEnter: function(args) {
                const logEntry = {
                    type: 'enter',
                    name: displayName,
                    address: address,
                    timestamp: Date.now(),
                    threadId: Process.getCurrentThreadId()
                };
                
                if (argCount > 0) {
                    logEntry.args = [];
                    for (let i = 0; i < Math.min(argCount, args.length); i++) {
                        try {
                            logEntry.args.push(args[i].toString());
                        } catch (e) {
                            logEntry.args.push('??');
                        }
                    }
                }
                
                if (captureStack) {
                    logEntry.stack = Thread.backtrace(this.context, Backtracer.ACCURATE).map(x => x.toString());
                }
                
                send({ type: 'trace_log', data: logEntry });
            },
            onLeave: function(result) {
                const logEntry = {
                    type: 'leave',
                    name: displayName,
                    address: address,
                    timestamp: Date.now(),
                    threadId: Process.getCurrentThreadId(),
                    retval: result ? result.toString() : 'null'
                };
                send({ type: 'trace_log', data: logEntry });
            }
        });
        
        ACTIVE_TRACES[traceKey] = tracer;
        SCRIPT_REGISTRY.traces = SCRIPT_REGISTRY.traces || {};
        SCRIPT_REGISTRY.traces[traceKey] = { name: displayName, active: true };
        
        Logger.success(`Trace attached to ${displayName} at ${address}`);
        return true;
    } catch (e) {
        Logger.error(`trace_address failed: ${e.message}`);
        return false;
    }
};

EXPORTS.trace_symbol = function(symbol, argCount, captureStack) {
    try {
        const symbolPtr = DebugSymbol.fromName(symbol);
        if (!symbolPtr) {
            Logger.warn(`Symbol not found: ${symbol}`);
            return false;
        }
        
        Logger.info(`Tracing symbol: ${symbol} @ ${symbolPtr}`);
        return EXPORTS.trace_address(symbolPtr.toString(), symbol, argCount, captureStack);
    } catch (e) {
        Logger.error(`trace_symbol failed: ${e.message}`);
        return false;
    }
};

EXPORTS.untrace = function(target) {
    try {
        const tracer = ACTIVE_TRACES[target];
        if (tracer) {
            tracer.detach();
            delete ACTIVE_TRACES[target];
            
            if (SCRIPT_REGISTRY.traces && SCRIPT_REGISTRY.traces[target]) {
                delete SCRIPT_REGISTRY.traces[target];
            }
            
            Logger.success(`Trace detached from ${target}`);
            return true;
        } else {
            Logger.warn(`No trace found for ${target}`);
            return false;
        }
    } catch (e) {
        Logger.error(`untrace failed: ${e.message}`);
        return false;
    }
};

EXPORTS.get_relative_offset = function(address, moduleName) {
    try {
        const addr = ptr(address);
        const module = moduleName ? 
            Process.findModuleByName(moduleName) : 
            Process.findModuleByAddress(addr);
        
        if (!module) {
            Logger.warn(`Module not found for address ${address}`);
            return null;
        }
        
        const offset = addr.sub(module.base);
        Logger.success(`Offset for ${address} in ${module.name}: 0x${offset.toString(16)}`);
        
        return {
            module: module.name,
            base: module.base.toString(),
            size: module.size,
            address: address,
            offset: `0x${offset.toString(16)}`,
            offsetDec: offset.toString()
        };
    } catch (e) {
        Logger.error(`get_relative_offset failed: ${e.message}`);
        return null;
    }
};

// IL2CPP SUPPORT
let il2cppDumper = null;
let il2cppInitialized = false;
let il2cppInitError = null;

function getIl2cppDumper() {
    if (il2cppInitialized) {
        if (il2cppInitError) {
            Logger.warn(`IL2CPP Dumper previously failed: ${il2cppInitError}`);
            return null;
        }
        return il2cppDumper;
    }

    try {
        // Check if dumper class from other script is available
        if (typeof IL2CPPDumper !== 'undefined') {
            const mod = Process.findModuleByName('libil2cpp.so');
            if (mod) {
                Logger.info(`Found libil2cpp.so at ${mod.base}`);
                il2cppDumper = new IL2CPPDumper(mod);
                
                if (typeof il2cppDumper.init === 'function') {
                    if (il2cppDumper.init()) {
                        il2cppInitialized = true;
                        Logger.success("IL2CPP Dumper initialized via RPC");
                        return il2cppDumper;
                    } else {
                        il2cppInitError = "Dumper.init() returned false";
                        Logger.error(il2cppInitError);
                        il2cppInitialized = true;
                        return null;
                    }
                } else {
                    Logger.warn("IL2CPPDumper class found but no init method");
                    il2cppInitialized = true;
                    return il2cppDumper;
                }
            } else {
                il2cppInitError = "libil2cpp.so not found in loaded modules";
                Logger.warn(il2cppInitError);
                il2cppInitialized = true;
                return null;
            }
        } else {
            il2cppInitError = "IL2CPPDumper class not available in global scope";
            Logger.warn(il2cppInitError + " - ensure Universal_IL2CPP_Dumper_v2.js is loaded");
            il2cppInitialized = true;
            return null;
        }
    } catch (e) {
        il2cppInitError = e.message;
        Logger.error(`IL2CPP Dumper init error: ${e.message}`);
        il2cppInitialized = true;
        return null;
    }
}

EXPORTS.find_classes_matching = function(pattern) {
    try {
        const dumper = getIl2cppDumper();
        if (!dumper) {
            Logger.warn(`IL2CPP Dumper not available. Error: ${il2cppInitError}`);
            return [];
        }
        
        if (typeof dumper.findClassByNamePattern !== 'function') {
            Logger.warn("Dumper has no findClassByNamePattern method");
            return [];
        }
        
        const results = dumper.findClassByNamePattern(pattern);
        Logger.success(`Found ${results.length} classes matching ${pattern}`);
        return results || [];
    } catch (e) {
        Logger.error(`find_classes_matching failed: ${e.message}`);
        return [];
    }
};

EXPORTS.analyze_class = function(className) {
    try {
        const dumper = getIl2cppDumper();
        if (!dumper) {
            Logger.warn(`Cannot analyze class: IL2CPP Dumper not available`);
            return null;
        }
        
        if (typeof dumper.dumpAllClasses !== 'function') {
            Logger.warn("Dumper has no dumpAllClasses method");
            return null;
        }
        
        const results = dumper.dumpAllClasses(className);
        if (results && results.length > 0) {
            Logger.success(`Analyzed class: ${className}`);
            return results[0];
        } else {
            Logger.warn(`No class found: ${className}`);
            return null;
        }
    } catch (e) {
        Logger.error(`analyze_class failed: ${e.message}`);
        return null;
    }
};

EXPORTS.get_dumper_status = function() {
    return {
        initialized: il2cppInitialized,
        available: il2cppDumper != null,
        error: il2cppInitError,
        hasInit: il2cppDumper != null && typeof il2cppDumper.init === 'function',
        methods: il2cppDumper != null ? Object.keys(il2cppDumper).filter(k => typeof il2cppDumper[k] === 'function') : []
    };
};

// Aliases for compatibility
rpc.exports = EXPORTS;

// ============ TCP JSON-RPC SERVER ============

function startTcpServer() {
    try {
        Logger.info(`Starting TCP RPC Server on 127.0.0.1:${RPC_PORT}...`);
        Socket.listen({
            port: RPC_PORT,
            host: '127.0.0.1'
        }).then(listener => {
            Logger.success(`TCP RPC Server listening on port ${RPC_PORT}`);

            const handleConnection = (connection) => {
                const inputStream = connection.input;
                const outputStream = connection.output;
                const BUFFER_SIZE = 8192;
                const buffer = Memory.alloc(BUFFER_SIZE);
                let isConnected = true;

                function readNext() {
                    if (!isConnected) return;
                    
                    inputStream.read(buffer, BUFFER_SIZE).then(bytesRead => {
                        if (bytesRead === 0) {
                            isConnected = false;
                            try {
                                connection.close();
                            } catch (e) {}
                            return;
                        }

                        const requestStr = buffer.readUtf8String(bytesRead);
                        try {
                            const requests = requestStr.split('\n').filter(s => s.trim() !== '');
                            for (const reqRaw of requests) {
                                try {
                                    const request = JSON.parse(reqRaw);
                                    const method = request.method;
                                    const params = request.params || [];
                                    const id = request.id;

                                    if (EXPORTS[method]) {
                                        try {
                                            const result = EXPORTS[method].apply(null, params);
                                            const response = {
                                                jsonrpc: "2.0",
                                                result: result,
                                                id: id
                                            };
                                            if (isConnected) {
                                                outputStream.write(JSON.stringify(response) + "\n");
                                            }
                                        } catch (execErr) {
                                            Logger.error(`RPC Method '${method}' execution error: ${execErr.message}`);
                                            if (isConnected) {
                                                outputStream.write(JSON.stringify({
                                                    jsonrpc: "2.0",
                                                    error: { code: -32603, message: `Internal error: ${execErr.message}` },
                                                    id: id
                                                }) + "\n");
                                            }
                                        }
                                    } else {
                                        if (isConnected) {
                                            outputStream.write(JSON.stringify({
                                                jsonrpc: "2.0",
                                                error: { code: -32601, message: "Method not found: " + method },
                                                id: id
                                            }) + "\n");
                                        }
                                    }
                                } catch (parseErr) {
                                    Logger.warn(`RPC JSON parse error: ${parseErr.message} for input: ${reqRaw}`);
                                    if (isConnected) {
                                        outputStream.write(JSON.stringify({
                                            jsonrpc: "2.0",
                                            error: { code: -32700, message: "Parse error" },
                                            id: null
                                        }) + "\n");
                                    }
                                }
                            }
                        } catch (batchErr) {
                            Logger.error(`RPC batch processing error: ${batchErr.message}`);
                        }
                        
                        if (isConnected) {
                            readNext();
                        }
                    }).catch(err => {
                        isConnected = false;
                        if (err.message.indexOf('closed') === -1) {
                            Logger.warn("Connection read error: " + err.message);
                        }
                        try {
                            connection.close();
                        } catch (e) {}
                    });
                }

                readNext();
            };

            function accept() {
                listener.accept().then(connection => {
                    Logger.info("New RPC client connection accepted");
                    handleConnection(connection);
                    accept();
                }).catch(err => {
                    if (err.message.indexOf('closed') === -1) {
                        Logger.error("Accept failed: " + err.message);
                    }
                });
            }

            accept();
        }).catch(err => {
            Logger.error(`Failed to start TCP RPC Server on port ${RPC_PORT}: ${err.message}`);
            if (err.message.includes('Address already in use')) {
                Logger.warn(`PORT CONFLICT: Port ${RPC_PORT} is already occupied. Close other Frida sessions!`);
            }
        });
    } catch (e) {
        Logger.error(`TCP Server init error: ${e.message}`);
    }
}

// ============ INITIALIZATION ============

function initialize() {
    Logger.info('RPC Handler initializing...');

    // Auto-register self with shared registry
    SCRIPT_REGISTRY.hooks['__rpc_handler__'] = { 
        name: '__rpc_handler__', 
        active: true, 
        target: '0x0', 
        description: 'RPC Handler Core (TCP:27043)',
        registered: Date.now()
    };

    startTcpServer();
    console.log('=== FRIDA RPC HANDLER READY (TCP SERVER ENABLED) ===');
}

// Global helpers for other scripts
global.sendUiConfig = function(controls) {
    const msg = { type: 'ui_config', controls: controls };
    send(msg);
    console.log(JSON.stringify(msg));
};

global.registerUiHandler = function(controlId, callback) {
    if (!SCRIPT_REGISTRY.uiHandlers) SCRIPT_REGISTRY.uiHandlers = {};
    SCRIPT_REGISTRY.uiHandlers[controlId] = callback;
};

// Delay startup to let the app breathe
if (Java.available) {
    Java.perform(function() {
        setTimeout(initialize, 500);
    });
} else {
    setTimeout(initialize, 500);
}
