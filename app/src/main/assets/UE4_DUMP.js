/**
 * @name Universal UE4 / UE5 Dumper & Inspector
 * @description High-speed in-memory SDK and metadata dumper for Unreal Engine 4 & 5 mobile games (arm64, arm, x64).
 * Extracts GUObjectArray, GNames, UClasses, UScriptStructs, UFunctions (RVA offsets),
 * UProperties/FProperties, and UEnums with streaming RPC and device JSON dump.
 * @author ReShift Security Research
 * @arch arm64, arm, x64
 * @category unreal
 * @tags ue4, ue5, unreal, dumper, inspector, rpc, native
 */

'use strict';

console.log("[***] ReShift Universal UE4 / UE5 Dumper loaded [***]");

// ============================================================================
// 1. CONFIGURATION & GLOBAL STATE
// ============================================================================
var moduleBase = null;
var moduleSize = 0;
var targetModuleName = null;
var appId = (typeof TARGET_PACKAGE !== 'undefined' && TARGET_PACKAGE) ? TARGET_PACKAGE : 
            ((typeof APP_ID !== 'undefined' && APP_ID) ? APP_ID : "");

if (!appId || appId === "unknown_ue4_app") {
    try {
        const f = new File("/proc/self/cmdline", "r");
        const s = f.readLine();
        f.close();
        if (s) {
            appId = s.split('\x00')[0].trim();
        }
    } catch(e) {}
}

var GUObjectArray = null;
var GName = null;
var known_GUObjectArray_Offset = null;
var known_GName_Offset = null;

var nameIds = [];
var dumpedClassNames = new Set();
var platform = Process.platform;
var arch = Process.arch;
var isBeforeUE425 = false;
var isActorDump = false;

var processInternal_offset = null;
var processEvent_offset = null;
var doHookProcessEvent = false;
var processEventFilterOutRegex = null;

var O_RDWR = 0x0002;
var O_CREAT = 0x0040;
var O_TRUNC = 0x0200;
var _open = null, _close = null, _lseek = null, _read = null, _write = null;

// ============================================================================
// 2. LOGGING & DUAL-PROTOCOL IPC EMISSION
// ============================================================================
function emitStatus(status, details) {
    const msg = "[UE4_STATUS] " + status + (details ? " " + details : "");
    console.log(msg);
    try {
        send({
            type: "UE4_STATUS",
            status: String(status),
            details: String(details || "")
        });
    } catch (e) {}
}

// ============================================================================
// 3. ANDROID 16 / GSI TREBLE STABILITY & ANTI-CRASH GUARDS
// ============================================================================
(function installCrashGuards() {
    function waitForJava(cb) {
        if (typeof Java !== 'undefined' && Java.available) {
            Java.perform(cb);
        } else {
            setTimeout(function() { waitForJava(cb); }, 60);
        }
    }
    waitForJava(function() {
        try {
            // Retrieve actual package name from ActivityThread if appId is not set
            if (!appId || appId === "unknown_ue4_app") {
                try {
                    const ActivityThread = Java.use('android.app.ActivityThread');
                    const app = ActivityThread.currentApplication();
                    if (app) {
                        appId = app.getPackageName();
                    }
                } catch(e) {}
            }

            var MediaCodec = Java.use('android.media.MediaCodec');
            var IOException = Java.use('java.io.IOException');
            MediaCodec.createByCodecName.overload('java.lang.String').implementation = function(name) {
                emitStatus("ANTICRASH", "Prevented GSI MediaCodec crash: disabled hardware decoder " + name);
                throw IOException['$new']('GSI hardware MediaCodec disabled to prevent SIGSEGV in libmedia.so');
            };
            MediaCodec.createDecoderByType.overload('java.lang.String').implementation = function(type) {
                emitStatus("ANTICRASH", "Prevented GSI MediaCodec crash: disabled decoder for " + type);
                throw IOException['$new']('GSI hardware MediaCodec disabled to prevent SIGSEGV in libmedia.so');
            };
        } catch(e) {}
    });
})();

// ============================================================================
// 4. NATIVE FILE WRITER ENGINE
// ============================================================================
function findExportAnywhere(name) {
    let addr = null;
    try {
        addr = Module.findExportByName(null, name);
        if (addr) return addr;
    } catch (e) {}

    const libs = ["libc.so", "libdl.so", "libm.so", "libbase.so"];
    for (let lib of libs) {
        try {
            addr = Module.findExportByName(lib, name);
            if (addr) return addr;
        } catch (e) {}
    }
    return null;
}

function initNativeFunctions() {
    if (_open) return;
    try {
        const openPtr = findExportAnywhere("open");
        const closePtr = findExportAnywhere("close");
        const lseekPtr = findExportAnywhere("lseek");
        const readPtr = findExportAnywhere("read");
        const writePtr = findExportAnywhere("write");

        if (openPtr && closePtr && writePtr) {
            _open = new NativeFunction(openPtr, "int", ["pointer", "int", "int"]);
            _close = new NativeFunction(closePtr, "int", ["int"]);
            _write = new NativeFunction(writePtr, "int", ["int", "pointer", "int"]);
            if (lseekPtr) _lseek = new NativeFunction(lseekPtr, "int", ["int", "int", "int"]);
            if (readPtr) _read = new NativeFunction(readPtr, "int", ["int", "pointer", "int"]);
        }
    } catch (e) {
        console.log("[!] Native function init warning: " + e);
    }
}

class NativeFileWriter {
    constructor(pkgName) {
        initNativeFunctions();
        this.fd = -1;
        this.filePath = null;

        const candidates = [];
        if (pkgName) {
            candidates.push("/data/data/" + pkgName + "/cache/ue4_dump.json");
        }
        candidates.push("/data/local/tmp/ue4_dump.json");
        candidates.push("/sdcard/Download/ue4_dump.json");

        if (_open && _write) {
            for (let path of candidates) {
                try {
                    const cPath = Memory.allocUtf8String(path);
                    const fd = _open(cPath, O_RDWR | O_CREAT | O_TRUNC, 438 /* 0666 */);
                    if (fd >= 0) {
                        this.fd = fd;
                        this.filePath = path;
                        break;
                    }
                } catch (e) {}
            }
        }
    }

    write(str) {
        if (this.fd < 0 || !_write) return;
        try {
            const buf = Memory.allocUtf8String(str);
            const len = Process.pointerSize === 8 ? str.length : str.length;
            _write(this.fd, buf, len);
        } catch (e) {}
    }

    flush() {}

    close() {
        if (this.fd >= 0 && _close) {
            try {
                _close(this.fd);
            } catch (e) {}
            this.fd = -1;
        }
    }
}

// ============================================================================
// 5. UNREAL ENGINE STRUCTURAL OFFSETS & GLOBALS
// ============================================================================
var FUObjectItemPadd = 0x0;
var FUObjectItemSize = (Process.pointerSize === 8) ? 0x18 : 0x10;

var offset_FUObjectArray_TUObjectArray = (Process.pointerSize === 8) ? 0x10 : 0x08;
var offset_TUObjectArray_NumElements = (Process.pointerSize === 8) ? 0x14 : 0x0C;

var FNameStride = 0x2;
var offset_GName_FNamePool = (platform === "linux") ? 0x30 : 0xC0;
var offset_FNamePool_CurrentBlock = 0x8;
var offset_FNamePool_CurrentByteCursor = 0xC;
var offset_FNamePool_Blocks = (Process.pointerSize === 8) ? 0x10 : 0x08;

