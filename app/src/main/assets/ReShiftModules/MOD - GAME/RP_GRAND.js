/**
 * RP GRAND MOBILE
 * @version 1.0
 * Target: com.Grand.Napal
 * @description Mod Menu for RP GRAND MOBILE
 * @APK: assets/Menu_RP.apk
 * @noedit
 * @author ReShift
 */

const CONFIG = {
    moduleName: "libUE4.so",
    offsets: {
        MainLuaState: {
            sendConsoleCommand: 0x7DB04D4,
            CallRemote: 0x7DB9188,
            SendBrowserExecute: 0x7DB933C,
            PhotomodeFly: 0x7DAC69C,
            flyState: 0x7DB1928,
            SetVehicleTuning: 0x7DB23FC,
            setHealth: 0x7DB13E4
        },
        LuaLib: {
            Conv_StringToLuaValue: 0x70818FC,
            Conv_BoolToLuaValue: 0x708165C,
            Conv_IntToLuaValue: 0x70819C4,
            Conv_FloatToLuaValue: 0x7081E88
        },
        CharacterMovementComponent: {
            SetMovementMode: 0xAA1AB14
        },
        HUD: {
            DrawHUD: 0x0A389D58,
            Project: 0x0A38B6DC,
            DrawLine: 0x0A38BD10,
            DrawRect: 0x0A38BC60,
            DrawText: 0x0A38AD0C
        }
    }
};

let libUE4 = {
    base: null,
    ready: false
};

let instances = {
    mainLuaState: null,
    deviceEspActive: true,      // 3D ESP Overlay standardmäßig aktiv
    deviceEspStyle: "3d",       // "3d" = 3D Bounding Box, "2d" = Corner Bracket Box
    deviceLineMode: "bottom",   // "bottom" | "top" | "both" | "off"
    deviceRadarActive: false,   // Tactical Radar Minimap
    deviceRadarSkin: "cyber",   // "cyber" | "circle" | "military" | "minimal" | "classic"
    deviceRadarRange: 150.0,
    friendlyProtect: true,      // Team / Family Protection

    // Native Aim Assist Engine Settings (1:1 aus ue4_lua_bridge.js)
    aimActive: true,            // Zielhilfe aktiv
    targetVehicleOccupants: true, // Personen in Fahrzeugen anvisieren
    aimAssistMode: "new",       // "new" = Zentrums-Magnetismus, "old" = Klassisch
    senseRadius: 120.0,         // FOV Kreis Radius in px (Min 20px, Max 150px)
    aimCorrectionZ: 70.0,       // Ziel-Höhe Z-Offset in cm (Kopf=70cm, Nacken=52cm, Brust=38cm)
    smoothFactor: 0.85,         // Magnetismus / Lock-Geschwindigkeit (0.1 - 1.0)
    hasLockedTarget: false,     // Target Lock Status Indicator

    // FOV Kreis Visuelle Einstellungen
    fovCircleActive: true,      // FOV Kreis im HUD anzeigen
    fovCenterDotActive: true,   // Punkt in der Mitte des FOV Kreises anzeigen
    fovSkin: "classic",         // "classic" | "brackets" | "crosshair" | "tactical"
    fovOffsetX: 0.0,            // X-Offset Kalibrierung in px

    // Fahrzeug-Tuning & PhotomodeFly & Survival
    vehicleTorqueMultiplier: 1.0, // Motor Drehmoment Multiplier (1.0x - 5.0x)
    photomodeFlyActive: false,    // PhotomodeFly Flugmodus
    debugCameraActive: false,     // Toggle Debug Camera
    lastNitroTrigger: 0,
    lastMedkitTrigger: 0,
    lastArmorTrigger: 0,
    controllerInputState: { lx: 0.0, ly: 0.0, rx: 0.0, ry: 0.0, l2: 0.0, r2: 0.0, lastUpdate: 0 },

    lastConfigSyncTime: 0,
    deviceHudNative: null,
    cefWidget: null,
    lastHudPtr: null,
    lastScreenSize: { sizeX: 1920, sizeY: 1080 },
    cachedPlayers: [],
    cachedMyLoc: { x: 0, y: 0, z: 0 },
    cachedMyYaw: 0.0,
    lastPlayerScanTime: 0,
    lastHudRenderTime: 0,
    verifiedFamilyCache: new Map(),  // playerId -> { family, name, dist, time }
    verifiedFamilyByName: new Map(), // cleanName -> { family, id, time }
    friendlyList: new Set(),
    friendlyFamilies: new Set()
};

function isValidPtr(p) {
    if (!p || p.isNull()) return false;
    try {
        const addr = p.toUInt53 ? p.toUInt53() : Number(p);
        return addr >= 0x10000 && addr <= 0x7FFFFFFFFF;
    } catch (_) {
        return false;
    }
}

function readFString(ptr) {
    if (!ptr || ptr.isNull()) return "";
    try {
        const dataPtr = ptr.readPointer();
        if (dataPtr.isNull()) return "";
        const len = ptr.add(8).readInt();
        if (len <= 0 || len > 2048) return "";
        return dataPtr.readUtf16String(len - 1);
    } catch (e) {
        return "";
    }
}

function readTextBlockText(tbPtr) {
    if (!tbPtr || !isValidPtr(tbPtr)) return "";
    try {
        const ftext = tbPtr.add(0x128);
        const tdata = ftext.readPointer();
        if (isValidPtr(tdata)) {
            const s = readFString(tdata.add(0x28)) || readFString(tdata.add(0x38));
            if (s && s.trim().length > 0 && s.trim().length < 80) return s.trim();
        }
    } catch (_) { }
    return "";
}

function isWidgetVisible(wPtr) {
    if (!wPtr || !isValidPtr(wPtr)) return false;
    try {
        const vis = wPtr.add(0xC3).readU8();
        return (vis === 0 || vis === 3 || vis === 4);
    } catch (_) {
        return false;
    }
}

function getLocalPlayerActor(mainLua) {
    try {
        if (!mainLua || !isValidPtr(mainLua)) return null;
        const gameMode = mainLua.add(0x2F0).readPointer();
        if (!isValidPtr(gameMode)) return null;

        let myChar = gameMode.add(0xA80).readPointer();
        if (!isValidPtr(myChar)) myChar = gameMode.add(0x9C8).readPointer();
        if (!isValidPtr(myChar)) myChar = gameMode.add(0x4E8).readPointer();
        if (!isValidPtr(myChar)) return null;
        return myChar;
    } catch (e) {
        return null;
    }
}

function getLocalVehicle(mainLua) {
    try {
        const myChar = getLocalPlayerActor(mainLua);
        if (!myChar || !isValidPtr(myChar)) return null;
        let veh = myChar.add(0x6A0).readPointer();
        if (!isValidPtr(veh)) veh = myChar.add(0x698).readPointer();
        if (!isValidPtr(veh)) veh = myChar.add(0x6C0).readPointer();
        if (!isValidPtr(veh)) return null;
        return veh;
    } catch (e) {
        return null;
    }
}

function applyVehicleTorque(mainLua) {
    if (typeof instances.vehicleTorqueMultiplier !== 'number' || instances.vehicleTorqueMultiplier <= 1.0) return;
    try {
        const veh = getLocalVehicle(mainLua);
        if (veh && isValidPtr(veh)) {
            // FGearEngine Pointer at 0x2F0 in LATEST_UPDATE.json
            let engine = veh.add(0x2F0).readPointer();
            if (!isValidPtr(engine)) engine = veh.add(0x4C0).readPointer();

            if (engine && isValidPtr(engine)) {
                engine.add(0x210).writeFloat(instances.vehicleTorqueMultiplier); // mTorqueScale
                engine.add(0x21C).writeFloat(15000.0); // mLimitRpm (Drehzahlbegrenzer)
            }
        }
    } catch (_) { }
}

function triggerNitro() {
    if (!instances.mainLuaState || !isValidPtr(instances.mainLuaState)) return;
    try {
        const veh = getLocalVehicle(instances.mainLuaState);
        if (veh && isValidPtr(veh)) {
            try {
                // MainVehicle.ActivateNitro offset 0x7DBEEEC
                const nitroAddr = libUE4.base.add(0x7DBEEEC);
                const nitroFunc = new NativeFunction(nitroAddr, 'void', ['pointer']);
                nitroFunc(veh);
            } catch (_) {
                // Fallback: MainLuaState.ActivateNitro offset 0x7DA9208
                const nitroAddrLua = libUE4.base.add(0x7DA9208);
                const nitroFuncLua = new NativeFunction(nitroAddrLua, 'void', ['pointer']);
                nitroFuncLua(instances.mainLuaState);
            }
        } else {
            // Fallback: MainLuaState.ActivateNitro offset 0x7DA9208
            const nitroAddrLua = libUE4.base.add(0x7DA9208);
            const nitroFuncLua = new NativeFunction(nitroAddrLua, 'void', ['pointer']);
            nitroFuncLua(instances.mainLuaState);
        }
    } catch (e) {
        console.log(`[Nitro] Trigger error: ${e}`);
    }
}

function allocFString(str) {
    if (!str) str = "";
    const s = str.length > 250 ? str.slice(0, 250) : str;
    const utf16Buf = Memory.alloc((s.length + 1) * 2);
    for (let i = 0; i < s.length; i++) {
        utf16Buf.add(i * 2).writeU16(s.charCodeAt(i));
    }
    utf16Buf.add(s.length * 2).writeU16(0);

    const fstr = Memory.alloc(16);
    fstr.writePointer(utf16Buf);
    fstr.add(8).writeInt(s.length + 1);
    fstr.add(12).writeInt(s.length + 1);
    return fstr;
}

function createLuaStringValue(str) {
    const stubs = getLuaConvStubs();
    if (!stubs || !stubs.strConv) return null;
    const luaVal = Memory.alloc(32);
    const fstr = allocFString(str);
    stubs.strConv(luaVal, fstr);
    return luaVal;
}

function createEmptyLuaTable(worldContext) {
    if (!libUE4.base || libUE4.base.isNull()) return null;
    try {
        const addr = libUE4.base.add(0x708BBAC); // LuaCreateTable
        const func = new NativeFunction(addr, 'void', ['pointer', 'pointer', 'pointer']);
        const luaVal = Memory.alloc(32);
        const ctx = (worldContext && isValidPtr(worldContext)) ? worldContext : (instances.mainLuaState || ptr(0));
        func(luaVal, ctx, ptr(0));
        return luaVal;
    } catch (_) {
        return null;
    }
}

function sendLuaConsoleCommand(cmd) {
    if (!instances.mainLuaState || !isValidPtr(instances.mainLuaState)) return false;
    const mlsOffsets = CONFIG.offsets.MainLuaState;
    if (!mlsOffsets || !mlsOffsets.sendConsoleCommand) return false;
    try {
        const luaCmd = createLuaStringValue(cmd);
        if (!luaCmd) return false;
        const cmdAddr = libUE4.base.add(mlsOffsets.sendConsoleCommand);
        const sendCmdFunc = new NativeFunction(cmdAddr, 'void', ['pointer', 'pointer']);
        sendCmdFunc(instances.mainLuaState, luaCmd);
        return true;
    } catch (e) {
        console.log(`[sendLuaConsoleCommand] Error sending "${cmd}": ${e}`);
        return false;
    }
}

function callLuaRemoteEvent(eventName, argsTable) {
    if (!instances.mainLuaState || !isValidPtr(instances.mainLuaState)) return false;
    const mlsOffsets = CONFIG.offsets.MainLuaState;
    if (!mlsOffsets || !mlsOffsets.CallRemote) return false;
    try {
        const luaEvt = createLuaStringValue(eventName);
        const luaArgs = argsTable || createEmptyLuaTable(instances.lastHudPtr || instances.mainLuaState);
        if (!luaEvt || !luaArgs) return false;
        const callRemoteAddr = libUE4.base.add(mlsOffsets.CallRemote);
        const callRemoteFunc = new NativeFunction(callRemoteAddr, 'void', ['pointer', 'pointer', 'pointer']);
        callRemoteFunc(instances.mainLuaState, luaEvt, luaArgs);
        console.log(`[CallRemote] Called "${eventName}"`);
        return true;
    } catch (e) {
        console.log(`[callLuaRemoteEvent] Error calling "${eventName}": ${e}`);
        return false;
    }
}

