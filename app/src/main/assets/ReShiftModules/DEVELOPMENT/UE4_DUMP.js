console.log("[***] UE4_DUMP.js loaded and script started [***]");

var moduleBase;
var appId;
var GUObjectArray;
var GName;

var nameIds = [];
var enumClasses = [];
var enumClassStr = "";
var platform = Process.platform;
var arch = Process.arch;
var isBeforeUE425 = false;
var isActorDump = false;

var processInternal_offset = null;
var processEvent_offset = null;
var processEvent = null;
var doHookProcessEvent = false;
var processEventFilterOutRegex;

var O_RDONLY = 0;
var SEEK_SET = 0;
var _open = null, _close = null, _lseek = null, _read = null;

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

function bypassExit() {
    console.log("[*] Exit bypass disabled for stealth.");
}

function initNativeFunctions() {
    if (_open) return;
    console.log("[*] Initializing native functions...");
    try {
        const openPtr = findExportAnywhere("open");
        const closePtr = findExportAnywhere("close");
        const lseekPtr = findExportAnywhere("lseek");
        const readPtr = findExportAnywhere("read");

        if (openPtr && closePtr && lseekPtr && readPtr) {
            _open = new NativeFunction(openPtr, "int", ["pointer", "int", "int"]);
            _close = new NativeFunction(closePtr, "int", ["int"]);
            _lseek = new NativeFunction(lseekPtr, "int", ["int", "int", "int"]);
            _read = new NativeFunction(readPtr, "int", ["int", "pointer", "int"]);
            console.log("[+] Native functions initialized successfully.");
        } else {
            console.log("[!] Failed to find core native functions!");
        }
    } catch (e) {
        console.log("[!] Native function init error: " + e);
    }
}

// Global Exception Handler to prevent Frida from disconnecting on script errors
Process.setExceptionHandler(function (details) {
    console.log("[!!!] SCRIPT CRASH DETECTED [!!!]");
    console.log("Details: " + JSON.stringify(details, null, 2));
    console.log("Address: " + details.address);
    if (details.context) {
        console.log("PC: " + details.context.pc);
    }
    return false; // Terminate process, but we at least see why
});

// Global
var FUObjectItemPadd = 0x0;
var FUObjectItemSize = 0x18;
// SDK
var offset_FUObjectArray_TUObjectArray = 0x10;
var offset_TUObjectArray_NumElements = 0x14;
// FNamePool
var FNameStride = 0x2
var offset_GName_FNamePool = platform == "linux" ? 0x30 : 0xc0;
var offset_FNamePool_CurrentBlock = 0x8;
var offset_FNamePool_CurrentByteCursor = 0xc;
var offset_FNamePool_Blocks = 0x10;
// FNameEntry
var offset_FNameEntry_Info = 0;
var FNameEntry_LenBit = 6;
var offset_FNameEntry_String = 0x2;
//Class: UObject
var offset_UObject_InternalIndex = 0xC;
var offset_UObject_ClassPrivate = 0x10;
var offset_UObject_FNameIndex = 0x18;
var offset_UObject_OuterPrivate = 0x20;
//Class: UField
var offset_UField_Next = 0x28;
//Class: UStruct
var offset_UStruct_SuperStruct = 0x40;
var offset_UStruct_Children = 0x48;
var offset_UStruct_ChildProperties = 0x50;
//Class: FField
var offset_FField_Class = 0x8;
var offset_FField_Next = 0x20;
var offset_FField_Name = 0x28;
//Class: UEnum
var offset_UENum_Names = null;
var offset_UENum_Count = null;
var offset_UENum_Max = null;
var enumItemSize = null;
//Class: UFunction
var offset_UFunction_FunctionFlags = 0xb0;
var offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
//Class: UProperty (FProperty in UE4.25+)
var offset_UProperty_ElementSize = 0x38;
var offset_UProperty_PropertyFlags = 0x40;
var offset_UProperty_OffsetInternal = 0x4c;
var offset_UProperty_size = 0x78;
//Class: UBoolProperty
var offset_UBoolProperty_FieldSize = null;
var offset_UBoolProperty_ByteOffset = null;
var offset_UBoolProperty_ByteMask = null;
var offset_UBoolProperty_FieldMask = null;
//Class: UObjectProperty
var offset_UObjectProperty_PropertyClass = null;
//Class: UClassProperty
var offset_UClassProperty_MetaClass = null;
//Class: UInterfaceProperty
var offset_UInterfaceProperty_InterfaceClass = null;
//Class: UArrayProperty
var offset_UArrayProperty_InnerProperty = null;
//Class: UMapProperty
var offset_UMapProperty_KeyProp = null;
var offset_UMapProperty_ValueProp = null;
//Class: USetProperty
var offset_USetProperty_ElementProp = null;
//Class: UStructProperty
var offset_UStructProperty_Struct = null;
//Class: UEnumProperty
var offset_UEnumProperty_EnumClass = null;
//Class: UWorld
var offset_UWorld_PersistentLevel = 0x30;
//Class: ULevel
var offset_ULevel_AActors = 0x98;
var offset_ULevel_AActorsCount = 0xA0;

function setOffsetProperty(offset_UProperty_size) {
    offset_UBoolProperty_FieldMask = offset_UProperty_size + 0x3
    offset_UBoolProperty_ByteMask = offset_UBoolProperty_FieldMask - 0x1;
    offset_UBoolProperty_ByteOffset = offset_UBoolProperty_ByteMask - 0x1;
    offset_UBoolProperty_FieldSize = offset_UBoolProperty_ByteOffset - 0x1;

    offset_UObjectProperty_PropertyClass = offset_UProperty_size;

    offset_UClassProperty_MetaClass = offset_UProperty_size + Process.pointerSize;

    offset_UInterfaceProperty_InterfaceClass = offset_UProperty_size;

    offset_UArrayProperty_InnerProperty = offset_UProperty_size;

    offset_UMapProperty_KeyProp = offset_UProperty_size;
    offset_UMapProperty_ValueProp = offset_UProperty_size + Process.pointerSize;

    offset_USetProperty_ElementProp = offset_UProperty_size;

    offset_UStructProperty_Struct = offset_UProperty_size;

    offset_UEnumProperty_EnumClass = offset_UProperty_size + Process.pointerSize;
}

