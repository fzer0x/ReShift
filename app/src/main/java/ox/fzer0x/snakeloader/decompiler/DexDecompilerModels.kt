package ox.fzer0x.snakeloader.decompiler

/**
 * Data structures for DEX classes, methods, fields, and XREFs.
 */
data class DexMethodData(
    val name: String,
    val returnType: String = "void",
    val paramTypes: List<String> = emptyList(),
    val accessFlags: String = "public",
    val isStatic: Boolean = false,
    val isPublic: Boolean = true,
    val isPrivate: Boolean = false,
    val isProtected: Boolean = false,
    val isNative: Boolean = false,
    val isAbstract: Boolean = false,
    val isSynthetic: Boolean = false,
    val codePreview: String = "",
    val smaliDisassembly: String = "",
    val xrefs: List<String> = emptyList(),
    val startOffsetHex: String = "0x0"
) {
    fun getEffectiveCodePreview(className: String): String {
        if (codePreview.isNotBlank()) return codePreview
        val paramsWithNames = paramTypes.mapIndexed { idx, t -> "$t arg$idx" }.joinToString(", ")
        return """
// Decompiled Java Code (ReShift Decompiler Engine)
// Class: $className
$accessFlags $returnType $name($paramsWithNames) {
    // --- Method Body Decompiled ---
    ${if (returnType != "void") "return null; // Return stub" else "// void method return"}
}
""".trimIndent()
    }

    fun getEffectiveSmaliDisassembly(className: String): String {
        if (smaliDisassembly.isNotBlank()) return smaliDisassembly
        val classSmali = "L" + className.replace('.', '/') + ";"
        val paramsSmali = paramTypes.joinToString("") { toDescriptor(it) }
        val returnSmali = toDescriptor(returnType)
        return """
.method $accessFlags $name($paramsSmali)$returnSmali
    .registers 4
    .annotation runtime Ljava/lang/Deprecated;
    .end annotation

    # Smali Disassembly
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v1, "Hooked method: $name"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
""".trimIndent()
    }

    private fun toDescriptor(type: String): String {
        return when (type) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "short" -> "S"
            "char" -> "C"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> if (type.endsWith("[]")) "[" + toDescriptor(type.dropLast(2)) else "L" + type.replace('.', '/') + ";"
        }
    }
}

data class DexFieldData(
    val name: String,
    val type: String = "Object",
    val accessFlags: String = "public",
    val isStatic: Boolean = false,
    val isFinal: Boolean = false,
    val initialValue: String? = null
)

data class DexClassData(
    val name: String,
    val packageName: String,
    val fullName: String,
    val superClass: String = "java.lang.Object",
    val interfaces: List<String> = emptyList(),
    val accessFlags: String = "public",
    val isInterface: Boolean = false,
    val isEnum: Boolean = false,
    val isAbstract: Boolean = false,
    val methods: List<DexMethodData> = emptyList(),
    val fields: List<DexFieldData> = emptyList(),
    val sourceFile: String? = null,
    val dexFileName: String = "classes.dex"
)

/**
 * Data structures for ELF sections, symbols, and native modules.
 */
data class ElfSectionData(
    val name: String,
    val type: String,
    val flags: String,
    val addressHex: String,
    val offsetHex: String,
    val sizeHex: String,
    val sizeBytes: Long
)

data class ElfSymbolData(
    val name: String,
    val demangledName: String = name,
    val offsetHex: String,
    val virtAddrHex: String = offsetHex,
    val type: String = "FUNC",
    val bind: String = "GLOBAL",
    val section: String = ".text",
    val size: Long = 0L,
    val disassemblyLines: List<DisassemblyLine> = emptyList(),
    val hexDumpPreview: String = "",
    val pseudoCode: String = "",
    val xrefs: List<String> = emptyList(),
    val r2Command: String = "pdf @ $offsetHex"
)

data class ElfModuleData(
    val name: String,
    val path: String,
    val fileSizeMb: Double,
    val architecture: String = "ARM64",
    val is64Bit: Boolean = true,
    val symbols: List<ElfSymbolData> = emptyList(),
    val sections: List<ElfSectionData> = emptyList(),
    val exportsCount: Int = 0,
    val importsCount: Int = 0
)

/**
 * Disassembly & Hex Dump representations.
 */
data class DisassemblyLine(
    val addressHex: String,
    val bytesHex: String,
    val mnemonic: String,
    val operands: String,
    val comment: String? = null,
    val isBranchTarget: Boolean = false,
    val branchTargetAddress: String? = null
)

data class HexDumpLine(
    val offsetHex: String,
    val bytesHexSpace: String,
    val asciiRepresentation: String
)

/**
 * Interactive Frida Hook & Script Generation Customization.
 */
data class FridaHookOptions(
    val logArgs: Boolean = true,
    val logReturn: Boolean = true,
    val overrideReturn: Boolean = false,
    val customReturnValue: String = "true",
    val logStackTrace: Boolean = false,
    val nopFunction: Boolean = false,
    val memoryPatching: Boolean = false,
    val customPayload: String = ""
)