function runLuaCodeWithContext(worldContext, codeStr) {
    if (!libUE4.base || libUE4.base.isNull() || !codeStr) return false;
    try {
        const ctx = (worldContext && isValidPtr(worldContext)) ? worldContext : (instances.mainLuaState || ptr(0));
        const luaRunStringAddr = libUE4.base.add(0x7084F58);
        const luaRunFunc = new NativeFunction(luaRunStringAddr, 'void', ['pointer', 'pointer', 'pointer', 'pointer', 'pointer']);

        const outRes = Memory.alloc(32);
        const codeFStr = allocFString(codeStr);
        const pathFStr = allocFString("reshift_survival.lua");

        luaRunFunc(outRes, ctx, ptr(0), codeFStr, pathFStr);
        console.log(`[LuaRunString] Executed with context ${ctx}: ${codeStr}`);
        return true;
    } catch (e) {
        console.log(`[LuaRunString] Error executing "${codeStr}": ${e}`);
        return false;
    }
}

function sendCefEvent(evtName, evtData) {
    if (!instances.cefWidget || !isValidPtr(instances.cefWidget)) return false;
    try {
        const addr = libUE4.base.add(0x7D848B8);
        const func = new NativeFunction(addr, 'void', ['pointer', 'pointer', 'pointer']);
        const nameFStr = allocFString(evtName);
        const dataFStr = allocFString(evtData || "");
        func(instances.cefWidget, nameFStr, dataFStr);
        console.log(`[CEF] Sent event "${evtName}" ("${evtData}")`);
        return true;
    } catch (e) {
        console.log(`[CEF] Error sending "${evtName}": ${e}`);
        return false;
    }
}

function triggerMedkit() {
    console.log("[Medkit] Triggering Medkit Use across CEF, Lua, and Native channels...");
    const hudCtx = instances.lastHudPtr || instances.mainLuaState;

    // 1. CEF UI Events
    sendCefEvent("useMedkit", "");
    sendCefEvent("useHeal", "");
    sendCefEvent("useItem", "medkit");
    sendCefEvent("useItem", "1");
    sendCefEvent("inventory_use", "medkit");

    // 2. Direct Lua Execution via LuaRunString with HUD Context
    runLuaCodeWithContext(hudCtx, 'if sendConsoleCommand then sendConsoleCommand("/heal") sendConsoleCommand("/medkit") sendConsoleCommand("/usemedkit") end');
    runLuaCodeWithContext(hudCtx, 'if CallRemote then CallRemote("useMedkit") CallRemote("useHeal") CallRemote("server:useItem", "medkit") CallRemote("server:useItem", "1") end');

    // 3. Native FLuaValue sendConsoleCommand
    sendLuaConsoleCommand("/heal");
    sendLuaConsoleCommand("/medkit");
    sendLuaConsoleCommand("/usemedkit");
    sendLuaConsoleCommand("/usemed");
    sendLuaConsoleCommand("/apotheke");

    // 4. Call Remote Events
    callLuaRemoteEvent("useMedkit");
    callLuaRemoteEvent("useHeal");
    callLuaRemoteEvent("server:useItem");

    // 5. Fallback setHealth(100.0) on MainLuaState
    if (instances.mainLuaState && isValidPtr(instances.mainLuaState)) {
        try {
            const mlsOffsets = CONFIG.offsets.MainLuaState;
            if (mlsOffsets && mlsOffsets.setHealth) {
                const setHealthAddr = libUE4.base.add(mlsOffsets.setHealth);
                const stubs = getLuaConvStubs();
                if (stubs && stubs.floatConv) {
                    const floatVal = Memory.alloc(32);
                    stubs.floatConv(floatVal, 100.0);
                    const setHealthFunc = new NativeFunction(setHealthAddr, 'void', ['pointer', 'pointer']);
                    setHealthFunc(instances.mainLuaState, floatVal);
                }
            }
        } catch (e) {
            console.log(`[Medkit] setHealth fallback error: ${e}`);
        }
    }
}

function triggerArmor() {
    console.log("[Armor] Triggering Armor / Weste Use across CEF, Lua, and Native channels...");
    const hudCtx = instances.lastHudPtr || instances.mainLuaState;

    // 1. CEF UI Events
    sendCefEvent("useArmor", "");
    sendCefEvent("useArmour", "");
    sendCefEvent("useWeste", "");
    sendCefEvent("useItem", "armor");
    sendCefEvent("useItem", "weste");
    sendCefEvent("inventory_use", "armor");

    // 2. Direct Lua Execution via LuaRunString with HUD Context
    runLuaCodeWithContext(hudCtx, 'if sendConsoleCommand then sendConsoleCommand("/armour") sendConsoleCommand("/armor") sendConsoleCommand("/weste") end');
    runLuaCodeWithContext(hudCtx, 'if CallRemote then CallRemote("useArmor") CallRemote("useArmour") CallRemote("useWeste") CallRemote("server:useItem", "armor") CallRemote("server:useItem", "weste") end');

    // 3. Native FLuaValue sendConsoleCommand
    sendLuaConsoleCommand("/armour");
    sendLuaConsoleCommand("/armor");
    sendLuaConsoleCommand("/weste");
    sendLuaConsoleCommand("/usearmour");
    sendLuaConsoleCommand("/usearmor");
    sendLuaConsoleCommand("/useweste");

    // 4. Call Remote Events
    callLuaRemoteEvent("useArmor");
    callLuaRemoteEvent("useArmour");
    callLuaRemoteEvent("useWeste");
    callLuaRemoteEvent("server:useItem");
}

// Native Conv_...ToLuaValue stubs for 100% engine-safe FLuaValue construction
let luaConvStubs = null;
function getLuaConvStubs() {
    if (luaConvStubs) return luaConvStubs;
    try {
        const base = libUE4.base;
        if (!base || base.isNull()) return null;
        const createStub = (targetAddr) => {
            const mem = Memory.alloc(32);
            Memory.protect(mem, 32, 'rwx');
            mem.writeByteArray([
                0xe8, 0x03, 0x00, 0xaa, // mov x8, x0
                0xe0, 0x03, 0x01, 0xaa, // mov x0, x1
                0x50, 0x00, 0x00, 0x58, // ldr x16, #8
                0x00, 0x02, 0x1f, 0xd6  // br x16
            ]);
            mem.add(16).writePointer(targetAddr);
            return new NativeFunction(mem, 'void', ['pointer', 'pointer']);
        };
        const luaLibOffsets = (CONFIG.offsets && CONFIG.offsets.LuaLib) ? CONFIG.offsets.LuaLib : {};
        const strConv = createStub(base.add(luaLibOffsets.Conv_StringToLuaValue || 0x70818FC));
        const boolConv = createStub(base.add(luaLibOffsets.Conv_BoolToLuaValue || 0x708165C));
        const intConv = createStub(base.add(luaLibOffsets.Conv_IntToLuaValue || 0x70819C4));
        const floatConv = createStub(base.add(luaLibOffsets.Conv_FloatToLuaValue || 0x7081E88));
        luaConvStubs = { strConv, boolConv, intConv, floatConv };
    } catch (e) {
        console.log(`[!] initLuaConvStubs error: ${e}`);
    }
    return luaConvStubs;
}

function allocLuaBool(val) {
    const p = Memory.alloc(96);
    p.writeByteArray(new Array(96).fill(0));
    try {
        const stubs = getLuaConvStubs();
        if (stubs && stubs.boolConv) {
            stubs.boolConv(p, ptr(val ? 1 : 0));
            return p;
        }
    } catch (_) { }
    p.writeU8(1); // Type = Bool
    p.add(1).writeU8(val ? 1 : 0);
    p.add(0x30).writeInt(-2);
    return p;
}

function togglePhotomodeFly(state) {
    try {
        instances.photomodeFlyActive = !!state;
        const mainLua = instances.mainLuaState;

        if (mainLua) {
            const myChar = getLocalPlayerActor(mainLua);
            if (myChar && isValidPtr(myChar)) {
                const movComp = myChar.add(0x288).readPointer(); // CharacterMovementComponent
                if (movComp && isValidPtr(movComp)) {
                    if (libUE4.base) {
                        try {
                            const setModeAddr = libUE4.base.add(CONFIG.offsets.CharacterMovementComponent.SetMovementMode);
                            const setMovementMode = new NativeFunction(setModeAddr, 'void', ['pointer', 'uint8', 'uint8']);
                            if (instances.photomodeFlyActive) {
                                setMovementMode(movComp, 5, 0); // 5 = MOVE_Flying
                                movComp.add(0x168).writeU8(5); // 5 = MOVE_Flying
                                movComp.add(0x388).writeU8(1); // bCheatFlying = true
                                movComp.add(0x198).writeFloat(2500.0); // MaxFlySpeed
                                movComp.add(0x150).writeFloat(0.0); // GravityScale = 0.0
                            } else {
                                setMovementMode(movComp, 1, 0); // 1 = MOVE_Walking
                                movComp.add(0x168).writeU8(1); // 1 = MOVE_Walking
                                movComp.add(0x388).writeU8(0); // bCheatFlying = false
                                movComp.add(0x150).writeFloat(1.0); // GravityScale = 1.0
                            }
                        } catch (_) { }
                    }
                }
            }
        }

        if (libUE4.base && mainLua) {
            try {
                // UMainLuaState::flyState @ 0x7DB1928 in LATEST_UPDATE.json
                const flyAddr = libUE4.base.add(CONFIG.offsets.MainLuaState.flyState);
                if (flyAddr && !flyAddr.isNull()) {
                    const stateBuf = Memory.alloc(64);
                    new NativeFunction(flyAddr, 'void', ['pointer', 'pointer'])(mainLua, stateBuf);
                }
            } catch (_) { }

            try {
                // UMainLuaState::PhotomodeFly @ 0x7DAC69C in LATEST_UPDATE.json
                const photoAddr = libUE4.base.add(CONFIG.offsets.MainLuaState.PhotomodeFly || 0x7DAC69C);
                if (photoAddr && !photoAddr.isNull()) {
                    new NativeFunction(photoAddr, 'void', ['pointer', 'pointer'])(mainLua, allocLuaBool(instances.photomodeFlyActive));
                }
            } catch (_) { }
        }
        console.log(`[★] PhotomodeFly: ${instances.photomodeFlyActive ? 'AKTIV' : 'DEAKTIVIERT'}`);
    } catch (e) {
        console.log("[!] PhotomodeFly Error: " + e);
    }
}

function toggleDebugCamera(state) {
    try {
        instances.debugCameraActive = !!state;
        if (libUE4.base && instances.mainLuaState) {
            const playerController = getLocalPlayerController(instances.mainLuaState);
            if (playerController && isValidPtr(playerController)) {
                const cheatMgrPtr = playerController.add(CONFIG.offsets.PlayerController.CheatManager || 0x478).readPointer();
                if (cheatMgrPtr && isValidPtr(cheatMgrPtr)) {
                    const toggleCamAddr = libUE4.base.add(CONFIG.offsets.CheatManager.ToggleDebugCamera || 0xAA1E0A0);
                    if (toggleCamAddr && !toggleCamAddr.isNull()) {
                        new NativeFunction(toggleCamAddr, 'void', ['pointer'])(cheatMgrPtr);
                    }
                }
            }
        }
        console.log(`[★] Debug Camera: ${instances.debugCameraActive ? 'AKTIV' : 'DEAKTIVIERT'}`);
    } catch (e) {
        console.log("[!] ToggleDebugCamera Error: " + e);
    }
}