var offset_FNameEntry_Info = 0;
var FNameEntry_LenBit = 6;
var offset_FNameEntry_String = 0x2;

// Class: UObject
var offset_UObject_InternalIndex = 0xC;
var offset_UObject_ClassPrivate = 0x10;
var offset_UObject_FNameIndex = 0x18;
var offset_UObject_OuterPrivate = 0x20;

// Class: UField
var offset_UField_Next = 0x28;

// Class: UStruct
var offset_UStruct_SuperStruct = 0x40;
var offset_UStruct_Children = 0x48;
var offset_UStruct_ChildProperties = 0x50;

// Class: FField (UE4.25+)
var offset_FField_Class = 0x8;
var offset_FField_Next = 0x20;
var offset_FField_Name = 0x28;

// Class: UEnum
var offset_UENum_Names = 0x40;
var offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
var offset_UENum_Max = offset_UENum_Count + 0x4;
var enumItemSize = 0x10;

// Class: UFunction
var offset_UFunction_FunctionFlags = 0xb0;
var offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;

// Class: UProperty (FProperty in UE4.25+)
var offset_UProperty_ElementSize = 0x38;
var offset_UProperty_PropertyFlags = 0x40;
var offset_UProperty_OffsetInternal = 0x4c;
var offset_UProperty_size = 0x78;

// Specific Property Offsets
var offset_UBoolProperty_FieldSize = null;
var offset_UBoolProperty_ByteOffset = null;
var offset_UBoolProperty_ByteMask = null;
var offset_UBoolProperty_FieldMask = null;
var offset_UObjectProperty_PropertyClass = null;
var offset_UClassProperty_MetaClass = null;
var offset_UInterfaceProperty_InterfaceClass = null;
var offset_UArrayProperty_InnerProperty = null;
var offset_UMapProperty_KeyProp = null;
var offset_UMapProperty_ValueProp = null;
var offset_USetProperty_ElementProp = null;
var offset_UStructProperty_Struct = null;
var offset_UEnumProperty_EnumClass = null;

function setOffsetProperty(propSize) {
    offset_UBoolProperty_FieldMask = propSize + 0x3;
    offset_UBoolProperty_ByteMask = offset_UBoolProperty_FieldMask - 0x1;
    offset_UBoolProperty_ByteOffset = offset_UBoolProperty_ByteMask - 0x1;
    offset_UBoolProperty_FieldSize = offset_UBoolProperty_ByteOffset - 0x1;

    offset_UObjectProperty_PropertyClass = propSize;
    offset_UClassProperty_MetaClass = propSize + Process.pointerSize;
    offset_UInterfaceProperty_InterfaceClass = propSize;
    offset_UArrayProperty_InnerProperty = propSize;

    offset_UMapProperty_KeyProp = propSize;
    offset_UMapProperty_ValueProp = propSize + Process.pointerSize;

    offset_USetProperty_ElementProp = propSize;
    offset_UStructProperty_Struct = propSize;
    offset_UEnumProperty_EnumClass = propSize + Process.pointerSize;
}

function setOffset(targetAppId) {
    emitStatus("CONFIG", "Configuring UE4 schema offsets for target: " + (targetAppId || "Generic UE4/UE5"));

    // Mortal Kombat (Android) - UE 4.27.2
    if (targetAppId === "com.wb.goog.mkx") {
        FNameStride = 0x4;
        offset_FNameEntry_Info = 0x4;
        FNameEntry_LenBit = 1;
        offset_FNameEntry_String = 0x6;
        offset_UField_Next = 0x30;
        offset_UObject_OuterPrivate = 0x28;
        offset_UStruct_SuperStruct = 0x48;
        offset_UStruct_Children = 0x50;
        offset_UStruct_ChildProperties = 0x58;
        offset_UFunction_FunctionFlags = 0xb8;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        offset_UProperty_ElementSize = 0x3c;
        offset_UProperty_size = 0x80;
        offset_UENum_Names = 0x48;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x18;
        setOffsetProperty(offset_UProperty_size);
    } 
    // Real Boxing 2, Odin Valhalla Rising (UE 4.24.3 - Pre-UE4.25)
    else if (targetAppId === "com.vividgames.realboxing2" || targetAppId === "com.kakaogames.odin" || targetAppId === "com.kakaogames.twodin") {
        isBeforeUE425 = true;
        offset_UStruct_SuperStruct = 0x40;
        offset_UStruct_Children = 0x48;
        offset_UStruct_ChildProperties = 0x0;
        offset_UFunction_FunctionFlags = 0x98;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        offset_UProperty_ElementSize = 0x34;
        offset_UProperty_PropertyFlags = 0x38;
        offset_UProperty_OffsetInternal = 0x44;
        offset_UProperty_size = 0x70;
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
    } 
    // Dislyte (UE 4.24)
    else if (targetAppId === "com.farlightgames.xgame.gp.kr") {
        isBeforeUE425 = true;
        offset_UStruct_SuperStruct = 0x40;
        offset_UStruct_Children = 0x48;
        offset_UStruct_ChildProperties = 0x0;
        offset_UFunction_FunctionFlags = 0x98;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        offset_UProperty_ElementSize = 0x34;
        offset_UProperty_PropertyFlags = 0x38;
        offset_UProperty_OffsetInternal = 0x44;
        offset_UProperty_size = 0x70;
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
    } 
    // Dead by Daylight, Wuthering Waves (UE 4.26 - 4.27)
    else if (targetAppId === 'com.netease.ma100asia' || targetAppId === 'com.netease.dbdena' || targetAppId === 'com.kurogame.wutheringwaves.global') {
        FNameStride = 0x4;
        offset_FNameEntry_Info = 0x4;
        FNameEntry_LenBit = 1;
        offset_FNameEntry_String = 0x6;
        offset_UField_Next = 0x30;
        offset_UStruct_SuperStruct = 0x48;
        offset_UStruct_Children = 0x50;
        offset_UStruct_ChildProperties = 0x58;
        offset_UFunction_FunctionFlags = 0xb8;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        offset_UProperty_ElementSize = 0x3c;
        offset_UProperty_PropertyFlags = 0x40;
        offset_UProperty_OffsetInternal = 0x4c;
        offset_UProperty_size = 0x80;
        offset_UENum_Names = 0x48;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x18;
        setOffsetProperty(offset_UProperty_size);
    } 
    // Grand Mobile (RP Grand) - UE4
    else if (targetAppId === 'com.Grand.Napal' || (targetAppId && targetAppId.indexOf('Grand.Napal') !== -1) || (targetAppId && targetAppId.indexOf('Napal') !== -1)) {
        FNameStride = 0x2;
        offset_GName_FNamePool = 0x0;
        offset_FNamePool_Blocks = 0x40;
        offset_FUObjectArray_TUObjectArray = 0x10;
        offset_TUObjectArray_NumElements = 0x14;
        FUObjectItemSize = 0x18;
        known_GUObjectArray_Offset = 0xdc7f2d8;
        known_GName_Offset = 0xdc3b000;
        setOffsetProperty(offset_UProperty_size);
    }
    // Rooftops Parkour Freerun (UE 5.4.2)
    else if (targetAppId === 'com.GreenGoGames.RooftopPrakourFreerun') {
        offset_FField_Class = 0x8;
        offset_FField_Next = 0x18;
        offset_FField_Name = 0x20;
        offset_UProperty_ElementSize = 0x30;
        offset_UProperty_PropertyFlags = 0x38;
        offset_UProperty_OffsetInternal = 0x44;
        offset_UProperty_size = 0x70;
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
        offset_UArrayProperty_InnerProperty = offset_UProperty_size + Process.pointerSize;
    } 
    // Generic Modern UE4.25+ / UE5 Defaults
    else {
        setOffsetProperty(offset_UProperty_size);
    }
}

