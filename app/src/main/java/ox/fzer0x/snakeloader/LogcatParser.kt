package ox.fzer0x.snakeloader

import java.util.regex.Pattern

object LogcatParser {
    private val LOG_PATTERN = Pattern.compile("""^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?):\s+(.*)$""")
    
    private val MODERN_PATTERN = Pattern.compile("""^(\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+-\d+)\s+(.*?)\s+(.*?)\s+([VDIWEF])\s+(.*)$""")

    private val SIMPLE_PATTERN = Pattern.compile("""^([VDIWEF])/(.*?)\(\s*\d+\):\s+(.*)$""")
    
    private val BASIC_PATTERN = Pattern.compile("""^([VDIWEF])/(.*?):\s+(.*)$""")

    private val SCRIPT_TAGS = listOf("Snakeloader", "Frida", "Zygisk", "LSPosed", "console", "V8", "QuickJS", "NaturalMotion", "Unity")

    data class ParsedLog(
        val timestamp: String,
        val tag: String,
        val priority: String,
        val message: String,
        val scriptName: String? = null,
        val isScriptRelated: Boolean = false
    )

    fun parse(line: String): ParsedLog {
        val modernMatcher = MODERN_PATTERN.matcher(line)
        if (modernMatcher.matches()) {
            val timestamp = modernMatcher.group(1) ?: ""
            val tag = modernMatcher.group(3)?.trim() ?: "Unknown"
            val priority = modernMatcher.group(5) ?: "I"
            val message = modernMatcher.group(6)?.trim() ?: ""
            
            return createParsedLog(line, timestamp, tag, priority, message)
        }

        val matcher = LOG_PATTERN.matcher(line)
        if (matcher.matches()) {
            val timestamp = matcher.group(1) ?: ""
            val priority = matcher.group(4) ?: "I"
            val tag = matcher.group(5)?.trim() ?: "Unknown"
            val message = matcher.group(6) ?: ""

            return createParsedLog(line, timestamp, tag, priority, message)
        }

        val simpleMatcher = SIMPLE_PATTERN.matcher(line)
        if (simpleMatcher.matches()) {
            val priority = simpleMatcher.group(1) ?: "I"
            val tag = simpleMatcher.group(2)?.trim() ?: "Unknown"
            val message = simpleMatcher.group(3) ?: ""

            return createParsedLog(line, "", tag, priority, message)
        }

        val basicMatcher = BASIC_PATTERN.matcher(line)
        if (basicMatcher.matches()) {
            val priority = basicMatcher.group(1) ?: "I"
            val tag = basicMatcher.group(2)?.trim() ?: "Unknown"
            val message = basicMatcher.group(3) ?: ""

            return createParsedLog(line, "", tag, priority, message)
        }

        val isRelated = line.contains("Snakeloader", ignoreCase = true)
        return ParsedLog(
            timestamp = "",
            tag = if (isRelated) "Related" else "System",
            priority = "I",
            message = line,
            isScriptRelated = isRelated
        )
    }

    private fun createParsedLog(original: String, timestamp: String, tag: String, priority: String, message: String): ParsedLog {
        val containsMarker = message.contains("[Snakeloader]", ignoreCase = true)
        
        var scriptName: String? = null
        var cleanMessage = message

        if (containsMarker) {
            val scriptMatch = Regex("""\[Snakeloader\]\s*\[(.*?)\]""").find(message)
            scriptName = scriptMatch?.groupValues?.get(1)
            cleanMessage = message.replace(Regex("""^\[Snakeloader\]\s*(\[.*?\])?\s*"""), "")
        }

        return ParsedLog(
            timestamp = timestamp,
            tag = tag,
            priority = priority,
            message = cleanMessage,
            scriptName = scriptName,
            isScriptRelated = containsMarker
        )
    }
}