// 1:1 Helper aus ue4_lua_bridge.js: Resolve AimComponent (AutoAimComponent at BaseCharacter + 0x4C0)
function getAimComponent(mainLua) {
    try {
        const myChar = getLocalPlayerActor(mainLua);
        if (!myChar) return null;
        const aimComp = myChar.add(0x4C0).readPointer();
        if (!aimComp || aimComp.isNull() || aimComp.compare(ptr("0x10000")) < 0) return null;
        return aimComp;
    } catch (e) {
        return null;
    }
}

// 1:1 Helper aus ue4_lua_bridge.js: Apply parameters directly to native AimComponent in memory
function applyAimComponentConfig(aimComp, cfg) {
    if (!aimComp || aimComp.isNull() || aimComp.compare(ptr("0x10000")) < 0) return false;
    try {
        if (typeof cfg.senseRadius === 'number') {
            aimComp.add(0xE4).writeFloat(cfg.senseRadius);
        }
        if (typeof cfg.senseDistance === 'number') {
            aimComp.add(0xE8).writeFloat(cfg.senseDistance);
        }
        if (typeof cfg.sensitivityYaw === 'number') {
            aimComp.add(0xCC).writeFloat(cfg.sensitivityYaw);
        }
        if (typeof cfg.sensitivityPitch === 'number') {
            aimComp.add(0xD0).writeFloat(cfg.sensitivityPitch);
        }
        if (typeof cfg.aimCorrectionY === 'number') {
            aimComp.add(0xD8).writeFloat(cfg.aimCorrectionY);
        }

        let baseCorrectionZ = (typeof cfg.aimCorrectionZ === 'number') ? cfg.aimCorrectionZ : 70.0;
        let effectiveZOffset = baseCorrectionZ;

        if (instances.cachedPlayers && instances.cachedPlayers.length > 0) {
            let targetIsCrouched = false;
            let closestDist = 999999.0;
            for (let i = 0; i < instances.cachedPlayers.length; i++) {
                const p = instances.cachedPlayers[i];
                if (p.isSelf || p.isFriendly || p.isDead || p.isNoclip || p.isInvisible || (typeof p.hp === 'number' && p.hp <= 0) || !p.location) continue;
                if (p.distMeters < closestDist && p.distMeters < 80.0) {
                    closestDist = p.distMeters;
                    targetIsCrouched = !!p.isCrouched;
                }
            }

            if (targetIsCrouched) {
                effectiveZOffset = baseCorrectionZ * (35.0 / 70.0);
            }
        }

        aimComp.add(0xDC).writeFloat(effectiveZOffset);

        if (typeof cfg.aimOffset === 'number') {
            aimComp.add(0xE0).writeFloat(cfg.aimOffset);
        }
        if (typeof cfg.verticalAimEnable === 'boolean') {
            aimComp.add(0xF1).writeU8(cfg.verticalAimEnable ? 1 : 0);
        }
        if (typeof cfg.isDebug === 'boolean') {
            aimComp.add(0xF0).writeU8(cfg.isDebug ? 1 : 0);
        }
        return true;
    } catch (e) {
        return false;
    }
}

function isPlayerFriendly(p) {
    if (!p || p.isSelf) return false;
    if (p.id && instances.friendlyList.has(p.id)) return true;
    if (p.name && instances.friendlyList.has(p.name)) return true;
    if (p.rawName && instances.friendlyList.has(p.rawName)) return true;
    if (p.family && instances.friendlyFamilies.has(p.family.toLowerCase().trim())) return true;
    return false;
}

/**
 * Synchronisiert Konfigurationseinstellungen aus /data/local/tmp/reshift_config.json
 */
let _nativeFopen = null;
let _nativeFread = null;
let _nativeFclose = null;

function readTextFileNative(path) {
    try {
        if (!_nativeFopen) {
            const fopenPtr = Module.findExportByName(null, "fopen");
            const freadPtr = Module.findExportByName(null, "fread");
            const fclosePtr = Module.findExportByName(null, "fclose");
            if (fopenPtr && freadPtr && fclosePtr) {
                _nativeFopen = new NativeFunction(fopenPtr, 'pointer', ['pointer', 'pointer']);
                _nativeFread = new NativeFunction(freadPtr, 'size_t', ['pointer', 'size_t', 'size_t', 'pointer']);
                _nativeFclose = new NativeFunction(fclosePtr, 'int', ['pointer']);
            }
        }
        if (_nativeFopen) {
            const cPath = Memory.allocUtf8String(path);
            const cMode = Memory.allocUtf8String("r");
            const fp = _nativeFopen(cPath, cMode);
            if (fp && !fp.isNull()) {
                const bufSize = 4096;
                const buf = Memory.alloc(bufSize);
                const bytesRead = _nativeFread(buf, 1, bufSize - 1, fp);
                _nativeFclose(fp);
                if (bytesRead > 0) {
                    return buf.readUtf8String(bytesRead);
                }
            }
        }
    } catch (_) { }

    try {
        const f = new File(path, "r");
        const content = f.readText();
        f.close();
        if (content && content.length > 0) return content;
    } catch (_) { }

    return null;
}

const configCandidatePaths = [
    "/data/data/com.Grand.Napal/files/reshift_config.json",
    "/sdcard/Android/data/com.Grand.Napal/files/reshift_config.json",
    "/storage/emulated/0/Android/data/com.Grand.Napal/files/reshift_config.json",
    "/data/local/tmp/reshift_config.json",
    "/sdcard/reshift_config.json",
    "/storage/emulated/0/reshift_config.json",
    "/sdcard/Documents/reshift_config.json"
];

function syncConfigFromFile() {
    const now = Date.now();
    if (now - instances.lastConfigSyncTime < 200) return;
    instances.lastConfigSyncTime = now;

    for (let i = 0; i < configCandidatePaths.length; i++) {
        const filePath = configCandidatePaths[i];
        const content = readTextFileNative(filePath);

        if (content && content.length > 5) {
            try {
                const cfg = JSON.parse(content);
                if (typeof cfg.espActive === "boolean") instances.deviceEspActive = cfg.espActive;
                if (typeof cfg.espStyle === "string") instances.deviceEspStyle = cfg.espStyle;
                if (typeof cfg.lineMode === "string") instances.deviceLineMode = cfg.lineMode;
                if (typeof cfg.radarActive === "boolean") instances.deviceRadarActive = cfg.radarActive;
                if (typeof cfg.friendlyProtect === "boolean") instances.friendlyProtect = cfg.friendlyProtect;

                if (typeof cfg.aimActive === "boolean") instances.aimActive = cfg.aimActive;
                if (typeof cfg.targetVehicleOccupants === "boolean") instances.targetVehicleOccupants = cfg.targetVehicleOccupants;
                if (typeof cfg.aimAssistMode === "string") instances.aimAssistMode = cfg.aimAssistMode;

                if (typeof cfg.senseRadius === "number") {
                    instances.senseRadius = Math.max(20.0, Math.min(150.0, cfg.senseRadius));
                }

                if (typeof cfg.aimCorrectionZ === "number") instances.aimCorrectionZ = cfg.aimCorrectionZ;
                if (typeof cfg.smoothFactor === "number") instances.smoothFactor = cfg.smoothFactor;

                if (typeof cfg.fovCircleActive === "boolean") instances.fovCircleActive = cfg.fovCircleActive;
                if (typeof cfg.fovCenterDotActive === "boolean") instances.fovCenterDotActive = cfg.fovCenterDotActive;
                if (typeof cfg.fovSkin === "string") instances.fovSkin = cfg.fovSkin;
                if (typeof cfg.fovOffsetX === "number") instances.fovOffsetX = cfg.fovOffsetX;

                if (typeof cfg.vehicleTorqueMultiplier === "number") {
                    instances.vehicleTorqueMultiplier = Math.max(1.0, Math.min(5.0, cfg.vehicleTorqueMultiplier));
                }

                if (typeof cfg.photomodeFlyActive === "boolean") {
                    if (instances.photomodeFlyActive !== cfg.photomodeFlyActive) {
                        instances.photomodeFlyActive = cfg.photomodeFlyActive;
                        togglePhotomodeFly(cfg.photomodeFlyActive);
                    }
                }

                if (typeof cfg.debugCameraActive === "boolean") {
                    if (instances.debugCameraActive !== cfg.debugCameraActive) {
                        instances.debugCameraActive = cfg.debugCameraActive;
                        toggleDebugCamera(cfg.debugCameraActive);
                    }
                }

                if (typeof cfg.nitroTrigger === "number") {
                    if (instances.lastNitroTrigger === undefined || cfg.nitroTrigger !== instances.lastNitroTrigger) {
                        const isFirstSync = (instances.lastNitroTrigger === undefined || instances.lastNitroTrigger === 0);
                        instances.lastNitroTrigger = cfg.nitroTrigger;
                        if (!isFirstSync) {
                            triggerNitro();
                        }
                    }
                }

                if (typeof cfg.medkitTrigger === "number") {
                    if (instances.lastMedkitTrigger === undefined || cfg.medkitTrigger !== instances.lastMedkitTrigger) {
                        const isFirstSync = (instances.lastMedkitTrigger === undefined || instances.lastMedkitTrigger === 0);
                        instances.lastMedkitTrigger = cfg.medkitTrigger;
                        if (!isFirstSync) {
                            triggerMedkit();
                        }
                    }
                }

                if (typeof cfg.armorTrigger === "number") {
                    if (instances.lastArmorTrigger === undefined || cfg.armorTrigger !== instances.lastArmorTrigger) {
                        const isFirstSync = (instances.lastArmorTrigger === undefined || instances.lastArmorTrigger === 0);
                        instances.lastArmorTrigger = cfg.armorTrigger;
                        if (!isFirstSync) {
                            triggerArmor();
                        }
                    }
                }
                break;
            } catch (_) { }
        }
    }
}

/**
 * Initialisiert die nativen UE4 C++ Renderer-Funktionen aus AHUD
 */
function initDeviceHudNative() {
    if (instances.deviceHudNative) return instances.deviceHudNative;
    if (!libUE4.base) return null;

    try {
        const base = libUE4.base;
        const projAddr = base.add(CONFIG.offsets.HUD.Project);
        const lineAddr = base.add(CONFIG.offsets.HUD.DrawLine);
        const rectAddr = base.add(CONFIG.offsets.HUD.DrawRect);
        const textAddr = base.add(CONFIG.offsets.HUD.DrawText);

        const fnProject = new NativeFunction(projAddr, ['float', 'float', 'float'], ['pointer', 'float', 'float', 'float']);

        const fnDrawLine = new NativeFunction(lineAddr, 'void', [
            'pointer',
            'float', 'float', 'float', 'float',
            'float', 'float', 'float', 'float',
            'float'
        ]);

        const fnDrawRect = new NativeFunction(rectAddr, 'void', [
            'pointer',
            'float', 'float', 'float', 'float',
            'float', 'float', 'float', 'float'
        ]);

        const fnDrawText = new NativeFunction(textAddr, 'void', [
            'pointer', 'pointer',
            'float', 'float', 'float', 'float',
            'float', 'float',
            'pointer',
            'float',
            'bool'
        ]);

        const scratchUtf16 = Memory.alloc(512);
        const scratchFStr = Memory.alloc(16);
        scratchFStr.writePointer(scratchUtf16);

        instances.deviceHudNative = {
            fnProject,
            fnDrawLine,
            fnDrawRect,
            fnDrawText,
            scratchUtf16,
            scratchFStr
        };
        console.log("[★] Native AHUD Render-Funktionen gebunden (Project, DrawLine, DrawRect, DrawText).");
        return instances.deviceHudNative;
    } catch (e) {
        console.log("[!] Error bind HUD functions: " + e);
        return null;
    }
}

function getDeviceDynamicFString(nat, str) {
    try {
        const s = str.length > 250 ? str.slice(0, 250) : str;
        for (let i = 0; i < s.length; i++) {
            nat.scratchUtf16.add(i * 2).writeU16(s.charCodeAt(i));
        }
        nat.scratchUtf16.add(s.length * 2).writeU16(0);
        const len = s.length + 1;
        nat.scratchFStr.add(8).writeInt(len);
        nat.scratchFStr.add(12).writeInt(len);
        return nat.scratchFStr;
    } catch (e) {
        return null;
    }
}