// ============================================================================
// 6. SAFE HIGH-SPEED MEMORY READERS (Zero-overhead direct try/catch)
// ============================================================================
function isReadable(addr, size) {
    if (!addr || addr.isNull() || addr.compare(ptr(0x1000)) <= 0) return false;
    try {
        addr.readU8();
        return true;
    } catch (e) {
        return false;
    }
}

function safeReadPointer(addr) {
    if (!addr || addr.isNull() || addr.compare(ptr(0x1000)) <= 0) return ptr(0);
    try {
        return addr.readPointer();
    } catch (e) {
        return ptr(0);
    }
}

function safeReadU32(addr) {
    if (!addr || addr.isNull() || addr.compare(ptr(0x1000)) <= 0) return 0;
    try {
        return addr.readU32();
    } catch (e) {
        return 0;
    }
}

function safeReadU16(addr) {
    if (!addr || addr.isNull() || addr.compare(ptr(0x1000)) <= 0) return 0;
    try {
        return addr.readU16();
    } catch (e) {
        return 0;
    }
}

function safeReadU8(addr) {
    if (!addr || addr.isNull() || addr.compare(ptr(0x1000)) <= 0) return 0;
    try {
        return addr.readU8();
    } catch (e) {
        return 0;
    }
}

function safeReadU64(addr) {
    if (!addr || addr.isNull() || addr.compare(ptr(0x1000)) <= 0) return 0;
    try {
        return addr.readU64();
    } catch (e) {
        return 0;
    }
}

// ============================================================================
// 7. UNREAL REFLECTION OBJECT MODELS
// ============================================================================
var UObject = {
    getClass: function(obj) {
        if (!obj || ptr(obj).isNull()) return ptr(0);
        return safeReadPointer(ptr(obj).add(offset_UObject_ClassPrivate));
    },
    getNameId: function(obj) {
        if (!obj || ptr(obj).isNull()) return 0;
        return safeReadU32(ptr(obj).add(offset_UObject_FNameIndex));
    },
    getName: function(obj) {
        if (this.isValid(obj)) {
            return getFNameFromID(this.getNameId(obj));
        }
        return "None";
    },
    getClassName: function(obj) {
        if (this.isValid(obj)) {
            var classPrivate = this.getClass(obj);
            return this.getName(classPrivate);
        }
        return "None";
    },
    isValid: function(obj) {
        if (!obj || ptr(obj).isNull()) return false;
        var p = ptr(obj);
        return p.compare(ptr(0x1000)) > 0 && this.getNameId(p) > 0 && this.getClass(p).compare(ptr(0x1000)) > 0;
    }
};

var UField = {
    getNext: function(field) {
        if (!field || ptr(field).isNull()) return ptr(0);
        return safeReadPointer(ptr(field).add(offset_UField_Next));
    }
};

var FField = {
    isValid: function(fField) {
        if (!fField || ptr(fField).isNull()) return false;
        var p = ptr(fField);
        if (p.compare(ptr(0x1000)) <= 0) return false;
        var classPtr = safeReadPointer(p.add(offset_FField_Class));
        return !classPtr.isNull() && classPtr.compare(ptr(0x1000)) > 0;
    },
    getName: function(fField) {
        if (!fField || ptr(fField).isNull()) return "None";
        return getFNameFromID(safeReadU32(ptr(fField).add(offset_FField_Name)));
    },
    getClassName: function(fField) {
        if (!fField || ptr(fField).isNull()) return "None";
        var classPtr = safeReadPointer(ptr(fField).add(offset_FField_Class));
        return getFNameFromID(safeReadU32(classPtr));
    },
    getNext: function(fField) {
        if (!fField || ptr(fField).isNull()) return ptr(0);
        return safeReadPointer(ptr(fField).add(offset_FField_Next));
    }
};

var UStruct = {
    getSuperClass: function(structz) {
        if (!structz || ptr(structz).isNull()) return ptr(0);
        return safeReadPointer(ptr(structz).add(offset_UStruct_SuperStruct));
    },
    getChildren: function(structz) {
        if (!structz || ptr(structz).isNull()) return ptr(0);
        return safeReadPointer(ptr(structz).add(offset_UStruct_Children));
    },
    getChildProperties: function(structz) {
        if (!structz || ptr(structz).isNull()) return ptr(0);
        return safeReadPointer(ptr(structz).add(offset_UStruct_ChildProperties));
    },
    getClassName: function(clazz) {
        return UObject.getName(clazz);
    },
    getStructClassPath: function(clazz) {
        var classname = UObject.getName(clazz);
        var superclass = this.getSuperClass(clazz);
        let depth = 0;
        while (UObject.isValid(superclass) && depth < 20) {
            classname += "." + UObject.getName(superclass);
            superclass = this.getSuperClass(superclass);
            depth++;
        }
        return classname;
    }
};

var UFunction = {
    getFunctionFlags: function(func) {
        if (!func || ptr(func).isNull()) return 0;
        return safeReadU32(ptr(func).add(offset_UFunction_FunctionFlags));
    },
    getFunc: function(func) {
        if (!func || ptr(func).isNull()) return ptr(0);
        return safeReadPointer(ptr(func).add(offset_UFunction_Func));
    }
};

var UProperty = {
    getElementSize: function(prop) {
        return safeReadU32(ptr(prop).add(offset_UProperty_ElementSize));
    },
    getPropertyFlags: function(prop) {
        return safeReadU64(ptr(prop).add(offset_UProperty_PropertyFlags));
    },
    getOffset: function(prop) {
        return safeReadU32(ptr(prop).add(offset_UProperty_OffsetInternal));
    }
};

var UBoolProperty = {
    getFieldSize: function(prop) { return safeReadU8(ptr(prop).add(offset_UBoolProperty_FieldSize)); },
    getByteOffset: function(prop) { return safeReadU8(ptr(prop).add(offset_UBoolProperty_ByteOffset)); },
    getByteMask: function(prop) { return safeReadU8(ptr(prop).add(offset_UBoolProperty_ByteMask)); },
    getFieldMask: function(prop) { return safeReadU8(ptr(prop).add(offset_UBoolProperty_FieldMask)); }
};

var UObjectProperty = {
    getPropertyClass: function(prop) {
        return safeReadPointer(ptr(prop).add(offset_UObjectProperty_PropertyClass));
    }
};

var UClassProperty = {
    getMetaClass: function(prop) {
        return safeReadPointer(ptr(prop).add(offset_UClassProperty_MetaClass));
    }
};

var UInterfaceProperty = {
    getInterfaceClass: function(prop) {
        return safeReadPointer(ptr(prop).add(offset_UInterfaceProperty_InterfaceClass));
    }
};

var UArrayProperty = {
    getInner: function(prop) {
        return safeReadPointer(ptr(prop).add(offset_UArrayProperty_InnerProperty));
    }
};

var UMapProperty = {
    getKeyProp: function(prop) { return safeReadPointer(ptr(prop).add(offset_UMapProperty_KeyProp)); },
    getValueProp: function(prop) { return safeReadPointer(ptr(prop).add(offset_UMapProperty_ValueProp)); }
};

var USetProperty = {
    getElementProp: function(prop) {
        return safeReadPointer(ptr(prop).add(offset_USetProperty_ElementProp));
    }
};

