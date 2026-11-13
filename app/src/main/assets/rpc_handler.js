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

// ============ RUNTIME PATCH ENGINE ============

if (!global.GLOBAL_SESSION_ID) {
    global.GLOBAL_SESSION_ID = 'session_' + Date.now() + '_' + Math.floor(Math.random() * 100000);
}
if (!global.PATCH_REGISTRY) {
    global.PATCH_REGISTRY = {};
}
if (!global.ACTIVE_AOB_SCANS) {
    global.ACTIVE_AOB_SCANS = {};
}

function bytesToHex(byteArray) {
    if (!byteArray) return '';
    const u8 = new Uint8Array(byteArray);
    let hex = '';
    for (let i = 0; i < u8.length; i++) {
        const b = u8[i].toString(16);
        hex += (b.length === 1 ? '0' + b : b);
    }
    return hex.toUpperCase();
}

function hexToBytes(hexStr) {
    if (!hexStr) return [];
    const cleanHex = hexStr.replace(/\s+/g, '');
    const bytes = [];
    for (let i = 0; i < cleanHex.length; i += 2) {
        bytes.push(parseInt(cleanHex.substr(i, 2), 16));
    }
    return bytes;
}

function rangesOverlap(start1, size1, start2, size2) {
    const end1 = start1.add(size1);
    const end2 = start2.add(size2);
    return start1.compare(end2) < 0 && start2.compare(end1) < 0;
}

EXPORTS.apply_patch_rva = function(moduleName, rvaHex, patchHex, patchId) {
    try {
        const mod = Process.findModuleByName(moduleName);
        if (!mod) {
            Logger.error(`apply_patch_rva failed: Module ${moduleName} not found`);
            return { success: false, error: `Module ${moduleName} not found`, status: 'UNVERIFIED' };
        }

        const rvaPtr = ptr(rvaHex.startsWith('0x') || rvaHex.startsWith('0X') ? rvaHex : '0x' + rvaHex);
        const runtimeAddr = mod.base.add(rvaPtr);
        const addrKey = runtimeAddr.toString();
        const patchBytes = hexToBytes(patchHex);
        const size = patchBytes.length;

        if (size === 0) {
            return { success: false, error: "Empty patch hex string", status: 'ERROR' };
        }

        // Multi-range boundary validation
        const rangeEnd = runtimeAddr.add(size);
        const memRange = Process.findRangeByAddress(runtimeAddr);
        if (!memRange || memRange.base.add(memRange.size).compare(rangeEnd) < 0) {
            Logger.error(`Patch range spans outside valid memory boundary at ${runtimeAddr}`);
            return { success: false, error: "Patch range spans outside valid memory boundary", status: 'ERROR' };
        }

        // Overlapping patch conflict detection
        for (const existingKey in global.PATCH_REGISTRY) {
            const entry = global.PATCH_REGISTRY[existingKey];
            if (entry && existingKey !== addrKey) {
                const entryAddr = ptr(entry.runtimeAddress);
                if (rangesOverlap(runtimeAddr, size, entryAddr, entry.currentPatchSize)) {
                    Logger.error(`Patch conflict: Overlaps with existing patch ${entry.patchId} at ${entry.runtimeAddress}`);
                    return { success: false, error: `Overlaps with existing patch ${entry.patchId}`, status: 'CONFLICT' };
                }
            }
        }

        const origProt = memRange.protection;

        if (!global.PATCH_REGISTRY[addrKey]) {
            const originalBytes = runtimeAddr.readByteArray(size);
            global.PATCH_REGISTRY[addrKey] = {
                patchId: patchId || ('patch_' + Date.now()),
                processId: Process.id,
                processStartIdentity: global.GLOBAL_SESSION_ID,
                moduleName: moduleName,
                moduleBase: mod.base.toString(),
                moduleSize: mod.size,
                rva: rvaHex,
                runtimeAddress: addrKey,
                originalBytesHex: bytesToHex(originalBytes),
                originalSize: size,
                currentPatchSize: size,
                lastVerifiedBytesHex: patchHex.toUpperCase(),
                status: 'PENDING',
                patchHistory: []
            };
        }

        try {
            Memory.protect(runtimeAddr, size, 'rwx');
            runtimeAddr.writeByteArray(patchBytes);
            Memory.flushInstructionCache(runtimeAddr, size);

            // Post-write verification
            const verifiedBytes = runtimeAddr.readByteArray(size);
            const verifiedHex = bytesToHex(verifiedBytes);
            if (verifiedHex.toLowerCase() !== patchHex.replace(/\s+/g, '').toLowerCase()) {
                throw new Error(`Post-write verification failed. Expected ${patchHex}, got ${verifiedHex}`);
            }

            const reg = global.PATCH_REGISTRY[addrKey];
            reg.currentPatchSize = size;
            reg.lastVerifiedBytesHex = verifiedHex;
            reg.status = 'ACTIVE';
            reg.patchHistory.push({ patchId: reg.patchId, patchHex: patchHex, timestamp: Date.now() });

            Logger.success(`Patch ${reg.patchId} applied at ${moduleName}+${rvaHex} (${addrKey})`);
            return {
                success: true,
                patchId: reg.patchId,
                status: 'ACTIVE',
                verifiedBytesHex: verifiedHex,
                originalBytesHex: reg.originalBytesHex,
                runtimeAddress: addrKey
            };
        } catch (writeErr) {
            Logger.error(`Patch transaction failed for ${addrKey}: ${writeErr.message}. Triggering rollback...`);
            const reg = global.PATCH_REGISTRY[addrKey];
            if (reg && reg.originalBytesHex) {
                try {
                    const origBytes = hexToBytes(reg.originalBytesHex);
                    runtimeAddr.writeByteArray(origBytes);
                    Memory.flushInstructionCache(runtimeAddr, reg.originalSize);
                    Logger.warn(`Rollback succeeded for ${addrKey}`);
                } catch (rbErr) {
                    Logger.error(`Rollback failed for ${addrKey}: ${rbErr.message}`);
                }
            }
            if (reg) reg.status = 'ERROR';
            return { success: false, error: writeErr.message, status: 'ERROR' };
        } finally {
            try {
                Memory.protect(runtimeAddr, size, origProt);
            } catch (e) {}
        }
    } catch (e) {
        Logger.error(`apply_patch_rva error: ${e.message}`);
        return { success: false, error: e.message, status: 'ERROR' };
    }
};

