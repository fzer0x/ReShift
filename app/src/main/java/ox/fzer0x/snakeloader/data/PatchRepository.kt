package ox.fzer0x.snakeloader.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PatchRepository(
    private val context: Context,
    private val transactionManager: PatchTransactionManager
) {
    companion object {
        private const val TAG = "PatchRepository"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _patches = MutableStateFlow<List<MemoryPatch>>(emptyList())
    val patches: StateFlow<List<MemoryPatch>> = _patches.asStateFlow()

    private var currentPackageName: String? = null

    fun loadPatchesForPackage(packageName: String) {
        currentPackageName = packageName
        val file = getPatchFile(packageName)
        if (!file.exists()) {
            _patches.value = emptyList()
            return
        }

        try {
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<MemoryPatch>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    MemoryPatch(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        packageName = packageName,
                        moduleName = obj.optString("moduleName"),
                        rvaOffsetHex = obj.optString("rvaOffsetHex"),
                        lastRuntimeAddressHex = if (obj.has("lastRuntimeAddressHex")) obj.getString("lastRuntimeAddressHex") else null,
                        originalBytesHex = if (obj.has("originalBytesHex")) obj.getString("originalBytesHex") else null,
                        patchBytesHex = obj.optString("patchBytesHex"),
                        status = PatchStatus.UNVERIFIED, // Reset to UNVERIFIED on load for recovery safety
                        targetArch = try { TargetArchitecture.valueOf(obj.optString("targetArch", "ARM64")) } catch (_: Exception) { TargetArchitecture.ARM64 },
                        preset = try { PatchPreset.valueOf(obj.optString("preset", "CUSTOM")) } catch (_: Exception) { PatchPreset.CUSTOM },
                        processId = if (obj.has("processId")) obj.optInt("processId") else null,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            _patches.value = list
            Log.d(TAG, "Loaded ${list.size} patches for package $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load patches for $packageName", e)
            _patches.value = emptyList()
        }
    }

    fun savePatch(patch: MemoryPatch) {
        val current = _patches.value.toMutableList()
        val index = current.indexOfFirst { it.id == patch.id }
        if (index >= 0) {
            current[index] = patch
        } else {
            current.add(patch)
        }
        _patches.value = current
        persistPatches(patch.packageName, current)
    }

    fun removePatch(patchId: String) {
        val current = _patches.value.toMutableList()
        val patch = current.find { it.id == patchId }
        current.removeAll { it.id == patchId }
        _patches.value = current

        patch?.let {
            scope.launch {
                transactionManager.restorePatch(it)
                persistPatches(it.packageName, current)
            }
        }
    }

    fun togglePatch(patchId: String) {
        val current = _patches.value.toMutableList()
        val index = current.indexOfFirst { it.id == patchId }
        if (index < 0) return

        val patch = current[index]
        scope.launch {
            val updated = if (patch.status == PatchStatus.ACTIVE) {
                transactionManager.restorePatch(patch)
            } else {
                transactionManager.applyPatch(patch)
            }
            current[index] = updated
            _patches.value = current.toList()
            persistPatches(patch.packageName, current)
        }
    }

    suspend fun verifyAllPatches() {
        val current = _patches.value.toMutableList()
        for (i in current.indices) {
            val p = current[i]
            val verifiedStatus = transactionManager.verifyPatchState(p)
            current[i] = p.copy(status = verifiedStatus)
        }
        _patches.value = current.toList()
    }

    suspend fun restoreAllVerified() {
        val current = _patches.value.toMutableList()
        for (i in current.indices) {
            val p = current[i]
            if (p.status == PatchStatus.ACTIVE) {
                val restored = transactionManager.restorePatch(p)
                current[i] = restored
            }
        }
        _patches.value = current.toList()
        currentPackageName?.let { persistPatches(it, current) }
    }

    private fun getPatchFile(packageName: String): File {
        val dir = File(context.filesDir, "patches")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "patches_${packageName}.json")
    }

    private fun persistPatches(packageName: String, list: List<MemoryPatch>) {
        try {
            val array = JSONArray()
            list.forEach { p ->
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("moduleName", p.moduleName)
                obj.put("rvaOffsetHex", p.rvaOffsetHex)
                obj.put("lastRuntimeAddressHex", p.lastRuntimeAddressHex)
                obj.put("originalBytesHex", p.originalBytesHex)
                obj.put("patchBytesHex", p.patchBytesHex)
                obj.put("targetArch", p.targetArch.name)
                obj.put("preset", p.preset.name)
                p.processId?.let { obj.put("processId", it) }
                obj.put("timestamp", p.timestamp)
                array.put(obj)
            }
            getPatchFile(packageName).writeText(array.toString(2))
            Log.d(TAG, "Persisted ${list.size} patches to disk for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist patches for $packageName", e)
        }
    }
}
