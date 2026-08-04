/**
 * @name Stalker Visualizer
 * @description Real-time Code Flow Tracing & Hot Path Analysis
 * @version 1.0
 * @author fzer0x
 */

'use strict';

const AGGREGATION_INTERVAL = 1000;
let hitMap = {};
let symbolCache = {};
let stalkerActive = false;
let targetModule = null;
let callStack = [];

function resolveSymbol(address) {
    const addrStr = address.toString();
    if (symbolCache[addrStr]) return symbolCache[addrStr];

    try {
        const symbol = DebugSymbol.fromAddress(address);
        if (symbol && symbol.name) {
            symbolCache[addrStr] = symbol.name;
            return symbol.name;
        }
    } catch (e) {}

    const module = Process.findModuleByAddress(address);
    if (module) {
        const name = `${module.name}!0x${address.sub(module.base).toString(16)}`;
        symbolCache[addrStr] = name;
        return name;
    }

    return addrStr;
}

function flushHits() {
    if (Object.keys(hitMap).length > 0) {
        const msg = {
            type: 'stalker_hits',
            hits: hitMap,
            tree: callStack.slice(-20) // Send recent call tree segments
        };
        send(msg);
        console.log(JSON.stringify(msg));
        hitMap = {};
        // We don't clear callStack here to keep the tree building on Android side
    }
}

global.startStalker = function(moduleName) {
    if (stalkerActive) return;
    targetModule = moduleName ? Process.findModuleByName(moduleName) : null;
    callStack = [];

    console.log(`[Stalker] Starting trace on thread ${Process.getCurrentThreadId()}`);

    Stalker.follow(Process.getCurrentThreadId(), {
        events: {
            call: true,
            ret: true,
            exec: false
        },
        onReceive: function(events) {
            const parsed = Stalker.parse(events, {
                annotate: true,
                stringify: false
            });

            parsed.forEach(ev => {
                const type = ev[0];
                if (type === 'call') {
                    const target = ev[2];
                    if (!targetModule || (target.compare(targetModule.base) >= 0 && target.compare(targetModule.base.add(targetModule.size)) < 0)) {
                        const addrStr = target.toString();
                        hitMap[addrStr] = (hitMap[addrStr] || 0) + 1;
                        const name = resolveSymbol(target);
                        callStack.push({ type: 'enter', name: name, addr: addrStr, depth: callStack.length });
                    }
                } else if (type === 'ret') {
                    if (callStack.length > 0) {
                        callStack.push({ type: 'exit', depth: callStack.length - 1 });
                    }
                }
            });
        }
    });

    stalkerActive = true;
    setInterval(flushHits, AGGREGATION_INTERVAL);
};

global.stopStalker = function() {
    Stalker.unfollow(Process.getCurrentThreadId());
    stalkerActive = false;
    flushHits();
    console.log('[Stalker] Stopped');
};

console.log('=== STALKER VISUALIZER SCRIPT LOADED ===');