var UStructProperty = {
    getStruct: function(prop) {
        return safeReadPointer(ptr(prop).add(offset_UStructProperty_Struct));
    }
};

var UEnum = {
    getNamesArray: function(en) { return safeReadPointer(ptr(en).add(offset_UENum_Names)); },
    getCount: function(en) { return safeReadU32(ptr(en).add(offset_UENum_Count)); }
};

var UEnumProperty = {
    getEnum: function(prop) { return safeReadPointer(ptr(prop).add(offset_UEnumProperty_EnumClass)); },
    getName: function(prop) { return UObject.getName(this.getEnum(prop)); }
};

var UByteProperty = {
    getEnum: function(prop) { return safeReadPointer(ptr(prop).add(offset_UProperty_size)); },
    getName: function(prop) { return UObject.getName(this.getEnum(prop)); }
};

// ============================================================================
// 8. FNAME & GUOBJECT RESOLUTION
// ============================================================================
function getFNameFromID(index) {
    try {
        if (!GName || index < 0) return "None";

        var Block = index >> 16;
        var Offset = index & 65535;

        var chunkCandidates = [
            GName.add(offset_GName_FNamePool).add(offset_FNamePool_Blocks + Block * Process.pointerSize),
            GName.add(offset_FNamePool_Blocks + Block * Process.pointerSize),
            GName.add(Block * Process.pointerSize)
        ];

        var NamePoolChunk = null;
        for (let chunkPtr of chunkCandidates) {
            if (isReadable(chunkPtr, Process.pointerSize)) {
                let cand = safeReadPointer(chunkPtr);
                if (!cand.isNull() && cand.compare(ptr(0x10000)) > 0 && isReadable(cand, 16)) {
                    NamePoolChunk = cand;
                    break;
                }
            }
        }
        if (!NamePoolChunk) return "None";

        var FNameEntry = NamePoolChunk.add(FNameStride * Offset);
        if (!isReadable(FNameEntry, 4)) return "None";

        var FNameEntryHeader;
        if (offset_FNameEntry_Info !== 0) {
            FNameEntryHeader = safeReadU16(FNameEntry.add(offset_FNameEntry_Info));
        } else {
            FNameEntryHeader = safeReadU16(FNameEntry);
        }

        var str_addr = FNameEntry.add(offset_FNameEntry_String);
        var str_length = FNameEntryHeader >> FNameEntry_LenBit;
        var wide = FNameEntryHeader & 1;

        if (wide) return "widestr";
        if (str_length <= 0 || str_length > 256) {
            str_length = FNameEntryHeader >> 6;
        }
        if (str_length > 0 && str_length < 256 && isReadable(str_addr, str_length)) {
            return str_addr.readUtf8String(str_length) || "None";
        }
        if (isReadable(str_addr, 4)) {
            let s = str_addr.readUtf8String(64);
            if (s && s.length > 0) return s;
        }
    } catch (e) {}
    return "None";
}

function getUObjectBaseObjectFromId(index) {
    try {
        if (!GUObjectArray) return ptr(0);

        var TUObjectArray = GUObjectArray.add(offset_FUObjectArray_TUObjectArray);
        var chunkArray = safeReadPointer(TUObjectArray);
        if (chunkArray.isNull() || chunkArray.compare(ptr(0x10000)) < 0) {
            TUObjectArray = GUObjectArray;
            chunkArray = safeReadPointer(TUObjectArray);
        }
        if (chunkArray.isNull() || chunkArray.compare(ptr(0x10000)) < 0) return ptr(0);

        var chunkIndex = parseInt(index / 0x10000);
        var chunkPtr = chunkArray.add(chunkIndex * Process.pointerSize);
        var FUObjectItemChunk = safeReadPointer(chunkPtr);
        if (FUObjectItemChunk.isNull() || FUObjectItemChunk.compare(ptr(0x10000)) < 0) return ptr(0);

        var objectPtr = FUObjectItemChunk.add(FUObjectItemPadd + (index % 0x10000) * FUObjectItemSize);
        return safeReadPointer(objectPtr);
    } catch (e) {
        return ptr(0);
    }
}

// Function Flag mapping
var funcFlags = [
    { flag: 0x00000001, name: "Final" },
    { flag: 0x00000002, name: "RequiredAPI" },
    { flag: 0x00000004, name: "BlueprintAuthorityOnly" },
    { flag: 0x00000008, name: "BlueprintCosmetic" },
    { flag: 0x00000400, name: "Native" },
    { flag: 0x00000800, name: "Event" },
    { flag: 0x00002000, name: "Static" },
    { flag: 0x00020000, name: "Public" },
    { flag: 0x00040000, name: "Private" },
    { flag: 0x00080000, name: "Protected" },
    { flag: 0x00200000, name: "Exec" },
    { flag: 0x01000000, name: "Net" },
    { flag: 0x02000000, name: "NetReliable" },
    { flag: 0x04000000, name: "BlueprintCallable" },
    { flag: 0x08000000, name: "BlueprintEvent" },
    { flag: 0x10000000, name: "BlueprintPure" },
    { flag: 0x40000000, name: "Const" }
];

function resolveProp(prop) {
    if (!prop || ptr(prop).isNull()) return "void*";
    try {
        var cname = isBeforeUE425 ? UObject.getClassName(prop) : FField.getClassName(prop);
        if (cname === "ObjectProperty" || cname === "WeakObjectProperty" || cname === "LazyObjectProperty" || cname === "SoftObjectProperty") {
            var propClass = UObjectProperty.getPropertyClass(prop);
            return UObject.getName(propClass) + "*";
        } else if (cname === "ClassProperty" || cname === "SoftClassProperty") {
            var metaClass = UClassProperty.getMetaClass(prop);
            return "TSubclassOf<" + UObject.getName(metaClass) + ">";
        } else if (cname === "StructProperty") {
            var structObj = UStructProperty.getStruct(prop);
            return UObject.getName(structObj);
        } else if (cname === "ArrayProperty") {
            return "TArray<" + resolveProp(UArrayProperty.getInner(prop)) + ">";
        } else if (cname === "SetProperty") {
            return "TSet<" + resolveProp(USetProperty.getElementProp(prop)) + ">";
        } else if (cname === "MapProperty") {
            return "TMap<" + resolveProp(UMapProperty.getKeyProp(prop)) + ", " + resolveProp(UMapProperty.getValueProp(prop)) + ">";
        } else if (cname === "BoolProperty") {
            return "bool";
        } else if (cname === "ByteProperty") {
            return "uint8";
        } else if (cname === "IntProperty") {
            return "int32";
        } else if (cname === "Int8Property") {
            return "int8";
        } else if (cname === "Int16Property") {
            return "int16";
        } else if (cname === "Int64Property") {
            return "int64";
        } else if (cname === "UInt16Property") {
            return "uint16";
        } else if (cname === "UInt32Property") {
            return "uint32";
        } else if (cname === "UInt64Property") {
            return "uint64";
        } else if (cname === "FloatProperty") {
            return "float";
        } else if (cname === "DoubleProperty") {
            return "double";
        } else if (cname === "StrProperty") {
            return "FString";
        } else if (cname === "NameProperty") {
            return "FName";
        } else if (cname === "TextProperty") {
            return "FText";
        } else if (cname === "EnumProperty") {
            return UEnumProperty.getName(prop);
        }
        return cname || "void*";
    } catch (e) {
        return "void*";
    }
}

