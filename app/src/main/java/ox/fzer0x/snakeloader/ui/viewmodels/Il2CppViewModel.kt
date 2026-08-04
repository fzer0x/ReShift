package ox.fzer0x.snakeloader.ui.viewmodels

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import ox.fzer0x.snakeloader.RpcManager

data class Il2CppClass(
    val name: String,
    val methods: List<String> = emptyList(),
    val fields: List<String> = emptyList(),
    val properties: List<String> = emptyList(),
    val parent: String = "",
    val size: Int = 0
)

class Il2CppViewModel(
    private val fridaManager: ox.fzer0x.snakeloader.FridaManager
) : ViewModel() {
    companion object {
        private const val TAG = "Il2CppViewModel"
    }

    val classes = mutableStateOf<List<Il2CppClass>>(emptyList())
    val isLoading = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val searchText = mutableStateOf("")
    val isRpcAvailable = mutableStateOf(false)

    fun checkConnection() {
        isRpcAvailable.value = fridaManager.getRpcManager() != null
    }

    fun searchClasses(query: String) {
        searchText.value = query
        if (query.length < 3) return

        val rpc = fridaManager.getRpcManager()
        if (rpc == null) {
            errorMessage.value = "RPC connection not established"
            isRpcAvailable.value = false
            return
        }

        isRpcAvailable.value = true
        isLoading.value = true
        errorMessage.value = null

        rpc.findClassesAsync(query) { results ->
            isLoading.value = false
            classes.value = results.map { map ->
                Il2CppClass(
                    name = map["name"] as? String ?: "Unknown",
                    methods = (map["methods"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    fields = (map["fields"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    parent = map["parent"] as? String ?: "",
                    size = (map["size"] as? Number)?.toInt() ?: 0
                )
            }
        }
    }

    fun hookMethod(className: String, methodName: String) {
        Log.d(TAG, "Hooking $className::$methodName")
    }
}