function setOffset(appId) {
    // Mortal Kombat(Android) offsets from AndUE4Dumper(https://github.com/MJx0/AndUE4Dumper)
    if (appId === "com.wb.goog.mkx") {  // UE 4.27.2
        // FNamePool
        FNameStride = 0x4
        // FNameEntry
        offset_FNameEntry_Info = 0x4;
        FNameEntry_LenBit = 1;
        offset_FNameEntry_String = 0x6;
        //Class: UField
        offset_UField_Next = 0x30;
        //Class: UObject
        offset_UObject_OuterPrivate = 0x28;
        //Class: UStruct
        offset_UStruct_SuperStruct = 0x48;
        offset_UStruct_Children = 0x50;
        offset_UStruct_ChildProperties = 0x58;
        //Class: UFunction
        offset_UFunction_FunctionFlags = 0xb8;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        //Class: UProperty
        offset_UProperty_ElementSize = 0x3c;
        offset_UProperty_size = 0x80;
        //UEnum
        offset_UENum_Names = 0x48;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x18;
        setOffsetProperty(offset_UProperty_size);
    } else if (appId === "com.vividgames.realboxing2" || appId === "com.kakaogames.odin" || appId === "com.kakaogames.twodin") {    // Real Boxing 2, Odin Vahalla Rising. UE4.24.3
        isBeforeUE425 = true;
        //Class: UStruct
        offset_UStruct_SuperStruct = 0x40;
        offset_UStruct_Children = 0x48;
        // no need before UE4.25
        offset_UStruct_ChildProperties = 0x0;
        //Class: UFunction
        offset_UFunction_FunctionFlags = 0x98;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        //Class: UProperty
        offset_UProperty_ElementSize = 0x34;
        offset_UProperty_PropertyFlags = 0x38;
        offset_UProperty_OffsetInternal = 0x44;
        offset_UProperty_size = 0x70;
        //UEnum
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
    } else if (appId === "com.farlightgames.xgame.gp.kr") { // Dislyte(Android) from AndUE4Dumper(https://github.com/MJx0/AndUE4Dumper)
        isBeforeUE425 = true;
        //Class: UStruct
        offset_UStruct_SuperStruct = 0x40;
        offset_UStruct_Children = 0x48;
        offset_UStruct_ChildProperties = 0x0;
        //Class: UFunction
        offset_UFunction_FunctionFlags = 0x98;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        //Class: UProperty
        offset_UProperty_ElementSize = 0x34;
        offset_UProperty_PropertyFlags = 0x38;
        offset_UProperty_OffsetInternal = 0x44;
        offset_UProperty_size = 0x70;
        //UEnum
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
    } else if (appId === 'com.farlightgames.farlight84.iosglobal' || appId === 'com.miraclegames.farlight84' || appId === 'com.proximabeta.mf.uamo' || appId === 'com.tencent.mf.uam' || appId === 'com.wemade.nightcrows' || appId === 'com.ncsoft.lineagew' || appId === 'com.netease.octopath.kr' || appId === 'com.xd.TLglobal' || appId === 'com.vic.bc.kr' || appId ==='com.vic.bc.jp' || appId === "com.perfect.tof.gp" || appId === "com.tof.ios" || appId === 'com.netmarble.arthdal' || appId === 'com.kakaogames.archewar') {    // farlight 84(UE 4.25.3), Arena Breakout(kr, cn)(UE 4.26.1), Night Crows, LineageW, octopath traveler(UE 4.26.2), torchlight infinite(UE 4.26.2), Black Clover Mobile(kr, jp)(UE 4.27.2), Tower of Fantasy, Arthdal Chronicles(UE 4.27.1), ArcheAge War
        //UEnum
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
    } else if (appId === 'com.netease.ma100asia' || appId === 'com.netease.dbdena' || appId === 'com.kurogame.wutheringwaves.global') { // Dead by Daylight(UE 4.27.2), Wuthering Waves(UE 4.26.2)
        // FNamePool
        FNameStride = 0x4
        // FNameEntry
        offset_FNameEntry_Info = 0x4;
        FNameEntry_LenBit = 1;
        offset_FNameEntry_String = 0x6;
        //Class: UField
        offset_UField_Next = 0x30;
        //Class: UStruct
        offset_UStruct_SuperStruct = 0x48;
        offset_UStruct_Children = 0x50;
        offset_UStruct_ChildProperties = 0x58;
        //Class: UFunction
        offset_UFunction_FunctionFlags = 0xb8;
        offset_UFunction_Func = offset_UFunction_FunctionFlags + 0x28;
        //Class: UProperty
        offset_UProperty_ElementSize = 0x3c;
        offset_UProperty_PropertyFlags = 0x40;
        offset_UProperty_OffsetInternal = 0x4c;
        offset_UProperty_size = 0x80;
        //UEnum
        offset_UENum_Names = 0x48;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x18;
        setOffsetProperty(offset_UProperty_size);
    } else if (appId === 'com.GreenGoGames.RooftopPrakourFreerun') {    // Rooftops Parkour Freerun (UE 5.4.2)
        //Class: FField
        offset_FField_Class = 0x8;
        offset_FField_Next = 0x18;
        offset_FField_Name = 0x20;
        //Class: UProperty
        offset_UProperty_ElementSize = 0x30;
        offset_UProperty_PropertyFlags = 0x38;
        offset_UProperty_OffsetInternal = 0x44;
        offset_UProperty_size = 0x70;
        //UEnum
        offset_UENum_Names = 0x40;
        offset_UENum_Count = offset_UENum_Names + Process.pointerSize;
        offset_UENum_Max = offset_UENum_Count + 0x4;
        enumItemSize = 0x10;
        setOffsetProperty(offset_UProperty_size);
        offset_UArrayProperty_InnerProperty = offset_UProperty_size + Process.pointerSize;
    } else {    // default
        setOffsetProperty(offset_UProperty_size);
    }
}

var UObject = {
    getClass: function(obj) {
        var classPrivate = ptr(obj).add(offset_UObject_ClassPrivate).readPointer();
        // console.log(`classPrivate: ${classPrivate}`);
        return classPrivate;
    },
    getNameId: function(obj) {
        // console.log(`obj: ${obj}`);
        try {
            var nameId = ptr(obj).add(offset_UObject_FNameIndex).readU32();
            // console.log(`nameId: ${nameId}`);
            return nameId;
        } catch(e) {
            return 0;
        }
    },
    getName: function(obj) {
        if (this.isValid(obj)){
            return getFNameFromID(this.getNameId(obj));
        } else {
            return "None";
        }
    },
    getClassName: function(obj) {
        if (this.isValid(obj)) {
            var classPrivate = this.getClass(obj);
            return this.getName(classPrivate);
        } else {
            return "None";
        }
    },
    isValid: function(obj) {
        var isValid = (ptr(obj) > 0 && this.getNameId(obj) > 0 && this.getClass(obj) > 0);
        // console.log(`isValid: ${isValid}`);
        return isValid;
    }
}

var UField = {
    getNext: function(field) {//UField*
        // console.log(`field: ${field}`);
        return field.add(offset_UField_Next).readPointer();
    }
};

var FField = {
    getName: function(fField) {
        return getFNameFromID(fField.add(offset_FField_Name).readU32());
    },
    getClassName: function(fField) {
        return getFNameFromID(fField.add(offset_FField_Class).readPointer().readU32());
    },
    getNext: function(fField) {//UField*
        return fField.add(offset_FField_Next).readPointer();
    }
};

