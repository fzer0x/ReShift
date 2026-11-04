package ox.fzer0x.snakeloader.decompiler

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin ARM64 (AArch64) Lightweight Disassembler & Pseudo-C Code Generator.
 * Decodes 32-bit ARM64 machine instructions into assembly mnemonics, operands,
 * PC-relative branch addresses, and high-level C-like pseudo-code representations.
 */
class Arm64Disassembler {

    /**
     * Disassembles a raw byte array starting at specified address into [DisassemblyLine] objects.
     */
    fun disassembleArm64(bytes: ByteArray, baseAddress: Long, maxInstructions: Int = 128): List<DisassemblyLine> {
        val lines = mutableListOf<DisassemblyLine>()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var pc = baseAddress
        var count = 0

        while (buffer.remaining() >= 4 && count < maxInstructions) {
            val insOffset = buffer.position()
            val ins = buffer.getInt()
            val insAddressHex = "0x${java.lang.Long.toHexString(pc)}"
            val bytesHex = String.format("%02X %02X %02X %02X", bytes[insOffset], bytes[insOffset + 1], bytes[insOffset + 2], bytes[insOffset + 3])

            val line = decodeInstruction(ins, pc, insAddressHex, bytesHex)
            lines.add(line)

            pc += 4
            count++
        }

        if (lines.isEmpty()) {
            lines.add(
                DisassemblyLine(
                    addressHex = "0x${java.lang.Long.toHexString(baseAddress)}",
                    bytesHex = "00 00 00 00",
                    mnemonic = "NOP",
                    operands = "",
                    comment = "// Empty or unreadable function body"
                )
            )
        }

        return lines
    }