// ============================================================================
// 9. HIGH-SPEED MEMORY SCANNER & HEURISTIC ENGINE
// ============================================================================
function safeScanPromise(pattern, perm) {
    return new Promise(function(resolve) {
        const mod = Process.findModuleByName(targetModuleName);
        if (!mod) return resolve(null);

        let ranges = [];
        try {
            ranges = mod.enumerateRanges(perm || 'r-x');
        } catch (e) {}

        if (!ranges || ranges.length === 0) {
            try {
                ranges = Process.enumerateRanges(perm || 'r-x').filter(r => 
                    r.base.compare(mod.base) >= 0 && r.base.compare(mod.base.add(mod.size)) <= 0
                );
            } catch (e) {}
        }

        if (!ranges || ranges.length === 0) return resolve(null);

        let resolved = false;
        let pending = ranges.length;

        const timer = setTimeout(function() {
            if (!resolved) {
                resolved = true;
                resolve(null);
            }
        }, 4000);

        for (const r of ranges) {
            Memory.scan(r.base, r.size, pattern, {
                onMatch: function(address, size) {
                    if (!resolved) {
                        resolved = true;
                        clearTimeout(timer);
                        resolve(address);
                        return 'stop';
                    }
                },
                onError: function(reason) {
                    pending--;
                    if (pending === 0 && !resolved) {
                        resolved = true;
                        clearTimeout(timer);
                        resolve(null);
                    }
                },
                onComplete: function() {
                    pending--;
                    if (pending === 0 && !resolved) {
                        resolved = true;
                        clearTimeout(timer);
                        resolve(null);
                    }
                }
            });
        }
    });
}

function safeScanMatchesPromise(pattern, perm, maxMatches) {
    maxMatches = maxMatches || 25;
    return new Promise(function(resolve) {
        const mod = Process.findModuleByName(targetModuleName);
        if (!mod) return resolve([]);

        let ranges = [];
        try {
            ranges = mod.enumerateRanges(perm || 'r-x');
        } catch (e) {}

        if (!ranges || ranges.length === 0) {
            try {
                ranges = Process.enumerateRanges(perm || 'r-x').filter(r => 
                    r.base.compare(mod.base) >= 0 && r.base.compare(mod.base.add(mod.size)) <= 0
                );
            } catch (e) {}
        }

        if (!ranges || ranges.length === 0) return resolve([]);

        let matches = [];
        let pending = ranges.length;
        let done = false;

        const timer = setTimeout(function() {
            if (!done) {
                done = true;
                resolve(matches);
            }
        }, 5000);

        for (const r of ranges) {
            Memory.scan(r.base, r.size, pattern, {
                onMatch: function(address, size) {
                    matches.push(address);
                    if (matches.length >= maxMatches) {
                        done = true;
                        clearTimeout(timer);
                        resolve(matches);
                        return 'stop';
                    }
                },
                onError: function(reason) {
                    pending--;
                    if (pending === 0 && !done) {
                        done = true;
                        clearTimeout(timer);
                        resolve(matches);
                    }
                },
                onComplete: function() {
                    pending--;
                    if (pending === 0 && !done) {
                        done = true;
                        clearTimeout(timer);
                        resolve(matches);
                    }
                }
            });
        }
    });
}

function resolveGlobal(codeAddr) {
    if (!codeAddr || ptr(codeAddr).isNull()) return null;
    try {
        let p = ptr(codeAddr);
        for (let i = 0; i < 8; i++) {
            let ins = Instruction.parse(p.add(i * 4));
            if (ins.mnemonic === 'adrp') {
                let reg = ins.operands[0] ? ins.operands[0].value : null;
                let adrpImm = ins.operands.find(op => op.type === 'imm')?.value;
                if (adrpImm !== undefined && reg) {
                    for (let j = i + 1; j < i + 6; j++) {
                        let nextIns = Instruction.parse(p.add(j * 4));
                        if (nextIns.mnemonic === 'add') {
                            if (nextIns.operands[1] && nextIns.operands[1].value === reg) {
                                let addImm = nextIns.operands.find(op => op.type === 'imm')?.value;
                                if (addImm !== undefined) {
                                    return ptr(adrpImm).add(ptr(addImm));
                                }
                            }
                        } else if (nextIns.mnemonic === 'ldr') {
                            let memOp = nextIns.operands.find(op => op.type === 'mem');
                            if (memOp && memOp.value && memOp.value.base === reg) {
                                let disp = memOp.value.disp || 0;
                                let targetAddr = ptr(adrpImm).add(ptr(disp));
                                let deref = safeReadPointer(targetAddr);
                                if (!deref.isNull() && deref.compare(ptr(0x10000)) > 0) {
                                    return deref;
                                }
                                return targetAddr;
                            }
                        }
                    }
                }
            }
        }
    } catch (e) {}
    return null;
}

function verifyGName(candidate) {
    if (!candidate || ptr(candidate).isNull()) return false;
    try {
        let p = ptr(candidate);

        let checkPtrs = [
            p.add(offset_GName_FNamePool).add(offset_FNamePool_Blocks),
            p.add(offset_FNamePool_Blocks),
            p
        ];

        let deref = safeReadPointer(p);
        if (!deref.isNull() && deref.compare(ptr(0x10000)) > 0) {
            checkPtrs.push(deref.add(offset_GName_FNamePool).add(offset_FNamePool_Blocks));
            checkPtrs.push(deref.add(offset_FNamePool_Blocks));
            checkPtrs.push(deref);
        }

        for (let chunk0Ptr of checkPtrs) {
            if (!isReadable(chunk0Ptr, Process.pointerSize)) continue;
            let chunk0 = safeReadPointer(chunk0Ptr);
            if (chunk0.isNull() || chunk0.compare(ptr(0x10000)) < 0 || !isReadable(chunk0, 32)) continue;

            let bytes = chunk0.readByteArray(32);
            let u8 = new Uint8Array(bytes);
            let ascii = "";
            for (let b of u8) {
                if (b >= 32 && b <= 126) ascii += String.fromCharCode(b);
            }
            if (ascii.indexOf("None") !== -1 || ascii.indexOf("ByteProperty") !== -1) {
                return true;
            }
        }
    } catch (e) {
        return false;
    }
    return false;
}

function verifyGUObjectArray(cand) {
    if (!cand || ptr(cand).isNull()) return false;
    try {
        let p = ptr(cand);
        let candidates = [
            p.add(offset_FUObjectArray_TUObjectArray),
            p
        ];

        let deref = safeReadPointer(p);
        if (!deref.isNull() && deref.compare(ptr(0x10000)) > 0) {
            candidates.push(deref.add(offset_FUObjectArray_TUObjectArray));
            candidates.push(deref);
        }

        for (let tuArray of candidates) {
            if (!isReadable(tuArray, 32)) continue;

            let chunkArr = safeReadPointer(tuArray);
            if (chunkArr.isNull() || chunkArr.compare(ptr(0x10000)) < 0) continue;
            if (!isReadable(chunkArr, Process.pointerSize)) continue;

            let chunk0 = safeReadPointer(chunkArr);
            if (chunk0.isNull() || chunk0.compare(ptr(0x10000)) < 0) continue;
            if (!isReadable(chunk0, 0x100)) continue;

            let numElements = safeReadU32(tuArray.add(offset_TUObjectArray_NumElements));
            if (numElements < 50 || numElements > 3000000) continue;

            let validUObjs = 0;
            let nameMatches = 0;
            for (let i = 1; i <= 20; i++) {
                let item = chunk0.add(i * FUObjectItemSize);
                let uobj = safeReadPointer(item);
                if (!uobj.isNull() && uobj.compare(ptr(0x10000)) > 0 && isReadable(uobj, 32)) {
                    validUObjs++;
                    if (GName) {
                        try {
                            let nid = safeReadU32(uobj.add(offset_UObject_FNameIndex));
                            let name = getFNameFromID(nid);
                            if (name && name !== 'None' && name !== 'widestr' && name.length >= 3) {
                                nameMatches++;
                            }
                        } catch (e) {}
                    }
                }
            }

            if (nameMatches >= 2) return true;
            if (validUObjs >= 4 && numElements >= 50) return true;
        }
        return false;
    } catch (e) {
        return false;
    }
}

