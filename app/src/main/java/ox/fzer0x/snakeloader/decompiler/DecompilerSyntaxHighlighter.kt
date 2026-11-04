package ox.fzer0x.snakeloader.decompiler

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Pure Compose Multi-Color Syntax Highlighting Engine for
 * Java, Smali, ARM64 Disassembly, and Hex Dumps.
 */
object DecompilerSyntaxHighlighter {

    private val KEYWORD_COLOR = Color(0xFFFF79C6)  // Pink / Magenta
    private val TYPE_COLOR = Color(0xFF8BE9FD)     // Cyan / Light Blue
    private val STRING_COLOR = Color(0xFF50FA7B)   // Bright Green
    private val NUMBER_COLOR = Color(0xFFBD93F9)   // Purple / Yellow
    private val REGISTER_COLOR = Color(0xFFFFB86C) // Orange
    private val OPCODE_COLOR = Color(0xFF00E5FF)   // Bright Cyan
    private val COMMENT_COLOR = Color(0xFF888888)  // Grey

    private val JAVA_KEYWORDS = setOf(
        "public", "private", "protected", "static", "final", "abstract", "synchronized",
        "volatile", "transient", "native", "class", "interface", "enum", "void",
        "boolean", "byte", "short", "char", "int", "long", "float", "double",
        "return", "if", "else", "for", "while", "do", "switch", "case", "new",
        "throw", "goto", "try", "catch", "null", "true", "false", "extends", "implements"
    )

    private val SMALI_DIRECTIVES = setOf(
        ".method", ".end method", ".registers", ".annotation", ".end annotation",
        ".field", ".class", ".super", ".source", ".line", ".implements"
    )

    private val ARM64_OPCODES = setOf(
        "RET", "NOP", "BL", "B", "CBZ", "CBNZ", "ADRP", "STP", "LDP", "ADD", "SUB",
        "MOV", "SVC", "BLR", "BR", "LDR", "STR", "LDRB", "STRB", "LDRH", "STRH",
        "CMP", "CMN", "TST", "AND", "ORR", "EOR", "BIC", "CSEL", "CSINC", "UNK"
    )

    /**
     * Highlighting for Java / Kotlin decompiled code.
     */
    fun highlightJavaCode(code: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = code.lines()
            lines.forEachIndexed { lIdx, line ->
                val commentIdx = line.indexOf("//")
                val mainPart = if (commentIdx != -1) line.substring(0, commentIdx) else line
                val commentPart = if (commentIdx != -1) line.substring(commentIdx) else ""

                val tokens = mainPart.split(Regex("(?<=\\s)|(?=\\s)|(?<=[(),;{}])|(?=[(),;{}])"))
                for (token in tokens) {
                    val cleanToken = token.trim()
                    when {
                        JAVA_KEYWORDS.contains(cleanToken) -> {
                            pushStyle(SpanStyle(color = KEYWORD_COLOR, fontWeight = FontWeight.Bold))
                            append(token)
                            pop()
                        }
                        cleanToken.startsWith("\"") || cleanToken.endsWith("\"") -> {
                            pushStyle(SpanStyle(color = STRING_COLOR))
                            append(token)
                            pop()
                        }
                        cleanToken.matches(Regex("0x[0-9a-fA-F]+|\\d+")) -> {
                            pushStyle(SpanStyle(color = NUMBER_COLOR))
                            append(token)
                            pop()
                        }
                        cleanToken.firstOrNull()?.isUpperCase() == true -> {
                            pushStyle(SpanStyle(color = TYPE_COLOR))
                            append(token)
                            pop()
                        }
                        else -> append(token)
                    }
                }

                if (commentPart.isNotEmpty()) {
                    pushStyle(SpanStyle(color = COMMENT_COLOR))
                    append(commentPart)
                    pop()
                }

                if (lIdx < lines.size - 1) append("\n")
            }
        }
    }

    /**
     * Highlighting for Smali bytecode disassembly.
     */
    fun highlightSmaliCode(code: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = code.lines()
            lines.forEachIndexed { lIdx, line ->
                val commentIdx = line.indexOf("#")
                val mainPart = if (commentIdx != -1) line.substring(0, commentIdx) else line
                val commentPart = if (commentIdx != -1) line.substring(commentIdx) else ""

                val tokens = mainPart.split(Regex("(?<=\\s)|(?=\\s)|(?<=[(),;{}])|(?=[(),;{}])"))
                for (token in tokens) {
                    val cleanToken = token.trim()
                    when {
                        SMALI_DIRECTIVES.contains(cleanToken) -> {
                            pushStyle(SpanStyle(color = KEYWORD_COLOR, fontWeight = FontWeight.Bold))
                            append(token)
                            pop()
                        }
                        cleanToken.matches(Regex("v\\d+|p\\d+")) -> {
                            pushStyle(SpanStyle(color = REGISTER_COLOR, fontWeight = FontWeight.SemiBold))
                            append(token)
                            pop()
                        }
                        cleanToken.startsWith("\"") || cleanToken.endsWith("\"") -> {
                            pushStyle(SpanStyle(color = STRING_COLOR))
                            append(token)
                            pop()
                        }
                        cleanToken.startsWith("L") && cleanToken.contains(";") -> {
                            pushStyle(SpanStyle(color = TYPE_COLOR))
                            append(token)
                            pop()
                        }
                        else -> append(token)
                    }
                }

                if (commentPart.isNotEmpty()) {
                    pushStyle(SpanStyle(color = COMMENT_COLOR))
                    append(commentPart)
                    pop()
                }

                if (lIdx < lines.size - 1) append("\n")
            }
        }
    }

    /**
     * Highlighting for ARM64 assembly disassembly.
     */
    fun highlightArm64Code(code: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = code.lines()
            lines.forEachIndexed { lIdx, line ->
                val commentIdx = line.indexOf("//")
                val mainPart = if (commentIdx != -1) line.substring(0, commentIdx) else line
                val commentPart = if (commentIdx != -1) line.substring(commentIdx) else ""

                val parts = mainPart.split(Regex("\\s+"))
                parts.forEachIndexed { pIdx, token ->
                    when {
                        token.startsWith("0x") -> {
                            pushStyle(SpanStyle(color = NUMBER_COLOR))
                            append(token)
                            pop()
                        }
                        ARM64_OPCODES.contains(token.uppercase()) || token.startsWith("B.") -> {
                            pushStyle(SpanStyle(color = OPCODE_COLOR, fontWeight = FontWeight.Bold))
                            append(token)
                            pop()
                        }
                        token.matches(Regex("x\\d+|w\\d+|sp|xzr|wzr")) -> {
                            pushStyle(SpanStyle(color = REGISTER_COLOR, fontWeight = FontWeight.SemiBold))
                            append(token)
                            pop()
                        }
                        else -> append(token)
                    }
                    if (pIdx < parts.size - 1) append(" ")
                }

                if (commentPart.isNotEmpty()) {
                    pushStyle(SpanStyle(color = COMMENT_COLOR))
                    append("  $commentPart")
                    pop()
                }

                if (lIdx < lines.size - 1) append("\n")
            }
        }
    }
}
