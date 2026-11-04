package ox.fzer0x.snakeloader

import androidx.compose.runtime.mutableStateOf
import java.util.ArrayDeque
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

    private const val MAX_LOGS = 1000
    private const val MAX_LOGCAT_ENTRIES = 2000

    private val lock = Any()

    private val logsDeque = ArrayDeque<LogEntry>(MAX_LOGS)
    private val fridaLogsDeque = ArrayDeque<LogEntry>(MAX_LOGS)
    private val logcatEntriesDeque = ArrayDeque<LogEntry>(MAX_LOGCAT_ENTRIES)

    private val _logs = mutableStateOf<List<LogEntry>>(emptyList())
    val logs: List<LogEntry> get() = synchronized(lock) {
        _logs.value = logsDeque.toList()
        _logs.value
    }

    private val _fridaLogs = mutableStateOf<List<LogEntry>>(emptyList())
    val fridaLogs: List<LogEntry> get() = synchronized(lock) {
        _fridaLogs.value = fridaLogsDeque.toList()
        _fridaLogs.value
    }

    private val _logcatEntries = mutableStateOf<List<LogEntry>>(emptyList())
    val logcatEntries: List<LogEntry> get() = synchronized(lock) {
        _logcatEntries.value = logcatEntriesDeque.toList()
        _logcatEntries.value
    }

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
            logsDeque.addFirst(entry)
            if (logsDeque.size > MAX_LOGS) {
                logsDeque.removeLast()
            }
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
            logcatEntriesDeque.addFirst(entry)
            if (logcatEntriesDeque.size > MAX_LOGCAT_ENTRIES) {
                logcatEntriesDeque.removeLast()
            }
        }
    }

    fun addFridaLog(message: String) {
        val parsed = parseFridaLog(message)
        synchronized(lock) {
            fridaLogsDeque.addFirst(parsed)
            if (fridaLogsDeque.size > MAX_LOGS) {
                fridaLogsDeque.removeLast()
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            _logs.value = logsDeque.toList()
            _fridaLogs.value = fridaLogsDeque.toList()
            _logcatEntries.value = logcatEntriesDeque.toList()
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
            logsDeque.clear()
            _logs.value = emptyList()
        }
    }

    fun clearFridaLogs() {
        synchronized(lock) {
            fridaLogsDeque.clear()
            _fridaLogs.value = emptyList()
        }
    }

    fun clearLogcat() {
        synchronized(lock) {
            logcatEntriesDeque.clear()
            _logcatEntries.value = emptyList()
        }
    }
}