    /**
     * Decodes a single 32-bit ARM64 instruction word into [DisassemblyLine].
     */
    fun decodeInstruction(ins: Int, pc: Long, addressHex: String, bytesHex: String): DisassemblyLine {
        // RET (0xD65F03C0)
        if (ins == 0xD65F03C0.toInt()) {
            return DisassemblyLine(addressHex, bytesHex, "RET", "", comment = "// Function Return")
        }

        // NOP (0xD503201F)
        if (ins == 0xD503201F.toInt()) {
            return DisassemblyLine(addressHex, bytesHex, "NOP", "", comment = null)
        }

        // BL imm26 (0x94000000, mask 0xFC000000)
        if ((ins and 0xFC000000.toInt()) == 0x94000000.toInt()) {
            val imm26 = signExtend(ins and 0x03FFFFFF, 26)
            val targetPc = pc + (imm26 * 4)
            val targetHex = "0x${java.lang.Long.toHexString(targetPc)}"
            return DisassemblyLine(
                addressHex, bytesHex, "BL", targetHex,
                comment = "// Call function at $targetHex",
                branchTargetAddress = targetHex
            )
        }

        // B imm26 (0x14000000, mask 0xFC000000)
        if ((ins and 0xFC000000.toInt()) == 0x14000000.toInt()) {
            val imm26 = signExtend(ins and 0x03FFFFFF, 26)
            val targetPc = pc + (imm26 * 4)
            val targetHex = "0x${java.lang.Long.toHexString(targetPc)}"
            return DisassemblyLine(
                addressHex, bytesHex, "B", targetHex,
                comment = "// Branch to $targetHex",
                branchTargetAddress = targetHex
            )
        }

        // B.cond imm19 (0x54000000, mask 0xFF000010 == 0x54000000)
        if ((ins and 0xFF000010.toInt()) == 0x54000000.toInt()) {
            val cond = ins and 0xF
            val imm19 = signExtend((ins shr 5) and 0x7FFFF, 19)
            val targetPc = pc + (imm19 * 4)
            val targetHex = "0x${java.lang.Long.toHexString(targetPc)}"
            val condStr = getConditionString(cond)
            return DisassemblyLine(
                addressHex, bytesHex, "B.$condStr", targetHex,
                comment = "// Branch if $condStr to $targetHex",
                isBranchTarget = true,
                branchTargetAddress = targetHex
            )
        }

        // CBZ / CBNZ xT, imm19
        if ((ins and 0x7E000000.toInt()) == 0x34000000.toInt() || (ins and 0x7E000000.toInt()) == 0x35000000.toInt()) {
            val isCbnz = (ins and 0x01000000) != 0
            val is64Bit = (ins and 0x80000000.toInt()) != 0
            val rt = ins and 0x1F
            val imm19 = signExtend((ins shr 5) and 0x7FFFF, 19)
            val targetPc = pc + (imm19 * 4)
            val targetHex = "0x${java.lang.Long.toHexString(targetPc)}"
            val regName = if (is64Bit) "x$rt" else "w$rt"
            val mnem = if (isCbnz) "CBNZ" else "CBZ"
            return DisassemblyLine(
                addressHex, bytesHex, mnem, "$regName, $targetHex",
                comment = "// Branch if $regName ${if (isCbnz) "!= 0" else "== 0"}",
                branchTargetAddress = targetHex
            )
        }

        // ADRP xd, imm (0x90000000, mask 0x9F000000 == 0x90000000)
        if ((ins and 0x9F000000.toInt()) == 0x90000000.toInt()) {
            val rd = ins and 0x1F
            val immhi = (ins shr 5) and 0x7FFFF
            val immlo = (ins shr 29) and 0x3
            val imm21 = signExtend((immhi shl 2) or immlo, 21)
            val pagePc = (pc and (-4096L)) + (imm21 * 4096)
            val targetHex = "0x${java.lang.Long.toHexString(pagePc)}"
            return DisassemblyLine(
                addressHex, bytesHex, "ADRP", "x$rd, $targetHex",
                comment = "// Form PC-relative page address"
            )
        }

        // STP / LDP (Store/Load Pair)
        if ((ins and 0x3A000000.toInt()) == 0x28000000.toInt()) {
            val isLdp = (ins and 0x00400000) != 0
            val is64Bit = (ins and 0x80000000.toInt()) != 0
            val rt1 = ins and 0x1F
            val rt2 = (ins shr 10) and 0x1F
            val rn = (ins shr 5) and 0x1F
            val imm7 = signExtend((ins shr 15) and 0x7F, 7)
            val offset = imm7 * (if (is64Bit) 8 else 4)

            val p1 = if (is64Bit) "x$rt1" else "w$rt1"
            val p2 = if (is64Bit) "x$rt2" else "w$rt2"
            val baseReg = if (rn == 31) "sp" else "x$rn"
            val mnem = if (isLdp) "LDP" else "STP"

            return DisassemblyLine(
                addressHex, bytesHex, mnem, "$p1, $p2, [$baseReg, #$offset]",
                comment = if (rt1 == 29 && rt2 == 30) "// Save/Restore FP and LR" else null
            )
        }

        // ADD / SUB (Immediate)
        if ((ins and 0x11000000.toInt()) == 0x11000000.toInt()) {
            val isSub = (ins and 0x40000000.toInt()) != 0
            val is64Bit = (ins and 0x80000000.toInt()) != 0
            val rd = ins and 0x1F
            val rn = (ins shr 5) and 0x1F
            val imm12 = (ins shr 10) and 0xFFF

            val dName = if (rd == 31) (if (is64Bit) "xzr" else "wzr") else (if (is64Bit) "x$rd" else "w$rd")
            val nName = if (rn == 31) "sp" else (if (is64Bit) "x$rn" else "w$rn")
            val mnem = if (isSub) "SUB" else "ADD"

            return DisassemblyLine(addressHex, bytesHex, mnem, "$dName, $nName, #$imm12")
        }

        // MOV (Register or Immediate)
        if ((ins and 0x7F800000.toInt()) == 0x2A000000.toInt() || (ins and 0x7F800000.toInt()) == 0x6A000000.toInt()) {
            val is64Bit = (ins and 0x80000000.toInt()) != 0
            val rd = ins and 0x1F
            val rm = (ins shr 16) and 0x1F
            val dName = if (is64Bit) "x$rd" else "w$rd"
            val mName = if (is64Bit) "x$rm" else "w$rm"
            return DisassemblyLine(addressHex, bytesHex, "MOV", "$dName, $mName")
        }

        // BLR xn (Indirect Function Call via Register)
        if ((ins and 0xFFFFFC1F.toInt()) == 0xD63F0000.toInt()) {
            val rn = (ins shr 5) and 0x1F
            return DisassemblyLine(
                addressHex, bytesHex, "BLR", "x$rn",
                comment = "// Call function pointer in x$rn"
            )
        }

        // BR xn (Indirect Branch via Register)
        if ((ins and 0xFFFFFC1F.toInt()) == 0xD61F0000.toInt()) {
            val rn = (ins shr 5) and 0x1F
            return DisassemblyLine(
                addressHex, bytesHex, "BR", "x$rn",
                comment = "// Branch to address in x$rn"
            )
        }

        // LDR / STR Unsigned Immediate (32-bit / 64-bit / Byte / Halfword)
        if ((ins and 0x3B000000.toInt()) == 0x39000000.toInt()) {
            val isLdr = (ins and 0x00400000) != 0
            val size = (ins shr 30) and 0x3
            val rt = ins and 0x1F
            val rn = (ins shr 5) and 0x1F
            val imm12 = (ins shr 10) and 0xFFF
            val offset = imm12 * (1 shl size)

            val is64Bit = size == 3
            val rName = if (is64Bit) "x$rt" else "w$rt"
            val baseReg = if (rn == 31) "sp" else "x$rn"
            val mnem = if (isLdr) (if (size == 0) "LDRB" else if (size == 1) "LDRH" else "LDR")
                       else (if (size == 0) "STRB" else if (size == 1) "STRH" else "STR")

            return DisassemblyLine(
                addressHex, bytesHex, mnem, "$rName, [$baseReg, #$offset]",
                comment = if (isLdr) "// Load from memory" else "// Store to memory"
            )
        }

        // LDR Literal (PC-Relative)
        if ((ins and 0x3B000000.toInt()) == 0x18000000.toInt()) {
            val is64Bit = (ins and 0x40000000.toInt()) != 0
            val rt = ins and 0x1F
            val imm19 = signExtend((ins shr 5) and 0x7FFFF, 19)
            val targetPc = pc + (imm19 * 4)
            val targetHex = "0x${java.lang.Long.toHexString(targetPc)}"
            val rName = if (is64Bit) "x$rt" else "w$rt"

            return DisassemblyLine(
                addressHex, bytesHex, "LDR", "$rName, $targetHex",
                comment = "// Load literal from $targetHex"
            )
        }

        // CMP / CMN (Compare Register)
        if ((ins and 0x7F200000.toInt()) == 0x6B000000.toInt()) {
            val is64Bit = (ins and 0x80000000.toInt()) != 0
            val rn = (ins shr 5) and 0x1F
            val rm = (ins shr 16) and 0x1F
            val nName = if (rn == 31) "sp" else (if (is64Bit) "x$rn" else "w$rn")
            val mName = if (is64Bit) "x$rm" else "w$rm"

            return DisassemblyLine(addressHex, bytesHex, "CMP", "$nName, $mName", comment = "// Compare registers")
        }

        // Bitwise Logic (AND, ORR, EOR)
        if ((ins and 0x1F000000.toInt()) == 0x0A000000.toInt()) {
            val op = (ins shr 29) and 0x3
            val is64Bit = (ins and 0x80000000.toInt()) != 0
            val rd = ins and 0x1F
            val rn = (ins shr 5) and 0x1F
            val rm = (ins shr 16) and 0x1F

            val mnem = when (op) {
                0 -> "AND"
                1 -> "ORR"
                2 -> "EOR"
                else -> "ANDS"
            }
            val dName = if (is64Bit) "x$rd" else "w$rd"
            val nName = if (is64Bit) "x$rn" else "w$rn"
            val mName = if (is64Bit) "x$rm" else "w$rm"

            return DisassemblyLine(addressHex, bytesHex, mnem, "$dName, $nName, $mName")
        }

        // CSEL / CSINC (Conditional Select)
        if ((ins and 0x1E200000.toInt()) == 0x1A800000.toInt()) {
            val rd = ins and 0x1F
            val rn = (ins shr 5) and 0x1F
            val rm = (ins shr 16) and 0x1F
            val cond = (ins shr 12) and 0xF
            val condStr = getConditionString(cond)

            return DisassemblyLine(addressHex, bytesHex, "CSEL", "x$rd, x$rn, x$rm, $condStr")
        }

        // SVC #imm (System Call)
        if ((ins and 0xFFE0001F.toInt()) == 0xD4000001.toInt()) {
            val imm16 = (ins shr 5) and 0xFFFF
            return DisassemblyLine(addressHex, bytesHex, "SVC", "#$imm16", comment = "// System Call ($imm16)")
        }

        // Fallback for unhandled ARM64 opcodes
        val opHex = String.format("0x%08X", ins)
        return DisassemblyLine(addressHex, bytesHex, "UNK", opHex, comment = "// Unknown ARM64 instruction")
    }