function projectWorldToScreen(hudPtr, nat, x, y, z) {
    try {
        const res = nat.fnProject(hudPtr, x, y, z);
        if (!res || !Array.isArray(res) || res.length < 3) return null;
        return { x: res[0], y: res[1], z: res[2], inFront: res[2] > 0.0 };
    } catch (e) {
        return null;
    }
}

// 1:1 Helper aus ue4_lua_bridge.js: Perform Smooth Camera Alignment Lock towards closest enemy
function performSmoothAimLock(mainLua, cfg) {
    if (!cfg || !cfg.active || !cfg.smoothLock) {
        instances.hasLockedTarget = false;
        return;
    }
    try {
        if (!mainLua || mainLua.isNull()) {
            instances.hasLockedTarget = false;
            return;
        }

        const fovRadiusPx = (typeof cfg.senseRadius === 'number') ? cfg.senseRadius : 120.0;
        if (fovRadiusPx <= 0) {
            instances.hasLockedTarget = false;
            return;
        }

        const gameMode = mainLua.add(0x2F0).readPointer();
        if (!gameMode || gameMode.isNull() || gameMode.compare(ptr("0x10000")) < 0) return;
        let pc = gameMode.add(0xA88).readPointer();
        if (!isValidPtr(pc)) {
            pc = gameMode.add(0x9D0).readPointer();
        }
        if (!isValidPtr(pc)) {
            pc = gameMode.add(0x4F0).readPointer();
        }
        if (!isValidPtr(pc)) return;

        const camMgr = pc.add(0x2B8).readPointer();
        if (!camMgr || camMgr.isNull() || camMgr.compare(ptr("0x10000")) < 0) return;
        const pov = camMgr.add(0x1AF0);
        const camX = pov.readFloat();
        const camY = pov.add(4).readFloat();
        const camZ = pov.add(8).readFloat();
        const curPitch = pov.add(12).readFloat();
        const curYaw = pov.add(16).readFloat();
        const curRoll = pov.add(20).readFloat();
        let camFov = pov.add(24).readFloat();
        if (isNaN(camFov) || camFov < 20.0 || camFov > 160.0) camFov = 80.0;

        if (isNaN(camX) || isNaN(camY) || isNaN(camZ)) return;

        const players = instances.cachedPlayers;
        if (!players || players.length === 0) {
            instances.hasLockedTarget = false;
            return;
        }

        let sizeX = 2400;
        let sizeY = 1080;
        if (instances.lastScreenSize && instances.lastScreenSize.sizeX > 0 && instances.lastScreenSize.sizeY > 0) {
            sizeX = instances.lastScreenSize.sizeX;
            sizeY = instances.lastScreenSize.sizeY;
        }

        const cx = sizeX / 2.0;
        const cy = sizeY / 2.0;

        const pRad = (curPitch * Math.PI) / 180.0;
        const yRad = (curYaw * Math.PI) / 180.0;
        const rRad = ((curRoll || 0.0) * Math.PI) / 180.0;

        const sp = Math.sin(pRad), cp = Math.cos(pRad);
        const sy = Math.sin(yRad), cy_rad = Math.cos(yRad);
        const sr = Math.sin(rRad), cr = Math.cos(rRad);

        const fx = cp * cy_rad;
        const fy = cp * sy;
        const fz = sp;

        const rx = cy_rad * sp * sr - sy * cr;
        const ry = sy * sp * sr + cy_rad * cr;
        const rz = -cp * sr;

        const ux = -(cy_rad * sp * cr + sy * sr);
        const uy = -(sy * sp * cr - cy_rad * sr);
        const uz = cp * cr;

        const fovRad = (camFov * Math.PI) / 180.0;
        const focalLen = (sizeX / 2.0) / Math.tan(fovRad / 2.0);

        let targetZOffset = 70.0;
        if (typeof cfg.aimCorrectionZ === 'number') {
            targetZOffset = cfg.aimCorrectionZ;
        }

        let targetYOffset = 0.0;
        if (typeof cfg.aimCorrectionY === 'number') {
            targetYOffset = cfg.aimCorrectionY;
        }

        let bestTarget = null;
        let minScreenDist = 999999.0;
        const maxDist = cfg.senseDistance || 8000.0;

        for (let i = 0; i < players.length; i++) {
            const p = players[i];
            if (p.isSelf || !p.location || p.distMeters < 0.8) continue;
            if (isPlayerFriendly(p)) continue;
            if (p.isDead || p.isNoclip || p.isInvisible || (typeof p.hp === 'number' && p.hp <= 0)) continue;
            if (instances.targetVehicleOccupants === false && p.inVehicle) continue;

            const isCrouchedTarget = !!p.isCrouched;
            const defaultHeadOffset = isCrouchedTarget ? 35.0 : 70.0;
            let targetHeadZ = p.location.z + defaultHeadOffset;

            if (p.headLocation && typeof p.headLocation.z === 'number' && p.headLocation.z > 0) {
                targetHeadZ = p.headLocation.z;
                if (isCrouchedTarget && targetHeadZ > (p.location.z + 50.0)) {
                    targetHeadZ = p.location.z + 35.0;
                }
            } else if (p.boxExtent && typeof p.boxExtent.z === 'number') {
                targetHeadZ = (p.location.z + p.boxExtent.z) - (isCrouchedTarget ? 8.0 : 12.0);
            }

            const customZOffset = (typeof cfg.aimCorrectionZ === 'number' && cfg.aimCorrectionZ !== 70.0)
                ? (isCrouchedTarget ? (cfg.aimCorrectionZ * (35.0 / 70.0) - 35.0) : (cfg.aimCorrectionZ - 70.0))
                : 0.0;
            const finalTargetZ = targetHeadZ + customZOffset;

            const dx = p.location.x - camX;
            const dy = (p.location.y + targetYOffset) - camY;
            const dz = finalTargetZ - camZ;
            const dist2d = Math.sqrt(dx * dx + dy * dy);
            const dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist3d > maxDist || dist3d < 50.0) continue;

            const depth = dx * fx + dy * fy + dz * fz;
            if (depth <= 20.0) continue;

            const right = dx * rx + dy * ry + dz * rz;
            const up = dx * ux + dy * uy + dz * uz;

            const screenX = cx + (right / depth) * focalLen;
            const screenY = cy - (up / depth) * focalLen;

            const screenDist = Math.sqrt((screenX - cx) * (screenX - cx) + (screenY - cy) * (screenY - cy));

            if (screenDist > fovRadiusPx) {
                continue;
            }

            let reqYaw = Math.atan2(dy, dx) * (180.0 / Math.PI);
            let reqPitch = Math.atan2(dz, dist2d) * (180.0 / Math.PI);

            let dYaw = reqYaw - curYaw;
            while (dYaw > 180.0) dYaw -= 360.0;
            while (dYaw < -180.0) dYaw += 360.0;
            let dPitch = reqPitch - curPitch;

            if (screenDist < minScreenDist) {
                minScreenDist = screenDist;
                bestTarget = { reqYaw, reqPitch, dYaw, dPitch, screenDist };
            }
        }

        instances.hasLockedTarget = !!bestTarget;

        if (bestTarget) {
            const angleDist = Math.sqrt(bestTarget.dYaw * bestTarget.dYaw + bestTarget.dPitch * bestTarget.dPitch);
            if (angleDist > 0.02) {
                const smooth = typeof cfg.smoothFactor === 'number' ? cfg.smoothFactor : 1.0;
                const sens = typeof cfg.sensitivityYaw === 'number' ? cfg.sensitivityYaw : 2.0;
                const aimMode = cfg.aimAssistMode || instances.aimAssistMode || "new";

                let stepYaw = 0.0;
                let stepPitch = 0.0;

                if (aimMode === "old" || aimMode === "classic") {
                    let speedFactor = 1.0;
                    if (smooth >= 0.85) {
                        speedFactor = 1.0;
                    } else {
                        speedFactor = Math.min(1.0, Math.pow(smooth, 0.3) * (sens >= 1.0 ? Math.sqrt(sens) : 1.0));
                    }
                    stepYaw = bestTarget.dYaw * speedFactor;
                    stepPitch = bestTarget.dPitch * speedFactor;
                } else {
                    const distRatio = Math.max(0.0, Math.min(1.0, bestTarget.screenDist / fovRadiusPx));
                    const centerMagnetism = Math.cos((distRatio * Math.PI) / 2.0);

                    let speedFactor = 1.0;
                    if (smooth >= 0.85) {
                        speedFactor = 1.0;
                    } else {
                        const baseSpeed = Math.min(1.0, Math.pow(smooth, 0.35) * (sens >= 1.0 ? Math.sqrt(sens) : 1.0));
                        const minEdgeFactor = 0.25;
                        const assistCurve = minEdgeFactor + (1.0 - minEdgeFactor) * Math.pow(centerMagnetism, 1.4);
                        speedFactor = Math.min(1.0, baseSpeed * assistCurve);

                        if (distRatio < 0.25) {
                            const innerBoost = (0.25 - distRatio) / 0.25;
                            speedFactor = Math.min(1.0, speedFactor + (1.0 - speedFactor) * innerBoost * 0.90);
                        }
                    }

                    stepYaw = bestTarget.dYaw * speedFactor;
                    stepPitch = bestTarget.dPitch * speedFactor;

                    const maxStepYaw = 2.0 + 7.0 * centerMagnetism;
                    if (Math.abs(stepYaw) > maxStepYaw) {
                        stepYaw = Math.sign(stepYaw) * maxStepYaw;
                    }
                    const maxStepPitch = 1.5 + 5.0 * centerMagnetism;
                    if (Math.abs(stepPitch) > maxStepPitch) {
                        stepPitch = Math.sign(stepPitch) * maxStepPitch;
                    }
                }

                let nextPitch = curPitch;
                if (cfg.verticalAimEnable !== false) {
                    nextPitch = Math.max(-70.0, Math.min(70.0, curPitch + stepPitch));
                }

                const nextYaw = curYaw + stepYaw;

                // 1:1 Schreibzugriff auf APlayerController ControlRotation bei Offset 0x288 & 0x28C
                if (cfg.verticalAimEnable !== false) {
                    pc.add(0x288).writeFloat(nextPitch);
                }
                pc.add(0x28C).writeFloat(nextYaw);
            }
        }
    } catch (e) { }
}

/**
 * Rendert den FOV Kreis & Target Lock Status im HUD
 */
function renderFovCircle(hud, nat, sizeX, sizeY) {
    if (instances.fovCircleActive === false) return;

    const cx = sizeX / 2.0 + (Number(instances.fovOffsetX) || 0.0);
    const cy = sizeY / 2.0;
    const fovR = instances.senseRadius || 120.0;
    const isLocked = instances.hasLockedTarget;
    const skin = instances.fovSkin || "classic";

    let r = isLocked ? 0.95 : 0.0;
    let g = isLocked ? 0.22 : 0.94;
    let b = isLocked ? 0.22 : 1.0;
    let a = 0.85;

    const segments = 32;
    for (let i = 0; i < segments; i++) {
        const a1 = (i / segments) * 2.0 * Math.PI;
        const a2 = ((i + 1) / segments) * 2.0 * Math.PI;
        nat.fnDrawLine(
            hud,
            cx + Math.cos(a1) * fovR,
            cy + Math.sin(a1) * fovR,
            cx + Math.cos(a2) * fovR,
            cy + Math.sin(a2) * fovR,
            r, g, b, a, 1.5
        );
    }

    if (skin === "brackets") {
        const L = Math.max(10.0, fovR * 0.30);
        nat.fnDrawLine(hud, cx - fovR, cy - L, cx - fovR, cy + L, r, g, b, 1.0, 2.5);
        nat.fnDrawLine(hud, cx + fovR, cy - L, cx + fovR, cy + L, r, g, b, 1.0, 2.5);
        nat.fnDrawLine(hud, cx - L, cy - fovR, cx + L, cy - fovR, r, g, b, 1.0, 2.5);
        nat.fnDrawLine(hud, cx - L, cy + fovR, cx + L, cy + fovR, r, g, b, 1.0, 2.5);
    } else if (skin === "crosshair" || skin === "tactical") {
        nat.fnDrawLine(hud, cx - fovR - 12.0, cy, cx - fovR + 10.0, cy, r, g, b, 1.0, 2.0);
        nat.fnDrawLine(hud, cx + fovR - 10.0, cy, cx + fovR + 12.0, cy, r, g, b, 1.0, 2.0);
        nat.fnDrawLine(hud, cx, cy - fovR - 12.0, cx, cy - fovR + 10.0, r, g, b, 1.0, 2.0);
        nat.fnDrawLine(hud, cx, cy + fovR - 10.0, cx, cy + fovR + 12.0, r, g, b, 1.0, 2.0);
    }

    if (instances.fovCenterDotActive !== false) {
        nat.fnDrawRect(hud, r, g, b, 1.0, cx - 2.5, cy - 2.5, 5.0, 5.0);
    }

    const badgeText = isLocked ? "🎯 TARGET LOCKED" : `FOV: ${Math.round(fovR)}px`;
    const fstr = getDeviceDynamicFString(nat, badgeText);
    if (fstr) {
        nat.fnDrawText(hud, fstr, r, g, b, 0.95, cx - 42.0, cy - fovR - 20.0, ptr(0), 0.85, 0);
    }
}

