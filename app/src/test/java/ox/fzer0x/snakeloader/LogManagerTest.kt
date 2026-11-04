package ox.fzer0x.snakeloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogManagerTest {

    @Before
    fun setUp() {
        LogManager.clearLogs()
        LogManager.clearFridaLogs()
        LogManager.clearLogcat()
    }

    @Test
    fun testAddLogAndRingBufferLimit() {
        repeat(1200) { i ->
            LogManager.addLog("com.example.app", "Log entry $i")
        }

        val logs = LogManager.logs
        assertEquals(1000, logs.size)
        // Check that newest entry is at index 0
        assertEquals("Log entry 1199", logs.first().message)
    }

    @Test
    fun testAddFridaLogAndClear() {
        LogManager.addFridaLog("[✓] Hooked target function successfully")
        assertEquals(1, LogManager.fridaLogs.size)
        assertEquals(LogManager.LogLevel.SUCCESS, LogManager.fridaLogs.first().level)

        LogManager.clearFridaLogs()
        assertTrue(LogManager.fridaLogs.isEmpty())
    }

    @Test
    fun testAddLogcatEntryLimit() {
        repeat(2500) { i ->
            LogManager.addLogcatEntry("Logcat line $i", "TestPkg", isScriptRelated = true, priority = "E")
        }

        val logcat = LogManager.logcatEntries
        assertEquals(2000, logcat.size)
        assertEquals("Logcat line 2499", logcat.first().message)
        assertEquals(LogManager.LogLevel.ERROR, logcat.first().level)
    }
}