var UStruct = {
    getSuperClass: function(structz) {//UStruct*
        // console.log(`UStruct.getSuperClass structz: ${structz}`);
        return structz.add(offset_UStruct_SuperStruct).readPointer()
    },
    getChildren: function(structz) {//UField*
        // console.log(`UStruct.getChildren structz: ${structz}`);
        return structz.add(offset_UStruct_Children).readPointer();
    },
    getChildProperties: function(structz) {//UField*
        // console.log(`UStruct.getChildProperties structz: ${structz}`);
        return structz.add(offset_UStruct_ChildProperties).readPointer();
    },
    getClassName: function(clazz) {
        return UObject.getName(clazz);
    },
    getClassPath: function(object) {
        var clazz = UObject.getClass(object);
        var classname = UObject.getName(clazz);

        var superclass = this.getSuperClass(clazz);
        while (UObject.isValid(superclass)) {
            classname += ".";
            classname += UObject.getName(superclass);

            superclass = this.getSuperClass(superclass);
        }

        return classname;
    },
    getStructClassPath: function(clazz) {
        var classname = UObject.getName(clazz);

        var superclass = this.getSuperClass(clazz);
        while (UObject.isValid(superclass)) {
            // console.log(`superclass: ${superclass}`)
            classname += ".";
            classname += UObject.getName(superclass);

            superclass = this.getSuperClass(superclass);
        }

        return classname;
    }
}

var UFunction = {
    getFunctionFlags: function(func) {
        return func.add(offset_UFunction_FunctionFlags).readU32();
    },
    getFunc: function(func) {
        // console.log(`func: ${func}`)
        return func.add(offset_UFunction_Func).readPointer();
    }
};

var UProperty = {
    getElementSize: function(prop) {
        return prop.add(offset_UProperty_ElementSize).readU32();
    },
    getPropertyFlags: function(prop) {
        return prop.add(offset_UProperty_PropertyFlags).readU64()
    },
    getOffset: function(prop) {
        return prop.add(offset_UProperty_OffsetInternal).readU32();
    }
};

var UBoolProperty = {
    getFieldSize: function(prop) {
        return prop.add(offset_UBoolProperty_FieldSize).readU8();
    },
    getByteOffset: function(prop) {
        // console.log(`prop: ${prop}`)
        return prop.add(offset_UBoolProperty_ByteOffset).readU8();
    },
    getByteMask: function(prop) {
        return prop.add(offset_UBoolProperty_ByteMask).readU8();
    },
    getFieldMask: function(prop) {
        return prop.add(offset_UBoolProperty_FieldMask).readU8();
    },
};

var UObjectProperty = {
    getPropertyClass: function(prop) {//class UClass*
        return prop.add(offset_UObjectProperty_PropertyClass).readPointer();
    }
};

var UClassProperty = {
    getMetaClass: function(prop) {//class UClass*
        return prop.add(offset_UClassProperty_MetaClass).readPointer();
    }
};

var UInterfaceProperty = {
    getInterfaceClass: function(prop) {//class UClass*
        return prop.add(offset_UInterfaceProperty_InterfaceClass).readPointer();
    }
};

var UArrayProperty = {
    getInner: function(prop) {//UProperty*
        return prop.add(offset_UArrayProperty_InnerProperty).readPointer();
    }
};

var UMapProperty = {
    getKeyProp: function(prop) {//UProperty*
        return prop.add(offset_UMapProperty_KeyProp).readPointer();
    },
    getValueProp: function(prop) {//UProperty*
        return prop.add(offset_UMapProperty_ValueProp).readPointer();
    }
};

var USetProperty = {
    getElementProp: function(prop) {//UProperty*
        return prop.add(offset_USetProperty_ElementProp).readPointer();
    }
};

var UStructProperty = {
    getStruct: function(prop) {//UStruct*
        return prop.add(offset_UStructProperty_Struct).readPointer();
    }
};

var UEnum = {
    getNamesArray: function(en) {
        return en.add(offset_UENum_Names).readPointer();
    },
    getCount: function(en) {
        return en.add(offset_UENum_Count).readU32();
    }
}

var UEnumProperty = {
    getEnum: function(prop) {
        return prop.add(offset_UEnumProperty_EnumClass).readPointer();
    },
    getName: function(prop) {
        return UObject.getName(this.getEnum(prop));
    }
}

var UByteProperty = {
    getEnum: function(prop) {
        return prop.add(offset_UProperty_size).readPointer();
    },
    getName: function(prop) {
        return UObject.getName(this.getEnum(prop));
    }
}

function getFNameFromID(index) {
    try {
        var Block = index >> 16;
        var Offset = index & 65535;

        if (!GName) return "None";

        var FNamePool = GName.add(offset_GName_FNamePool);
        var NamePoolChunkPtr = FNamePool.add(offset_FNamePool_Blocks + Block * Process.pointerSize);
        if (NamePoolChunkPtr.isNull()) return "None";

        var NamePoolChunk = NamePoolChunkPtr.readPointer();
        if (NamePoolChunk.isNull()) return "None";

        var FNameEntry = NamePoolChunk.add(FNameStride * Offset);

        var FNameEntryHeader;
        if (offset_FNameEntry_Info !== 0) {
            FNameEntryHeader = FNameEntry.add(offset_FNameEntry_Info).readU16();
        } else {
            FNameEntryHeader = FNameEntry.readU16();
        }

        var str_addr = FNameEntry.add(offset_FNameEntry_String);
        var str_length = FNameEntryHeader >> FNameEntry_LenBit;
        var wide = FNameEntryHeader & 1;

        if (wide) return "widestr";

        if (str_length > 0 && str_length < 250) {
            return str_addr.readUtf8String(str_length);
        }
    } catch (e) {
        // Silent catch to prevent script termination during mass dumping
    }
    return "None";
}

function getUObjectBaseObjectFromId(index) {
    try {
        if (!GUObjectArray) return ptr(0);

        var TUObjectArrayPtr = GUObjectArray.add(offset_FUObjectArray_TUObjectArray);
        if (TUObjectArrayPtr.isNull()) return ptr(0);

        var TUObjectArray = TUObjectArrayPtr.readPointer();
        if (TUObjectArray.isNull()) return ptr(0);

        var chunkIndex = parseInt(index / 0x10000);
        var chunkPtr = TUObjectArray.add(chunkIndex * Process.pointerSize);
        if (chunkPtr.isNull()) return ptr(0);

        var FUObjectItemObjects = chunkPtr.readPointer();
        if (FUObjectItemObjects.isNull()) return ptr(0);

        var objectPtr = FUObjectItemObjects.add(FUObjectItemPadd + (index % 0x10000) * FUObjectItemSize);
        if (objectPtr.isNull()) return ptr(0);

        return objectPtr.readPointer();
    } catch (e) {
        return ptr(0);
    }
}