async function findGNameAndGUObjectArrayDynamically(mod) {
    emitStatus("DYNAMIC", "Scanning BSS and writable memory for GNames and GUObjectArray (handling target update)...");

    let rwRanges = [];
    try {
        const searchBase = mod.base;
        const searchEnd = mod.base.add(mod.size + 0x20000000); // 512MB search window

        rwRanges = Process.enumerateRanges('rw-').filter(function(r) {
            return r.base.compare(searchBase) >= 0 && r.base.compare(searchEnd) < 0;
        });
    } catch(e) {}

    if (!rwRanges || rwRanges.length === 0) {
        try {
            rwRanges = mod.enumerateRanges('rw-');
        } catch(e) {}
    }

    if (!rwRanges || rwRanges.length === 0) {
        emitStatus("WARN", "No writable ranges found for dynamic resolution.");
        return;
    }

    let sliceCount = 0;
    for (const range of rwRanges) {
        if (GName && GUObjectArray) break;

        const limit = range.base.add(range.size - 32);
        for (let curr = range.base; curr.compare(limit) < 0; curr = curr.add(Process.pointerSize)) {
            sliceCount++;
            if (sliceCount % 16384 === 0) {
                await new Promise(r => setTimeout(r, 0));
            }

            // 1. Resolve GUObjectArray TUObjectArray signature
            if (!GUObjectArray) {
                try {
                    let maxElements = curr.add(16).readU32();
                    let numElements = curr.add(20).readU32();
                    let maxChunks = curr.add(24).readU32();
                    let numChunks = curr.add(28).readU32();

                    if (maxElements >= 32768 && maxElements <= 16777216 &&
                        numElements > 50 && numElements <= maxElements &&
                        maxChunks > 0 && maxChunks <= 512 &&
                        numChunks > 0 && numChunks <= maxChunks) {

                        let objectsPtr = safeReadPointer(curr);
                        if (!objectsPtr.isNull() && objectsPtr.compare(ptr(0x10000)) > 0 && isReadable(objectsPtr, Process.pointerSize)) {
                            let chunk0 = safeReadPointer(objectsPtr);
                            if (!chunk0.isNull() && chunk0.compare(ptr(0x10000)) > 0 && isReadable(chunk0, 0x100)) {
                                let validItems = 0;
                                for (let k = 1; k <= 15; k++) {
                                    let itemObj = safeReadPointer(chunk0.add(k * FUObjectItemSize));
                                    if (!itemObj.isNull() && itemObj.compare(ptr(0x10000)) > 0 && isReadable(itemObj, 32)) {
                                        validItems++;
                                    }
                                }
                                if (validItems >= 3) {
                                    let cand = curr.sub(offset_FUObjectArray_TUObjectArray);
                                    if (verifyGUObjectArray(cand)) {
                                        GUObjectArray = cand;
                                    } else {
                                        GUObjectArray = curr;
                                        offset_FUObjectArray_TUObjectArray = 0;
                                    }
                                    emitStatus("FOUND_GLOBAL", `Dynamically resolved GUObjectArray at ${GUObjectArray} (Offset: ${GUObjectArray.sub(moduleBase)})`);
                                }
                            }
                        }
                    }
                } catch(e) {}
            }

            // 2. Resolve GNames FNamePool Blocks[0] signature
            if (!GName) {
                try {
                    let block0 = safeReadPointer(curr);
                    if (!block0.isNull() && block0.compare(ptr(0x10000000)) > 0 && isReadable(block0, 16)) {
                        let bytes = block0.readByteArray(16);
                        let u8 = new Uint8Array(bytes);
                        let str = "";
                        for (let b of u8) {
                            if (b >= 32 && b <= 126) str += String.fromCharCode(b);
                        }
                        if (str.indexOf("None") !== -1) {
                            let cand = curr.sub(offset_FNamePool_Blocks).sub(offset_GName_FNamePool);
                            if (verifyGName(cand)) {
                                GName = cand;
                                emitStatus("FOUND_GLOBAL", `Dynamically resolved GName at ${GName} (Offset: ${GName.sub(moduleBase)})`);
                            } else {
                                let directCand = curr.sub(offset_FNamePool_Blocks);
                                if (verifyGName(directCand)) {
                                    GName = directCand;
                                    offset_GName_FNamePool = 0;
                                    emitStatus("FOUND_GLOBAL", `Dynamically resolved GName at ${GName} (Offset: ${GName.sub(moduleBase)})`);
                                } else {
                                    GName = curr;
                                    offset_GName_FNamePool = 0;
                                    offset_FNamePool_Blocks = 0;
                                    emitStatus("FOUND_GLOBAL", `Dynamically resolved GName at ${GName} (Offset: ${GName.sub(moduleBase)})`);
                                }
                            }
                        }
                    }
                } catch(e) {}
            }
        }
    }
}

async function searchNone() {
    try {
        let noneHex = "4E 6F 6E 65 00"; // "None\0"
        let noneAddr = await safeScanPromise(noneHex, "r--");
        if (noneAddr) {
            let buf = Memory.alloc(Process.pointerSize);
            buf.writePointer(noneAddr);
            let b = buf.readByteArray(Process.pointerSize);
            let uint8 = new Uint8Array(b);
            let ptrHex = "";
            for (let i = 0; i < uint8.length; i++) {
                ptrHex += uint8[i].toString(16).padStart(2, '0') + " ";
            }
            let ref = await safeScanPromise(ptrHex.trim(), "r--");
            if (ref) return ptr(ref);
        }
    } catch (e) {}
    return null;
}

