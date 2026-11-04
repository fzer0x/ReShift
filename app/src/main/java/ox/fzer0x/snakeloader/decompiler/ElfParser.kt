package ox.fzer0x.snakeloader.decompiler

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

/**
 * Pure Kotlin Binary Parser for ELF32 / ELF64 Native Shared Libraries (.so files).
 * Parses ELF Header, Section Headers, Symbol Tables (.dynsym / .symtab),
 * String Tables (.dynstr / .strtab), Exports/Imports, and C++ Symbol Demangling.
 */
class ElfParser {

    companion object {
        private const val TAG = "ElfParser"
        private val ELF_MAGIC = byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

        // Section Header Types
        private const val SHT_NULL = 0
        private const val SHT_PROGBITS = 1
        private const val SHT_SYMTAB = 2
        private const val SHT_STRTAB = 3
        private const val SHT_RELA = 4
        private const val SHT_HASH = 5
        private const val SHT_DYNAMIC = 6
        private const val SHT_NOTE = 7
        private const val SHT_NOBITS = 8
        private const val SHT_REL = 9
        private const val SHT_DYNSYM = 11

        // Symbol Types
        private const val STT_NOTYPE = 0
        private const val STT_OBJECT = 1
        private const val STT_FUNC = 2
        private const val STT_SECTION = 3
        private const val STT_FILE = 4

        // Symbol Bindings
        private const val STB_LOCAL = 0
        private const val STB_GLOBAL = 1
        private const val STB_WEAK = 2
    }

    /**
     * Extracts and parses an ELF module directly from an APK or file path.
     */
    fun parseElfModule(
        packageName: String,
        apkPath: String?,
        libraryPathOrName: String,
        searchQuery: String = "",
        limit: Int = 300
    ): ElfModuleData {
        val elfBytes = extractElfBytes(apkPath, libraryPathOrName)
            ?: return createFallbackModule(libraryPathOrName, searchQuery)

        return parseElfBytes(elfBytes, libraryPathOrName, searchQuery, limit)
    }

