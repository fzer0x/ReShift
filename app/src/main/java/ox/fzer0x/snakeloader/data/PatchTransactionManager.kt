package ox.fzer0x.snakeloader.data

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ox.fzer0x.snakeloader.RpcBridge

class PatchTransactionManager(private val rpcBridge: RpcBridge) {
    companion object {
        private const val TAG = "PatchTransactionManager"
    }

    private val mutex = Mutex()

    suspend fun applyPatch(patch: MemoryPatch): MemoryPatch = mutex.withLock {
        Log.d(TAG, "Executing atomic patch transaction for ${patch.name} (${patch.moduleName}+${patch.rvaOffsetHex})")

        val response = rpcBridge.callRpc(
            "apply_patch_rva",
            listOf(patch.moduleName, patch.rvaOffsetHex, patch.patchBytesHex, patch.id)
        )

        if (response.error != null) {
            Log.e(TAG, "Patch transaction RPC failed: ${response.error}")
            return patch.copy(status = PatchStatus.ERROR)
        }

        val resultObj = response.result as? Map<*, *>
        val success = resultObj?.get("success") as? Boolean ?: false
        val statusStr = resultObj?.get("status") as? String ?: "ERROR"
        val origHex = resultObj?.get("originalBytesHex") as? String ?: patch.originalBytesHex
        val runtimeAddr = resultObj?.get("runtimeAddress") as? String ?: patch.lastRuntimeAddressHex

        val newStatus = when (statusStr) {
            "ACTIVE" -> PatchStatus.ACTIVE
            "CONFLICT" -> PatchStatus.CONFLICT
            "INACTIVE" -> PatchStatus.INACTIVE
            else -> PatchStatus.ERROR
        }

        return patch.copy(
            status = newStatus,
            originalBytesHex = origHex ?: patch.originalBytesHex,
            lastRuntimeAddressHex = runtimeAddr ?: patch.lastRuntimeAddressHex
        )
    }

    suspend fun restorePatch(patch: MemoryPatch): MemoryPatch = mutex.withLock {
        Log.d(TAG, "Executing restore transaction for ${patch.name} (${patch.moduleName}+${patch.rvaOffsetHex})")

        val response = rpcBridge.callRpc(
            "restore_original_rva",
            listOf(patch.moduleName, patch.rvaOffsetHex)
        )

        if (response.error != null) {
            Log.e(TAG, "Restore transaction RPC failed: ${response.error}")
            return patch.copy(status = PatchStatus.ERROR)
        }

        val resultObj = response.result as? Map<*, *>
        val success = resultObj?.get("success") as? Boolean ?: false
        val statusStr = resultObj?.get("status") as? String ?: "ERROR"

        val newStatus = when {
            success || statusStr == "INACTIVE" -> PatchStatus.INACTIVE
            statusStr == "UNVERIFIED" -> PatchStatus.UNVERIFIED
            else -> PatchStatus.ERROR
        }

        return patch.copy(status = newStatus)
    }

    suspend fun verifyPatchState(patch: MemoryPatch): PatchStatus = mutex.withLock {
        val response = rpcBridge.callRpc(
            "verify_patch_state",
            listOf(
                patch.moduleName,
                patch.rvaOffsetHex,
                patch.originalBytesHex ?: "",
                patch.patchBytesHex
            )
        )

        if (response.error != null) {
            return PatchStatus.UNVERIFIED
        }

        val resultObj = response.result as? Map<*, *>
        val statusStr = resultObj?.get("status") as? String ?: "UNVERIFIED"

        return when (statusStr) {
            "ACTIVE" -> PatchStatus.ACTIVE
            "INACTIVE" -> PatchStatus.INACTIVE
            "CONFLICT" -> PatchStatus.CONFLICT
            else -> PatchStatus.UNVERIFIED
        }
    }
}
