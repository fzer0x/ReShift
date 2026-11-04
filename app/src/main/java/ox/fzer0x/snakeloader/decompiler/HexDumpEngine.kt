package ox.fzer0x.snakeloader.decompiler

/**
 * Pure Kotlin Binary Hex & ASCII Dump Engine.
 * Generates 16-byte aligned Hex dump lines with addresses, hex space byte columns,
 * and ASCII character representations.
 */
class HexDumpEngine {

    /**
     * Formats raw bytes into a list of [HexDumpLine] objects starting at [startAddress].
     */
    fun generateHexDumpLines(bytes: ByteArray, startAddress: Long, maxBytes: Int = 256): List<HexDumpLine> {
        val lines = mutableListOf<HexDumpLine>()
        val totalToRead = Math.min(bytes.size, maxBytes)

        for (i in 0 until totalToRead step 16) {
            val addr = startAddress + i
            val addrHex = String.format("0x%08x", addr)

            val lineBytes = bytes.copyOfRange(i, Math.min(bytes.size, i + 16))

            val hexSb = StringBuilder()
            val asciiSb = StringBuilder()

            for (j in 0 until 16) {
                if (j < lineBytes.size) {
                    val b = lineBytes[j].toInt() and 0xff
                    hexSb.append(String.format("%02x ", b))
                    if (j == 7) hexSb.append(" ")

                    if (b in 32..126) {
                        asciiSb.append(b.toChar())
                    } else {
                        asciiSb.append('.')
                    }
                } else {
                    hexSb.append("   ")
                    if (j == 7) hexSb.append(" ")
                    asciiSb.append(' ')
                }
            }

            lines.add(
                HexDumpLine(
                    offsetHex = addrHex,
                    bytesHexSpace = hexSb.toString().trimEnd(),
                    asciiRepresentation = "|${asciiSb}|"
                )
            )
        }

        if (lines.isEmpty()) {
            lines.add(
                HexDumpLine(
                    offsetHex = String.format("0x%08x", startAddress),
                    bytesHexSpace = "00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00",
                    asciiRepresentation = "|................|"
                )
            )
        }

        return lines
    }

    /**
     * Formats raw bytes into a single multiline Hex/ASCII dump string.
     */
    fun generateHexDumpString(bytes: ByteArray, startAddress: Long, maxBytes: Int = 256): String {
        val lines = generateHexDumpLines(bytes, startAddress, maxBytes)
        return lines.joinToString("\n") { "${it.offsetHex}  ${it.bytesHexSpace.padEnd(49)}  ${it.asciiRepresentation}" }
    }
}