/**
 * Native On-Device 60FPS Player Scan Engine
 */
function scanNearbyPlayersInternal() {
    if (!instances.mainLuaState) return { success: false, error: "MainLuaState nicht erfasst" };
    const mainLua = instances.mainLuaState;
    try {
        const gameMode = mainLua.add(0x2F0).readPointer();
        if (!gameMode || !isValidPtr(gameMode)) {
            return { success: false, error: "GameMode ungültig" };
        }

        const myChar = getLocalPlayerActor(mainLua);
        if (!myChar || !isValidPtr(myChar)) {
            return { success: true, count: 0, myLocation: { x: 0, y: 0, z: 0 }, myHeading: { yaw: 0 }, players: [] };
        }

        let myLoc = { x: 0, y: 0, z: 0 };
        let myHeading = { yaw: 0 };

        const myRoot = myChar.add(0x130).readPointer();
        if (myRoot && isValidPtr(myRoot)) {
            let mx = myRoot.add(0x1D0).readFloat();
            let my = myRoot.add(0x1D4).readFloat();
            let mz = myRoot.add(0x1D8).readFloat();
            if (mx === 0 && my === 0 && mz === 0) {
                mx = myRoot.add(0x100).readFloat();
                my = myRoot.add(0x104).readFloat();
                mz = myRoot.add(0x108).readFloat();
            }
            if (mx === 0 && my === 0 && mz === 0) {
                mx = myRoot.add(0x11C).readFloat();
                my = myRoot.add(0x120).readFloat();
                mz = myRoot.add(0x124).readFloat();
            }
            if (!isNaN(mx) && !isNaN(my) && !isNaN(mz)) {
                myLoc = { x: mx, y: my, z: mz };
            }

            try {
                let qx = myRoot.add(0x1C0).readFloat();
                let qy = myRoot.add(0x1C4).readFloat();
                let qz = myRoot.add(0x1C8).readFloat();
                let qw = myRoot.add(0x1CC).readFloat();
                if (!isNaN(qw) && (qx !== 0 || qy !== 0 || qz !== 0 || qw !== 0)) {
                    let yawRad = Math.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz));
                    myHeading.yaw = Math.round((yawRad * 180.0 / Math.PI) * 10) / 10;
                } else {
                    let ryaw = myRoot.add(0x12C).readFloat();
                    if (!isNaN(ryaw)) myHeading.yaw = Math.round(ryaw * 10) / 10;
                }
            } catch (_) { }
        }

        try {
            let pc = gameMode.add(0xA88).readPointer();
            if (!isValidPtr(pc)) pc = gameMode.add(0x9D0).readPointer();
            if (!isValidPtr(pc)) pc = gameMode.add(0x4F0).readPointer();
            if (isValidPtr(pc)) {
                const camMgr = pc.add(0x2B8).readPointer();
                if (isValidPtr(camMgr)) {
                    const pov = camMgr.add(0x1AF0);
                    const yaw = pov.add(16).readFloat();
                    const fov = pov.add(24).readFloat();
                    if (!isNaN(yaw) && !isNaN(fov) && fov > 20 && fov < 160) {
                        myHeading.yaw = Math.round(yaw * 10) / 10;
                    }
                }
            }
        } catch (_) { }

        const players = [];
        const seenActors = new Set();
        let localPlayerId = -1;
        let localPlayerName = "";

        function isHumanCharacter(actor) {
            if (!isValidPtr(actor)) return false;
            try {
                let moveComp = actor.add(0x288).readPointer();
                if (!isValidPtr(moveComp)) {
                    moveComp = actor.add(0x290).readPointer() || actor.add(0x298).readPointer() || actor.add(0x280).readPointer() || actor.add(0x308).readPointer() || actor.add(0x318).readPointer() || actor.add(0x2B8).readPointer();
                }
                const meshComp = actor.add(0x280).readPointer() || actor.add(0x288).readPointer() || actor.add(0x298).readPointer();
                const capsuleComp = actor.add(0x290).readPointer() || actor.add(0x130).readPointer() || actor.add(0x288).readPointer();
                const rootComp = actor.add(0x130).readPointer() || actor.add(0x290).readPointer() || actor.add(0x280).readPointer() || actor.add(0x138).readPointer();

                if (!isValidPtr(meshComp) && !isValidPtr(capsuleComp) && !isValidPtr(rootComp) && !isValidPtr(moveComp)) return false;
                return true;
            } catch (_) {
                return false;
            }
        }

        function readLocAndBounds(actor) {
            if (!isValidPtr(actor)) return null;
            try {
                let root = actor.add(0x130).readPointer();
                if (!isValidPtr(root)) root = actor.add(0x290).readPointer();
                if (!isValidPtr(root)) root = actor.add(0x280).readPointer();
                if (!isValidPtr(root)) root = actor.add(0x138).readPointer();
                if (!isValidPtr(root)) root = actor.add(0x140).readPointer();
                if (!isValidPtr(root)) root = actor.add(0x180).readPointer();
                if (!isValidPtr(root)) return null;

                let px = root.add(0x1D0).readFloat();
                let py = root.add(0x1D4).readFloat();
                let pz = root.add(0x1D8).readFloat();
                if (px === 0 && py === 0 && pz === 0) {
                    px = root.add(0x100).readFloat();
                    py = root.add(0x104).readFloat();
                    pz = root.add(0x108).readFloat();
                }
                if (px === 0 && py === 0 && pz === 0) {
                    px = root.add(0x11C).readFloat();
                    py = root.add(0x120).readFloat();
                    pz = root.add(0x124).readFloat();
                }
                if (px === 0 && py === 0 && pz === 0) {
                    px = root.add(0x140).readFloat();
                    py = root.add(0x144).readFloat();
                    pz = root.add(0x148).readFloat();
                }

                if (isNaN(px) || isNaN(py) || isNaN(pz) || Math.abs(px) > 5000000 || Math.abs(py) > 5000000 || Math.abs(pz) > 5000000) {
                    return null;
                }

                return {
                    loc: {
                        x: Math.round(px * 10) / 10,
                        y: Math.round(py * 10) / 10,
                        z: Math.round(pz * 10) / 10
                    }
                };
            } catch (e) {
                return null;
            }
        }

        function addPlayerEntry(actor, idVal, nameVal, pingVal, isLocal) {
            if (!isValidPtr(actor)) return;
            if (!isHumanCharacter(actor)) return;

            const actorKey = actor.toString();
            if (seenActors.has(actorKey)) return;
            seenActors.add(actorKey);

            const data = readLocAndBounds(actor);
            if (!data) return;

            let inVehicle = false;
            let isInvisible = false;
            let isCrouched = false;

            try {
                const c1 = (actor.add(0x330).readU8() & 0x01) !== 0;
                const c2 = (actor.add(0x338).readU8() & 0x01) !== 0;
                const c3 = (actor.add(0x398).readU8() & 0x01) !== 0;
                const c4 = (actor.add(0x3C0).readU8() & 0x01) !== 0;
                if (c1 || c2 || c3 || c4) {
                    isCrouched = true;
                }
            } catch (_) { }

            if (!isCrouched) {
                try {
                    let moveComp = actor.add(0x288).readPointer();
                    if (!isValidPtr(moveComp)) moveComp = actor.add(0x290).readPointer() || actor.add(0x298).readPointer();
                    if (isValidPtr(moveComp)) {
                        const m1 = (moveComp.add(0x388).readU8() & 0x04) !== 0;
                        const m2 = (moveComp.add(0x188).readU8() & 0x01) !== 0;
                        const m3 = (moveComp.add(0x18C).readU8() & 0x01) !== 0;
                        if (m1 || m2 || m3) {
                            isCrouched = true;
                        }
                    }
                } catch (_) { }
            }

            if (!isCrouched) {
                try {
                    let capsuleComp = actor.add(0x290).readPointer();
                    if (!isValidPtr(capsuleComp)) capsuleComp = actor.add(0x130).readPointer();
                    if (isValidPtr(capsuleComp)) {
                        const hOffs = [0x460, 0x1E8, 0x1F0, 0x1E0, 0x1F4, 0x1A8];
                        for (let ho of hOffs) {
                            let halfH = capsuleComp.add(ho).readFloat();
                            if (!isNaN(halfH) && halfH > 15.0 && halfH < 72.0) {
                                isCrouched = true;
                                break;
                            }
                        }
                    }
                } catch (_) { }
            }

            try {
                let vehPtr = actor.add(0x6A0).readPointer();
                if (!isValidPtr(vehPtr)) vehPtr = actor.add(0x698).readPointer();
                if (isValidPtr(vehPtr)) inVehicle = true;
            } catch (_) { }

            try {
                const actorFlags = actor.add(0x58).readU8();
                if ((actorFlags & 0x08) !== 0 || (actorFlags & 0x01) !== 0) {
                    isInvisible = true;
                }
            } catch (_) { }

            const dx = (data.loc.x - myLoc.x) / 100.0;
            const dy = (data.loc.y - myLoc.y) / 100.0;
            const dz = (data.loc.z - myLoc.z) / 100.0;
            let distMeters = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (isNaN(distMeters)) distMeters = 0.0;
            distMeters = Math.round(distMeters * 10) / 10;

            if (!nameVal || nameVal.trim().length === 0 || idVal === undefined || idVal === null || idVal <= 0) {
                try {
                    const netPlayer = actor.add(0x5E0).readPointer();
                    if (isValidPtr(netPlayer)) {
                        const npName = readFString(netPlayer.add(0x68)) || readFString(netPlayer.add(0x30));
                        if (npName && npName.trim().length > 0) nameVal = npName.trim();
                        if (idVal === undefined || idVal === null || idVal <= 0) {
                            const npId = netPlayer.add(0x10).readInt() || netPlayer.add(0x14).readInt() || netPlayer.add(0x24).readInt();
                            if (npId > 0 && npId < 100000 && npId !== 112) idVal = npId;
                        }
                    }
                } catch (_) { }
            }

            if (idVal === undefined || idVal === null || idVal <= 0 || !nameVal || nameVal.trim().length === 0) {
                try {
                    const ps = actor.add(0x240).readPointer();
                    if (isValidPtr(ps)) {
                        const candId = ps.add(0x224).readInt();
                        if (candId > 0 && candId < 10000) {
                            if (idVal === undefined || idVal === null || idVal <= 0) idVal = candId;
                        }
                        if (pingVal === 0) pingVal = ps.add(0x228).readU8();
                        if (!nameVal || nameVal.trim().length === 0) {
                            const psName = readFString(ps.add(0x2F8));
                            if (psName && psName.trim().length > 0) nameVal = psName.trim();
                        }
                    }
                } catch (e) { }
            }

            let familyVal = "";
            let wFamily = "";
            let isWidgetValidForPlayer = false;

            try {
                const wc = actor.add(0x4D8).readPointer();
                if (isValidPtr(wc)) {
                    const w = wc.add(0x560).readPointer();
                    if (isValidPtr(w)) {
                        let wName = "";
                        let wId = -1;

                        const nameTb = w.add(0x4D0).readPointer();
                        if (isValidPtr(nameTb)) {
                            const nStr = readTextBlockText(nameTb);
                            if (nStr && nStr.length > 0 && nStr.length < 60) wName = nStr.trim();
                        }

                        const idTb = w.add(0x4E0).readPointer();
                        if (isValidPtr(idTb)) {
                            const idStr = readTextBlockText(idTb);
                            if (idStr) {
                                const parsed = parseInt(idStr.replace(/[^0-9]/g, ''));
                                if (!isNaN(parsed) && parsed > 0) wId = parsed;
                            }
                        }

                        const idMatches = (idVal !== undefined && idVal !== null && idVal > 0 && wId > 0 && idVal === wId);
                        const idConflicts = (idVal !== undefined && idVal !== null && idVal > 0 && wId > 0 && idVal !== wId);

                        let nameMatches = false;
                        let nameConflicts = false;
                        if (nameVal && wName) {
                            const cNameVal = nameVal.toLowerCase().replace(/[^a-z0-9]/g, '');
                            const cWName = wName.toLowerCase().replace(/[^a-z0-9]/g, '');
                            if (cNameVal.length > 0 && cWName.length > 0) {
                                if (cNameVal === cWName || cNameVal.includes(cWName) || cWName.includes(cNameVal)) {
                                    nameMatches = true;
                                } else {
                                    nameConflicts = true;
                                }
                            }
                        }

                        if (isLocal) {
                            isWidgetValidForPlayer = true;
                        } else {
                            const isLocalBleed = (localPlayerId > 0 && wId === localPlayerId) ||
                                (localPlayerName && wName && wName.toLowerCase().trim() === localPlayerName.toLowerCase().trim());
                            if (!isLocalBleed && !idConflicts && !nameConflicts) {
                                if (idMatches || nameMatches) {
                                    isWidgetValidForPlayer = true;
                                } else if ((idVal === undefined || idVal === null || idVal <= 0) && (!nameVal || nameVal.trim().length === 0)) {
                                    if (wId > 0 || (wName && wName.length > 0)) {
                                        isWidgetValidForPlayer = true;
                                        if (wId > 0) idVal = wId;
                                        if (wName) nameVal = wName;
                                    }
                                }
                            }
                        }

                        let famVisible = false;
                        const famBorder = w.add(0x528).readPointer();
                        const famText = w.add(0x4C8).readPointer();
                        if (isValidPtr(famBorder) && isWidgetVisible(famBorder)) {
                            famVisible = true;
                        }

                        if (isWidgetValidForPlayer && famVisible && isValidPtr(famText)) {
                            const fStr = readTextBlockText(famText);
                            if (fStr && fStr.length > 0 && fStr.length < 60) {
                                wFamily = fStr;
                            }
                        }
                    }
                }
            } catch (_) { }

            const hasRealId = (idVal !== undefined && idVal !== null && idVal > 0);
            const finalId = hasRealId ? idVal : (isLocal ? 0 : (players.length + 1));
            let finalName = nameVal;
            if (!finalName || finalName.trim().length === 0) {
                if (isLocal) finalName = "Ich";
                else if (hasRealId) finalName = `Spieler #${finalId}`;
                else finalName = `🎭 Maskiert #${finalId}`;
            }

            if (isLocal) {
                if (hasRealId) localPlayerId = finalId;
                if (finalName && finalName !== "Ich") localPlayerName = finalName;
            }

            if (isWidgetValidForPlayer && wFamily && wFamily.length > 0) {
                familyVal = wFamily;
                if (hasRealId) {
                    instances.verifiedFamilyCache.set(finalId, {
                        family: wFamily,
                        name: finalName,
                        dist: distMeters,
                        time: Date.now()
                    });
                }
                if (finalName && !finalName.startsWith("Spieler #") && !finalName.startsWith("🎭")) {
                    instances.verifiedFamilyByName.set(finalName.toLowerCase().trim(), {
                        family: wFamily,
                        id: finalId,
                        time: Date.now()
                    });
                }
            } else if (isWidgetValidForPlayer && distMeters < 35.0) {
                familyVal = "";
                if (hasRealId) instances.verifiedFamilyCache.delete(finalId);
                if (finalName && !finalName.startsWith("Spieler #") && !finalName.startsWith("🎭")) {
                    instances.verifiedFamilyByName.delete(finalName.toLowerCase().trim());
                }
            } else if (distMeters >= 35.0) {
                let cached = null;
                if (hasRealId && instances.verifiedFamilyCache.has(finalId)) {
                    cached = instances.verifiedFamilyCache.get(finalId);
                } else if (finalName && !finalName.startsWith("Spieler #") && !finalName.startsWith("🎭") && instances.verifiedFamilyByName.has(finalName.toLowerCase().trim())) {
                    cached = instances.verifiedFamilyByName.get(finalName.toLowerCase().trim());
                }

                if (cached && cached.family && (Date.now() - cached.time < 300000)) {
                    familyVal = cached.family;
                }
            }

            let isNoclip = false;
            try {
                const moveComp = actor.add(0x288).readPointer();
                if (isValidPtr(moveComp)) {
                    const movementMode = moveComp.add(0x168).readU8();
                    if (movementMode === 5 || movementMode === 6) isNoclip = true;
                }
            } catch (_) { }

            if (finalName && (finalName.toUpperCase().includes("NOCLIP") || finalName.toUpperCase().includes("ADMIN"))) {
                isNoclip = true;
            }
            if (finalName && (finalName.toUpperCase().includes("UNSICHTBAR") || finalName.toUpperCase().includes("INVISIBLE"))) {
                isInvisible = true;
            }

            let hp = 100.0, maxHp = 100.0, armour = 0.0, maxArmour = 100.0, isDead = false;
            try {
                const hpComp = actor.add(0x4E0).readPointer();
                if (isValidPtr(hpComp)) {
                    const candHp = hpComp.add(0xF0).readFloat();
                    const candMaxHp = hpComp.add(0xFC).readFloat();
                    const candArm = hpComp.add(0xF4).readFloat();
                    const candMaxArm = hpComp.add(0x100).readFloat();
                    if (!isNaN(candHp)) hp = Math.round(candHp * 10) / 10;
                    if (!isNaN(candMaxHp) && candMaxHp > 0) maxHp = Math.round(candMaxHp * 10) / 10;
                    if (!isNaN(candArm)) armour = Math.round(candArm * 10) / 10;
                    if (!isNaN(candMaxArm) && candMaxArm > 0) maxArmour = Math.round(candMaxArm * 10) / 10;
                    if (hp <= 0.0) isDead = true;
                }
            } catch (_) { }

            if (!isLocal) {
                if (distMeters < 0.8 || (localPlayerId > 0 && finalId === localPlayerId) || (localPlayerName && finalName && finalName.toLowerCase().trim() === localPlayerName.toLowerCase().trim())) {
                    isLocal = true;
                }
            }

            if (isLocal) {
                seenActors.add(actorKey);
                try {
                    let vehPtr = actor.add(0x6A0).readPointer();
                    if (!isValidPtr(vehPtr)) vehPtr = actor.add(0x698).readPointer();
                    if (isValidPtr(vehPtr)) seenActors.add(vehPtr.toString());
                } catch (_) { }
            }

            const isFriendly = isPlayerFriendly({ id: finalId, name: finalName, family: familyVal });

            players.push({
                id: finalId,
                name: finalName,
                rawName: finalName,
                family: familyVal || "",
                distMeters: isLocal ? 0.0 : distMeters,
                location: data.loc,
                isSelf: !!isLocal,
                isFriendly: isFriendly,
                isNoclip: isNoclip,
                isInvisible: isInvisible,
                inVehicle: inVehicle,
                isCrouched: isCrouched,
                hp: hp,
                maxHp: maxHp,
                armour: armour,
                maxArmour: maxArmour,
                isDead: isDead
            });
        }

        if (myChar && isValidPtr(myChar)) {
            addPlayerEntry(myChar, 0, "Ich", 0, true);
        }

        try {
            let netGame = gameMode.add(0x1E88).readPointer();
            if (!isValidPtr(netGame)) netGame = gameMode.add(0x1DC0).readPointer();
            if (!isValidPtr(netGame)) netGame = gameMode.add(0xEC0).readPointer();
            if (isValidPtr(netGame)) {
                const playerPool = netGame.add(0xF8).readPointer();
                if (isValidPtr(playerPool)) {
                    const arrPtr = playerPool.add(0x28);
                    const pData = arrPtr.readPointer();
                    const pCount = arrPtr.add(8).readInt();
                    if (isValidPtr(pData) && pCount > 0 && pCount < 2000) {
                        for (let i = 0; i < pCount; i++) {
                            try {
                                const np = pData.add(i * 8).readPointer();
                                if (!isValidPtr(np)) continue;
                                const charPtr = np.add(0x90).readPointer();
                                if (isValidPtr(charPtr)) {
                                    const isLocal = myChar && charPtr.equals(myChar);
                                    let npId = i, npName = null, npPing = 0;
                                    try {
                                        const candId = np.add(0x10).readInt() || np.add(0x14).readInt() || np.add(0x24).readInt();
                                        if (candId > 0 && candId < 100000 && candId !== 112) npId = candId;
                                    } catch (_) { }
                                    try { npName = readFString(np.add(0x68)) || readFString(np.add(0x30)); } catch (_) { }
                                    try { npPing = np.add(0x28).readInt(); } catch (_) { }
                                    addPlayerEntry(charPtr, npId, npName, npPing, isLocal);
                                }
                            } catch (_) { }
                        }
                    }
                }
            }
        } catch (_) { }

        try {
            const gameState = gameMode.add(0x280).readPointer();
            if (isValidPtr(gameState)) {
                const playerArrayPtr = gameState.add(0x238);
                const dataPtr = playerArrayPtr.readPointer();
                const count = playerArrayPtr.add(8).readInt();
                if (isValidPtr(dataPtr) && count > 0 && count < 1000) {
                    for (let i = 0; i < count; i++) {
                        try {
                            const ps = dataPtr.add(i * 8).readPointer();
                            if (!isValidPtr(ps)) continue;
                            const pid = ps.add(0x224).readInt();
                            const ping = ps.add(0x228).readU8();
                            const name = readFString(ps.add(0x2F8));
                            const pawn = ps.add(0x280).readPointer();
                            if (isValidPtr(pawn)) {
                                const isLocal = myChar && pawn.equals(myChar);
                                addPlayerEntry(pawn, pid, name, ping, isLocal);
                            }
                        } catch (_) { }
                    }
                }
            }
        } catch (_) { }

        try {
            let world = (gameMode && isValidPtr(gameMode)) ? (gameMode.add(0x240).readPointer() || mainLua.add(0x240).readPointer()) : mainLua.add(0x240).readPointer();
            if (isValidPtr(world)) {
                const persistentLevel = world.add(0x30).readPointer();
                if (isValidPtr(persistentLevel)) {
                    const actorArrayOffsets = [0x98, 0x90, 0xA0];
                    for (let aOff of actorArrayOffsets) {
                        try {
                            const actorsData = persistentLevel.add(aOff).readPointer();
                            const actorsCount = persistentLevel.add(aOff + 8).readInt();
                            if (isValidPtr(actorsData) && actorsCount > 0 && actorsCount < 5000) {
                                for (let i = 0; i < actorsCount; i++) {
                                    try {
                                        const act = actorsData.add(i * 8).readPointer();
                                        if (isValidPtr(act) && isHumanCharacter(act)) {
                                            const isLocal = myChar && act.equals(myChar);
                                            addPlayerEntry(act, null, null, 0, isLocal);
                                        }
                                    } catch (_) { }
                                }
                                break;
                            }
                        } catch (_) { }
                    }
                }
            }
        } catch (_) { }

        return {
            success: true,
            count: players.length,
            myLocation: myLoc,
            myHeading: myHeading,
            players: players
        };
    } catch (e) {
        return { success: false, error: e.toString() };
    }
}

