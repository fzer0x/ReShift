package ox.fzer0x.snakeloader.decompiler

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Pure Kotlin Binary Parser for Android Dalvik Executable (.dex) files.
 * Parses DEX header, String IDs, Type IDs, Proto IDs, Field IDs, Method IDs,
 * Class Definitions, Access Flags, Smali stubs, and XREFs without external tools.
 */
class DexParser {

    companion object {
        private const val TAG = "DexParser"
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a) // "dex\n"
        private const val NO_INDEX = 0xffffffff.toInt()
    }

    /**
     * Memory-mapped parser for a standalone .dex file on disk.
     */
    fun parseDexFile(dexFile: File, searchQuery: String = "", limit: Int = 300): List<DexClassData> {
        if (!dexFile.exists()) return emptyList()
        return try {
            RandomAccessFile(dexFile, "r").use { raf ->
                val channel = raf.channel
                val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                mappedBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val bytes = ByteArray(channel.size().toInt())
                mappedBuffer.get(bytes)
                val parsed = parseDexBytes(bytes, dexFileName = dexFile.name)
                if (searchQuery.isNotBlank()) {
                    val q = searchQuery.lowercase()
                    parsed.filter {
                        it.fullName.lowercase().contains(q) ||
                        it.name.lowercase().contains(q) ||
                        it.methods.any { m -> m.name.lowercase().contains(q) } ||
                        it.fields.any { f -> f.name.lowercase().contains(q) }
                    }.take(limit)
                } else {
                    parsed.take(limit)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing memory-mapped DEX file ${dexFile.absolutePath}", e)
            emptyList()
        }
    }

    /**
     * Extracts and parses all classes across all DEX files (classes.dex, classes2.dex, etc.) inside an APK file.
     */
    fun parseApkDexClasses(apkPath: String, searchQuery: String = "", limit: Int = 300): List<DexClassData> {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return emptyList()

        val allClasses = mutableListOf<DexClassData>()

        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (searchQuery.isBlank() && allClasses.size >= limit * 2) break
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                        try {
                            val entrySize = entry.size.toInt()
                            val dexBytes = if (entrySize in 1..100_000_000) {
                                val buf = ByteArray(entrySize)
                                zip.getInputStream(entry).use { inputStream ->
                                    var read = 0
                                    while (read < entrySize) {
                                        val count = inputStream.read(buf, read, entrySize - read)
                                        if (count <= 0) break
                                        read += count
                                    }
                                }
                                buf
                            } else {
                                zip.getInputStream(entry).use { it.readBytes() }
                            }
                            val parsed = parseDexBytes(dexBytes, dexFileName = entry.name)
                            allClasses.addAll(parsed)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing ${entry.name} in $apkPath", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading APK ZIP $apkPath", e)
        }

        // Filter by searchQuery if present
        return if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            allClasses.filter {
                it.fullName.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                it.methods.any { m -> m.name.lowercase().contains(q) } ||
                it.fields.any { f -> f.name.lowercase().contains(q) }
            }.take(limit)
        } else {
            allClasses.take(limit)
        }
    }

    /**
     * Parses a byte array containing raw .dex binary data into a list of [DexClassData].
     */
    fun parseDexBytes(bytes: ByteArray, dexFileName: String = "classes.dex"): List<DexClassData> {
        if (bytes.size < 112) return emptyList()

        // Check DEX magic
        if (bytes[0] != DEX_MAGIC[0] || bytes[1] != DEX_MAGIC[1] ||
            bytes[2] != DEX_MAGIC[2] || bytes[3] != DEX_MAGIC[3]) {
            Log.w(TAG, "Invalid DEX Magic header in $dexFileName")
            return emptyList()
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Read DEX Header
        val stringIdsSize = buffer.getInt(0x38)
        val stringIdsOff = buffer.getInt(0x3c)
        val typeIdsSize = buffer.getInt(0x40)
        val typeIdsOff = buffer.getInt(0x44)
        val protoIdsSize = buffer.getInt(0x48)
        val protoIdsOff = buffer.getInt(0x4c)
        val fieldIdsSize = buffer.getInt(0x50)
        val fieldIdsOff = buffer.getInt(0x54)
        val methodIdsSize = buffer.getInt(0x58)
        val methodIdsOff = buffer.getInt(0x5c)
        val classDefsSize = buffer.getInt(0x60)
        val classDefsOff = buffer.getInt(0x64)

        // 1. Read String Table
        val stringTable = parseStringTable(buffer, bytes, stringIdsSize, stringIdsOff)

        // 2. Read Type Table
        val typeTable = parseTypeTable(buffer, stringTable, typeIdsSize, typeIdsOff)

        // 3. Read Proto Table
        val protoTable = parseProtoTable(buffer, bytes, stringTable, typeTable, protoIdsSize, protoIdsOff)

        // 4. Read Field Table
        val fieldTable = parseFieldTable(buffer, stringTable, typeTable, fieldIdsSize, fieldIdsOff)

        // 5. Read Method Table
        val methodTable = parseMethodTable(buffer, stringTable, typeTable, protoTable, methodIdsSize, methodIdsOff)

        // 6. Read Class Definitions
        return parseClassDefs(
            buffer = buffer,
            bytes = bytes,
            stringTable = stringTable,
            typeTable = typeTable,
            fieldTable = fieldTable,
            methodTable = methodTable,
            classDefsSize = classDefsSize,
            classDefsOff = classDefsOff,
            dexFileName = dexFileName
        )
    }

    private fun parseStringTable(buffer: ByteBuffer, bytes: ByteArray, size: Int, offset: Int): List<String> {
        val list = ArrayList<String>(size)
        for (i in 0 until size) {
            val stringOff = buffer.getInt(offset + i * 4)
            if (stringOff in bytes.indices) {
                // Read ULEB128 string length
                var pos = stringOff
                val lengthUleb = readUleb128(bytes, pos)
                pos += lengthUleb.bytesRead
                
                // Read MUTF-8 string bytes until null terminator 0x00
                val start = pos
                while (pos < bytes.size && bytes[pos] != 0.toByte()) {
                    pos++
                }
                val strBytes = bytes.copyOfRange(start, pos)
                list.add(String(strBytes, Charsets.UTF_8))
            } else {
                list.add("")
            }
        }
        return list
    }

    private fun parseTypeTable(buffer: ByteBuffer, strings: List<String>, size: Int, offset: Int): List<String> {
        val list = ArrayList<String>(size)
        for (i in 0 until size) {
            val descriptorIndex = buffer.getInt(offset + i * 4)
            if (descriptorIndex in strings.indices) {
                list.add(strings[descriptorIndex])
            } else {
                list.add("Ljava/lang/Object;")
            }
        }
        return list
    }

    private data class ProtoItem(val shorty: String, val returnType: String, val paramTypes: List<String>)

    private fun parseProtoTable(
        buffer: ByteBuffer,
        bytes: ByteArray,
        strings: List<String>,
        types: List<String>,
        size: Int,
        offset: Int
    ): List<ProtoItem> {
        val list = ArrayList<ProtoItem>(size)
        for (i in 0 until size) {
            val base = offset + i * 12
            val shortyIdx = buffer.getInt(base)
            val returnTypeIdx = buffer.getInt(base + 4)
            val parametersOff = buffer.getInt(base + 8)

            val shorty = strings.getOrElse(shortyIdx) { "V" }
            val retType = types.getOrElse(returnTypeIdx) { "V" }
            val params = mutableListOf<String>()

            if (parametersOff > 0 && parametersOff in bytes.indices) {
                val paramCount = buffer.getInt(parametersOff)
                for (p in 0 until paramCount) {
                    val typeIdx = buffer.getShort(parametersOff + 4 + p * 2).toInt() and 0xffff
                    params.add(types.getOrElse(typeIdx) { "java.lang.Object" })
                }
            }

            list.add(ProtoItem(shorty, formatDescriptor(retType), params.map { formatDescriptor(it) }))
        }
        return list
    }

    private data class FieldItem(val classType: String, val fieldType: String, val name: String)

    private fun parseFieldTable(
        buffer: ByteBuffer,
        strings: List<String>,
        types: List<String>,
        size: Int,
        offset: Int
    ): List<FieldItem> {
        val list = ArrayList<FieldItem>(size)
        for (i in 0 until size) {
            val base = offset + i * 8
            val classIdx = buffer.getShort(base).toInt() and 0xffff
            val typeIdx = buffer.getShort(base + 2).toInt() and 0xffff
            val nameIdx = buffer.getInt(base + 4)

            val classType = types.getOrElse(classIdx) { "java.lang.Object" }
            val fieldType = types.getOrElse(typeIdx) { "java.lang.Object" }
            val name = strings.getOrElse(nameIdx) { "field$i" }

            list.add(FieldItem(formatDescriptor(classType), formatDescriptor(fieldType), name))
        }
        return list
    }

    private data class MethodItem(val classType: String, val name: String, val returnType: String, val paramTypes: List<String>)

    private fun parseMethodTable(
        buffer: ByteBuffer,
        strings: List<String>,
        types: List<String>,
        protos: List<ProtoItem>,
        size: Int,
        offset: Int
    ): List<MethodItem> {
        val list = ArrayList<MethodItem>(size)
        for (i in 0 until size) {
            val base = offset + i * 8
            val classIdx = buffer.getShort(base).toInt() and 0xffff
            val protoIdx = buffer.getShort(base + 2).toInt() and 0xffff
            val nameIdx = buffer.getInt(base + 4)

            val classType = types.getOrElse(classIdx) { "java.lang.Object" }
            val name = strings.getOrElse(nameIdx) { "method$i" }
            val proto = protos.getOrNull(protoIdx)

            val retType = proto?.returnType ?: "void"
            val params = proto?.paramTypes ?: emptyList()

            list.add(MethodItem(formatDescriptor(classType), name, retType, params))
        }
        return list
    }

    private fun parseClassDefs(
        buffer: ByteBuffer,
        bytes: ByteArray,
        stringTable: List<String>,
        typeTable: List<String>,
        fieldTable: List<FieldItem>,
        methodTable: List<MethodItem>,
        classDefsSize: Int,
        classDefsOff: Int,
        dexFileName: String
    ): List<DexClassData> {
        val result = mutableListOf<DexClassData>()

        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val classIdx = buffer.getInt(base)
            val accessFlagsInt = buffer.getInt(base + 4)
            val superClassIdx = buffer.getInt(base + 8)
            val interfacesOff = buffer.getInt(base + 12)
            val sourceFileIdx = buffer.getInt(base + 16)
            val classDataOff = buffer.getInt(base + 24)

            val classDescriptor = typeTable.getOrElse(classIdx) { "LUnknownClass;" }
            val formattedFullName = formatDescriptor(classDescriptor)
            val className = formattedFullName.substringAfterLast('.')
            val packageName = formattedFullName.substringBeforeLast('.', "")

            val superClassDesc = if (superClassIdx != NO_INDEX) typeTable.getOrElse(superClassIdx) { "Ljava/lang/Object;" } else "Ljava/lang/Object;"
            val formattedSuperClass = formatDescriptor(superClassDesc)

            val sourceFile = if (sourceFileIdx != NO_INDEX && sourceFileIdx in stringTable.indices) stringTable[sourceFileIdx] else null

            // Read Interfaces
            val interfaces = mutableListOf<String>()
            if (interfacesOff > 0 && interfacesOff in bytes.indices) {
                val ifaceCount = buffer.getInt(interfacesOff)
                for (idx in 0 until ifaceCount) {
                    val ifaceTypeIdx = buffer.getShort(interfacesOff + 4 + idx * 2).toInt() and 0xffff
                    interfaces.add(formatDescriptor(typeTable.getOrElse(ifaceTypeIdx) { "java.lang.Object" }))
                }
            }

            // Parse Class Data (Fields & Methods)
            val classMethods = mutableListOf<DexMethodData>()
            val classFields = mutableListOf<DexFieldData>()

            if (classDataOff > 0 && classDataOff in bytes.indices) {
                var pos = classDataOff
                val staticFieldsSizeUleb = readUleb128(bytes, pos)
                pos += staticFieldsSizeUleb.bytesRead
                val instanceFieldsSizeUleb = readUleb128(bytes, pos)
                pos += instanceFieldsSizeUleb.bytesRead
                val directMethodsSizeUleb = readUleb128(bytes, pos)
                pos += directMethodsSizeUleb.bytesRead
                val virtualMethodsSizeUleb = readUleb128(bytes, pos)
                pos += virtualMethodsSizeUleb.bytesRead

                // Read Static Fields
                var fieldIdx = 0
                for (f in 0 until staticFieldsSizeUleb.value) {
                    val idxDiff = readUleb128(bytes, pos)
                    pos += idxDiff.bytesRead
                    val flags = readUleb128(bytes, pos)
                    pos += flags.bytesRead

                    fieldIdx += idxDiff.value
                    if (fieldIdx in fieldTable.indices) {
                        val item = fieldTable[fieldIdx]
                        val accessStr = parseAccessFlags(flags.value, isField = true)
                        classFields.add(
                            DexFieldData(
                                name = item.name,
                                type = item.fieldType,
                                accessFlags = accessStr,
                                isStatic = true,
                                isFinal = (flags.value and 0x0010) != 0
                            )
                        )
                    }
                }

                // Read Instance Fields
                fieldIdx = 0
                for (f in 0 until instanceFieldsSizeUleb.value) {
                    val idxDiff = readUleb128(bytes, pos)
                    pos += idxDiff.bytesRead
                    val flags = readUleb128(bytes, pos)
                    pos += flags.bytesRead

                    fieldIdx += idxDiff.value
                    if (fieldIdx in fieldTable.indices) {
                        val item = fieldTable[fieldIdx]
                        val accessStr = parseAccessFlags(flags.value, isField = true)
                        classFields.add(
                            DexFieldData(
                                name = item.name,
                                type = item.fieldType,
                                accessFlags = accessStr,
                                isStatic = false,
                                isFinal = (flags.value and 0x0010) != 0
                            )
                        )
                    }
                }

                // Read Direct Methods
                var methodIdx = 0
                for (m in 0 until directMethodsSizeUleb.value) {
                    val idxDiff = readUleb128(bytes, pos)
                    pos += idxDiff.bytesRead
                    val flags = readUleb128(bytes, pos)
                    pos += flags.bytesRead
                    val codeOff = readUleb128(bytes, pos)
                    pos += codeOff.bytesRead

                    methodIdx += idxDiff.value
                    if (methodIdx in methodTable.indices) {
                        val item = methodTable[methodIdx]
                        val accessStr = parseAccessFlags(flags.value, isField = false)

                        val dalvikRes = parseDalvikBytecode(
                            buffer = buffer,
                            bytes = bytes,
                            codeOff = codeOff.value,
                            className = formattedFullName,
                            methodName = item.name,
                            accessFlags = accessStr,
                            returnType = item.returnType,
                            paramTypes = item.paramTypes,
                            stringTable = stringTable,
                            typeTable = typeTable,
                            fieldTable = fieldTable,
                            methodTable = methodTable
                        )

                        classMethods.add(
                            DexMethodData(
                                name = item.name,
                                returnType = item.returnType,
                                paramTypes = item.paramTypes,
                                accessFlags = accessStr,
                                isStatic = (flags.value and 0x0008) != 0,
                                isPublic = (flags.value and 0x0001) != 0,
                                isPrivate = (flags.value and 0x0002) != 0,
                                isProtected = (flags.value and 0x0004) != 0,
                                isNative = (flags.value and 0x0100) != 0,
                                isAbstract = (flags.value and 0x0400) != 0,
                                codePreview = dalvikRes.javaCode,
                                smaliDisassembly = dalvikRes.smaliCode,
                                xrefs = dalvikRes.calledMethods.map { "callee: $it" },
                                startOffsetHex = "0x${Integer.toHexString(codeOff.value)}"
                            )
                        )
                    }
                }

                // Read Virtual Methods
                methodIdx = 0
                for (m in 0 until virtualMethodsSizeUleb.value) {
                    val idxDiff = readUleb128(bytes, pos)
                    pos += idxDiff.bytesRead
                    val flags = readUleb128(bytes, pos)
                    pos += flags.bytesRead
                    val codeOff = readUleb128(bytes, pos)
                    pos += codeOff.bytesRead

                    methodIdx += idxDiff.value
                    if (methodIdx in methodTable.indices) {
                        val item = methodTable[methodIdx]
                        val accessStr = parseAccessFlags(flags.value, isField = false)

                        val dalvikRes = parseDalvikBytecode(
                            buffer = buffer,
                            bytes = bytes,
                            codeOff = codeOff.value,
                            className = formattedFullName,
                            methodName = item.name,
                            accessFlags = accessStr,
                            returnType = item.returnType,
                            paramTypes = item.paramTypes,
                            stringTable = stringTable,
                            typeTable = typeTable,
                            fieldTable = fieldTable,
                            methodTable = methodTable
                        )

                        classMethods.add(
                            DexMethodData(
                                name = item.name,
                                returnType = item.returnType,
                                paramTypes = item.paramTypes,
                                accessFlags = accessStr,
                                isStatic = (flags.value and 0x0008) != 0,
                                isPublic = (flags.value and 0x0001) != 0,
                                isPrivate = (flags.value and 0x0002) != 0,
                                isProtected = (flags.value and 0x0004) != 0,
                                isNative = (flags.value and 0x0100) != 0,
                                isAbstract = (flags.value and 0x0400) != 0,
                                codePreview = dalvikRes.javaCode,
                                smaliDisassembly = dalvikRes.smaliCode,
                                xrefs = dalvikRes.calledMethods.map { "callee: $it" },
                                startOffsetHex = "0x${Integer.toHexString(codeOff.value)}"
                            )
                        )
                    }
                }
            }

            val classAccessStr = parseAccessFlags(accessFlagsInt, isField = false)

            result.add(
                DexClassData(
                    name = className,
                    packageName = packageName,
                    fullName = formattedFullName,
                    superClass = formattedSuperClass,
                    interfaces = interfaces,
                    accessFlags = classAccessStr,
                    isInterface = (accessFlagsInt and 0x0200) != 0,
                    isEnum = (accessFlagsInt and 0x4000) != 0,
                    isAbstract = (accessFlagsInt and 0x0400) != 0,
                    methods = classMethods,
                    fields = classFields,
                    sourceFile = sourceFile,
                    dexFileName = dexFileName
                )
            )
        }

        return result
    }

    private data class Uleb128Result(val value: Int, val bytesRead: Int)

    private fun readUleb128(bytes: ByteArray, offset: Int): Uleb128Result {
        var result = 0
        var count = 0
        var cur: Int
        var pos = offset
        do {
            if (pos >= bytes.size) break
            cur = bytes[pos++].toInt() and 0xff
            result = result or ((cur and 0x7f) shl (count * 7))
            count++
        } while (cur and 0x80 != 0 && count < 5)
        return Uleb128Result(result, count)
    }

    private fun formatDescriptor(desc: String): String {
        var d = desc
        var arrayPrefix = ""
        while (d.startsWith("[")) {
            arrayPrefix += "[]"
            d = d.substring(1)
        }
        val typeName = when {
            d == "V" -> "void"
            d == "Z" -> "boolean"
            d == "B" -> "byte"
            d == "S" -> "short"
            d == "C" -> "char"
            d == "I" -> "int"
            d == "J" -> "long"
            d == "F" -> "float"
            d == "D" -> "double"
            d.startsWith("L") && d.endsWith(";") -> d.substring(1, d.length - 1).replace('/', '.')
            else -> d
        }
        return typeName + arrayPrefix
    }

    private fun parseAccessFlags(flags: Int, isField: Boolean): String {
        val sb = StringBuilder()
        if ((flags and 0x0001) != 0) sb.append("public ")
        if ((flags and 0x0002) != 0) sb.append("private ")
        if ((flags and 0x0004) != 0) sb.append("protected ")
        if ((flags and 0x0008) != 0) sb.append("static ")
        if ((flags and 0x0010) != 0) sb.append("final ")
        if ((flags and 0x0020) != 0) sb.append("synchronized ")
        if ((flags and 0x0040) != 0) sb.append("volatile ")
        if ((flags and 0x0080) != 0) sb.append("transient ")
        if ((flags and 0x0100) != 0) sb.append("native ")
        if ((flags and 0x0400) != 0) sb.append("abstract ")
        if ((flags and 0x1000) != 0) sb.append("synthetic ")
        return sb.toString().trim()
    }

    private fun generateJavaDecompiledCode(
        className: String,
        methodName: String,
        access: String,
        returnType: String,
        paramTypes: List<String>
    ): String {
        val paramsWithNames = paramTypes.mapIndexed { idx, t -> "$t arg$idx" }.joinToString(", ")
        val simpleClassName = className.substringAfterLast('.')
        return """
// Decompiled Java Code (ReShift Decompiler Engine)
// Class: $className
$access $returnType $methodName($paramsWithNames) {
    // --- Method Body Decompiled ---
    ${if (returnType != "void") "return null; // Return stub" else "// void method return"}
}
""".trimIndent()
    }

    private fun generateSmaliStub(
        className: String,
        methodName: String,
        access: String,
        returnType: String,
        paramTypes: List<String>
    ): String {
        val classSmali = "L" + className.replace('.', '/') + ";"
        val paramsSmali = paramTypes.joinToString("") { toDescriptor(it) }
        val returnSmali = toDescriptor(returnType)
        return """
.method $access $methodName($paramsSmali)$returnSmali
    .registers 4
    .annotation runtime Ljava/lang/Deprecated;
    .end annotation

    # Smali Disassembly
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v1, "Hooked method: $methodName"
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

    private data class DalvikCodeResult(
        val smaliCode: String,
        val javaCode: String,
        val calledMethods: List<String>
    )

    private fun parseDalvikBytecode(
        buffer: ByteBuffer,
        bytes: ByteArray,
        codeOff: Int,
        className: String,
        methodName: String,
        accessFlags: String,
        returnType: String,
        paramTypes: List<String>,
        stringTable: List<String>,
        typeTable: List<String>,
        fieldTable: List<FieldItem>,
        methodTable: List<MethodItem>
    ): DalvikCodeResult {
        if (codeOff <= 0 || codeOff + 16 > bytes.size) {
            return DalvikCodeResult(
                smaliCode = generateSmaliStub(className, methodName, accessFlags, returnType, paramTypes),
                javaCode = generateJavaDecompiledCode(className, methodName, accessFlags, returnType, paramTypes),
                calledMethods = emptyList()
            )
        }

        val registersSize = buffer.getShort(codeOff).toInt() and 0xffff
        val insnsSize = buffer.getInt(codeOff + 12)

        if (insnsSize <= 0 || codeOff + 16 + (insnsSize * 2) > bytes.size) {
            return DalvikCodeResult(
                smaliCode = generateSmaliStub(className, methodName, accessFlags, returnType, paramTypes),
                javaCode = generateJavaDecompiledCode(className, methodName, accessFlags, returnType, paramTypes),
                calledMethods = emptyList()
            )
        }

        val smaliSb = StringBuilder()
        val javaSb = StringBuilder()
        val calledMethods = mutableListOf<String>()

        val paramsWithNames = paramTypes.mapIndexed { idx, t -> "$t arg$idx" }.joinToString(", ")
        javaSb.append("// Decompiled Java Code (ReShift Decompiler Engine)\n")
        javaSb.append("// Class: $className\n")
        javaSb.append("$accessFlags $returnType $methodName($paramsWithNames) {\n")

        val paramsSmali = paramTypes.joinToString("") { toDescriptor(it) }
        val returnSmali = toDescriptor(returnType)

        smaliSb.append(".method $accessFlags $methodName($paramsSmali)$returnSmali\n")
        smaliSb.append("    .registers $registersSize\n")

        val insnsBase = codeOff + 16
        var pc = 0
        val stringVars = mutableMapOf<Int, String>()

        while (pc < insnsSize) {
            val insnOff = insnsBase + (pc * 2)
            if (insnOff + 2 > bytes.size) break

            val unit0 = buffer.getShort(insnOff).toInt() and 0xffff
            val opcode = unit0 and 0xff
            val regA = (unit0 shr 8) and 0x0f
            val regB = (unit0 shr 12) and 0x0f
            val regAA = (unit0 shr 8) and 0xff

            when (opcode) {
                0x00 -> {
                    smaliSb.append("    nop\n")
                    pc += 1
                }
                0x01, 0x04, 0x07 -> {
                    smaliSb.append("    move-object v$regA, v$regB\n")
                    javaSb.append("    v$regA = v$regB;\n")
                    pc += 1
                }
                0x0e -> {
                    smaliSb.append("    return-void\n")
                    javaSb.append("    return;\n")
                    pc += 1
                }
                0x0f, 0x10, 0x11 -> {
                    val retOp = if (opcode == 0x11) "return-object" else "return"
                    smaliSb.append("    $retOp v$regAA\n")
                    val retVal = stringVars[regAA] ?: "v$regAA"
                    javaSb.append("    return $retVal;\n")
                    pc += 1
                }
                0x12 -> {
                    val literal = (unit0 shl 16) shr 28
                    smaliSb.append("    const/4 v$regA, $literal\n")
                    javaSb.append("    int v$regA = $literal;\n")
                    pc += 1
                }
                0x13 -> {
                    if (insnOff + 4 <= bytes.size) {
                        val literal = buffer.getShort(insnOff + 2).toInt()
                        smaliSb.append("    const/16 v$regAA, $literal\n")
                        javaSb.append("    int v$regAA = $literal;\n")
                    } else {
                        smaliSb.append("    const/16 v$regAA, ...\n")
                    }
                    pc += 2
                }
                0x14 -> {
                    if (insnOff + 6 <= bytes.size) {
                        val literal = buffer.getInt(insnOff + 2)
                        smaliSb.append("    const v$regAA, $literal\n")
                        javaSb.append("    int v$regAA = $literal;\n")
                    } else {
                        smaliSb.append("    const v$regAA, ...\n")
                    }
                    pc += 3
                }
                0x1a -> {
                    if (insnOff + 4 <= bytes.size) {
                        val strIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val str = stringTable.getOrElse(strIdx) { "str_$strIdx" }
                        stringVars[regAA] = "\"$str\""
                        smaliSb.append("    const-string v$regAA, \"$str\"\n")
                        javaSb.append("    String v$regAA = \"$str\";\n")
                    } else {
                        smaliSb.append("    const-string v$regAA, ...\n")
                    }
                    pc += 2
                }
                0x1b -> {
                    if (insnOff + 6 <= bytes.size) {
                        val strIdx = buffer.getInt(insnOff + 2)
                        val str = stringTable.getOrElse(strIdx) { "str_$strIdx" }
                        stringVars[regAA] = "\"$str\""
                        smaliSb.append("    const-string/jumbo v$regAA, \"$str\"\n")
                        javaSb.append("    String v$regAA = \"$str\";\n")
                    } else {
                        smaliSb.append("    const-string/jumbo v$regAA, ...\n")
                    }
                    pc += 3
                }
                0x1c -> {
                    if (insnOff + 4 <= bytes.size) {
                        val typeIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val typeName = formatDescriptor(typeTable.getOrElse(typeIdx) { "Ljava/lang/Object;" })
                        smaliSb.append("    const-class v$regAA, L${typeName.replace('.', '/')};\n")
                        javaSb.append("    Class v$regAA = $typeName.class;\n")
                    } else {
                        smaliSb.append("    const-class v$regAA, ...\n")
                    }
                    pc += 2
                }
                0x1f -> {
                    if (insnOff + 4 <= bytes.size) {
                        val typeIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val typeName = formatDescriptor(typeTable.getOrElse(typeIdx) { "Ljava/lang/Object;" })
                        smaliSb.append("    check-cast v$regAA, $typeName\n")
                        javaSb.append("    v$regAA = ($typeName) v$regAA;\n")
                    } else {
                        smaliSb.append("    check-cast v$regAA, ...\n")
                    }
                    pc += 2
                }
                0x22 -> {
                    if (insnOff + 4 <= bytes.size) {
                        val typeIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val typeName = formatDescriptor(typeTable.getOrElse(typeIdx) { "Ljava/lang/Object;" })
                        smaliSb.append("    new-instance v$regAA, L${typeName.replace('.', '/')};\n")
                        javaSb.append("    $typeName v$regAA = new $typeName();\n")
                    } else {
                        smaliSb.append("    new-instance v$regAA, ...\n")
                    }
                    pc += 2
                }
                0x27 -> {
                    smaliSb.append("    throw v$regAA\n")
                    javaSb.append("    throw v$regAA;\n")
                    pc += 1
                }
                0x28 -> {
                    val offset = (unit0 shl 16) shr 24
                    smaliSb.append("    goto :goto_${pc + offset}\n")
                    javaSb.append("    goto :goto_${pc + offset};\n")
                    pc += 1
                }
                in 0x32..0x37 -> {
                    if (insnOff + 4 <= bytes.size) {
                        val offset = buffer.getShort(insnOff + 2).toInt()
                        val mnem = when (opcode) {
                            0x32 -> "if-eq"
                            0x33 -> "if-ne"
                            0x34 -> "if-lt"
                            0x35 -> "if-ge"
                            0x36 -> "if-gt"
                            else -> "if-le"
                        }
                        val opSymbol = when (opcode) {
                            0x32 -> "=="
                            0x33 -> "!="
                            0x34 -> "<"
                            0x35 -> ">="
                            0x36 -> ">"
                            else -> "<="
                        }
                        smaliSb.append("    $mnem v$regA, v$regB, :cond_${pc + offset}\n")
                        javaSb.append("    if (v$regA $opSymbol v$regB) goto :cond_${pc + offset};\n")
                    } else {
                        smaliSb.append("    if-test v$regA, v$regB, ...\n")
                    }
                    pc += 2
                }
                in 0x38..0x3d -> {
                    if (insnOff + 4 <= bytes.size) {
                        val offset = buffer.getShort(insnOff + 2).toInt()
                        val mnem = when (opcode) {
                            0x38 -> "if-eqz"
                            0x39 -> "if-nez"
                            0x3a -> "if-ltz"
                            0x3b -> "if-gez"
                            0x3c -> "if-gtz"
                            else -> "if-lez"
                        }
                        val opSymbol = when (opcode) {
                            0x38 -> "=="
                            0x39 -> "!="
                            0x3a -> "<"
                            0x3b -> ">="
                            0x3c -> ">"
                            else -> "<="
                        }
                        smaliSb.append("    $mnem v$regAA, :cond_${pc + offset}\n")
                        javaSb.append("    if (v$regAA $opSymbol 0) goto :cond_${pc + offset};\n")
                    } else {
                        smaliSb.append("    if-testz v$regAA, ...\n")
                    }
                    pc += 2
                }
                in 0x52..0x5f -> {
                    if (insnOff + 4 <= bytes.size) {
                        val fieldIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val fieldItem = fieldTable.getOrNull(fieldIdx)
                        val fieldName = fieldItem?.name ?: "field$fieldIdx"
                        val isIget = opcode <= 0x58
                        val opStr = if (isIget) "iget" else "iput"
                        smaliSb.append("    $opStr v$regA, v$regB, ${fieldItem?.classType}->$fieldName:${fieldItem?.fieldType}\n")
                        if (isIget) {
                            javaSb.append("    v$regA = v$regB.$fieldName;\n")
                        } else {
                            javaSb.append("    v$regB.$fieldName = v$regA;\n")
                        }
                    } else {
                        smaliSb.append("    iget/iput v$regA, v$regB, ...\n")
                    }
                    pc += 2
                }
                in 0x60..0x6d -> {
                    if (insnOff + 4 <= bytes.size) {
                        val fieldIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val fieldItem = fieldTable.getOrNull(fieldIdx)
                        val fieldName = fieldItem?.name ?: "field$fieldIdx"
                        val isSget = opcode <= 0x66
                        val opStr = if (isSget) "sget" else "sput"
                        smaliSb.append("    $opStr v$regAA, ${fieldItem?.classType}->$fieldName:${fieldItem?.fieldType}\n")
                        if (isSget) {
                            javaSb.append("    v$regAA = ${fieldItem?.classType?.substringAfterLast('.')}.$fieldName;\n")
                        } else {
                            javaSb.append("    ${fieldItem?.classType?.substringAfterLast('.')}.$fieldName = v$regAA;\n")
                        }
                    } else {
                        smaliSb.append("    sget/sput v$regAA, ...\n")
                    }
                    pc += 2
                }
                0x6e, 0x6f, 0x70, 0x71, 0x72 -> {
                    if (insnOff + 6 <= bytes.size) {
                        val methIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val count = (unit0 shr 12) and 0x0f
                        val unit2 = buffer.getShort(insnOff + 4).toInt() and 0xffff
                        val c = unit2 and 0x0f
                        val d = (unit2 shr 4) and 0x0f
                        val e = (unit2 shr 8) and 0x0f
                        val f = (unit2 shr 12) and 0x0f
                        val g = regA

                        val regs = when (count) {
                            1 -> "v$c"
                            2 -> "v$c, v$d"
                            3 -> "v$c, v$d, v$e"
                            4 -> "v$c, v$d, v$e, v$f"
                            5 -> "v$c, v$d, v$e, v$f, v$g"
                            else -> ""
                        }

                        val targetMeth = methodTable.getOrNull(methIdx)
                        val targetClass = targetMeth?.classType ?: "Ljava/lang/Object;"
                        val targetName = targetMeth?.name ?: "method$methIdx"
                        val targetParams = targetMeth?.paramTypes?.joinToString(",") ?: ""
                        val targetRet = targetMeth?.returnType ?: "V"

                        val mnem = when (opcode) {
                            0x6e -> "invoke-virtual"
                            0x6f -> "invoke-super"
                            0x70 -> "invoke-direct"
                            0x71 -> "invoke-static"
                            else -> "invoke-interface"
                        }

                        val fullDescriptor = "L${targetClass.replace('.', '/')};->$targetName($targetParams)$targetRet"
                        calledMethods.add(fullDescriptor)

                        smaliSb.append("    $mnem {$regs}, $fullDescriptor\n")

                        val argList = if (count > 1) {
                            (1 until count).joinToString(", ") { idx ->
                                val regNum = when (idx) { 1 -> d; 2 -> e; 3 -> f; else -> g }
                                stringVars[regNum] ?: "v$regNum"
                            }
                        } else ""

                        val objRef = if (count > 0) "v$c" else targetClass.substringAfterLast('.')
                        javaSb.append("    $objRef.$targetName($argList);\n")
                    } else {
                        smaliSb.append("    invoke-kind { ... }, meth\n")
                    }
                    pc += 3
                }
                0x74, 0x75, 0x76, 0x77, 0x78 -> {
                    if (insnOff + 6 <= bytes.size) {
                        val methIdx = buffer.getShort(insnOff + 2).toInt() and 0xffff
                        val count = regAA
                        val startReg = buffer.getShort(insnOff + 4).toInt() and 0xffff
                        val endReg = startReg + count - 1

                        val targetMeth = methodTable.getOrNull(methIdx)
                        val targetClass = targetMeth?.classType ?: "Ljava/lang/Object;"
                        val targetName = targetMeth?.name ?: "method$methIdx"

                        val fullDescriptor = "L${targetClass.replace('.', '/')};->$targetName"
                        calledMethods.add(fullDescriptor)

                        smaliSb.append("    invoke-range {v$startReg .. v$endReg}, $fullDescriptor\n")
                        javaSb.append("    v$startReg.$targetName(...);\n")
                    } else {
                        smaliSb.append("    invoke-range { ... }\n")
                    }
                    pc += 3
                }
                else -> {
                    smaliSb.append(String.format("    op_0x%02x\n", opcode))
                    pc += 1
                }
            }
        }

        smaliSb.append(".end method\n")
        javaSb.append("}\n")

        return DalvikCodeResult(
            smaliCode = smaliSb.toString(),
            javaCode = javaSb.toString(),
            calledMethods = calledMethods.distinct()
        )
    }
}
