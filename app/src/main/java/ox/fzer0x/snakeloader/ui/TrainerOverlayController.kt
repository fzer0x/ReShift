package ox.fzer0x.snakeloader.ui

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.data.MemoryPatch
import ox.fzer0x.snakeloader.data.PatchRepository

class TrainerOverlayController(
    private val context: Context,
    private val patchRepository: PatchRepository,
    private val fridaManager: FridaManager
) {
    companion object {
        private const val TAG = "TrainerOverlayController"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val patches: StateFlow<List<MemoryPatch>> = patchRepository.patches

    fun togglePatch(patchId: String) {
        Log.d(TAG, "Trainer Overlay toggling patch: $patchId")
        scope.launch {
            patchRepository.togglePatch(patchId)
        }
    }

    fun restoreAllVerified() {
        Log.d(TAG, "Trainer Overlay restoring all verified patches")
        scope.launch {
            patchRepository.restoreAllVerified()
        }
    }

    fun isFridaConnected(): Boolean {
        val status = fridaManager.getFridaStatus()
        return status["server_running"] as? Boolean ?: false
    }
}