/**
 * Rendert das 1:1 On-Device Native UE4 UCanvas / AHUD 3D ESP Overlay direkt auf dem Bildschirm
 */
function renderDeviceEsp(hud, sizeX, sizeY) {
    if (!instances.deviceEspActive) return;

    const nat = initDeviceHudNative();
    if (!nat) return;

    const players = instances.cachedPlayers;
    if (!players || players.length === 0) return;

    const screenBottomX = sizeX / 2.0;
    const screenBottomY = sizeY - 10.0;

    for (let i = 0; i < players.length; i++) {
        const p = players[i];
        if (p.isSelf || !p.location || p.distMeters < 0.8) continue;

        const loc = p.location;
        const extentZ = p.isCrouched ? 40.0 : 90.0;
        const extentX = p.isCrouched ? 32.0 : 45.0;
        const extent = { x: extentX, y: extentX, z: extentZ };
        const isClose = p.distMeters < 30;

        // 1. Projektion der 3D Welt-Koordinaten auf 2D Bildschirm-Koordinaten via AHUD::Project
        const feetProj = projectWorldToScreen(hud, nat, loc.x, loc.y, loc.z - extent.z);
        const headProj = projectWorldToScreen(hud, nat, loc.x, loc.y, loc.z + extent.z + 15.0);

        if (!feetProj || !feetProj.inFront || !headProj || !headProj.inFront) continue;
        if (feetProj.x < -100 || feetProj.x > sizeX + 100 || feetProj.y < -100 || feetProj.y > sizeY + 100) continue;

        let r = isClose ? 0.95 : 0.0;
        let g = isClose ? 0.26 : 0.94;
        let b = isClose ? 0.26 : 1.0;
        let a = 1.0;

        if (p.isFriendly) {
            r = 0.06; g = 0.73; b = 0.51; // Emerald Grün für Team / Freunde
        } else if (p.isNoclip || p.isInvisible) {
            r = 0.85; g = 0.15; b = 0.95; // Violett für Admins / Unsichtbare
        } else if (p.inVehicle) {
            r = 0.10; g = 0.75; b = 0.95; // Cyan für Spieler in Fahrzeugen
        }

        // 2. Snaplines vom unteren/oberen Bildschirmrand zeichnen
        const lineMode = instances.deviceLineMode || "bottom";
        if (lineMode === "bottom" || lineMode === "both") {
            nat.fnDrawLine(hud, screenBottomX, screenBottomY, feetProj.x, feetProj.y, r, g, b, 0.8, 1.5);
        }
        if (lineMode === "top" || lineMode === "both") {
            nat.fnDrawLine(hud, screenBottomX, 10.0, headProj.x, Math.min(headProj.y, feetProj.y), r, g, b, 0.8, 1.5);
        }

        const boxH = Math.max(12.0, Math.abs(feetProj.y - headProj.y));
        const boxW = Math.max(8.0, boxH * 0.5);
        const leftX = feetProj.x - boxW / 2.0;
        const topY = headProj.y;

        if (instances.deviceEspStyle === "3d") {
            // 3. Volle 3D Bounding-Box (8 Vertices im Raum projiziert)
            const corners = [
                { x: loc.x - extent.x, y: loc.y - extent.y, z: loc.z - extent.z },
                { x: loc.x + extent.x, y: loc.y - extent.y, z: loc.z - extent.z },
                { x: loc.x + extent.x, y: loc.y + extent.y, z: loc.z - extent.z },
                { x: loc.x - extent.x, y: loc.y + extent.y, z: loc.z - extent.z },
                { x: loc.x - extent.x, y: loc.y - extent.y, z: loc.z + extent.z },
                { x: loc.x + extent.x, y: loc.y - extent.y, z: loc.z + extent.z },
                { x: loc.x + extent.x, y: loc.y + extent.y, z: loc.z + extent.z },
                { x: loc.x - extent.x, y: loc.y + extent.y, z: loc.z + extent.z }
            ];

            const p2d = [];
            let valid3d = true;
            for (let c = 0; c < 8; c++) {
                const proj = projectWorldToScreen(hud, nat, corners[c].x, corners[c].y, corners[c].z);
                if (!proj || !proj.inFront) { valid3d = false; break; }
                p2d.push(proj);
            }

            if (valid3d) {
                // Boden-Ring (0-1, 1-2, 2-3, 3-0)
                nat.fnDrawLine(hud, p2d[0].x, p2d[0].y, p2d[1].x, p2d[1].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[1].x, p2d[1].y, p2d[2].x, p2d[2].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[2].x, p2d[2].y, p2d[3].x, p2d[3].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[3].x, p2d[3].y, p2d[0].x, p2d[0].y, r, g, b, a, 1.5);

                // Dach-Ring (4-5, 5-6, 6-7, 7-4)
                nat.fnDrawLine(hud, p2d[4].x, p2d[4].y, p2d[5].x, p2d[5].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[5].x, p2d[5].y, p2d[6].x, p2d[6].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[6].x, p2d[6].y, p2d[7].x, p2d[7].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[7].x, p2d[7].y, p2d[4].x, p2d[4].y, r, g, b, a, 1.5);

                // Vertikale Säulen (0-4, 1-5, 2-6, 3-7)
                nat.fnDrawLine(hud, p2d[0].x, p2d[0].y, p2d[4].x, p2d[4].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[1].x, p2d[1].y, p2d[5].x, p2d[5].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[2].x, p2d[2].y, p2d[6].x, p2d[6].y, r, g, b, a, 1.5);
                nat.fnDrawLine(hud, p2d[3].x, p2d[3].y, p2d[7].x, p2d[7].y, r, g, b, a, 1.5);
            }
        } else {
            // 2D Eck-Kästchen Box
            const cLen = Math.max(4.0, boxW * 0.25);
            nat.fnDrawLine(hud, leftX, topY, leftX + cLen, topY, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX, topY, leftX, topY + cLen, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX + boxW, topY, leftX + boxW - cLen, topY, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX + boxW, topY, leftX + boxW, topY + cLen, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX, topY + boxH, leftX + cLen, topY + boxH, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX, topY + boxH, leftX, topY + boxH - cLen, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX + boxW, topY + boxH, leftX + boxW - cLen, topY + boxH, r, g, b, a, 2.0);
            nat.fnDrawLine(hud, leftX + boxW, topY + boxH, leftX + boxW, topY + boxH - cLen, r, g, b, a, 2.0);
        }

        // 4. HP & Weste Fortschrittsbalken direkt unter den Füßen
        const barW = Math.max(34.0, Math.min(boxW, 85.0));
        const barH = 4.0;
        const barX = feetProj.x - barW / 2.0;
        const hpY = topY + boxH + 4.0;
        const armY = hpY + barH + 2.5;

        const curHp = (p.hp !== undefined) ? p.hp : 100.0;
        const maxHp = (p.maxHp !== undefined && p.maxHp > 0) ? p.maxHp : 100.0;
        const curArm = (p.armour !== undefined) ? p.armour : 0.0;
        const maxArm = (p.maxArmour !== undefined && p.maxArmour > 0) ? p.maxArmour : 100.0;

        const hpRatio = Math.max(0.0, Math.min(1.0, curHp / maxHp));
        const armRatio = Math.max(0.0, Math.min(1.0, curArm / maxArm));

        nat.fnDrawRect(hud, 0.03, 0.05, 0.10, 0.9, barX - 1, hpY - 1, barW + 2, barH + 2);
        if (hpRatio > 0.0) {
            const hpR = hpRatio > 0.5 ? 0.08 : (hpRatio > 0.2 ? 0.96 : 0.94);
            const hpG = hpRatio > 0.5 ? 0.82 : (hpRatio > 0.2 ? 0.65 : 0.24);
            const hpB = hpRatio > 0.5 ? 0.38 : 0.15;
            nat.fnDrawRect(hud, hpR, hpG, hpB, 1.0, barX, hpY, Math.max(2.0, barW * hpRatio), barH);
        }

        nat.fnDrawRect(hud, 0.03, 0.05, 0.10, 0.9, barX - 1, armY - 1, barW + 2, barH + 2);
        if (armRatio > 0.0) {
            nat.fnDrawRect(hud, 0.0, 0.85, 1.0, 1.0, barX, armY, Math.max(2.0, barW * armRatio), barH);
        }

        // 5. HP/ARM Werte
        const statStr = `HP:${Math.round(curHp)}  ARM:${Math.round(curArm)}`;
        const statFStr = getDeviceDynamicFString(nat, statStr);
        if (statFStr) {
            nat.fnDrawText(hud, statFStr, 0.35, 0.85, 1.0, 1.0, barX, armY + barH + 3.0, ptr(0), 0.85, 0);
        }

        // 6. Name, Familien-Tag & Distanz Text
        const pName = (p.name && p.name !== 'Player' && p.name !== 'Actor') ? p.name : `Spieler #${p.id || '?'}`;
        const famPart = (p.family && p.family.length > 0) ? ` [${p.family}]` : "";
        const labelStr = `${pName}${famPart} (${p.distMeters}m)`;
        const labelFStr = getDeviceDynamicFString(nat, labelStr);
        if (labelFStr) {
            nat.fnDrawText(hud, labelFStr, 0.98, 0.80, 0.15, 1.0, barX - 10, armY + barH + 16.0, ptr(0), 0.9, 0);
        }
    }
}

