/**
 * MEMORY DUMPER
 * @version 1.0
 * @description Provides memory inspection and analysis capabilities
 */

'use strict';

const DEBUG = true;

class Logger {
    static info(msg) { console.log('[MemDump] [INFO]', msg); }
    static success(msg) { console.log('[MemDump] [✓]', msg); }
    static warn(msg) { console.log('[MemDump] [!]', msg); }
    static error(msg) { console.error('[MemDump] [✗]', msg); }
}

const MemoryDumper = {
    /**
     * Scan memory for patterns
     */
    scanMemory: function(pattern, rangeStart, rangeEnd) {
        Logger.info(`Scanning for pattern: ${pattern}`);
        const results = [];
        
        try {
            // Use Frida's Memory.scan for pattern matching
            const regex = new RegExp(pattern);
            Memory.scan(ptr(rangeStart), rangeEnd - rangeStart, pattern, {
                onMatch: function(address, size) {
                    results.push({
                        address: address.toString(),
                        size: size,
                        data: address.readByteArray(size)
                    });
                    Logger.success(`Found match at ${address}`);
                },
                onError: function(reason) {
                    Logger.warn(`Scan error: ${reason}`);
                },
                onComplete: function() {
                    Logger.info(`Scan complete - found ${results.length} matches`);
                }
            });
        } catch (e) {
            Logger.error(`Memory scan failed: ${e.message}`);
        }
        
        return results;
    },

    /**
     * Dump memory range to file or buffer
     */
    dumpRange: function(start, end) {
        Logger.info(`Dumping memory range: ${start} - ${end}`);
        
        try {
            const addr = ptr(start);
            const size = end - start;
            const data = addr.readByteArray(size);
            
            Logger.success(`Dumped ${size} bytes from ${addr}`);
            return {
                address: start.toString(),
                size: size,
                data: data
            };
        } catch (e) {
            Logger.error(`Range dump failed: ${e.message}`);
            return null;
        }
    },

    /**
     * Find heap allocations
     */
    enumerateHeap: function(maxResults) {
        Logger.info('Enumerating heap...');
        const results = [];
        
        try {
            const heapBlocks = [];
            Memory.enumerate({
                onMatch: function(range) {
                    if (results.length < (maxResults || 100)) {
                        heapBlocks.push({
                            base: range.base.toString(),
                            size: range.size,
                            protection: range.protection,
                            file: range.file?.path || null
                        });
                        results.push(range);
                    }
                },
                onComplete: function() {
                    Logger.success(`Found ${heapBlocks.length} memory blocks`);
                }
            });
            
            return heapBlocks;
        } catch (e) {
            Logger.error(`Heap enumeration failed: ${e.message}`);
            return [];
        }
    },

    /**
     * Find memory protection violations (R/W/X permissions)
     */
    findExecutableMemory: function() {
        Logger.info('Finding executable memory...');
        const results = [];
        
        try {
            Process.enumerateRanges('x').forEach(range => {
                results.push({
                    base: range.base.toString(),
                    size: range.size,
                    protection: range.protection,
                    file: range.file?.path || null
                });
            });
            
            Logger.success(`Found ${results.length} executable regions`);
        } catch (e) {
            Logger.error(`Failed to enumerate executable ranges: ${e.message}`);
        }
        
        return results;
    },

    /**
     * Monitor memory access
     */
    monitorMemoryAccess: function(address, size, accessType) {
        Logger.info(`Monitoring ${accessType} access on ${address}`);
        
        try {
            const addr = ptr(address);
            
            // Use Frida's MemoryAccessMonitor if available
            const monitor = MemoryAccessMonitor.enable({
                base: addr,
                size: size,
                onAccess: function(details) {
                    Logger.info(`Memory ${accessType}: ${details.address} from ${details.from}`);
                    
                    // Register with RPC
                    if (rpc.exports.onMemoryAccess) {
                        rpc.exports.onMemoryAccess({
                            address: details.address.toString(),
                            type: details.type,
                            from: details.from.toString()
                        });
                    }
                }
            });
            
            Logger.success('Memory monitoring started');
            return true;
        } catch (e) {
            Logger.error(`Memory monitoring failed: ${e.message}`);
            return false;
        }
    },

    /**
     * Dump process maps
     */
    dumpMaps: function() {
        Logger.info('Dumping process memory maps...');
        
        try {
            const maps = [];
            Process.enumerateRanges('---').forEach(range => {
                maps.push({
                    base: range.base.toString(),
                    size: range.size,
                    protection: range.protection,
                    file: range.file?.path || '[heap]'
                });
            });
            
            Logger.success(`Total ${maps.length} memory regions`);
            return maps;
        } catch (e) {
            Logger.error(`Failed to dump maps: ${e.message}`);
            return [];
        }
    },

    /**
     * Analyze module memory
     */
    analyzeModule: function(moduleName) {
        Logger.info(`Analyzing module: ${moduleName}`);
        
        try {
            const module = Process.getModuleByName(moduleName);
            if (!module) {
                Logger.warn(`Module not found: ${moduleName}`);
                return null;
            }

            const analysis = {
                name: module.name,
                base: module.base.toString(),
                size: module.size,
                path: module.path,
                exports: [],
                segments: []
            };

            // Get exports
            try {
                analysis.exports = module.enumerateExports().slice(0, 20).map(e => ({
                    name: e.name,
                    address: e.address.toString(),
                    type: e.type
                }));
            } catch (e) {
                Logger.warn(`Failed to enumerate exports: ${e.message}`);
            }

            // Get segments
            try {
                analysis.segments = module.enumerateSegments().map(s => ({
                    name: s.name,
                    address: s.address.toString(),
                    size: s.size,
                    protection: s.protection
                }));
            } catch (e) {
                Logger.warn(`Failed to enumerate segments: ${e.message}`);
            }

            Logger.success(`Module analyzed: ${moduleName}`);
            return analysis;
        } catch (e) {
            Logger.error(`Module analysis failed: ${e.message}`);
            return null;
        }
    },

    /**
     * Get pointer size and check if address is valid
     */
    validateAddress: function(addressStr) {
        try {
            const addr = ptr(addressStr);
            
            // Try to read a byte to validate
            const canRead = addr.readU8 !== undefined;
            
            return {
                address: addressStr,
                valid: true,
                pointerSize: Process.pointerSize,
                arch: Process.arch
            };
        } catch (e) {
            return {
                address: addressStr,
                valid: false,
                error: e.message
            };
        }
    }
};