EXPORTS.replace_patch_rva = function(moduleName, rvaHex, newPatchHex, patchId) {
    return EXPORTS.apply_patch_rva(moduleName, rvaHex, newPatchHex, patchId);
};

EXPORTS.restore_original_rva = function(moduleName, rvaHex) {
    try {
        const mod = Process.findModuleByName(moduleName);
        if (!mod) {
            return { success: false, error: `Module ${moduleName} not found`, status: 'UNVERIFIED' };
        }

        const rvaPtr = ptr(rvaHex.startsWith('0x') || rvaHex.startsWith('0X') ? rvaHex : '0x' + rvaHex);
        const runtimeAddr = mod.base.add(rvaPtr);
        const addrKey = runtimeAddr.toString();

        const reg = global.PATCH_REGISTRY[addrKey];
        if (!reg || !reg.originalBytesHex) {
            Logger.warn(`No original backup found in registry for ${addrKey}`);
            return { success: false, error: "No original backup found", status: 'UNVERIFIED' };
        }

        const origBytes = hexToBytes(reg.originalBytesHex);
        const size = origBytes.length;

        const memRange = Process.findRangeByAddress(runtimeAddr);
        const origProt = memRange ? memRange.protection : 'r-x';

        try {
            Memory.protect(runtimeAddr, size, 'rwx');
            runtimeAddr.writeByteArray(origBytes);
            Memory.flushInstructionCache(runtimeAddr, size);

            const verifiedBytes = bytesToHex(runtimeAddr.readByteArray(size));
            if (verifiedBytes.toLowerCase() !== reg.originalBytesHex.toLowerCase()) {
                throw new Error(`Restore verification failed. Expected ${reg.originalBytesHex}, got ${verifiedBytes}`);
            }

            reg.status = 'INACTIVE';
            reg.lastVerifiedBytesHex = verifiedBytes;
            Logger.success(`Restored original bytes at ${moduleName}+${rvaHex} (${addrKey})`);
            return { success: true, status: 'INACTIVE', restoredBytesHex: verifiedBytes };
        } catch (err) {
            Logger.error(`Restore failed for ${addrKey}: ${err.message}`);
            reg.status = 'ERROR';
            return { success: false, error: err.message, status: 'ERROR' };
        } finally {
            try {
                if (memRange) Memory.protect(runtimeAddr, size, origProt);
            } catch (e) {}
        }
    } catch (e) {
        Logger.error(`restore_original_rva error: ${e.message}`);
        return { success: false, error: e.message, status: 'ERROR' };
    }
};

