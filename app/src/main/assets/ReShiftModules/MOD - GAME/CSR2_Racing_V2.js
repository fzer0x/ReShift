/**
 * CSR Racing 2 Mod
 * @version 1.4
 * @description Unlimited Gold, Money & Keys. Use in Zygisk Mode! Do not use on your real Account. Do not use on online mode. This mod is for educational purposes only.
 * @noedit
 */

'use strict';

const IL2CPP_SO = 'libil2cpp.so';
const TAG = "CSR2_DYNAMIC";

// Fallback Offsets (DUMPED 2026-07-18)
const FALLBACK_OFFSETS = {
    ThrowProfileError: 0x2c407dc,
    LogOperation: 0x2c40638,
    get_passed: 0x2c40990,
    AddTamperedCar_CanTrigger: 0x2c7afd0,
    GetTamperedCars_CanTrigger: 0x2c7be48,
    FireLogOperationEvent: 0x2c406f8,
    ZTrack_GuildBan_ToTrackEvent: 0x2cb38ec,
    ZTrack_SystemBan_ToTrackEvent: 0x2cb5794,
    SpendGold: 0x3250cbc,
    SpendCash: 0x3253820,
    SpendFlexibleCurrency: 0x32540fc,
    set_GoldSpentSetAndMangle: 0x32359c0,
    set_CashSpentSetAndMangle: 0x3235840,
    get_BalanceGoldKeys: 0x34b96a4,
    get_BalanceSilverKeys: 0x34b96fc,
    get_BalanceBronzeKeys: 0x34b9754,
    SpendGachaKeys: 0x3254814
};

let api = {};
let ResolvedMethods = {};

function log(msg) {
    console.log(`[${TAG}] ${msg}`);
}

/**
 * 1. LOW-LEVEL STEALTH
 */
function applyFridaStealth() {
    try {
        const libc = Process.getModuleByName("libc.so");
        const ptracePtr = libc.findExportByName("ptrace");
        if (ptracePtr) {
            Interceptor.replace(ptracePtr, new NativeCallback(() => 0, 'long', ['int', 'int', 'pointer', 'pointer']));
            log("✓ ptrace stealth active");
        }
    } catch (e) {
        log("✗ Frida stealth error: " + e);
    }
}

/**
 * 2. IL2CPP API INITIALIZATION
 */
function initIl2cppApi(il2cpp) {
    const resolve = (name, ret, args) => {
        let addr = il2cpp.findExportByName(name);
        if (!addr || addr.isNull()) {
            const exports = il2cpp.enumerateExports();
            for (let exp of exports) { if (exp.name === name) { addr = exp.address; break; } }
        }
        return addr ? new NativeFunction(addr, ret, args) : null;
    };

    api = {
        domain_get: resolve('il2cpp_domain_get', 'pointer', []),
        domain_get_assemblies: resolve('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']),
        assembly_get_image: resolve('il2cpp_assembly_get_image', 'pointer', ['pointer']),
        image_get_class_count: resolve('il2cpp_image_get_class_count', 'size_t', ['pointer']),
        image_get_class: resolve('il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']),
        class_get_name: resolve('il2cpp_class_get_name', 'pointer', ['pointer']),
        class_get_namespace: resolve('il2cpp_class_get_namespace', 'pointer', ['pointer']),
        class_get_methods: resolve('il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer']),
        method_get_name: resolve('il2cpp_method_get_name', 'pointer', ['pointer']),
        method_get_address: resolve('il2cpp_method_get_address', 'pointer', ['pointer'])
    };

    if (!api.method_get_address || api.method_get_address === null) {
        api.method_get_address = function(ptr) { return ptr.readPointer(); };
    }
    return !!api.domain_get;
}

/**
 * 3. DYNAMIC SYMBOL RESOLVER
 */