// ============ RPC EXPORTS ============

rpc.exports.dumpMemoryRange = function(start, end) {
    return MemoryDumper.dumpRange(start, end);
};

rpc.exports.scanMemory = function(pattern, rangeStart, rangeEnd) {
    return MemoryDumper.scanMemory(pattern, rangeStart, rangeEnd);
};

rpc.exports.enumerateHeap = function(maxResults) {
    return MemoryDumper.enumerateHeap(maxResults);
};

rpc.exports.findExecutableMemory = function() {
    return MemoryDumper.findExecutableMemory();
};

rpc.exports.dumpMaps = function() {
    return MemoryDumper.dumpMaps();
};

rpc.exports.analyzeModule = function(moduleName) {
    return MemoryDumper.analyzeModule(moduleName);
};

rpc.exports.validateAddress = function(addressStr) {
    return MemoryDumper.validateAddress(addressStr);
};

// Register with shared script registry
if (typeof SCRIPT_REGISTRY !== 'undefined') {
    SCRIPT_REGISTRY.hooks['__memory_dumper__'] = { 
        name: '__memory_dumper__', 
        active: true, 
        target: '0x0', 
        description: 'Memory Dumper Core Hook',
        registered: Date.now()
    };
    Logger.success('Memory Dumper registered with shared registry');
} else {
    Logger.warn('SCRIPT_REGISTRY not available - running standalone');
}

Logger.success('Memory Dumper initialized - RPC exports ready');