function resolveProp(recurrce, prop) {
    if (prop) {
        if (isBeforeUE425) {
            var cname = UObject.getClassName(prop);
        } else {
            var cname = FField.getClassName(prop);
        }
        // console.log(`resolveProp cname: ${cname}`);

        if (cname === "ObjectProperty" || cname === "WeakObjectProperty"
            || cname === "LazyObjectProperty" || cname === "AssetObjectProperty"
            || cname === "SoftObjectProperty") {
            var propertyClass = UObjectProperty.getPropertyClass(prop);
            recurrce.push(...[propertyClass]);
            return UObject.getName(propertyClass) + "*";
        } else if (cname === "ClassProperty" || cname === "AssetClassProperty" ||
                   cname === "SoftClassProperty") {
            var metaClass = UClassProperty.getMetaClass(prop);
            recurrce.push(...[metaClass]);
            return "class " + UObject.getName(metaClass);
        } else if (cname === "InterfaceProperty") {
            var interfaceClass = UInterfaceProperty.getInterfaceClass(prop);
            recurrce.push(...[interfaceClass]);
            return "interface class" + UObject.getName(interfaceClass);
        } else if (cname === "StructProperty") {
            var Struct = UStructProperty.getStruct(prop);
            // console.log(`StructProperty addr: ${Struct}`);
            recurrce.push(...[Struct]);
            return UObject.getName(Struct);
        } else if (cname === "ArrayProperty") {
            return resolveProp(recurrce, UArrayProperty.getInner(prop)) + "[]";
        } else if (cname === "SetProperty") {
            return "<" + resolveProp(recurrce, USetProperty.getElementProp(prop)) + ">";
        } else if (cname === "MapProperty") {
            return "<" + resolveProp(recurrce, UMapProperty.getKeyProp(prop)) + "," +
                   resolveProp(recurrce, UMapProperty.getValueProp(prop)) + ">";
        } else if (cname === "BoolProperty") {
            return "bool";
        } else if (cname === "ByteProperty") {
            var enumObj = UByteProperty.getEnum(prop);
            if (offset_UENum_Names !== null && UObject.isValid(enumObj)) {
                var enumName = UByteProperty.getName(prop);
                if (!enumClasses.includes(enumName)) {
                    enumClasses.push(enumName);
                    enumClassStr += "enum " + enumName + " {";
                    for (var count = 0; count < UEnum.getCount(enumObj); count++) {
                        var index = UEnum.getNamesArray(enumObj).add(count * enumItemSize).readU32();
                        enumClassStr += "\n\t" + getFNameFromID(index).replace(enumName + "::", "")
                    }
                    enumClassStr += "\n};\n";
                    return "enum " + enumName;
                } else {
                    return "enum " + enumName;
                }
            } else {
                return "byte";
            }
        } else if (cname === "IntProperty") {
            return "int";
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
        } else if (cname === "DoubleProperty") {
            return "double";
        } else if (cname === "FloatProperty") {
            return "float";
        } else if (cname === "EnumProperty") {
            var enumName = UEnumProperty.getName(prop);
            if (offset_UENum_Names !== null) {
                var enumObj = UEnumProperty.getEnum(prop);
                if (!enumClasses.includes(enumName)) {
                    enumClasses.push(enumName);
                    enumClassStr += "enum " + enumName + " {";
                    for (var count = 0; count < UEnum.getCount(enumObj); count++) {
                        var index = UEnum.getNamesArray(enumObj).add(count * enumItemSize).readU32();
                        enumClassStr += "\n\t" + getFNameFromID(index).replace(enumName + "::", "")
                    }
                    enumClassStr += "\n};\n";
                    return "enum " + enumName;
                } else {
                    return "enum " + enumName;
                }
            } else {
                return "enum " + enumName;
            }
        } else if (cname === "StrProperty") {
            return "FString";
        } else if (cname === "TextProperty") {
            return "FText";
        } else if (cname === "NameProperty") {
            return "FName";
        } else if (cname === "DelegateProperty" || cname === "MulticastDelegateProperty") {
            return "delegate";
        } else {
            if (isBeforeUE425) {
                return UObject.getName(prop) + "(" + cname + ")";
            } else {
                return FField.getName(prop) + "(" + cname + ")";
            }
        }
    }
    return "NULL";
}

