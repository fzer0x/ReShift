package ox.fzer0x.snakeloader.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ox.fzer0x.snakeloader.db.Il2CppDatabaseHelper
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppFilter

class Il2CppRepository(
    private val context: Context,
    private val dbHelper: Il2CppDatabaseHelper
) {
    companion object {
        private const val TAG = "Il2CppRepository"
    }

    private val gson = Gson()
    private val classListType = object : TypeToken<List<Il2CppClassData>>() {}.type

    private val _indexedClassCount = MutableStateFlow(0)
    val indexedClassCount: StateFlow<Int> = _indexedClassCount.asStateFlow()

    /**
     * Ingests Frida RPC stream batch directly in memory without spawning root shell `cp` commands.
     */
    suspend fun handleFridaRpcStream(packageName: String, jsonMessage: String) = withContext(Dispatchers.IO) {
        try {
            if (!jsonMessage.contains("IL2CPP_RPC_STREAM_BATCH")) return@withContext

            val jsonStr = if (jsonMessage.trim().startsWith("{")) jsonMessage.trim() else jsonMessage.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else "" }
            if (jsonStr.isEmpty()) return@withContext

            val jsonObj = gson.fromJson(jsonStr, JsonObject::class.java) ?: return@withContext
            val payload = jsonObj.getAsJsonObject("payload") ?: return@withContext
            val classesArray = payload.getAsJsonArray("classes") ?: return@withContext

            val streamedClasses: List<Il2CppClassData>? = gson.fromJson(classesArray, classListType)
            if (!streamedClasses.isNullOrEmpty()) {
                dbHelper.insertClassesBatch(packageName, streamedClasses)
                val total = dbHelper.getClassesCount(packageName)
                _indexedClassCount.value = total
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parsing stream batch error: ${e.message}")
        }
    }

    suspend fun queryClasses(
        packageName: String,
        query: String,
        filter: Il2CppFilter,
        limit: Int = 1000
    ): List<Il2CppClassData> = withContext(Dispatchers.IO) {
        dbHelper.queryClasses(packageName, query, filter, limit)
    }

    suspend fun getClassesCount(
        packageName: String,
        query: String = "",
        filter: Il2CppFilter = Il2CppFilter.ALL
    ): Int = withContext(Dispatchers.IO) {
        dbHelper.getClassesCount(packageName, query, filter)
    }

    suspend fun clearPackage(packageName: String) = withContext(Dispatchers.IO) {
        dbHelper.clearPackage(packageName)
        _indexedClassCount.value = 0
    }
}
