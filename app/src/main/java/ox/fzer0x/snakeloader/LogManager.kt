package ox.fzer0x.snakeloader

import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicLong

object LogManager {
    enum class LogLevel {
        INFO, SUCCESS, WARN, ERROR, DEBUG, VERBOSE
    }

    private val idCounter = AtomicLong(0)

    data class LogEntry(
        val id: Long,
        val timestamp: Long = System.currentTimeMillis(),
        val packageName: String,
        val message: String,
        val level: LogLevel = LogLevel.INFO,
        val tag: String? = null,
        val isScriptRelated: Boolean = false
    )

    private val _logs = mutableStateOf<List<LogEntry>>(emptyList())
    val logs: List<LogEntry> get() = _logs.value

    private val _fridaLogs = mutableStateOf<List<LogEntry>>(emptyList())
    val fridaLogs: List<LogEntry> get() = _fridaLogs.value

    private val _logcatEntries = mutableStateOf<List<LogEntry>>(emptyList())
    val logcatEntries: List<LogEntry> get() = _logcatEntries.value

    private val lock = Any()

    fun addLog(packageName: String, message: String, isError: Boolean = false) {
        val level = if (isError) LogLevel.ERROR else LogLevel.INFO
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            packageName = packageName, 
            message = message, 
            level = level, 
            isScriptRelated = true
        )
        
        synchronized(lock) {
            val newList = _logs.value.toMutableList()
            newList.add(0, entry)
            if (newList.size > 1000) newList.removeAt(newList.size - 1)
            _logs.value = newList
        }
    }

    fun addLogcatEntry(message: String, packageName: String = "Logcat", isScriptRelated: Boolean = false, priority: String = "I") {
        val level = when (priority) {
            "E", "F" -> LogLevel.ERROR
            "W" -> LogLevel.WARN
            "D" -> LogLevel.DEBUG
            "V" -> LogLevel.VERBOSE
            else -> {
                if (message.contains("failed", ignoreCase = true) || message.contains("error", ignoreCase = true)) LogLevel.ERROR
                else LogLevel.INFO
            }
        }
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            packageName = packageName, 
            message = message, 
            level = level, 
            isScriptRelated = isScriptRelated
        )
        
        synchronized(lock) {
            val newList = _logcatEntries.value.toMutableList()
            newList.add(0, entry)
            if (newList.size > 2000) {
                while (newList.size > 2000) {
                    newList.removeAt(newList.size - 1)
                }
            }
            _logcatEntries.value = newList
        }
    }

    fun addFridaLog(message: String) {
        val parsed = parseFridaLog(message)
        synchronized(lock) {
            val newList = _fridaLogs.value.toMutableList()
            newList.add(0, parsed)
            if (newList.size > 1000) newList.removeAt(newList.size - 1)
            _fridaLogs.value = newList
        }
    }

    private fun parseFridaLog(message: String): LogEntry {
        var level = LogLevel.INFO
        val cleanMessage = message
        var tag: String? = "FRIDA"

        if (message.trim().startsWith("____") || message.trim().startsWith("/") || message.trim().startsWith("|") || message.trim().startsWith(">")) {
            return LogEntry(
                id = idCounter.incrementAndGet(),
                packageName = "Frida", 
                message = message, 
                level = LogLevel.VERBOSE, 
                tag = "BANNER"
            )
        }

        when {
            message.contains("[✓]") || message.contains("success", ignoreCase = true) -> {
                level = LogLevel.SUCCESS
                tag = "SUCCESS"
            }
            message.contains("[!]") || message.contains("warn", ignoreCase = true) -> {
                level = LogLevel.WARN
                tag = "WARN"
            }
            message.contains("[✗]") || message.contains("[ERROR]") || message.contains("Error:", ignoreCase = true) -> {
                level = LogLevel.ERROR
                tag = "ERROR"
            }
            message.contains("[Snakeloader]") -> {
                tag = "SNAKE"
                if (message.contains("[INFO]")) level = LogLevel.INFO
                if (message.contains("[VERBOSE]")) level = LogLevel.VERBOSE
            }
        }

        return LogEntry(
            id = idCounter.incrementAndGet(),
            packageName = "Frida",
            message = cleanMessage,
            level = level,
            tag = tag,
            isScriptRelated = true
        )
    }

    fun clearLogs() {
        synchronized(lock) {
            _logs.value = emptyList()
        }
    }

    fun clearFridaLogs() {
        synchronized(lock) {
            _fridaLogs.value = emptyList()
        }
    }

    fun clearLogcat() {
        synchronized(lock) {
            _logcatEntries.value = emptyList()
        }
    }
}