function writeStructChild(childprop) {
    var recurrce = [];
    var child = childprop;
    // console.log(`writeStructChild child before validation: ${child}`);
    while (UObject.isValid(child)) {
        var prop = child;
        if (isBeforeUE425) {
            var oname = UObject.getName(prop);
            var cname = UObject.getClassName(prop);
        } else {
            var oname = FField.getName(prop);
            var cname = FField.getClassName(prop);
        }
        // console.log(`writeStructChild child after validation: ${child}`);
        // console.log(`oname: ${oname}, cname: ${cname}`);
        if (cname === "ObjectProperty" || cname === "WeakObjectProperty" || cname === "LazyObjectProperty" || cname === "AssetObjectProperty" || cname === "SoftObjectProperty") {
            var propertyClass = UObjectProperty.getPropertyClass(prop);
            console.log(`\t${UObject.getName(propertyClass)}* ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
            recurrce.push(propertyClass);
        } else if (cname === "ClassProperty" || cname === "AssetClassProperty" || cname === "SoftClassProperty") {
            var metaClass = UClassProperty.getMetaClass(prop);
            console.log(`\tclass ${UObject.getName(metaClass)}* ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
            recurrce.push(metaClass);
        } else if (cname === "InterfaceProperty") {
            var interfaceClass = UInterfaceProperty.getInterfaceClass(prop);
            console.log(`\tinterface class ${UObject.getName(interfaceClass)}* ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`)
        } else if (cname === "StructProperty") {
            var Struct = UStructProperty.getStruct(prop);
            console.log(`\t${UObject.getName(Struct)} ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
            recurrce.push(Struct);
        } else if (cname === "ArrayProperty") {
            console.log(`\t${resolveProp(recurrce, UArrayProperty.getInner(prop))}[] ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "SetProperty") {
            console.log(`\t${resolveProp(recurrce, USetProperty.getElementProp(prop))} ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "MapProperty") {
            console.log(`\t<${resolveProp(recurrce, UMapProperty.getKeyProp(prop))}, ${resolveProp(recurrce, UMapProperty.getValueProp(prop))}> ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "BoolProperty") {
            console.log(`\tbool ${oname} //(ByteOffset: ${ptr(UBoolProperty.getByteOffset(prop))}, ByteMask: ${UBoolProperty.getByteMask(prop)}, FieldMask: ${UBoolProperty.getFieldMask(prop)}) [Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "ByteProperty") {
            var enumObj = UByteProperty.getEnum(prop);
            if (offset_UENum_Names !== null && UObject.isValid(enumObj)) {
                var enumName = UByteProperty.getName(prop);
                console.log(`\tenum ${enumName} ${oname} { //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
                for (var count = 0; count < UEnum.getCount(enumObj); count++) {
                    var index = UEnum.getNamesArray(enumObj).add(count * enumItemSize).readU32();
                    console.log(`\t\t${getFNameFromID(index).replace(enumName + "::", "")}`)
                }
                console.log("\t};")
            } else {
                console.log(`\tbyte ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
            }
        } else if (cname === "IntProperty") {
            console.log(`\tint ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "Int8Property") {
            console.log(`\tint8 ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "Int16Property") {
            console.log(`\tint16 ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "Int64Property") {
            console.log(`\tint64 ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "UInt16Property") {
            console.log(`\tint16 ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "UInt32Property") {
            console.log(`\tint32 ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "UInt64Property") {
            console.log(`\tint64 ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "DoubleProperty") {
            console.log(`\tdouble ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "FloatProperty") {
            console.log(`\tfloat ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "EnumProperty") {
            var enumName = UEnumProperty.getName(prop);
            if (offset_UENum_Names !== null) {
                var enumObj = UEnumProperty.getEnum(prop);
                console.log(`\tenum ${enumName} ${oname} { //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
                for (var count = 0; count < UEnum.getCount(enumObj); count++) {
                    var index = UEnum.getNamesArray(enumObj).add(count * enumItemSize).readU32();
                    console.log(`\t\t${getFNameFromID(index).replace(enumName + "::", "")}`)
                }
                console.log("\t};")
            } else {
                console.log(`\tenum ${enumName} ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
            }
        } else if (cname === "StrProperty") {
            console.log(`\tFString ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "TextProperty") {
            console.log(`\tFText ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "NameProperty") {
            console.log(`\tFName ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "DelegateProperty" || cname === "MulticastDelegateProperty" || cname === "MulticastInlineDelegateProperty" || cname === "MulticastSparseDelegateProperty") {
            console.log(`\tdelegate ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (cname === "XigPtrProperty") {
            console.log(`\tXigPtrProperty ${oname}; //[Offset: ${ptr(UProperty.getOffset(prop))}, Size: ${UProperty.getElementSize(prop)}]`);
        } else if (isBeforeUE425) {
            if (cname.startsWith("Function") || cname === "DelegateFunction") {
                var returnVal = "void";
                var params = "";
                var flags = "";

                var funcParam = UStruct.getChildren(prop);
                // console.log(`funcParam: ${funcParam});
                while (UObject.isValid(funcParam)) {
                    var PropertyFlags = UProperty.getPropertyFlags(funcParam);
                    // console.log(`PropertyFlags: ${PropertyFlags});
                    if ((PropertyFlags & 0x0000000000000400) == 0x0000000000000400) {
                        returnVal = resolveProp(recurrce, funcParam);
                    } else {
                        if ((PropertyFlags & 0x0000000000000100) == 0x0000000000000100) {
                            params += "out ";
                        }
                        /*if((PropertyFlags & 0x0000000008000000) == 0x0000000008000000){
                            params += "ref ";
                        }*/
                        if ((PropertyFlags & 0x0000000000000002) == 0x0000000000000002) {
                            params += "const ";
                        }
                        // console.log(`funcParam will go in...: ${funcParam});
                        params += resolveProp(recurrce, funcParam);
                        // params += "blahblah";
                        params += " ";
                        params += UObject.getName(funcParam);
                        params += ", ";
                    }

                    funcParam = UField.getNext(funcParam);
                }

                if (params.length > 0) {
                    params = params.slice(0, -2);
                }

                var FunctionFlags = UFunction.getFunctionFlags(prop);
                // console.log(`FunctionFlags: ${FunctionFlags});

                if ((FunctionFlags & 0x00002000) == 0x00002000) {
                    returnVal = "static " + returnVal;
                }
                /*if((FunctionFlags & 0x00000001) == 0x00000001){
                    returnVal = "final " + returnVal;
                }
                if((FunctionFlags & 0x00020000) == 0x00020000){
                    returnVal = "public " + returnVal;
                }
                if((FunctionFlags & 0x00040000) == 0x00040000){
                    returnVal = "private " + returnVal;
                }
                if((FunctionFlags & 0x00080000) == 0x00080000){
                    returnVal = "protected " + returnVal;
                }*/

                for (let mapping of funcFlags) {
                    if ((FunctionFlags & mapping.flag) == mapping.flag) {
                        flags += `${mapping.name}|`
                    }
                }

                console.log(`\t${returnVal} ${oname}(${params}); // ${UFunction.getFunc(prop).sub(moduleBase)} ${flags !== "" ? ("[" + flags.slice(0, -1) + "]") : ""} ${isActorDump ? ("// Object addr: " + child) : ""}`);
            } else if (cname === "Class" || cname === "Package") {
            } else {
                console.log(`\t${cname} ${oname}; //[Size: ${UProperty.getElementSize(prop)}]`);
            }
        } else {
            console.log(`\t${cname} ${oname}; //[Size: ${UProperty.getElementSize(prop)}]`);
        }

        if (isBeforeUE425) {
            child = UField.getNext(child);
        } else {
            child = FField.getNext(child);
        }
    }
    return recurrce;
}

var funcFlags = [
    {flag:0x00000001, name: "Final"},   // Function is final (prebindable, non-overridable function).
	{flag:0x00000004, name: "BlueprintAuthorityOly"},   // Function will only run if the object has network authority
	{flag:0x00000008, name: "BlueprinCosmetic"},    // Function is cosmetic in nature and should not be invoked on dedicated servers
	{flag:0x00000400, name: "Native"},  // Native function.
	{flag:0x00000800, name: "Event"},   // Event function.
	{flag:0x00002000, name: "Static"},  // Static function.
    {flag:0x00008000, name: "UbergraphFunction"},   // Function is used as the merge 'ubergraph' for a blueprint, only assigned when using the persistent 'ubergraph' frame
    {flag:0x00010000, name: "MulticastDlegate"},    // Function is a multi-cast delegate signature (also requires FUNC_Delegate to be set!)
    {flag:0x00100000, name: "Delegate"},    // Function is delegate signature (either single-cast or multi-cast, depending on whether FUNC_MulticastDelegate is set.)
    {flag:0x04000000, name: "BlueprintCallable"},    // function can be called from blueprint code
	{flag:0x08000000, name: "BlueprintEvent"},  // function can be overridden/implemented from a blueprint
	{flag:0x10000000, name: "BlueprintPure"},    // function can be called from blueprint code, and is also pure (produces no side effects). If you set this, you should set FUNC_BlueprintCallable as well.
    {flag:0x20000000, name: "EditorOnly"},  // function can only be called from an editor scrippt.
    {flag:0x40000000, name: "Const"},   // function can be called from blueprint code, and only reads state (never writes state)
]

function writeStructChild_Func(childprop) {
    var recurrce = [];
    var child = childprop;
    // console.log(`child: ${child}`);
    while (UObject.isValid(child)) {
        var prop = child;
        var oname = UObject.getName(prop);

        if (doHookProcessEvent && oname.match(processEventFilterOutRegex))
            break;

        var cname = UObject.getClassName(prop);
        // console.log(`writeStructChild_Func child: ${child}`);
        // console.log(`cname: ${cname}`);
        if (cname.startsWith("Function") || cname === "DelegateFunction") {
            var returnVal = "void";
            var params = "";
            var flags = "";

            var funcParam = UStruct.getChildProperties(prop);
            // console.log(`funcParam: ${funcParam});
            while (UObject.isValid(funcParam)) {
                var PropertyFlags = UProperty.getPropertyFlags(funcParam);
                // console.log(`PropertyFlags: ${PropertyFlags});
                if ((PropertyFlags & 0x0000000000000400) == 0x0000000000000400) {
                    returnVal = resolveProp(recurrce, funcParam);
                } else {
                    if ((PropertyFlags & 0x0000000000000100) == 0x0000000000000100) {
                        params += "out ";
                    }
                    /*if((PropertyFlags & 0x0000000008000000) == 0x0000000008000000){
                        params += "ref ";
                    }*/
                    if ((PropertyFlags & 0x0000000000000002) == 0x0000000000000002) {
                        params += "const ";
                    }
                    // console.log(`funcParam will go in...: ${funcParam});
                    params += resolveProp(recurrce, funcParam);
                    // params += "blahblah";
                    params += " ";
                    params += FField.getName(funcParam);
                    params += ", ";
                }

                funcParam = FField.getNext(funcParam);
            }

            if (params.length > 0) {
                params = params.slice(0, -2);
            }

            var FunctionFlags = UFunction.getFunctionFlags(prop);
            // console.log(`FunctionFlags: ${FunctionFlags});

            if ((FunctionFlags & 0x00002000) == 0x00002000) {
                returnVal = "static " + returnVal;
            }
            /*if((FunctionFlags & 0x00000001) == 0x00000001){
                returnVal = "final " + returnVal;
            }
            if((FunctionFlags & 0x00020000) == 0x00020000){
                returnVal = "public " + returnVal;
            }
            if((FunctionFlags & 0x00040000) == 0x00040000){
                returnVal = "private " + returnVal;
            }
            if((FunctionFlags & 0x00080000) == 0x00080000){
                returnVal = "protected " + returnVal;
            }*/

            for (let mapping of funcFlags) {
                if ((FunctionFlags & mapping.flag) == mapping.flag) {
                    flags += `${mapping.name}|`
                }
            }

            console.log(`\t${returnVal} ${oname}(${params}); // ${UFunction.getFunc(prop).sub(moduleBase)} ${flags !== "" ? ("[" + flags.slice(0, -1) + "]") : ""} ${isActorDump ? ("// Object addr: " + child) : ""}`);

            if (processInternal_offset === null && flags.slice(0, -1).match(/^(?!.*Native).*Blue.*/)) {
                processInternal_offset = UFunction.getFunc(prop).sub(moduleBase);
            }
        } else if (cname === "Class" || cname === "Package") {
        } else {
            console.log(`\t${cname} ${oname}; //[Size: ${UProperty.getElementSize(prop)}]`);
        }

        if (doHookProcessEvent)
            break;

        child = UField.getNext(child);
    }
    return recurrce;
}

function writeStruct(clazz) {
    var recurrce = [];

    var currStruct = clazz;
    while (UObject.isValid(currStruct)) {
        // console.log(`currStruct: ${currStruct}`)
        var name = UObject.getName(currStruct);
        // console.log(`name: ${name}`);
        if (name === "None" || name.indexOf("/Game/") > -1 || name.indexOf("_png") > -1 || name === "") {
            // console.log(`name is ${name} gonna break`);
            break;
        }

        var nameId = UObject.getNameId(currStruct);
        // console.log(nameId);
        if (!nameIds.includes(nameId)) {
            nameIds.push(nameId);
            if (isActorDump) {
                if (UStruct.getStructClassPath(currStruct) === 'Actor.Object') {
                    console.log(`Class: ${UStruct.getStructClassPath(currStruct)} // ${currStruct}`)    // for debugging
                    if (isBeforeUE425) {
                        recurrce.push(...writeStructChild(UStruct.getChildren(currStruct)));
                    } else {
                        recurrce.push(...writeStructChild(UStruct.getChildProperties(currStruct)));
                        recurrce.push(...writeStructChild_Func(UStruct.getChildren(currStruct)));
                    }
                }
            } else {
                console.log(`Class: ${UStruct.getStructClassPath(currStruct)}`)
                if (isBeforeUE425) {
                    recurrce.push(...writeStructChild(UStruct.getChildren(currStruct)));
                } else {
                    recurrce.push(...writeStructChild(UStruct.getChildProperties(currStruct)));
                    recurrce.push(...writeStructChild_Func(UStruct.getChildren(currStruct)));
                }
            }
        }
        currStruct = UStruct.getSuperClass(currStruct);
    }
    // console.log(`recurse: ${recurrce}`);
    for (var key in recurrce) {
        writeStruct(recurrce[key]);
    }
}

function dumpActor() {
    isActorDump = true;
    dumpSdk();
}

function dumpObjects() {
    if (GUObjectArray === undefined) {
        console.log(`Do set(<moduleName>) first`);
        return;
    } else if (GUObjectArray === null) {
        console.log(`Provide GUObjectArray address by GUObjectArray = moduleBase.add(<offset of GUObjectArray>);`);
        return;
    }
    var ObjectCount = GUObjectArray.add(offset_FUObjectArray_TUObjectArray).add(offset_TUObjectArray_NumElements).readU32();
    console.log(`ObjectCount: ${ObjectCount}`);

    for (var i = 0; i < ObjectCount; i++) {
        var UObjectBaseObject = getUObjectBaseObjectFromId(i);
        if (UObject.isValid(UObjectBaseObject)) {
            var name = UObject.getName(UObjectBaseObject);
            var className = UObject.getClassName(UObjectBaseObject);
            console.log(`${i}. Class: ${className}\n\tObjectName: ${name} // ${UObjectBaseObject}`);
        }
    }
}

function dumpSdk() {
    if (GUObjectArray === undefined) {
        console.log(`Do set(<moduleName>) first`);
        return;
    } else if (GUObjectArray === null) {
        console.log(`Provide GUObjectArray address by GUObjectArray = moduleBase.add(<offset of GUObjectArray>);`);
        return;
    }
    // empty below variables before performing the dump
    if (nameIds.length > 0) nameIds.length = 0;
    if (enumClasses.length > 0) enumClasses.length = 0;
    if (enumClassStr != "") enumClassStr = "";

    var ObjectCount = GUObjectArray.add(offset_FUObjectArray_TUObjectArray).add(offset_TUObjectArray_NumElements).readU32();

    for (var i = 0; i < ObjectCount; i++) {
        var UObjectBaseObject = getUObjectBaseObjectFromId(i);
        if (UObject.isValid(UObjectBaseObject)) {
            // console.log(`UObjectBaseObject: ${UObjectBaseObject}`);
            var clazz = UObject.getClass(UObjectBaseObject);
            writeStruct(clazz);
        }
    }
    if (offset_UENum_Names !== null) console.log(enumClassStr);
}

var GNameSearchCompleted = false;
var GUObjectArraySearchCompleted = false;
var GNamePatternFoundAddr;
var GUObjectArrayPatternFoundAddr;
function scanMemoryForGName(scanStart, scanSize, mempattern) {
    if (GNameSearchCompleted) {
        console.log(`[*] Memory scan done for GName!`);
        if (GNamePatternFoundAddr === undefined) {
            GNamePatternFoundAddr = ptr(0x0);
        }
        return;
    }
    Memory.scan(scanStart, scanSize, mempattern, {
        onMatch: function (address, size) {
            if (GNameSearchCompleted) return;
            GNamePatternFoundAddr = ptr(address);
            GNameSearchCompleted = true;
        },
        onError: function(reason) {
            var newstart = ptr(reason.match(/(0x[0-9a-f]+)/)[1]).add(0x4);
            var newsize = scanSize - parseInt(newstart.sub(scanStart));
            this.error = true;
            scanMemoryForGName(newstart, newsize, mempattern);
        },
        onComplete: function() {
            if (!this.error) {
                GNameSearchCompleted = true;
                scanMemoryForGName(scanStart, scanSize, mempattern);
            }
        }
    })
}

function scanMemoryForGUObjectArray(scanStart, scanSize, mempattern) {
    if (GUObjectArraySearchCompleted) {
        console.log(`[*] Memory scan done for GUObjectArray!`);
        if (GUObjectArrayPatternFoundAddr === undefined) {
            GUObjectArrayPatternFoundAddr = ptr(0x0);
        }
        return;
    }
    Memory.scan(scanStart, scanSize, mempattern, {
        onMatch: function (address, size) {
            if (GUObjectArraySearchCompleted) return;
            GUObjectArrayPatternFoundAddr = ptr(address);

            if (appId === 'com.wemade.nightcrows' || appId === 'com.GreenGoGames.RooftopPrakourFreerun') {
                var adrp, add;
                var disasm = Instruction.parse(GUObjectArrayPatternFoundAddr);
                adrp = disasm.operands.find(op => op.type === 'imm')?.value;

                disasm = Instruction.parse(GUObjectArrayPatternFoundAddr.add(0x8));
                add = disasm.operands.find(op => op.type === 'imm')?.value;

                if (ptr(adrp).add(ptr(add)).readUtf8String() === "CloseDisregardForGC" || ptr(adrp).add(ptr(add)).readUtf8String() === "DisableDisregardForGC") {
                    GUObjectArraySearchCompleted = true;
                }
            }
        },
        onError: function(reason) {
            var newstart = ptr(reason.match(/(0x[0-9a-f]+)/)[1]).add(0x4);
            var newsize = scanSize - parseInt(newstart.sub(scanStart));
            this.error = true;
            scanMemoryForGUObjectArray(newstart, newsize, mempattern);
        },
        onComplete: function() {
            if (!this.error) {
                GUObjectArraySearchCompleted = true;
                scanMemoryForGUObjectArray(scanStart, scanSize, mempattern);
            }
        }
    })
}

function safeScan(pattern, perm, callback) {
    const mod = Process.findModuleByName("libUE4.so");
    if (!mod) {
        if (callback) callback(null);
        return;
    }

    // Narrow down ranges based on typical Unreal Engine layouts
    let ranges = [];
    const allRanges = mod.enumerateRanges(perm || 'r--');

    if (perm === 'r-x') {
        // Code pattern search: prioritize executable ranges
        ranges = allRanges;
    } else if (perm === 'r--' || !perm) {
        // String/Pointer search: prioritize data/rodata
        // We look for sections if available, otherwise use all read ranges
        try {
            const sections = mod.enumerateSections();
            const targets = [".rodata", ".data", ".bss", "__rodata", "__data", "__bss"];
            sections.forEach(s => {
                if (targets.includes(s.name)) {
                    ranges.push({base: s.address, size: s.size});
                }
            });
        } catch (e) {}

        if (ranges.length === 0) {
            ranges = allRanges;
        }
    } else {
        ranges = allRanges;
    }

    let scanCount = ranges.length;
    let found = false;

    if (scanCount === 0) {
        if (callback) callback(null);
        return;
    }

    ranges.forEach(range => {
        Memory.scan(range.base, range.size, pattern, {
            onMatch: function (address, size) {
                if (!found) {
                    found = true;
                    if (callback) callback(address);
                }
                return 'stop';
            },
            onComplete: function () {
                scanCount--;
                if (scanCount === 0 && !found) {
                    if (callback) callback(null);
                }
            },
            onError: function (reason) {
                scanCount--;
                if (scanCount === 0 && !found) {
                    if (callback) callback(null);
                }
            }
        });
    });
}

function safeScanPromise(pattern, perm) {
    return new Promise((resolve) => {
        safeScan(pattern, perm, (address) => {
            resolve(address);
        });
    });
}

// Find GName
async function findGName(moduleName) {
    var addr = findExportAnywhere("_Zeq12FNameEntryId5EName");
    if (addr === null) {
        console.log(`[!] Cannot find GName export. Searching memory...`);
        var pattern = "?? ?? ?? ?? 08 01 ?? 91 ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? 08 69 69 b8 1f 01 00 6b e0 17 9f 1a c0 03 5f d6";
        addr = await safeScanPromise(pattern, 'r-x');

        if (!addr) {
            pattern = "c8 00 00 37 ?? ?? ?? ?? 00 00 ?? 91";
            addr = await safeScanPromise(pattern, 'r-x');
        }

        if (addr) {
            console.log(`[*] Found GName search function at ${addr}`);
            let resolved = resolveGlobal(addr);
            if (resolved) {
                GName = ptr(resolved);
                console.log(`[*] Got GName via code resolution: ${GName}`);
                return true;
            }
        }

        console.log(`[!] Automated GName search failed.`);
        return false;
    }

    console.log(`[*] Export found, but skipping Interceptor for stability. Using pattern scan...`);
    // Fallback to pattern scan even if export exists to be safer
    var pattern = "c8 00 00 37 ?? ?? ?? ?? 00 00 ?? 91";
    addr = await safeScanPromise(pattern, 'r-x');
    if (addr) {
        let resolved = resolveGlobal(addr);
        if (resolved) {
            GName = ptr(resolved);
            console.log(`[*] Got GName via code resolution (after export match): ${GName}`);
            return true;
        }
    }

    return false;
}

// Find GUObjectArray
async function findGUObjectArray(moduleName) {
    GUObjectArray = findExportAnywhere("GUObjectArray");
    if (GUObjectArray === null) {
        console.log(`[!] Cannot find GUObjectArray export. Searching memory...`);
        var pattern = "e1 ?? 40 b9 e2 ?? 40 b9 e3 ?? 40 39";
        var addr = await safeScanPromise(pattern, 'r-x');
        if (addr) {
            GUObjectArray = resolveGlobal(addr);
            if (GUObjectArray) {
                console.log(`[*] Got GUObjectArray via code resolution: ${GUObjectArray}`);
                return true;
            }
        }
        console.log(`[!] Automated GUObjectArray search failed.`);
        return false;
    }
    return true;
}

async function searchNone() {
    console.log("[*] Searching for 'None' name entry...");
    let noneAddr = await safeScanPromise("4E 6F 6E 65 00", "r--"); // "None\0"
    if (noneAddr) {
        console.log("[+] Found 'None' string at " + noneAddr + ". Searching for pointer...");
        let ptrHex = "";
        let buf = Memory.alloc(8);
        buf.writePointer(noneAddr);
        let b = buf.readByteArray(8);
        let uint8 = new Uint8Array(b);
        for (let i = 0; i < uint8.length; i++) ptrHex += uint8[i].toString(16).padStart(2, '0') + " ";
        let ref = await safeScanPromise(ptrHex.trim(), "r--");
        if (ref) {
            console.log("[+] Found pointer to 'None' at " + ref);
            return ptr(ref);
        }
    }
    return null;
}

async function set(moduleName) {
    try {
        const mod = Process.findModuleByName(moduleName);
        if (!mod) {
            console.log("[!] Module not found: " + moduleName);
            return;
        }
        moduleBase = ptr(mod.base);
        console.log(`[+] Initializing for ${moduleName} at ${moduleBase} (Size: ${mod.size})`);

        appId = "com.Grand.Napal";

        console.log("[*] Searching for UE4 Globals...");

        // 1. Find GUObjectArray (more reliable pattern)
        let guobject_patterns = [
            "20 00 80 52 ?? ?? ?? 94", // AllocateObjectPool call
            "e1 ?? 40 b9 e2 ?? 40 b9 e3 ?? 40 39"
        ];

        for (let p of guobject_patterns) {
            let addr = await safeScanPromise(p, 'r-x');
            if (addr) {
                GUObjectArray = resolveGlobal(addr);
                if (GUObjectArray) {
                    GUObjectArray = ptr(GUObjectArray);
                    console.log("[+] Resolved GUObjectArray: " + GUObjectArray + " (Offset: " + GUObjectArray.sub(moduleBase) + ")");
                    break;
                }
            }
        }

        // 2. Find GName
        let gname_patterns = [
            "42 79 74 65 50 72 6F 70 65 72 74 79 00", // "ByteProperty"
            "c8 00 00 37 ?? ?? ?? ?? 00 00 ?? 91"    // operator== code
        ];

        for (let p of gname_patterns) {
            let addr = await safeScanPromise(p, p.startsWith("42") ? "r--" : "r-x");
            if (addr) {
                if (p.startsWith("42")) {
                    console.log("[*] Found 'ByteProperty' at " + addr + ". Searching for all references...");

                    let ptrHex = "";
                    let buf = Memory.alloc(8);
                    buf.writePointer(addr);
                    let b = buf.readByteArray(8);
                    let uint8 = new Uint8Array(b);
                    for (let i = 0; i < uint8.length; i++) {
                        ptrHex += uint8[i].toString(16).padStart(2, '0') + " ";
                    }
                    ptrHex = ptrHex.trim();

                    console.log("[*] Searching for pointer pattern: " + ptrHex);
                    let ref = await safeScanPromise(ptrHex, "r--");
                    if (ref) {
                        GName = ref.sub(ptr(offset_GName_FNamePool));
                        console.log("[+] Found GName via direct pointer reference at " + ref);
                    }
                } else {
                    GName = resolveGlobal(addr);
                }
                if (GName) {
                    GName = ptr(GName);
                    console.log("[+] Resolved GName: " + GName + " (Offset: " + GName.sub(moduleBase) + ")");
                    break;
                }
            }
        }

        if (!GName || !GUObjectArray) {
            console.log("[!] Fast resolution incomplete. Trying heuristic offsets...");
            if (GUObjectArray && !GName) {
                // Try searching for 'None'
                let noneRef = await searchNone();
                if (noneRef) {
                     GName = noneRef.sub(ptr(offset_GName_FNamePool)).sub(ptr(offset_FNamePool_Blocks));
                     console.log("[+] Found GName candidate via 'None' search: " + GName);
                }

                if (!GName) {
                    let common_offsets = [-0x20, 0x20, -0x38, 0x38, -0x40, 0x40, -0x30, 0x30, -0x10, 0x10, -0x48, 0x48];
                    for (let off of common_offsets) {
                        let candidate = GUObjectArray.add(ptr(off));
                        try {
                            let blocksPtr = safeReadPointer(candidate.add(ptr(offset_GName_FNamePool)));
                            if (!blocksPtr.isNull() && blocksPtr.compare(moduleBase) > 0 && blocksPtr.compare(moduleBase.add(mod.size)) < 0) {
                                 let nameEntry = safeReadPointer(blocksPtr);
                                 if (!nameEntry.isNull() && nameEntry.compare(moduleBase) > 0) {
                                     GName = candidate;
                                     console.log("[+] Heuristic: Found GName candidate at " + GName + " (Offset: " + off + ")");
                                     break;
                                 }
                            }
                        } catch (e) {}
                    }
                }
            }
            if (!GUObjectArray) await findGUObjectArray(moduleName);
            if (!GName) await findGName(moduleName);
        }

        setOffset(appId);

        console.log(`\n[*] Final Config: GUObjectArray: ${GUObjectArray}, GName: ${GName}`);

        // Safe check
        if (GName && isReadable(GName.add(ptr(offset_GName_FNamePool)), 8)) {
             console.log("[+] GName seems valid (readable).");
        } else if (GName) {
             console.log("[!] WARNING: GName is set but not readable at expected pool offset.");
        }

        console.log("[+] Initialization complete. Commands: dumpSdk(), dumpObjects(), dumpActor()");
    } catch (err) {
        console.log("[!!!] ERROR IN set(): " + err.stack);
    }
}

// Start script manually using startDump()
async function startDump() {
    bypassExit();
    waitForModule("libUE4.so", async (mod) => {
        console.log("[*] libUE4.so loaded. Starting in 5s...");
        setTimeout(async () => {
            await set("libUE4.so");
        }, 5000);
    });
}