EXPORTS.verify_patch_state = function(moduleName, rvaHex, expectedOrigHex, expectedPatchHex) {
    try {
        const mod = Process.findModuleByName(moduleName);
        if (!mod) {
            return { status: 'UNVERIFIED', error: `Module ${moduleName} not loaded` };
        }

        const rvaPtr = ptr(rvaHex.startsWith('0x') || rvaHex.startsWith('0X') ? rvaHex : '0x' + rvaHex);
        const runtimeAddr = mod.base.add(rvaPtr);
        const addrKey = runtimeAddr.toString();

        const size = expectedPatchHex ? hexToBytes(expectedPatchHex).length : (expectedOrigHex ? hexToBytes(expectedOrigHex).length : 4);
        const currentBytes = bytesToHex(runtimeAddr.readByteArray(size)).toUpperCase();

        const cleanExpectedPatch = expectedPatchHex ? expectedPatchHex.replace(/\s+/g, '').toUpperCase() : null;
        const cleanExpectedOrig = expectedOrigHex ? expectedOrigHex.replace(/\s+/g, '').toUpperCase() : null;

        let status = 'UNVERIFIED';
        if (cleanExpectedPatch && currentBytes === cleanExpectedPatch) {
            status = 'ACTIVE';
        } else if (cleanExpectedOrig && currentBytes === cleanExpectedOrig) {
            status = 'INACTIVE';
        } else {
            status = 'CONFLICT';
        }

        if (global.PATCH_REGISTRY[addrKey]) {
            global.PATCH_REGISTRY[addrKey].status = status;
            global.PATCH_REGISTRY[addrKey].lastVerifiedBytesHex = currentBytes;
        }

        return {
            status: status,
            runtimeAddress: addrKey,
            currentBytesHex: currentBytes,
            expectedPatchHex: cleanExpectedPatch,
            expectedOrigHex: cleanExpectedOrig
        };
    } catch (e) {
        Logger.error(`verify_patch_state error: ${e.message}`);
        return { status: 'ERROR', error: e.message };
    }
};

EXPORTS.list_patch_registry = function() {
    return {
        processId: Process.id,
        sessionId: global.GLOBAL_SESSION_ID,
        count: Object.keys(global.PATCH_REGISTRY).length,
        entries: global.PATCH_REGISTRY
    };
};


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

// ============ ASYNCHRONOUS AOB SCANNER ENGINE ============