// ============================================================================
// 10. ENGINE INITIALIZER & GLOBAL RESOLUTION
// ============================================================================
async function initializeUE4Globals(modName) {
    targetModuleName = modName;
    const mod = Process.findModuleByName(modName);
    if (!mod) {
        emitStatus("ERROR", "Target module not found: " + modName);
        return false;
    }
    moduleBase = ptr(mod.base);
    moduleSize = mod.size;

    setOffset(appId);

    // 0. Instant direct resolution via verified target offsets (0ms, 0% CPU, no ANR)
    if (known_GName_Offset && !GName) {
        let cand = moduleBase.add(known_GName_Offset);
        if (verifyGName(cand)) {
            GName = cand;
            emitStatus("FOUND_GLOBAL", `Resolved GName via target offset at ${GName} (Offset: ${GName.sub(moduleBase)})`);
        }
    }

    if (known_GUObjectArray_Offset && !GUObjectArray) {
        let cand = moduleBase.add(known_GUObjectArray_Offset);
        if (verifyGUObjectArray(cand)) {
            GUObjectArray = cand;
            emitStatus("FOUND_GLOBAL", `Resolved GUObjectArray via target offset at ${GUObjectArray} (Offset: ${GUObjectArray.sub(moduleBase)})`);
        }
    }

    if (GName && GUObjectArray) {
        return true;
    }

    emitStatus("ANALYZING", `Scanning ${modName} memory (Base: ${moduleBase}, Size: ${(moduleSize / 1024 / 1024).toFixed(1)} MB)...`);

    // 1. Dynamic In-Memory BSS Scanner for GName and GUObjectArray (works across any app update!)
    await findGNameAndGUObjectArrayDynamically(mod);

    if (GName && GUObjectArray) {
        emitStatus("READY", `Dynamic resolution successful! GName: ${GName.sub(moduleBase)}, GUObjectArray: ${GUObjectArray.sub(moduleBase)}`);
        return true;
    }

    // 2. Fallback: ARM64 code pattern scan
    if (!GName) {
        let gname_patterns = [
            "c8 00 00 37 ?? ?? ?? ?? 00 00 ?? 91",
            "42 79 74 65 50 72 6F 70 65 72 74 79 00"
        ];

        for (let p of gname_patterns) {
            let matches = await safeScanMatchesPromise(p, p.startsWith("42") ? "r--" : "r-x", 15);
            for (let addr of matches) {
                if (p.startsWith("42")) {
                    let buf = Memory.alloc(Process.pointerSize);
                    buf.writePointer(addr);
                    let b = buf.readByteArray(Process.pointerSize);
                    let uint8 = new Uint8Array(b);
                    let ptrHex = "";
                    for (let i = 0; i < uint8.length; i++) {
                        ptrHex += uint8[i].toString(16).padStart(2, '0') + " ";
                    }
                    let ref = await safeScanPromise(ptrHex.trim(), "r--");
                    if (ref) {
                        let cand = ptr(ref).sub(ptr(offset_GName_FNamePool));
                        if (verifyGName(cand)) {
                            GName = cand;
                            emitStatus("FOUND_GLOBAL", `Resolved GName via pointer ref at ${GName}`);
                            break;
                        }
                    }
                } else {
                    let cand = resolveGlobal(addr);
                    if (cand && verifyGName(cand)) {
                        GName = cand;
                        emitStatus("FOUND_GLOBAL", `Resolved GName at ${GName} (Offset: ${GName.sub(moduleBase)})`);
                        break;
                    }
                }
            }
            if (GName) break;
        }
    }

    if (!GName) {
        let noneRef = await searchNone();
        if (noneRef) {
            let cand = noneRef.sub(ptr(offset_GName_FNamePool)).sub(ptr(offset_FNamePool_Blocks));
            if (verifyGName(cand)) {
                GName = cand;
                emitStatus("FOUND_GLOBAL", `Resolved GName via None search at ${GName}`);
            }
        }
    }

    if (!GUObjectArray) {
        let guobject_patterns = [
            "20 00 80 52 ?? ?? ?? 94",
            "e1 ?? 40 b9 e2 ?? 40 b9 e3 ?? 40 39",
            "08 00 80 52 ?? ?? ?? 94"
        ];

        for (let p of guobject_patterns) {
            let matches = await safeScanMatchesPromise(p, 'r-x', 20);
            for (let addr of matches) {
                let cand = resolveGlobal(addr);
                if (cand && verifyGUObjectArray(cand)) {
                    GUObjectArray = cand;
                    emitStatus("FOUND_GLOBAL", `Resolved GUObjectArray at ${GUObjectArray} (Offset: ${GUObjectArray.sub(moduleBase)})`);
                    break;
                }
            }
            if (GUObjectArray) break;
        }
    }

    if (!GUObjectArray || !GName) {
        emitStatus("ERROR", `Failed to locate GUObjectArray (${GUObjectArray}) or GName (${GName})`);
        return false;
    }

    return true;
}

// ============================================================================
// 11. UNIVERSAL METADATA EXTRACTION & STREAMING ENGINE
// ============================================================================
class UniversalUE4Dumper {
    constructor() {
        this.outFile = new NativeFileWriter(appId);
        this.totalClasses = 0;
        this.isFirstInFile = true;
    }

    extractClassMetadata(clazz) {
        if (!UObject.isValid(clazz)) return null;

        const className = UObject.getName(clazz);
        if (className === "None" || className.indexOf("/Game/") > -1 || className.indexOf("_png") > -1 || !className) {
            return null;
        }

        const fullPath = UStruct.getStructClassPath(clazz);
        const superClass = UStruct.getSuperClass(clazz);
        const superName = UObject.isValid(superClass) ? UObject.getName(superClass) : "";

        const methods = [];
        const fields = [];

        function isPropValid(p) {
            if (!p || ptr(p).isNull() || ptr(p).compare(ptr(0x1000)) <= 0) return false;
            return isBeforeUE425 ? UObject.isValid(p) : FField.isValid(p);
        }

        // Extract Properties / Fields
        let childProp = isBeforeUE425 ? UStruct.getChildren(clazz) : UStruct.getChildProperties(clazz);
        let propDepth = 0;
        while (isPropValid(childProp) && propDepth < 1000) {
            propDepth++;
            try {
                let pName = isBeforeUE425 ? UObject.getName(childProp) : FField.getName(childProp);
                let pClass = isBeforeUE425 ? UObject.getClassName(childProp) : FField.getClassName(childProp);

                if (pClass && !pClass.startsWith("Function") && pClass !== "DelegateFunction") {
                    let pType = resolveProp(childProp);
                    let pOffset = UProperty.getOffset(childProp);
                    let pSize = UProperty.getElementSize(childProp);

                    fields.push({
                        name: pName,
                        type: pType,
                        offset: "0x" + pOffset.toString(16).toUpperCase(),
                        size: pSize
                    });
                }
            } catch (e) {}
            childProp = isBeforeUE425 ? UField.getNext(childProp) : FField.getNext(childProp);
        }

        // Extract Functions / Methods
        let childFunc = isBeforeUE425 ? UStruct.getChildren(clazz) : UStruct.getChildren(clazz);
        let funcDepth = 0;
        while (UObject.isValid(childFunc) && funcDepth < 500) {
            funcDepth++;
            try {
                let fClass = UObject.getClassName(childFunc);
                if (fClass && (fClass.startsWith("Function") || fClass === "DelegateFunction")) {
                    let fName = UObject.getName(childFunc);
                    let fFlags = UFunction.getFunctionFlags(childFunc);
                    let funcAddr = UFunction.getFunc(childFunc);

                    let flagsList = [];
                    for (let mapping of funcFlags) {
                        if ((fFlags & mapping.flag) === mapping.flag) {
                            flagsList.push(mapping.name);
                        }
                    }

                    // Extract parameters & return value
                    let params = [];
                    let returnType = "void";
                    let funcParam = isBeforeUE425 ? UStruct.getChildren(childFunc) : UStruct.getChildProperties(childFunc);
                    let pIdx = 0;
                    while (isPropValid(funcParam) && pIdx < 30) {
                        pIdx++;
                        let pFlags = UProperty.getPropertyFlags(funcParam);
                        let pTypeStr = resolveProp(funcParam);
                        let paramName = isBeforeUE425 ? UObject.getName(funcParam) : FField.getName(funcParam);

                        if ((pFlags & 0x0000000000000400) !== 0) {
                            returnType = pTypeStr;
                        } else {
                            params.push(`${pTypeStr} ${paramName}`);
                        }
                        funcParam = isBeforeUE425 ? UField.getNext(funcParam) : FField.getNext(funcParam);
                    }

                    let rvaOffset = "0x0";
                    let vaPointer = "0x0";
                    if (funcAddr && !funcAddr.isNull()) {
                        vaPointer = funcAddr.toString();
                        if (moduleBase && funcAddr.compare(moduleBase) > 0) {
                            rvaOffset = "0x" + funcAddr.sub(moduleBase).toString(16).toUpperCase();
                        }
                    }

                    methods.push({
                        name: fName,
                        returnType: returnType,
                        paramCount: params.length,
                        params: params,
                        offset: rvaOffset,
                        pointer: vaPointer,
                        flags: flagsList.join("|")
                    });
                }
            } catch (e) {}
            childFunc = UField.getNext(childFunc);
        }

        // Package / Namespace detection
        let outerObj = safeReadPointer(ptr(clazz).add(offset_UObject_OuterPrivate));
        let namespaceStr = UObject.isValid(outerObj) ? UObject.getName(outerObj) : "UnrealEngine";

        return {
            name: className,
            namespace: namespaceStr,
            fullName: fullPath,
            assembly: "libUE4.so",
            parent: superName,
            isEnum: false,
            isValueType: false,
            isInterface: false,
            size: 0,
            methods: methods,
            fields: fields,
            properties: []
        };
    }