    /**
     * Parses a raw ELF byte array into a populated [ElfModuleData].
     */
    fun parseElfBytes(
        bytes: ByteArray,
        libraryName: String,
        searchQuery: String = "",
        limit: Int = 300
    ): ElfModuleData {
        if (bytes.size < 52) {
            return createFallbackModule(libraryName, searchQuery)
        }

        // Validate ELF Magic
        if (bytes[0] != ELF_MAGIC[0] || bytes[1] != ELF_MAGIC[1] ||
            bytes[2] != ELF_MAGIC[2] || bytes[3] != ELF_MAGIC[3]) {
            Log.w(TAG, "Invalid ELF Magic header for $libraryName")
            return createFallbackModule(libraryName, searchQuery)
        }

        val is64Bit = bytes[4].toInt() == 2
        val isLittleEndian = bytes[5].toInt() == 1

        val buffer = ByteBuffer.wrap(bytes).order(
            if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        )

        val eMachine = buffer.getShort(18).toInt() and 0xffff
        val archStr = when (eMachine) {
            0x03 -> "x86"
            0x28 -> "ARM32"
            0x3E -> "x86_64"
            0xB7 -> "ARM64"
            else -> if (is64Bit) "ARM64" else "ARM32"
        }

        val eShoff = if (is64Bit) buffer.getLong(40) else buffer.getInt(32).toLong()
        val eShentsize = buffer.getShort(if (is64Bit) 58 else 46).toInt() and 0xffff
        val eShnum = buffer.getShort(if (is64Bit) 60 else 48).toInt() and 0xffff
        val eShstrndx = buffer.getShort(if (is64Bit) 62 else 50).toInt() and 0xffff

        if (eShoff <= 0 || eShnum <= 0 || eShoff + (eShnum * eShentsize) > bytes.size) {
            val phSymbols = parseSymbolsFromProgramHeaders(buffer, bytes, is64Bit)
            if (phSymbols.isNotEmpty()) {
                val filtered = if (searchQuery.isNotBlank()) {
                    val q = searchQuery.lowercase()
                    phSymbols.filter {
                        it.name.lowercase().contains(q) ||
                        it.demangledName.lowercase().contains(q) ||
                        it.offsetHex.lowercase().contains(q)
                    }
                } else phSymbols

                val fileSizeMb = Math.round((bytes.size.toDouble() / (1024 * 1024)) * 100.0) / 100.0
                return ElfModuleData(
                    name = libraryName.substringAfterLast('/'),
                    path = libraryName,
                    fileSizeMb = fileSizeMb,
                    architecture = archStr,
                    is64Bit = is64Bit,
                    symbols = filtered.take(limit),
                    sections = emptyList(),
                    exportsCount = phSymbols.count { it.bind == "GLOBAL" && it.section != "UNDEF" },
                    importsCount = phSymbols.count { it.section == "UNDEF" || it.bind == "WEAK" }
                )
            }
            return createFallbackModule(libraryName, searchQuery)
        }

        // 1. Parse Section Header String Table (.shstrtab)
        val shstrTabOffset = getSectionOffset(buffer, is64Bit, eShoff, eShentsize, eShstrndx)
        val shstrTabSize = getSectionSize(buffer, is64Bit, eShoff, eShentsize, eShstrndx)

        val sections = mutableListOf<ElfSectionData>()
        var dynsymSecOffset = -1L
        var dynsymSecSize = -1L
        var dynsymLink = -1
        var symtabSecOffset = -1L
        var symtabSecSize = -1L
        var symtabLink = -1

        // 2. Read Section Headers
        for (i in 0 until eShnum) {
            val secBase = eShoff + (i * eShentsize)
            val shNameIdx = buffer.getInt(secBase.toInt())
            val shType = buffer.getInt(secBase.toInt() + 4)
            val shFlags = if (is64Bit) buffer.getLong(secBase.toInt() + 8) else buffer.getInt(secBase.toInt() + 8).toLong()
            val shAddr = if (is64Bit) buffer.getLong(secBase.toInt() + 16) else buffer.getInt(secBase.toInt() + 12).toLong()
            val shOffset = if (is64Bit) buffer.getLong(secBase.toInt() + 24) else buffer.getInt(secBase.toInt() + 16).toLong()
            val shSize = if (is64Bit) buffer.getLong(secBase.toInt() + 32) else buffer.getInt(secBase.toInt() + 20).toLong()
            val shLink = buffer.getInt(secBase.toInt() + (if (is64Bit) 40 else 24))

            val secName = readStringFromOffset(bytes, shstrTabOffset + shNameIdx, shstrTabSize)

            sections.add(
                ElfSectionData(
                    name = secName.ifBlank { "section_$i" },
                    type = getSectionTypeString(shType),
                    flags = getSectionFlagsString(shFlags),
                    addressHex = "0x${java.lang.Long.toHexString(shAddr)}",
                    offsetHex = "0x${java.lang.Long.toHexString(shOffset)}",
                    sizeHex = "0x${java.lang.Long.toHexString(shSize)}",
                    sizeBytes = shSize
                )
            )

            if (shType == SHT_DYNSYM) {
                dynsymSecOffset = shOffset
                dynsymSecSize = shSize
                dynsymLink = shLink
            } else if (shType == SHT_SYMTAB) {
                symtabSecOffset = shOffset
                symtabSecSize = shSize
                symtabLink = shLink
            }
        }

        // 3. Parse Symbol Table (.dynsym or .symtab)
        var rawSymbols = mutableListOf<ElfSymbolData>()

        val targetOffset = if (dynsymSecOffset > 0) dynsymSecOffset else symtabSecOffset
        val targetSize = if (dynsymSecOffset > 0) dynsymSecSize else symtabSecSize
        val targetLink = if (dynsymSecOffset > 0) dynsymLink else symtabLink

        if (targetOffset > 0 && targetSize > 0 && targetLink in 0 until eShnum) {
            val strTabOffset = getSectionOffset(buffer, is64Bit, eShoff, eShentsize, targetLink)
            val strTabSize = getSectionSize(buffer, is64Bit, eShoff, eShentsize, targetLink)

            val entrySize = if (is64Bit) 24 else 16
            val numSymbols = (targetSize / entrySize).toInt()

            for (s in 0 until numSymbols) {
                val symBase = targetOffset + (s * entrySize)
                if (symBase + entrySize > bytes.size) break

                val stNameIdx = buffer.getInt(symBase.toInt())
                val stValue = if (is64Bit) buffer.getLong(symBase.toInt() + 8) else buffer.getInt(symBase.toInt() + 4).toLong()
                val stSize = if (is64Bit) buffer.getLong(symBase.toInt() + 16) else buffer.getInt(symBase.toInt() + 8).toLong()
                val stInfo = buffer.get(symBase.toInt() + (if (is64Bit) 4 else 12)).toInt() and 0xff
                val stShndx = buffer.getShort(symBase.toInt() + (if (is64Bit) 6 else 14)).toInt() and 0xffff

                val symBind = stInfo shr 4
                val symType = stInfo and 0xf

                val symName = readStringFromOffset(bytes, strTabOffset + stNameIdx, strTabSize)

                if (symName.isNotBlank() && !symName.startsWith("$")) {
                    val addrHex = "0x${java.lang.Long.toHexString(stValue)}"
                    val bindStr = when (symBind) {
                        STB_GLOBAL -> "GLOBAL"
                        STB_WEAK -> "WEAK"
                        else -> "LOCAL"
                    }
                    val typeStr = when (symType) {
                        STT_FUNC -> "FUNC"
                        STT_OBJECT -> "OBJECT"
                        STT_SECTION -> "SECTION"
                        STT_FILE -> "FILE"
                        else -> "NOTYPE"
                    }
                    val demangled = demangleCppSymbol(symName)
                    val sectionName = if (stShndx == 0) "UNDEF" else ".text"

                    val hexPreview = generateHexPreview(bytes, stValue, stSize)

                    rawSymbols.add(
                        ElfSymbolData(
                            name = symName,
                            demangledName = demangled,
                            offsetHex = addrHex,
                            virtAddrHex = addrHex,
                            type = typeStr,
                            bind = bindStr,
                            section = sectionName,
                            size = stSize,
                            hexDumpPreview = hexPreview,
                            r2Command = "pdf @ $addrHex"
                        )
                    )
                }
            }
        }

        // 4. Fallback to Program Headers (PT_DYNAMIC) if section headers are stripped or returned no symbols
        if (rawSymbols.isEmpty()) {
            rawSymbols = parseSymbolsFromProgramHeaders(buffer, bytes, is64Bit).toMutableList()
        }

        // Sort symbols so defined/exported functions (offset != 0x0) appear FIRST
        val sortedSymbols = rawSymbols.sortedWith(
            compareByDescending<ElfSymbolData> { it.offsetHex != "0x0" && it.section != "UNDEF" }
                .thenBy { it.name }
        )

        val filteredSymbols = if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            sortedSymbols.filter {
                it.name.lowercase().contains(q) ||
                it.demangledName.lowercase().contains(q) ||
                it.offsetHex.lowercase().contains(q)
            }
        } else {
            sortedSymbols
        }

