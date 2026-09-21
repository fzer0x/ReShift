package ox.fzer0x.snakeloader.utils

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

object ProcessMonitor {
    private const val TAG = "ProcessMonitor"

    private val POLL_INTERVALS = longArrayOf(100, 200, 400, 800, 1600, 3200, 5000)
    private const val MAX_POLL_INTERVAL = 5000L

    private val processStateCache = ConcurrentHashMap<String, Boolean>()
    private val allRunningProcesses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastPollTime = 0L

    @Volatile
    private var lastFullRefreshTime = 0L

    @Synchronized
    fun refreshAllProcesses(force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (!force && currentTime - lastFullRefreshTime < 2000) return

        try {
            val result = ShellExecutor.execute("ps -A", useRoot = true)
            if (result.isSuccess) {
                val lines = result.stdout.lines().drop(1)
                val newProcesses = mutableSetOf<String>()
                lines.forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 9) {
                        val name = parts.last()
                        if (name.isNotEmpty()) newProcesses.add(name)
                    } else if (parts.isNotEmpty()) {
                        val name = parts.last()
                        if (name.isNotEmpty()) newProcesses.add(name)
                    }
                }
                allRunningProcesses.clear()
                allRunningProcesses.addAll(newProcesses)
                lastFullRefreshTime = currentTime
                Log.d(TAG, "Full process refresh completed: ${allRunningProcesses.size} processes found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh all processes", e)
        }
    }

    fun isProcessRunning(processName: String, forceRefresh: Boolean = false): Boolean {
        val currentTime = System.currentTimeMillis()
        
        if (!forceRefresh && currentTime - lastFullRefreshTime < 3000) {
            return checkInCache(processName)
        }

        if (!forceRefresh && processStateCache.containsKey(processName)) {
            val timeSinceLastCheck = currentTime - lastPollTime
            if (timeSinceLastCheck < 2000) {
                return processStateCache[processName] ?: false
            }
        }

        refreshAllProcesses(force = true)
        val isRunning = checkInCache(processName)
        
        processStateCache[processName] = isRunning
        lastPollTime = currentTime
        
        return isRunning
    }

    private fun checkInCache(processName: String): Boolean {
        if (allRunningProcesses.contains(processName)) return true
        
        val snapshot = allRunningProcesses.toList()
        return snapshot.any { 
            it == processName || 
            it.endsWith("/$processName") || 
            (it.startsWith("/") && it.substringAfterLast("/") == processName)
        }
    }

    private fun checkProcessRunning(processName: String): Boolean {
        return try {
            val pgrepResult = ShellExecutor.execute("pgrep -f '$processName'", useRoot = true)
            if (pgrepResult.isSuccess && pgrepResult.stdout.trim().isNotEmpty()) {
                return true
            }

            val psResult = ShellExecutor.execute("ps -A", useRoot = true)
            psResult.stdout.contains(processName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking process $processName", e)
            false
        }
    }

    suspend fun waitForProcessStart(processName: String, timeoutMs: Long = 10000): Boolean {
        Log.d(TAG, "Waiting for process $processName to start...")
        
        val startTime = System.currentTimeMillis()
        var pollIndex = 0
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isProcessRunning(processName, forceRefresh = true)) {
                Log.d(TAG, "Process $processName started")
                return true
            }

            val interval = POLL_INTERVALS.getOrElse(pollIndex) { MAX_POLL_INTERVAL }
            delay(interval)
            
            pollIndex = minOf(pollIndex + 1, POLL_INTERVALS.size - 1)
        }

        Log.w(TAG, "Timeout waiting for process $processName to start")
        return false
    }

    suspend fun waitForProcessStop(processName: String, timeoutMs: Long = 5000): Boolean {
        Log.d(TAG, "Waiting for process $processName to stop...")
        
        val startTime = System.currentTimeMillis()
        var pollIndex = 0
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (!isProcessRunning(processName, forceRefresh = true)) {
                Log.d(TAG, "Process $processName stopped")
                return true
            }

            val interval = POLL_INTERVALS.getOrElse(pollIndex) { MAX_POLL_INTERVAL }
            delay(interval)
            
            pollIndex = minOf(pollIndex + 1, POLL_INTERVALS.size - 1)
        }

        Log.w(TAG, "Timeout waiting for process $processName to stop")
        return false
    }

    fun getProcessId(processName: String): String? {
        return try {
            val result = ShellExecutor.execute("pidof $processName", useRoot = true)
            if (result.isSuccess) {
                result.stdout.trim().split(" ").firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting PID for $processName", e)
            null
        }
    }

    fun clearCache() {
        processStateCache.clear()
        allRunningProcesses.clear()
        lastPollTime = 0L
        lastFullRefreshTime = 0L
        Log.d(TAG, "Process state cache cleared")
    }

    fun getProcessesMatchingPattern(pattern: String): List<String> {
        return try {
            val result = ShellExecutor.execute("ps -A -o NAME", useRoot = true)
            if (result.isSuccess) {
                result.stdout.lines()
                    .drop(1)
                    .map { it.trim() }
                    .filter { it.contains(pattern, ignoreCase = true) }
                    .distinct()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting processes matching pattern", e)
            emptyList()
        }
    }

    fun monitorProcessState(
        processName: String,
        onStateChange: (Boolean) -> Unit,
        intervalMs: Long = 2000
    ) {
        var lastState = isProcessRunning(processName)
        
        Thread {
            while (true) {
                try {
                    Thread.sleep(intervalMs)
                    val currentState = isProcessRunning(processName, forceRefresh = true)
                    
                    if (currentState != lastState) {
                        Log.d(TAG, "Process $processName state changed: $lastState -> $currentState")
                        onStateChange(currentState)
                        lastState = currentState
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Process monitoring interrupted for $processName")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring process $processName", e)
                }
            }
        }.start()
    }
}