/**
 * Rendert das 2D Tactical Player Radar
 */
function renderDeviceRadar(hud, nat, sizeX, sizeY) {
    if (!instances.deviceRadarActive) return;

    const radSkin = instances.deviceRadarSkin || "cyber";
    const radSize = (radSkin === "minimal") ? 180.0 : 200.0;
    const radMargin = 20.0;
    const radX = sizeX - radSize - radMargin;
    const radY = radMargin + 20.0;
    const cx = radX + radSize / 2.0;
    const cy = radY + radSize / 2.0;
    const radius = (radSize / 2.0) - 8.0;
    const maxRange = instances.deviceRadarRange || 150.0;

    nat.fnDrawRect(hud, 0.02, 0.04, 0.09, 0.85, radX, radY, radSize, radSize);
    nat.fnDrawLine(hud, radX, radY, radX + radSize, radY, 0.0, 0.94, 1.0, 0.6, 1.5);
    nat.fnDrawLine(hud, radX + radSize, radY, radX + radSize, radY + radSize, 0.0, 0.94, 1.0, 0.6, 1.5);
    nat.fnDrawLine(hud, radX + radSize, radY + radSize, radX, radY + radSize, 0.0, 0.94, 1.0, 0.6, 1.5);
    nat.fnDrawLine(hud, radX, radY + radSize, radX, radY, 0.0, 0.94, 1.0, 0.6, 1.5);

    nat.fnDrawLine(hud, cx - radius, cy, cx + radius, cy, 0.0, 0.94, 1.0, 0.4, 1.0);
    nat.fnDrawLine(hud, cx, cy - radius, cx, cy + radius, 0.0, 0.94, 1.0, 0.4, 1.0);

    nat.fnDrawLine(hud, cx, cy - 6, cx - 4, cy + 5, 0.98, 0.80, 0.15, 1.0, 2.0);
    nat.fnDrawLine(hud, cx, cy - 6, cx + 4, cy + 5, 0.98, 0.80, 0.15, 1.0, 2.0);
    nat.fnDrawLine(hud, cx - 4, cy + 5, cx + 4, cy + 5, 0.98, 0.80, 0.15, 1.0, 2.0);

    const players = instances.cachedPlayers;
    const myLoc = instances.cachedMyLoc;
    const myYawRad = (instances.cachedMyYaw * Math.PI) / 180.0;

    if (players && players.length > 0) {
        for (let i = 0; i < players.length; i++) {
            const p = players[i];
            if (p.isSelf || !p.location || p.distMeters < 0.8) continue;

            const dx = (p.location.x - myLoc.x) / 100.0;
            const dy = (p.location.y - myLoc.y) / 100.0;

            const rx = dx * Math.cos(-myYawRad) - dy * Math.sin(-myYawRad);
            const ry = dx * Math.sin(-myYawRad) + dy * Math.cos(-myYawRad);

            const px = cx + (rx / maxRange) * radius;
            const py = cy - (ry / maxRange) * radius;

            const distCenter = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
            if (distCenter <= radius) {
                let blipR = p.isFriendly ? 0.06 : (p.distMeters < 30 ? 0.95 : 0.22);
                let blipG = p.isFriendly ? 0.85 : (p.distMeters < 30 ? 0.26 : 0.65);
                let blipB = p.isFriendly ? 0.55 : (p.distMeters < 30 ? 0.26 : 0.98);

                let blipSize = p.isCrouched ? 4.0 : 6.0;
                let halfB = blipSize / 2.0;

                if (p.isCrouched) {
                    // Geduckter Spieler: Kompaktes 4.0px Radar-Kästchen (Gelb-Orange)
                    nat.fnDrawRect(hud, 1.0, 0.8, 0.2, 0.95, px - halfB, py - halfB, blipSize, blipSize);
                } else {
                    nat.fnDrawRect(hud, blipR, blipG, blipB, 0.95, px - halfB, py - halfB, blipSize, blipSize);
                }
            }
        }
    }
}