EXPORTS.start_aob_scan = function(moduleName, patternHex, minFixedBytes = 2, maxResults = 100) {
    const scanId = 'scan_' + Date.now() + '_' + Math.floor(Math.random() * 10000);
    Logger.info(`Starting AOB scan ${scanId} for pattern: ${patternHex} in ${moduleName || 'all executable modules'}`);

    if (!patternHex || patternHex.trim() === '') {
        return { scanId: scanId, status: 'ERROR', error: 'Pattern is empty', results: [] };
    }

    const cleanPattern = patternHex.trim();
    const tokens = cleanPattern.split(/\s+/);
    let fixedByteCount = 0;
    for (const token of tokens) {
        if (token !== '?' && token !== '??') {
            if (!/^[0-9a-fA-F]{2}$/.test(token)) {
                return { scanId: scanId, status: 'ERROR', error: `Invalid byte token: ${token}`, results: [] };
            }
            fixedByteCount++;
        }
    }

    if (fixedByteCount < minFixedBytes) {
        return { scanId: scanId, status: 'ERROR', error: `Pattern requires at least ${minFixedBytes} non-wildcard byte(s)`, results: [] };
    }

    const scanState = {
        scanId: scanId,
        moduleName: moduleName,
        pattern: cleanPattern,
        status: 'RUNNING',
        cancelRequested: false,
        results: [],
        error: null,
        startTime: Date.now()
    };
    global.ACTIVE_AOB_SCANS[scanId] = scanState;

    setTimeout(() => {
        try {
            let ranges = [];
            if (moduleName) {
                const mod = Process.findModuleByName(moduleName);
                if (mod) {
                    ranges = Process.enumerateRanges({ protection: 'r-x', coalesce: true })
                        .filter(r => r.base.compare(mod.base) >= 0 && r.base.compare(mod.base.add(mod.size)) < 0);
                    if (ranges.length === 0) {
                        ranges = [{ base: mod.base, size: mod.size, protection: 'r-x' }];
                    }
                }
            } else {
                ranges = Process.enumerateRanges({ protection: 'r-x', coalesce: true });
            }

            let completedRanges = 0;
            if (ranges.length === 0) {
                scanState.status = 'COMPLETED';
                return;
            }

            ranges.forEach(range => {
                if (scanState.cancelRequested || scanState.results.length >= maxResults) return;

                Memory.scan(range.base, range.size, cleanPattern, {
                    onMatch: function(address, size) {
                        if (scanState.cancelRequested) return 'stop';

                        const mod = Process.findModuleByAddress(address);
                        const modName = mod ? mod.name : (moduleName || 'unknown');
                        const rvaOffsetHex = mod ? '0x' + address.sub(mod.base).toString(16) : '0x' + address.toString(16);

                        let matchedBytesHex = '';
                        try {
                            matchedBytesHex = bytesToHex(address.readByteArray(size));
                        } catch (e) {}

                        scanState.results.push({
                            addressHex: address.toString(),
                            moduleName: modName,
                            rvaOffsetHex: rvaOffsetHex,
                            matchedBytesHex: matchedBytesHex,
                            size: size
                        });

                        if (scanState.results.length >= maxResults) return 'stop';
                    },
                    onError: function(reason) {
                        Logger.warn(`AOB scan range error: ${reason}`);
                    },
                    onComplete: function() {
                        completedRanges++;
                        if (completedRanges >= ranges.length || scanState.cancelRequested || scanState.results.length >= maxResults) {
                            if (scanState.cancelRequested) {
                                scanState.status = 'CANCELLED';
                            } else {
                                scanState.status = 'COMPLETED';
                            }
                            Logger.success(`AOB scan ${scanId} finished with ${scanState.results.length} matches (status: ${scanState.status})`);
                        }
                    }
                });
            });
        } catch (e) {
            Logger.error(`AOB scan ${scanId} failed: ${e.message}`);
            scanState.status = 'ERROR';
            scanState.error = e.message;
        }
    }, 10);

    return { scanId: scanId, status: 'RUNNING', results: [] };
};

EXPORTS.cancel_aob_scan = function(scanId) {
    const scanState = global.ACTIVE_AOB_SCANS[scanId];
    if (scanState) {
        scanState.cancelRequested = true;
        scanState.status = 'CANCELLED';
        Logger.info(`AOB scan cancellation requested for ${scanId}`);
        return true;
    }
    return false;
};

EXPORTS.get_aob_scan_status = function(scanId) {
    const scanState = global.ACTIVE_AOB_SCANS[scanId];
    if (!scanState) {
        return { scanId: scanId, status: 'NOT_FOUND', results: [] };
    }
    return {
        scanId: scanId,
        status: scanState.status,
        pattern: scanState.pattern,
        resultsCount: scanState.results.length,
        results: scanState.results,
        error: scanState.error
    };
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
