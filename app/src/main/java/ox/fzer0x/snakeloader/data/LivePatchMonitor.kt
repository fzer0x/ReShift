package ox.fzer0x.snakeloader.data

import android.util.Log
import kotlinx.coroutines.*

class LivePatchMonitor(
    private val patchRepository: PatchRepository,
    private val intervalMs: Long = 5000L
) {
    companion object {
        private const val TAG = "LivePatchMonitor"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        Log.d(TAG, "Starting LivePatchMonitor background verification loop (${intervalMs}ms interval)")

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    delay(intervalMs)
                    patchRepository.verifyAllPatches()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "LivePatchMonitor error during verification iteration", e)
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.d(TAG, "LivePatchMonitor stopped")
    }
}