/**
 * Multi-Strategy Module Waiter (Immediate + Interval + dlopen Interceptor)
 */
function waitForModule(moduleName, callback) {
    console.log(`[ReShift] Suche Modul "${moduleName}"...`);

    const mod = Process.findModuleByName(moduleName);
    if (mod && mod.base && !mod.base.isNull()) {
        console.log(`[★] ${moduleName} bereits im Speicher bei: ${mod.base}`);
        callback(mod.base);
        return;
    }

    console.log(`[!] ${moduleName} noch nicht geladen. Warte auf dlopen / App-Start...`);

    let found = false;
    const checkInterval = setInterval(() => {
        try {
            const m = Process.findModuleByName(moduleName);
            if (m && m.base && !m.base.isNull()) {
                if (!found) {
                    found = true;
                    clearInterval(checkInterval);
                    console.log(`[★] ${moduleName} erkannt (Polling) bei: ${m.base}`);
                    callback(m.base);
                }
            }
        } catch (_) { }
    }, 100);

    const dlopenExport = Module.findExportByName(null, "android_dlopen_ext") || Module.findExportByName(null, "dlopen");
    if (dlopenExport) {
        try {
            Interceptor.attach(dlopenExport, {
                onEnter: function (args) {
                    if (args[0] && !args[0].isNull()) {
                        try {
                            this.path = args[0].readUtf8String();
                        } catch (_) { }
                    }
                },
                onLeave: function (retval) {
                    if (this.path && this.path.indexOf(moduleName) !== -1 && !found) {
                        try {
                            const m = Process.findModuleByName(moduleName);
                            if (m && m.base && !m.base.isNull()) {
                                found = true;
                                clearInterval(checkInterval);
                                console.log(`[★] ${moduleName} geladen via dlopen bei: ${m.base}`);
                                callback(m.base);
                            }
                        } catch (_) { }
                    }
                }
            });
        } catch (_) { }
    }
}

function initEspHooks(base) {
    if (!base || base.isNull()) return;

    libUE4.base = base;
    libUE4.ready = true;
    console.log(`[★] Initialisiere 1:1 UE4 Native UCanvas Overlay & Aim Assist Engine an Base: ${base}`);

    // MainLuaState Hooks zur automatischen Erfassung der Lua-State Instanz
    const mlsOffsets = CONFIG.offsets.MainLuaState;
    if (mlsOffsets) {
        const hookMLS = (offset) => {
            if (offset) {
                try {
                    Interceptor.attach(base.add(offset), {
                        onEnter: function (args) {
                            if (args[0] && isValidPtr(args[0])) {
                                instances.mainLuaState = args[0];
                            }
                        }
                    });
                } catch (_) { }
            }
        };

        hookMLS(mlsOffsets.sendConsoleCommand);
        hookMLS(mlsOffsets.CallRemote);
        hookMLS(mlsOffsets.SendBrowserExecute);
        hookMLS(mlsOffsets.setHealth);
        hookMLS(0x7DB9444); // SendClientMessage
        hookMLS(0x7DB954C); // EventRegister
        hookMLS(0x7DACC48); // setLogged
        hookMLS(0x7DB3190); // ChangeSettings
    }

    // CEFWidget Interceptor to capture cefWidget pointer and log all UI events
    try {
        const cefEvtAddr = base.add(0x7D848B8);
        Interceptor.attach(cefEvtAddr, {
            onEnter: function (args) {
                if (args[0] && isValidPtr(args[0])) {
                    instances.cefWidget = args[0];
                }
                try {
                    const name = readFString(args[1]);
                    const data = readFString(args[2]);
                    if (name) {
                        console.log(`[CEF Event Log] Name: "${name}", Data: "${data}"`);
                    }
                } catch (_) { }
            }
        });
    } catch (_) { }

    // Native Hook an AHUD::DrawHUD (Direkter 1:1 UE4 Native Render Pass)
    try {
        const drawHudAddr = base.add(CONFIG.offsets.HUD.DrawHUD);
        Interceptor.attach(drawHudAddr, {
            onEnter: function (args) {
                const hud = args[0];
                if (!hud || hud.isNull()) return;

                const nowTs = Date.now();
                if (nowTs - instances.lastHudRenderTime < 3) return; // Deduplizierungs-Guard
                instances.lastHudRenderTime = nowTs;

                // Capture BaseCharacter & CEFWidget pointers from local player pawn
                try {
                    const localPawn = hud.add(0x2A8).readPointer();
                    if (localPawn && isValidPtr(localPawn)) {
                        const cefPtr = localPawn.add(0x4B8).readPointer();
                        if (cefPtr && isValidPtr(cefPtr)) {
                            instances.cefWidget = cefPtr;
                        }
                    }
                } catch (_) { }

                // Sync Konfiguration aus /data/local/tmp/reshift_config.json
                syncConfigFromFile();

                // Apply Continuous Vehicle Torque Multiplier (0x2F0)
                if (instances.mainLuaState && instances.vehicleTorqueMultiplier > 1.0) {
                    applyVehicleTorque(instances.mainLuaState);
                }

                const canvas = hud.add(0x270).readPointer();
                if (!canvas || canvas.isNull()) return;

                const sizeX = canvas.add(0x40).readInt();
                const sizeY = canvas.add(0x44).readInt();

                if (sizeX > 0 && sizeY > 0) {
                    const nat = initDeviceHudNative();
                    if (!nat) return;

                    if (instances.mainLuaState && (nowTs - instances.lastPlayerScanTime >= 16)) {
                        instances.lastPlayerScanTime = nowTs;
                        try {
                            const scanRes = scanNearbyPlayersInternal();
                            if (scanRes && scanRes.success && scanRes.players) {
                                instances.cachedPlayers = scanRes.players;
                                instances.cachedMyLoc = scanRes.myLocation || { x: 0, y: 0, z: 0 };
                                instances.cachedMyYaw = (scanRes.myHeading && scanRes.myHeading.yaw) ? scanRes.myHeading.yaw : 0.0;
                            }
                        } catch (_) { }
                    }

                    // 1:1 Perform Native Smooth Aim Lock aus ue4_lua_bridge.js
                    if (instances.aimActive && instances.mainLuaState) {
                        performSmoothAimLock(instances.mainLuaState, {
                            active: true,
                            smoothLock: true,
                            aimAssistMode: instances.aimAssistMode || "new",
                            senseRadius: instances.senseRadius || 120.0,
                            senseDistance: 12000.0,
                            sensitivityYaw: 2.0,
                            sensitivityPitch: 2.0,
                            aimCorrectionZ: instances.aimCorrectionZ || 70.0,
                            smoothFactor: instances.smoothFactor || 0.85,
                            verticalAimEnable: true
                        });
                    }

                    // 2. Render FOV Circle & Lock Crosshair
                    renderFovCircle(hud, nat, sizeX, sizeY);

                    // 3. Render 3D ESP Bounding Box / Corner Overlay
                    renderDeviceEsp(hud, sizeX, sizeY);

                    // 4. Render Tactical Radar Minimap (falls aktiviert)
                    renderDeviceRadar(hud, nat, sizeX, sizeY);
                }
            }
        });
        console.log(`[★] AHUD::DrawHUD erfolgreich gesetzt bei ${drawHudAddr}`);
        console.log(`[✓] ReShift Grand Mobile 1:1 Native UE4 UCanvas 3D ESP, Engine Tuning & PhotomodeFly AKTIV!`);
    } catch (e) {
        console.log(`[!] DrawHUD Fehler: ${e}`);
    }
}

// Frida RPC Exports zur Steuerung
rpc.exports = {
    toggleEsp: function () {
        instances.deviceEspActive = !instances.deviceEspActive;
        console.log(`[RPC] ESP Overlay: ${instances.deviceEspActive ? 'AKTIV' : 'DEAKTIVIERT'}`);
        return { success: true, active: instances.deviceEspActive };
    },
    toggleAim: function () {
        instances.aimActive = !instances.aimActive;
        console.log(`[RPC] Zielhilfe: ${instances.aimActive ? 'AKTIV' : 'DEAKTIVIERT'}`);
        return { success: true, active: instances.aimActive };
    },
    setEspStyle: function (style) {
        instances.deviceEspStyle = (style === "2d") ? "2d" : "3d";
        console.log(`[RPC] ESP Stil: ${instances.deviceEspStyle.toUpperCase()}`);
        return { success: true, style: instances.deviceEspStyle };
    },
    setSnaplines: function (mode) {
        instances.deviceLineMode = mode;
        console.log(`[RPC] Snaplines: ${instances.deviceLineMode.toUpperCase()}`);
        return { success: true, mode: instances.deviceLineMode };
    },
    toggleRadar: function () {
        instances.deviceRadarActive = !instances.deviceRadarActive;
        console.log(`[RPC] Radar: ${instances.deviceRadarActive ? 'AKTIV' : 'DEAKTIVIERT'}`);
        return { success: true, active: instances.deviceRadarActive };
    },
    setVehicleTorque: function (multiplier) {
        instances.vehicleTorqueMultiplier = Math.max(1.0, Math.min(5.0, Number(multiplier) || 1.0));
        console.log(`[RPC] Motor Drehmoment: ${instances.vehicleTorqueMultiplier}x`);
        return { success: true, multiplier: instances.vehicleTorqueMultiplier };
    },
    togglePhotomodeFly: function (state) {
        togglePhotomodeFly(state);
        return { success: true, active: instances.photomodeFlyActive };
    },
    getStatus: function () {
        return {
            espActive: instances.deviceEspActive,
            espStyle: instances.deviceEspStyle,
            lineMode: instances.deviceLineMode,
            radarActive: instances.deviceRadarActive,
            friendlyProtect: instances.friendlyProtect,
            aimActive: instances.aimActive,
            aimAssistMode: instances.aimAssistMode,
            senseRadius: instances.senseRadius,
            aimCorrectionZ: instances.aimCorrectionZ,
            smoothFactor: instances.smoothFactor,
            fovCircleActive: instances.fovCircleActive,
            fovCenterDotActive: instances.fovCenterDotActive,
            fovSkin: instances.fovSkin,
            fovOffsetX: instances.fovOffsetX,
            vehicleTorqueMultiplier: instances.vehicleTorqueMultiplier,
            photomodeFlyActive: instances.photomodeFlyActive
        };
    }
};

// Script Entry Point
console.log(`[ReShift] === Grand_Standalone.js geladen (Target: ${CONFIG.moduleName}) ===`);
waitForModule(CONFIG.moduleName, function(base) {
    initEspHooks(base);
});