function resolveAllMethods() {
    log("Resolving game methods dynamically...");

    const targets = {
        "ProfileIntegrity": ["get_passed", "ThrowProfileError", "LogOperation", "FireLogOperationEvent"],
        "PlayerProfile": ["SpendGold", "SpendCash", "SpendFlexibleCurrency", "get_BalanceGoldKeys", "get_BalanceSilverKeys", "get_BalanceBronzeKeys", "set_GoldSpentSetAndMangle", "set_CashSpentSetAndMangle"],
        "AntiCheat": ["AddTamperedCar_CanTrigger", "GetTamperedCars_CanTrigger"],
        "ZTrack": ["ZTrack_GuildBan_ToTrackEvent", "ZTrack_SystemBan_ToTrackEvent"]
    };

    const domain = api.domain_get();
    const sizePtr = Memory.alloc(Process.pointerSize);
    const assemblies = api.domain_get_assemblies(domain, sizePtr);
    const count = sizePtr.readUInt();

    for (let i = 0; i < count; i++) {
        const assembly = assemblies.add(i * Process.pointerSize).readPointer();
        const image = api.assembly_get_image(assembly);
        const classCount = api.image_get_class_count(image);

        for (let j = 0; j < classCount; j++) {
            const klass = api.image_get_class(image, j);
            if (klass.isNull()) continue;

            const className = api.class_get_name(klass).readUtf8String();
            if (targets[className]) {
                const iter = Memory.alloc(Process.pointerSize).writePointer(NULL);
                let method;
                while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                    const mName = api.method_get_name(method).readUtf8String();
                    if (targets[className].includes(mName)) {
                        ResolvedMethods[mName] = api.method_get_address(method);
                        log(`✓ Resolved: ${className}.${mName} -> ${ResolvedMethods[mName]}`);
                    }
                }
            }
        }
    }
}

function getAddr(name, il2cppBase) {
    if (ResolvedMethods[name]) return ResolvedMethods[name];
    if (FALLBACK_OFFSETS[name]) {
        log(`⚠ Using fallback for ${name}`);
        return il2cppBase.add(FALLBACK_OFFSETS[name]);
    }
    return null;
}

/**
 * 4. INTEGRITY BYPASS & CURRENCY PROTECTION
 */
function applyIntegrityBypass(il2cppBase) {
    log("Applying Integrity & Currency Protection...");

    const pPassed = getAddr("get_passed", il2cppBase);
    if (pPassed) Interceptor.replace(pPassed, new NativeCallback(() => 1, 'int', ['pointer']));

    const pThrow = getAddr("ThrowProfileError", il2cppBase);
    if (pThrow) Interceptor.replace(pThrow, new NativeCallback(() => { log("⚠️ Suppressed integrity crash!"); }, 'void', ['pointer', 'pointer']));

    const silent = new NativeCallback(() => {}, 'void', ['pointer', 'int', 'int', 'pointer']);
    const pLogOp = getAddr("LogOperation", il2cppBase);
    if (pLogOp) Interceptor.replace(pLogOp, silent);

    const pFireLog = getAddr("FireLogOperationEvent", il2cppBase);
    if (pFireLog) Interceptor.replace(pFireLog, new NativeCallback(() => {}, 'void', ['pointer', 'pointer']));

    // --- ANTI-DEBT PROTECTION ---
    const blockDeduction = new NativeCallback((instance, amount) => {
        log(`💰 Anti-Debt: Blocked deduction of ${amount} units.`);
    }, 'void', ['pointer', 'int', 'pointer']);

    ["SpendGold", "SpendCash", "SpendFlexibleCurrency"].forEach(m => {
        const addr = getAddr(m, il2cppBase);
        if (addr) Interceptor.replace(addr, blockDeduction);
    });

    const blockMangle = new NativeCallback(() => {}, 'void', ['pointer', 'int']);
    ["set_GoldSpentSetAndMangle", "set_CashSpentSetAndMangle"].forEach(m => {
        const addr = getAddr(m, il2cppBase);
        if (addr) Interceptor.replace(addr, blockMangle);
    });

    // --- KEY PROTECTION ---
    const fakeKeys = (retval) => { retval.replace(ptr(3000)); };
    ["get_BalanceBronzeKeys", "get_BalanceSilverKeys", "get_BalanceGoldKeys"].forEach(m => {
        const addr = getAddr(m, il2cppBase);
        if (addr) Interceptor.attach(addr, { onLeave: fakeKeys });
    });

    log("Protection hooks active.");
}