    /**
     * Generates C-like pseudo-code for a function given its disassembly lines and symbol name.
     */
    fun generatePseudoCode(symbolName: String, lines: List<DisassemblyLine>): String {
        val sb = StringBuilder()
        sb.append("// ReShift Pseudo-C Decompiler Engine\n")
        sb.append("// Symbol: $symbolName\n\n")
        sb.append("long $symbolName(long a0, long a1, long a2, long a3) {\n")

        var hasCalls = false
        var lastReturnValue = "0"

        for (line in lines) {
            when (line.mnemonic) {
                "STP", "LDP" -> {
                    // Prologue / Epilogue setup
                }
                "BL" -> {
                    hasCalls = true
                    sb.append("    // Call ${line.operands}\n")
                    sb.append("    a0 = ${line.operands.substringAfterLast("0x")}(a0, a1);\n")
                    lastReturnValue = "a0"
                }
                "BLR" -> {
                    hasCalls = true
                    sb.append("    // Call Function Pointer ${line.operands}\n")
                    sb.append("    a0 = ((func_ptr)${line.operands})(a0, a1);\n")
                    lastReturnValue = "a0"
                }
                "MOV" -> {
                    sb.append("    // ${line.mnemonic} ${line.operands}\n")
                }
                "LDR", "LDRB", "LDRH" -> {
                    sb.append("    // Load: ${line.operands}\n")
                }
                "STR", "STRB", "STRH" -> {
                    sb.append("    // Store: ${line.operands}\n")
                }
                "CMP" -> {
                    sb.append("    // Compare ${line.operands}\n")
                }
                "CSEL" -> {
                    sb.append("    // Conditional Select ${line.operands}\n")
                }
                "CBZ" -> {
                    val reg = line.operands.substringBefore(',')
                    val target = line.operands.substringAfter(',')
                    sb.append("    if ($reg == 0) goto $target;\n")
                }
                "CBNZ" -> {
                    val reg = line.operands.substringBefore(',')
                    val target = line.operands.substringAfter(',')
                    sb.append("    if ($reg != 0) goto $target;\n")
                }
                "SVC" -> {
                    sb.append("    // Linux System Call (${line.operands})\n")
                    sb.append("    a0 = syscall(a0, a1, a2);\n")
                }
                "RET" -> {
                    sb.append("    return $lastReturnValue;\n")
                }
            }
        }

        if (!sb.toString().contains("return")) {
            sb.append("    return 0;\n")
        }

        sb.append("}\n")
        return sb.toString()
    }

    private fun signExtend(valIn: Int, bits: Int): Long {
        val shift = 32 - bits
        return ((valIn shl shift) shr shift).toLong()
    }

    private fun getConditionString(cond: Int): String = when (cond) {
        0 -> "EQ"
        1 -> "NE"
        2 -> "CS"
        3 -> "CC"
        4 -> "MI"
        5 -> "PL"
        6 -> "VS"
        7 -> "VC"
        8 -> "HI"
        9 -> "LS"
        10 -> "GE"
        11 -> "LT"
        12 -> "GT"
        13 -> "LE"
        else -> "AL"
    }
}