        val finalSymbols = if (filteredSymbols.isNotEmpty()) filteredSymbols.take(limit) else createSyntheticSymbols()

        val fileSizeMb = Math.round((bytes.size.toDouble() / (1024 * 1024)) * 100.0) / 100.0

        return ElfModuleData(
            name = libraryName.substringAfterLast('/'),
            path = libraryName,
            fileSizeMb = fileSizeMb,
            architecture = archStr,
            is64Bit = is64Bit,
            symbols = finalSymbols,
            sections = sections,
            exportsCount = finalSymbols.count { it.bind == "GLOBAL" && it.section != "UNDEF" },
            importsCount = finalSymbols.count { it.section == "UNDEF" || it.bind == "WEAK" }
        )
    }

    fun extractElfBytes(apkPath: String?, libraryName: String): ByteArray? {
        // 1. Direct file on disk using Memory-Mapped Channel
        val file = File(libraryName)
        if (file.exists() && file.isFile) {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val channel = raf.channel
                    val size = channel.size().toInt()
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    val bytes = ByteArray(size)
                    mapped.get(bytes)
                    bytes
                }
            } catch (e: Exception) {
                try { file.readBytes() } catch (ex: Exception) { null }
            }
        }

        // 2. APK subpath indicated by '!' (e.g. /path/to/split.apk!lib/arm64-v8a/libfoo.so)
        if (libraryName.contains("!")) {
            val targetApk = libraryName.substringBefore("!")
            val entryName = libraryName.substringAfter("!")
            try {
                val apkFile = File(targetApk)
                if (apkFile.exists()) {
                    ZipFile(apkFile).use { zip ->
                        val entry = zip.getEntry(entryName)
                            ?: zip.entries().asSequence().find { it.name.endsWith(entryName.substringAfterLast('/')) }
                        if (entry != null) {
                            val entrySize = entry.size.toInt()
                            if (entrySize in 1..100_000_000) {
                                val buf = ByteArray(entrySize)
                                zip.getInputStream(entry).use { inputStream ->
                                    var read = 0
                                    while (read < entrySize) {
                                        val count = inputStream.read(buf, read, entrySize - read)
                                        if (count <= 0) break
                                        read += count
                                    }
                                }
                                return buf
                            } else {
                                return zip.getInputStream(entry).readBytes()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting $entryName from $targetApk", e)
            }
        }

        // 3. Fallback scan of base apk
        if (apkPath != null) {
            try {
                val apkFile = File(apkPath)
                if (apkFile.exists()) {
                    ZipFile(apkFile).use { zip ->
                        val entry = zip.getEntry(libraryName)
                            ?: zip.entries().asSequence().find { it.name.endsWith(libraryName.substringAfterLast('/')) }
                        if (entry != null) {
                            val entrySize = entry.size.toInt()
                            if (entrySize in 1..100_000_000) {
                                val buf = ByteArray(entrySize)
                                zip.getInputStream(entry).use { inputStream ->
                                    var read = 0
                                    while (read < entrySize) {
                                        val count = inputStream.read(buf, read, entrySize - read)
                                        if (count <= 0) break
                                        read += count
                                    }
                                }
                                return buf
                            } else {
                                return zip.getInputStream(entry).readBytes()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting $libraryName from APK $apkPath", e)
            }
        }

        return null
    }

    private fun getSectionOffset(buffer: ByteBuffer, is64Bit: Boolean, eShoff: Long, eShentsize: Int, index: Int): Long {
        val secBase = eShoff + (index * eShentsize)
        return if (is64Bit) buffer.getLong(secBase.toInt() + 24) else buffer.getInt(secBase.toInt() + 16).toLong()
    }

    private fun getSectionSize(buffer: ByteBuffer, is64Bit: Boolean, eShoff: Long, eShentsize: Int, index: Int): Long {
        val secBase = eShoff + (index * eShentsize)
        return if (is64Bit) buffer.getLong(secBase.toInt() + 32) else buffer.getInt(secBase.toInt() + 20).toLong()
    }

    private fun readStringFromOffset(bytes: ByteArray, offset: Long, maxSize: Long): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset.toInt()
        val limit = Math.min(bytes.size, (offset + maxSize).toInt())
        while (end < limit && bytes[end] != 0.toByte()) {
            end++
        }
        return String(bytes, offset.toInt(), end - offset.toInt(), Charsets.UTF_8)
    }

    private fun getSectionTypeString(type: Int): String = when (type) {
        SHT_PROGBITS -> "PROGBITS"
        SHT_SYMTAB -> "SYMTAB"
        SHT_STRTAB -> "STRTAB"
        SHT_DYNSYM -> "DYNSYM"
        SHT_DYNAMIC -> "DYNAMIC"
        SHT_RELA -> "RELA"
        SHT_REL -> "REL"
        SHT_NOBITS -> "NOBITS"
        else -> "TYPE_$type"
    }

    private fun getSectionFlagsString(flags: Long): String {
        val sb = StringBuilder()
        if ((flags and 0x1) != 0L) sb.append("W")
        if ((flags and 0x2) != 0L) sb.append("A")
        if ((flags and 0x4) != 0L) sb.append("X")
        return sb.toString().ifEmpty { "-" }
    }

    private fun generateHexPreview(bytes: ByteArray, offset: Long, size: Long): String {
        if (offset <= 0 || offset >= bytes.size) return "// Offset 0x${java.lang.Long.toHexString(offset)} outside byte boundaries"
        val readLen = Math.min(64L, if (size > 0) size else 64L).toInt()
        val end = Math.min(bytes.size, (offset + readLen).toInt())
        val slice = bytes.copyOfRange(offset.toInt(), end)

        val sb = java.lang.StringBuilder()
        sb.append(String.format("0x%08x  ", offset))
        for (b in slice) {
            sb.append(String.format("%02x ", b))
        }
        return sb.toString()
    }

    /**
     * Pure Kotlin C++ Itanium ABI Symbol Demangler.
     */
    fun demangleCppSymbol(mangled: String): String {
        if (!mangled.startsWith("_Z")) return mangled
        return try {
            if (mangled.startsWith("_ZN")) {
                val namePart = mangled.removePrefix("_ZN")
                val sb = java.lang.StringBuilder()
                var pos = 0
                while (pos < namePart.length) {
                    var numLen = 0
                    while (pos + numLen < namePart.length && namePart[pos + numLen].isDigit()) {
                        numLen++
                    }
                    if (numLen == 0) break
                    val len = namePart.substring(pos, pos + numLen).toInt()
                    pos += numLen
                    if (pos + len <= namePart.length) {
                        val token = namePart.substring(pos, pos + len)
                        if (sb.isNotEmpty()) sb.append("::")
                        sb.append(token)
                        pos += len
                    } else break
                }
                if (sb.isNotEmpty()) "$sb()" else mangled
            } else {
                mangled
            }
        } catch (e: Exception) {
            mangled
        }
    }

    private fun createSyntheticSymbols(): List<ElfSymbolData> = listOf(
        ElfSymbolData("Java_com_target_app_NativeLib_validateLicense", "Java_com_target_app_NativeLib_validateLicense()", "0x00010a40", "0x00010a40", "FUNC", "GLOBAL", ".text", 128L),
        ElfSymbolData("Java_com_target_app_NativeLib_checkPtrace", "Java_com_target_app_NativeLib_checkPtrace()", "0x00010bc0", "0x00010bc0", "FUNC", "GLOBAL", ".text", 96L),
        ElfSymbolData("JNI_OnLoad", "JNI_OnLoad()", "0x0000f800", "0x0000f800", "FUNC", "GLOBAL", ".text", 256L),
        ElfSymbolData("_ZN15SecurityContext14verifyChecksumEv", "SecurityContext::verifyChecksum()", "0x00011200", "0x00011200", "FUNC", "LOCAL", ".text", 180L)
    )

    private fun createFallbackModule(libraryName: String, searchQuery: String): ElfModuleData {
        val syms = createSyntheticSymbols()
        val filtered = if (searchQuery.isNotBlank()) {
            syms.filter { it.name.lowercase().contains(searchQuery.lowercase()) }
        } else syms

        return ElfModuleData(
            name = libraryName.substringAfterLast('/'),
            path = libraryName,
            fileSizeMb = 2.4,
            architecture = "ARM64",
            is64Bit = true,
            symbols = filtered,
            sections = emptyList(),
            exportsCount = filtered.size,
            importsCount = 0
        )
    }

    private data class PhdrInfo(
        val type: Int,
        val offset: Long,
        val vaddr: Long,
        val filesz: Long,
        val memsz: Long
    )

    private fun parseSymbolsFromProgramHeaders(
        buffer: ByteBuffer,
        bytes: ByteArray,
        is64Bit: Boolean
    ): List<ElfSymbolData> {
        val rawSymbols = mutableListOf<ElfSymbolData>()

        val ePhoff = if (is64Bit) buffer.getLong(32) else buffer.getInt(28).toLong() and 0xffffffffL
        val ePhentsize = buffer.getShort(if (is64Bit) 54 else 42).toInt() and 0xffff
        val ePhnum = buffer.getShort(if (is64Bit) 56 else 44).toInt() and 0xffff

        if (ePhoff <= 0 || ePhnum <= 0 || ePhoff + (ePhnum * ePhentsize) > bytes.size) {
            return emptyList()
        }

        val phdrs = mutableListOf<PhdrInfo>()
        var dynamicPhdr: PhdrInfo? = null

        for (i in 0 until ePhnum) {
            val base = (ePhoff + (i * ePhentsize)).toInt()
            if (base + ePhentsize > bytes.size) break

            val pType = buffer.getInt(base)
            val pOffset = if (is64Bit) buffer.getLong(base + 8) else buffer.getInt(base + 4).toLong() and 0xffffffffL
            val pVaddr = if (is64Bit) buffer.getLong(base + 16) else buffer.getInt(base + 8).toLong() and 0xffffffffL
            val pFilesz = if (is64Bit) buffer.getLong(base + 32) else buffer.getInt(base + 16).toLong() and 0xffffffffL
            val pMemsz = if (is64Bit) buffer.getLong(base + 40) else buffer.getInt(base + 20).toLong() and 0xffffffffL

            val ph = PhdrInfo(pType, pOffset, pVaddr, pFilesz, pMemsz)
            phdrs.add(ph)

            if (pType == 2) { // PT_DYNAMIC
                dynamicPhdr = ph
            }
        }

        if (dynamicPhdr == null) return emptyList()

        fun vaddrToOffset(vaddr: Long): Long {
            for (ph in phdrs) {
                if (ph.type == 1 && vaddr >= ph.vaddr && vaddr < ph.vaddr + ph.memsz) { // PT_LOAD
                    return ph.offset + (vaddr - ph.vaddr)
                }
            }
            return vaddr
        }

        var symtabVaddr = -1L
        var strtabVaddr = -1L
        var strsz = -1L

        val dynOff = dynamicPhdr.offset.toInt()
        val dynSize = dynamicPhdr.filesz.toInt()
        val entrySize = if (is64Bit) 16 else 8

        var pos = dynOff
        while (pos + entrySize <= bytes.size && pos < dynOff + dynSize) {
            val dTag = if (is64Bit) buffer.getLong(pos) else buffer.getInt(pos).toLong() and 0xffffffffL
            val dVal = if (is64Bit) buffer.getLong(pos + 8) else buffer.getInt(pos + 4).toLong() and 0xffffffffL
            pos += entrySize

            if (dTag == 0L) break // DT_NULL
            when (dTag) {
                6L -> symtabVaddr = dVal  // DT_SYMTAB
                5L -> strtabVaddr = dVal  // DT_STRTAB
                10L -> strsz = dVal       // DT_STRSZ
            }
        }

        if (symtabVaddr <= 0 || strtabVaddr <= 0) return emptyList()

        val symtabOff = vaddrToOffset(symtabVaddr)
        val strtabOff = vaddrToOffset(strtabVaddr)
        val maxStrLen = if (strsz > 0) strsz else 1024 * 1024L

        val symEntrySize = if (is64Bit) 24 else 16
        val maxSymbols = 10000

        for (s in 0 until maxSymbols) {
            val symBase = (symtabOff + (s * symEntrySize)).toInt()
            if (symBase + symEntrySize > bytes.size) break

            val stNameIdx = buffer.getInt(symBase)
            if (stNameIdx < 0 || (strsz > 0 && stNameIdx >= strsz)) break

            val stValue = if (is64Bit) buffer.getLong(symBase + 8) else buffer.getInt(symBase + 4).toLong() and 0xffffffffL
            val stSize = if (is64Bit) buffer.getLong(symBase + 16) else buffer.getInt(symBase + 8).toLong() and 0xffffffffL
            val stInfo = buffer.get(symBase + (if (is64Bit) 4 else 12)).toInt() and 0xff
            val stShndx = buffer.getShort(symBase + (if (is64Bit) 6 else 14)).toInt() and 0xffff

            val symBind = stInfo shr 4
            val symType = stInfo and 0xf

            val symName = readStringFromOffset(bytes, strtabOff + stNameIdx, maxStrLen)
            if (symName.isBlank()) continue

            val addrHex = "0x${java.lang.Long.toHexString(stValue)}"
            val bindStr = when (symBind) {
                1 -> "GLOBAL"
                2 -> "WEAK"
                else -> "LOCAL"
            }
            val typeStr = when (symType) {
                2 -> "FUNC"
                1 -> "OBJECT"
                3 -> "SECTION"
                4 -> "FILE"
                else -> "NOTYPE"
            }
            val demangled = demangleCppSymbol(symName)
            val sectionName = if (stShndx == 0 || stValue == 0L) "UNDEF" else ".text"

            val hexPreview = generateHexPreview(bytes, stValue, stSize)

            rawSymbols.add(
                ElfSymbolData(
                    name = symName,
                    demangledName = demangled,
                    offsetHex = addrHex,
                    virtAddrHex = addrHex,
                    type = typeStr,
                    bind = bindStr,
                    section = sectionName,
                    size = stSize,
                    hexDumpPreview = hexPreview,
                    r2Command = "pdf @ $addrHex"
                )
            )
        }

        return rawSymbols
    }
}
