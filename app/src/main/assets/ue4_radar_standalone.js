/**
 * ============================================================================
 * Standalone UE4 On-Device Player Radar (Frida Script)
 * Target: libUE4.so (ARM64)
 * Feature: Exklusive 2D Tactical Player Radar Visualisierung auf dem Display
 * ============================================================================
 */

const CONFIG = {
    moduleName: "libUE4.so",
    offsets: {
        HUD: {
            DrawHUD: 0x0A387508,
            DrawLine: 0x0A3894C0,
            DrawRect: 0x0A389410
        }
    }
};

let libUE4 = {
    base: null,
    ready: false
};

let instances = {
    mainLuaState: null,
    deviceRadarActive: true,  // Radar standardmäßig aktivieren
    deviceRadarRange: 150.0,  // Max. Distanz im Radar (Meter)
    deviceRadarSkin: "cyber", // Optionale Skins: "classic", "cyber", "circle", "minimal", "military"
    deviceHudNative: null,
    cachedPlayers: [],
    cachedMyLoc: { x: 0, y: 0, z: 0 },
    cachedMyYaw: 0.0,
    lastPlayerScanTime: 0,
    lastHudRenderTime: 0
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

/**
 * Initialisiert die nativen UE4 C++ Renderer-Funktionen aus AHUD
 */
function initDeviceHudNative() {
    if (instances.deviceHudNative) return instances.deviceHudNative;
    if (!libUE4.base) return null;

    try {
        const base = libUE4.base;
        const lineAddr = base.add(CONFIG.offsets.HUD.DrawLine);
        const rectAddr = base.add(CONFIG.offsets.HUD.DrawRect);

        // Native NativeFunction Thunks (ARM64 AAPCS64 Calling Convention)
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

        instances.deviceHudNative = { fnDrawLine, fnDrawRect };
        console.log("[Radar] Native AHUD Render-Funktionen gebunden.");
        return instances.deviceHudNative;
    } catch (e) {
        console.log("[!] Error bind HUD functions: " + e);
        return null;
    }
}

/**
 * Rendert das 2D Tactical Player Radar direkt in AHUD::DrawHUD (Native Pass)
 */
function renderDeviceRadar(hud, sizeX, sizeY) {
    if (!instances.deviceRadarActive) return;

    const nat = initDeviceHudNative();
    if (!nat) return;

    const radSkin = instances.deviceRadarSkin || "cyber";
    const radSize = (radSkin === "minimal") ? 180.0 : 200.0;
    const radMargin = 20.0;
    const radX = sizeX - radSize - radMargin;
    const radY = radMargin + 20.0;
    const cx = radX + radSize / 2.0;
    const cy = radY + radSize / 2.0;
    const radius = (radSize / 2.0) - 8.0;
    const maxRange = instances.deviceRadarRange || 150.0;

    // 1. Radar Hintergrunde & Rahmen zeichnen
    nat.fnDrawRect(hud, 0.02, 0.04, 0.09, 0.85, radX, radY, radSize, radSize);

    // Äußerer Rahmen
    nat.fnDrawLine(hud, radX, radY, radX + radSize, radY, 0.0, 0.94, 1.0, 0.6, 1.5);
    nat.fnDrawLine(hud, radX + radSize, radY, radX + radSize, radY + radSize, 0.0, 0.94, 1.0, 0.6, 1.5);
    nat.fnDrawLine(hud, radX + radSize, radY + radSize, radX, radY + radSize, 0.0, 0.94, 1.0, 0.6, 1.5);
    nat.fnDrawLine(hud, radX, radY + radSize, radX, radY, 0.0, 0.94, 1.0, 0.6, 1.5);

    // Fadenkreuz
    nat.fnDrawLine(hud, cx - radius, cy, cx + radius, cy, 0.0, 0.94, 1.0, 0.4, 1.0);
    nat.fnDrawLine(hud, cx, cy - radius, cx, cy + radius, 0.0, 0.94, 1.0, 0.4, 1.0);

    // Eigener Positionsanzeiger (Gelbes Dreieck)
    nat.fnDrawLine(hud, cx, cy - 6, cx - 4, cy + 5, 0.98, 0.80, 0.15, 1.0, 2.0);
    nat.fnDrawLine(hud, cx, cy - 6, cx + 4, cy + 5, 0.98, 0.80, 0.15, 1.0, 2.0);
    nat.fnDrawLine(hud, cx - 4, cy + 5, cx + 4, cy + 5, 0.98, 0.80, 0.15, 1.0, 2.0);

    // 2. Spieler-Blips rendern
    const players = instances.cachedPlayers;
    const myLoc = instances.cachedMyLoc;
    const myYawRad = (instances.cachedMyYaw * Math.PI) / 180.0;

    if (players && players.length > 0) {
        for (let i = 0; i < players.length; i++) {
            const p = players[i];
            if (p.isSelf || !p.location) continue;

            const dx = (p.location.x - myLoc.x) / 100.0;
            const dy = (p.location.y - myLoc.y) / 100.0;

            // Koordinaten bezogen auf Blickrichtung (Yaw) drehen
            const rx = dx * Math.cos(-myYawRad) - dy * Math.sin(-myYawRad);
            const ry = dx * Math.sin(-myYawRad) + dy * Math.cos(-myYawRad);

            const px = cx + (rx / maxRange) * radius;
            const py = cy - (ry / maxRange) * radius;

            const distCenter = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
            if (distCenter <= radius) {
                let blipR = 0.95, blipG = 0.26, blipB = 0.26; // Rot (< 30m)

                if (p.distMeters && p.distMeters > 30) {
                    blipR = 0.22; blipG = 0.65; blipB = 0.98; // Blau (> 30m)
                }

                // Quad-Blip zeichnen
                nat.fnDrawRect(hud, blipR, blipG, blipB, 0.95, px - 3, py - 3, 6, 6);
            }
        }
    }
}

/**
 * Liest Spieler-Standorte & Blickrichtung aus der Engine
 */
function updatePlayerLocations() {
    if (!instances.mainLuaState) return;
    try {
        const mainLua = instances.mainLuaState;
        const gameMode = mainLua.add(0x2F0).readPointer();
        if (!isValidPtr(gameMode)) return;

        let myChar = gameMode.add(0xA80).readPointer();
        if (!isValidPtr(myChar)) myChar = gameMode.add(0x9C8).readPointer();
        if (!isValidPtr(myChar)) return;

        const myRoot = myChar.add(0x130).readPointer();
        if (!isValidPtr(myRoot)) return;

        let mx = myRoot.add(0x1D0).readFloat();
        let my = myRoot.add(0x1D4).readFloat();
        let mz = myRoot.add(0x1D8).readFloat();

        let yawRad = 0;
        let qx = myRoot.add(0x1C0).readFloat();
        let qy = myRoot.add(0x1C4).readFloat();
        let qz = myRoot.add(0x1C8).readFloat();
        let qw = myRoot.add(0x1CC).readFloat();
        if (!isNaN(qw) && (qx !== 0 || qy !== 0 || qz !== 0 || qw !== 0)) {
            yawRad = Math.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz));
        }

        instances.cachedMyLoc = { x: mx, y: my, z: mz };
        instances.cachedMyYaw = yawRad * (180.0 / Math.PI);
    } catch (_) {}
}