/**
 * 5. SMART PRICE SCANNER
 */
function applySmartPatch() {
    log("Start Price Scanner (Optimized)...");

    const domain = api.domain_get();
    const sizePtr = Memory.alloc(Process.pointerSize);
    const assemblies = api.domain_get_assemblies(domain, sizePtr);
    const count = sizePtr.readUInt();

    const targetClassKeywords = ["Price", "Cost", "Store", "Purchase", "CarData", "Upgrade", "Bundle", "Product", "Economy", "Offer", "PlayerProfile", "Gacha", "LootBox", "Crate"];
    const priceMethods = ["get_CashPrice", "get_GoldPrice", "get_Price", "get_Cost", "GetCashPrice", "GetGoldPrice", "CostInCash", "GoldPrice", "CalculateFusingCashCost", "GetSpinCost"];

    let hookedCount = 0;

    for (let i = 0; i < count; i++) {
        const assembly = assemblies.add(i * Process.pointerSize).readPointer();
        const image = api.assembly_get_image(assembly);
        const classCount = api.image_get_class_count(image);

        for (let j = 0; j < classCount; j++) {
            const klass = api.image_get_class(image, j);
            if (klass.isNull()) continue;
            const className = api.class_get_name(klass).readUtf8String();

            if (!targetClassKeywords.some(kw => className.includes(kw))) continue;

            const iter = Memory.alloc(Process.pointerSize).writePointer(NULL);
            let method;
            while (!(method = api.class_get_methods(klass, iter)).isNull()) {
                const mName = api.method_get_name(method).readUtf8String();

                if (priceMethods.includes(mName)) {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        try {
                            Interceptor.attach(addr, { onLeave: function(retval) { retval.replace(ptr(0)); } });
                            hookedCount++;
                        } catch(e) {}
                    }
                }

                if (mName === "CanAfford" || mName === "CanAffordGold" || mName === "CanAffordCash" || mName === "IsPlayerAbleToFuseUpgrade" ||
                    mName === "CanAffordSilverOrGoldSpin" || mName === "get_IsFreeSpinAvailable") {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        try {
                            Interceptor.attach(addr, { onLeave: function(retval) { retval.replace(ptr(1)); } });
                            hookedCount++;
                        } catch(e) {}
                    }
                }

                if (className === "PlayerProfile") {
                    const addr = api.method_get_address(method);
                    if (addr && !addr.isNull()) {
                        try {
                            if ((mName.includes("Gold") || mName.includes("Cash")) && (mName.startsWith("get_") || mName.startsWith("Get"))) {
                                Interceptor.attach(addr, {
                                    onLeave: function(retval) {
                                        if (mName.toLowerCase().includes("gold")) {
                                            retval.replace(ptr(4999));
                                        } else {
                                            retval.replace(ptr(9999999));
                                        }
                                    }
                                });
                                hookedCount++;
                            }
                        } catch(e) {}
                    }
                }
            }
        }
    }
    log(`Smart Scan complete. Hooked ${hookedCount} economy methods.`);
}

/**
 * 6. MAIN BOOT SEQUENCE
 */
function boot() {
    const il2cppMod = Process.findModuleByName(IL2CPP_SO);
    if (!il2cppMod) {
        setTimeout(boot, 1000);
        return;
    }

    if (!initIl2cppApi(il2cppMod)) {
        setTimeout(boot, 1000);
        return;
    }

    log("✓ Engine Ready. Resolving Symbols...");
    resolveAllMethods();

    applyIntegrityBypass(il2cppMod.base);

    setTimeout(() => {
        applySmartPatch();

        // Physics
        const tireAddr = il2cppMod.findExportByName("TireData_ServeWheelSpinVsTyreGripCurve");
        if (tireAddr) Interceptor.replace(tireAddr, new NativeCallback(() => 5.0, 'float', []));

        log("ALL MODS INJECTED");
    }, 5000);
}

applyFridaStealth();
setTimeout(boot, 5000);