    startDump() {
        emitStatus("EXTRACTING", "Reading ObjectCount from GUObjectArray...");

        let TUObjectArray = GUObjectArray.add(offset_FUObjectArray_TUObjectArray);
        let chunkArray = safeReadPointer(TUObjectArray);
        if (chunkArray.isNull() || chunkArray.compare(ptr(0x10000)) < 0) {
            TUObjectArray = GUObjectArray;
            chunkArray = safeReadPointer(TUObjectArray);
        }
        if (chunkArray.isNull() || chunkArray.compare(ptr(0x10000)) < 0) {
            emitStatus("ERROR", "TUObjectArray chunk array is null or invalid");
            return;
        }

        const objectCount = safeReadU32(TUObjectArray.add(offset_TUObjectArray_NumElements));
        emitStatus("PROGRESS", "Total UE4 UObjects in pool: " + objectCount);

        if (this.outFile && this.outFile.fd >= 0) {
            this.outFile.write("[\n");
        }

        const batchSize = 120;
        let currentIdx = 0;
        const self = this;

        function processChunk() {
            try {
                const chunkBatch = [];
                const endIdx = Math.min(currentIdx + batchSize, objectCount);

                for (let i = currentIdx; i < endIdx; i++) {
                    const uobj = getUObjectBaseObjectFromId(i);
                    if (!UObject.isValid(uobj)) continue;

                    const clazz = UObject.getClass(uobj);
                    if (!UObject.isValid(clazz)) continue;

                    const className = UObject.getName(clazz);
                    if (dumpedClassNames.has(className)) continue;
                    dumpedClassNames.add(className);

                    const classObj = self.extractClassMetadata(clazz);
                    if (classObj) {
                        chunkBatch.push(classObj);
                        self.totalClasses++;

                        if (self.outFile && self.outFile.fd >= 0) {
                            try {
                                const chunk = (self.isFirstInFile ? "" : ",\n") + JSON.stringify(classObj);
                                self.outFile.write(chunk);
                                self.isFirstInFile = false;
                            } catch (e) {}
                        }
                    }
                }

                if (chunkBatch.length > 0) {
                    try {
                        send({
                            type: "UE4_RPC_STREAM_BATCH",
                            classes: chunkBatch
                        });
                    } catch (e) {}
                }

                currentIdx = endIdx;
                if (currentIdx < objectCount) {
                    if (currentIdx % 1200 === 0 || currentIdx >= objectCount - batchSize) {
                        emitStatus("PROGRESS", `Parsed ${currentIdx}/${objectCount} objects (${self.totalClasses} classes)...`);
                    }
                    setTimeout(processChunk, 1);
                } else {
                    if (self.outFile && self.outFile.fd >= 0) {
                        self.outFile.write("\n]\n");
                        self.outFile.close();
                    }
                    try {
                        send({
                            type: "UE4_RPC_STREAM_COMPLETE",
                            totalClasses: self.totalClasses
                        });
                    } catch (e) {}
                    emitStatus("COMPLETED", `UE4 Dump finished! Total Classes: ${self.totalClasses} (Saved to ${self.outFile.filePath || "RPC stream"})`);
                }
            } catch (err) {
                emitStatus("ERROR", "Exception during UE4 dump: " + err);
            }
        }

        processChunk();
    }
}

// ============================================================================
// 12. RUNNER & AUTOMATIC INJECTION ORCHESTRATION
// ============================================================================
function findUE4Module() {
    const commonModules = [
        "libUE4.so", "libUnreal.so", "libShadowTrackerExtra.so",
        "libgcloud.so", "libApex.so", "libanogs.so"
    ];
    for (let name of commonModules) {
        let m = Process.findModuleByName(name);
        if (m) return m;
    }
    // Search all loaded modules
    const mods = Process.enumerateModules();
    for (let m of mods) {
        const lower = m.name.toLowerCase();
        if (lower.includes("ue4") || lower.includes("unreal")) {
            return m;
        }
    }
    return null;
}

function start() {
    let triggered = false;
    let pollTimer = null;

    const delaySec = (typeof INJECTION_DELAY_SEC !== 'undefined') ? Number(INJECTION_DELAY_SEC) : 0;
    if (delaySec > 0) {
        emitStatus("WAITING_DELAY", "Waiting " + delaySec + " seconds before starting injection...");
    }

    setTimeout(function() {
        async function launchDumper(mod) {
            if (triggered) return;
            triggered = true;
            if (pollTimer) clearInterval(pollTimer);

            emitStatus("INITIALIZING", `Found UE4 module ${mod.name} at ${mod.base}. Initializing...`);

            const ok = await initializeUE4Globals(mod.name);
            if (ok) {
                emitStatus("READY", "UE4 Globals verified. Launching streaming dumper...");
                setTimeout(function() {
                    const dumper = new UniversalUE4Dumper();
                    dumper.startDump();
                }, 500);
            }
        }

        const targetMod = findUE4Module();
        if (targetMod) {
            launchDumper(targetMod);
        } else {
            emitStatus("WAITING", "Waiting for Unreal Engine module (libUE4.so)...");

            pollTimer = setInterval(function() {
                const m = findUE4Module();
                if (m) {
                    launchDumper(m);
                }
            }, 1000);

            // Also hook dlopen to catch early module loading
            const dlopenSymbols = ["dlopen", "android_dlopen_ext", "__loader_dlopen"];
            for (let i = 0; i < dlopenSymbols.length; i++) {
                const sym = dlopenSymbols[i];
                let ptr = null;
                try {
                    ptr = Module.findExportByName("libc.so", sym) || Module.findExportByName("libdl.so", sym) || Module.findExportByName(null, sym);
                } catch(e) {}
                if (ptr) {
                    try {
                        Interceptor.attach(ptr, {
                            onEnter: function (args) {
                                try {
                                    this.path = args[0].readUtf8String();
                                } catch (e) {
                                    this.path = null;
                                }
                            },
                            onLeave: function () {
                                if (this.path && (this.path.indexOf("UE4") !== -1 || this.path.indexOf("Unreal") !== -1)) {
                                    let m = findUE4Module();
                                    if (m) launchDumper(m);
                                }
                            }
                        });
                    } catch (e) {}
                }
            }
        }
    }, delaySec * 1000);
}

// Auto-start upon script injection
setImmediate(function() {
    start();
});