/**
 * Initialisiert Frida Interceptor Hooks für libUE4.so
 */
function initRadarHooks() {
    const base = Module.findBaseAddress(CONFIG.moduleName);
    if (!base) {
        console.log(`[!] Warten auf Modul ${CONFIG.moduleName}...`);
        setTimeout(initRadarHooks, 1000);
        return;
    }

    libUE4.base = base;
    libUE4.ready = true;
    console.log(`[★] ${CONFIG.moduleName} geladen bei: ${base}`);

    // Native Hook an AHUD::DrawHUD
    try {
        const drawHudAddr = base.add(CONFIG.offsets.HUD.DrawHUD);
        Interceptor.attach(drawHudAddr, {
            onEnter: function (args) {
                const hud = args[0];
                if (!hud || hud.isNull()) return;

                const nowTs = Date.now();
                if (nowTs - instances.lastHudRenderTime < 3) return; // Deduplizierungs-Guard
                instances.lastHudRenderTime = nowTs;

                const canvas = hud.add(0x270).readPointer();
                if (!canvas || canvas.isNull()) return;

                const sizeX = canvas.add(0x40).readInt();
                const sizeY = canvas.add(0x44).readInt();

                if (sizeX > 0 && sizeY > 0) {
                    updatePlayerLocations();
                    renderDeviceRadar(hud, sizeX, sizeY);
                }
            }
        });
        console.log(`[★] AHUD::DrawHUD Hook erfolgreich gesetzt bei ${drawHudAddr}`);
    } catch (e) {
        console.log(`[!] DrawHUD Hook Fehler: ${e}`);
    }
}

// Script-Start
setImmediate(initRadarHooks);
