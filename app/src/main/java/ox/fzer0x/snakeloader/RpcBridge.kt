package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

data class RpcRequest(
    val method: String,
    val params: List<Any> = emptyList()
)

data class RpcResponse(
    val result: Any? = null,
    val error: String? = null
)

data class HookInfo(
    val name: String,
    val active: Boolean,
    val target: String = ""
)

data class MemoryDumpInfo(
    val address: String,
    val size: Int,
    val data: String = ""
)

class RpcBridge(private val context: Context) {
    companion object {
        private const val TAG = "RpcBridge"
        private const val DEFAULT_RPC_PORT = 27043
        private const val TIMEOUT_MS = 5000L
    }

    private fun getEffectivePort(): Int {
        val stealthManager = StealthConfigManager(context, SettingsManager(context))
        val port = stealthManager.getActivePort()
        return if (port > 0) port else DEFAULT_RPC_PORT
    }

    private var rpcServer: Process? = null
    private var rpcClient: FridaRpcClient? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    suspend fun startRpcServer(): Boolean = withContext(Dispatchers.IO) {
        try {
            val port = getEffectivePort()
            Log.d(TAG, "Starting RPC bridge connection sequence (Target: 127.0.0.1:$port)")
            val maxRetries = 25
            var retries = 0
            var lastError: Exception? = null

            while (retries < maxRetries) {
                try {
                    val client = FridaRpcClient("127.0.0.1", port)
                    Log.d(TAG, "RPC connection attempt ${retries + 1}/$maxRetries...")
                    val testResult = client.call("testConnection")
                    
                    if (testResult != null) {
                        Log.d(TAG, "RPC Connected and verified successfully!")
                        rpcClient = client
                        return@withContext true
                    } else {
                        Log.w(TAG, "RPC connected but script hasn't exported testConnection yet (attempt ${retries + 1})")
                        client.close()
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (retries % 5 == 0) {
                        Log.d(TAG, "RPC connection status: ${e.message} (retrying...)")
                    }
                }
                
                retries++
                if (retries < maxRetries) {
                    val waitTime = if (retries < 5) 1000L else 2000L
                    delay(waitTime)
                }
            }
            Log.e(TAG, "RPC connection failed after $maxRetries attempts. Last error: ${lastError?.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in startRpcServer", e)
            false
        }
    }

    suspend fun callRpc(method: String, params: List<Any> = emptyList()): RpcResponse {
        return withContext(Dispatchers.IO) {
            try {
                val client = rpcClient ?: return@withContext RpcResponse(error = "RPC client not initialized")
                
                val result = withTimeoutOrNull(TIMEOUT_MS) {
                    client.call(method, params)
                }
                
                if (result == null) {
                    return@withContext RpcResponse(error = "RPC call timeout after ${TIMEOUT_MS}ms for method: $method")
                }
                
                RpcResponse(result = result)
            } catch (e: Exception) {
                Log.e(TAG, "RPC call failed for $method", e)
                RpcResponse(error = e.message ?: "Unknown error during RPC call")
            }
        }
    }

    suspend fun listHooks(): List<HookInfo> {
        return try {
            Log.d(TAG, "Attempting to list hooks...")
            val response = callRpc("listRegisteredHooks")
            Log.d(TAG, "listRegisteredHooks response: $response")
            
            val hooks = response.result as? List<*> ?: emptyList<Any>()
            Log.d(TAG, "Found ${hooks.size} hook names: $hooks")
            
            hooks.mapNotNull { hookName ->
                val hookResponse = callRpc("getHookStatus", listOf(hookName as Any))
                Log.d(TAG, "getHookStatus for $hookName: $hookResponse")
                (hookResponse.result as? Map<*, *>)?.let {
                    HookInfo(
                        name = it["name"] as? String ?: hookName.toString(),
                        active = it["active"] as? Boolean ?: false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list hooks", e)
            emptyList()
        }
    }

    suspend fun toggleHook(hookName: String): Boolean {
        return try {
            val response = callRpc("toggleHook", listOf(hookName))
            response.result as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle hook", e)
            false
        }
    }

    suspend fun dumpMemory(address: String, size: Int): ByteArray? {
        return try {
            val response = callRpc("getMemoryDump", listOf(address, size))
            response.result as? ByteArray
        } catch (e: Exception) {
            Log.e(TAG, "Memory dump failed", e)
            null
        }
    }

    suspend fun getRpcDiagnostics(): Map<String, Any>? {
        return try {
            val response = callRpc("get_rpc_diagnostics")
            response.result as? Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get diagnostics", e)
            null
        }
    }

    suspend fun listActiveTraces(): Map<String, Any>? {
        return try {
            val response = callRpc("list_active_traces")
            response.result as? Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list traces", e)
            null
        }
    }

    suspend fun getModuleInfo(moduleName: String? = null): Map<String, Any>? {
        return try {
            val params = if (moduleName != null) listOf(moduleName) else emptyList()
            val response = callRpc("get_module_info", params)
            response.result as? Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get module info", e)
            null
        }
    }

    suspend fun listModules(): List<Map<String, Any>> {
        return try {
            val response = callRpc("list_modules")
            (response.result as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list modules", e)
            emptyList()
        }
    }

    suspend fun getDumperStatus(): Map<String, Any>? {
        return try {
            val response = callRpc("get_dumper_status")
            response.result as? Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dumper status", e)
            null
        }
    }
    suspend fun analyzeClass(className: String): Map<String, Any>? {
        val response = callRpc("analyze_class", listOf(className))
        return response.result as? Map<String, Any>
    }

    suspend fun findClasses(pattern: String): List<Map<String, Any>> {
        val response = callRpc("find_classes_matching", listOf(pattern))
        return (response.result as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
    }

    suspend fun traceAddress(address: String, name: String? = null, argCount: Int = 0, captureStack: Boolean = false): Boolean {
        val response = callRpc("trace_address", listOf(address, name ?: address, argCount, captureStack))
        return response.result as? Boolean ?: false
    }

    suspend fun traceSymbol(symbol: String, argCount: Int = 0, captureStack: Boolean = false): Boolean {
        val response = callRpc("trace_symbol", listOf(symbol, argCount, captureStack))
        return response.result as? Boolean ?: false
    }

    suspend fun untrace(target: String): Boolean {
        val response = callRpc("untrace", listOf(target))
        return response.result as? Boolean ?: false
    }

    suspend fun startStalker(moduleName: String? = null): Boolean {
        val params = if (moduleName != null) listOf(moduleName) else emptyList()
        val response = callRpc("startStalker", params)
        return response.result as? Boolean ?: false
    }

    suspend fun stopStalker(): Boolean {
        val response = callRpc("stopStalker")
        return response.result as? Boolean ?: false
    }

    suspend fun sendUiEvent(controlId: String, value: Any): Boolean {
        val response = callRpc("onUiEvent", listOf(controlId, value))
        return response.result as? Boolean ?: false
    }

    suspend fun findPattern(moduleName: String, pattern: String): List<Map<String, Any>> {
        val response = callRpc("scanMemory", listOf(pattern, moduleName))
        return (response.result as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
    }

    suspend fun writeMemory(address: String, hexValue: String): Boolean {
        val response = callRpc("writeMemory", listOf(address, hexValue))
        return response.result as? Boolean ?: false
    }

    suspend fun getRelativeOffset(address: String, moduleName: String? = null): Map<String, Any>? {
        val params = if (moduleName != null) listOf(address, moduleName) else listOf(address)
        val response = callRpc("get_relative_offset", params)
        return response.result as? Map<String, Any>
    }

    fun cleanup() {
        scope.cancel()
        rpcClient?.close()
    }
}

class FridaRpcClient(private val host: String, private val port: Int) {
    companion object {
        private const val TAG = "FridaRpcClient"
        private const val READ_TIMEOUT_MS = 10000
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private var socket: java.net.Socket? = null
    private var nextId = 1
    private var connected = false

    init {
        connect()
    }

    private fun connect() {
        try {
            socket = java.net.Socket().apply {
                soTimeout = READ_TIMEOUT_MS
                connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            connected = true
            Log.d(TAG, "Connected to $host:$port")
        } catch (e: Exception) {
            connected = false
            Log.d(TAG, "Connection failed to $host:$port: ${e.message}")
            throw e
        }
    }

    fun call(method: String, params: List<Any> = emptyList()): Any? {
        return try {
            if (!connected || socket == null) {
                connect()
            }

            val id = nextId++
            val request = buildJsonRpcRequest(id, method, params)
            
            val socket = socket ?: throw Exception("Socket not connected")
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            output.write(request.toByteArray())
            output.flush()

            val buffer = ByteArray(65536)
            val bytesRead = input.read(buffer)
            
            if (bytesRead <= 0) {
                throw Exception("Connection closed by server")
            }
            
            val response = String(buffer, 0, bytesRead)
            parseJsonRpcResponse(response)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "RPC call timeout for $method", e)
            connected = false
            null
        } catch (e: java.net.SocketException) {
            Log.e(TAG, "Socket error during RPC call", e)
            connected = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "RPC call failed for $method", e)
            connected = false
            null
        }
    }

    private fun buildJsonRpcRequest(id: Int, method: String, params: List<Any>): String {
        val paramStr = params.joinToString(",") { formatJsonValue(it) }
        return """{"jsonrpc":"2.0","id":$id,"method":"$method","params":[$paramStr]}
"""
    }

    private fun formatJsonValue(value: Any): String {
        return when (value) {
            is String -> "\"${value.replace("\"", "\\\"")}\"" 
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> "\"${value.toString().replace("\"", "")}\"" 
        }
    }

    private fun parseJsonRpcResponse(response: String?): Any? {
        return try {
            if (response.isNullOrBlank()) {
                Log.w(TAG, "Empty response from RPC server")
                return null
            }
            
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(response, Map::class.java) as? Map<*, *>
            
            when {
                map != null && map.containsKey("result") -> {
                    Log.d(TAG, "RPC Success: result type = ${map["result"]?.javaClass?.simpleName}")
                    map["result"]
                }
                map != null && map.containsKey("error") -> {
                    val error = map["error"] as? Map<*, *>
                    val errorMsg = error?.get("message") as? String ?: "Unknown RPC error"
                    val errorCode = error?.get("code") as? Number
                    Log.e(TAG, "RPC Error [$errorCode]: $errorMsg")
                    null
                }
                else -> {
                    Log.w(TAG, "Unexpected RPC response format: $response")
                    null
                }
            }
        } catch (e: com.google.gson.JsonSyntaxException) {
            Log.e(TAG, "Failed to parse RPC response as JSON: $response", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RPC response", e)
            null
        }
    }

    fun close() {
        try {
            socket?.close()
            connected = false
            Log.d(TAG, "Socket closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket", e)
        }
    }
}

class RpcManager(private val context: Context) {
    companion object {
        private const val TAG = "RpcManager"
    }

    private val rpcBridge = RpcBridge(context)
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    suspend fun startRpc(): Boolean {
        return rpcBridge.startRpcServer()
    }

    fun listHooksAsync(callback: (List<HookInfo>) -> Unit) {
        scope.launch {
            val hooks = rpcBridge.listHooks()
            callback(hooks)
        }
    }

    fun toggleHookAsync(hookName: String, callback: (Boolean) -> Unit) {
        scope.launch {
            val result = rpcBridge.toggleHook(hookName)
            callback(result)
        }
    }

    fun dumpMemoryAsync(address: String, size: Int, callback: (ByteArray?) -> Unit) {
        scope.launch {
            val data = rpcBridge.dumpMemory(address, size)
            callback(data)
        }
    }

    fun getDiagnosticsAsync(callback: (Map<String, Any>?) -> Unit) {
        scope.launch {
            val result = rpcBridge.getRpcDiagnostics()
            callback(result)
        }
    }

    fun listTracesAsync(callback: (Map<String, Any>?) -> Unit) {
        scope.launch {
            val result = rpcBridge.listActiveTraces()
            callback(result)
        }
    }

    fun getModuleInfoAsync(moduleName: String? = null, callback: (Map<String, Any>?) -> Unit) {
        scope.launch {
            val result = rpcBridge.getModuleInfo(moduleName)
            callback(result)
        }
    }

    fun listModulesAsync(callback: (List<Map<String, Any>>) -> Unit) {
        scope.launch {
            val result = rpcBridge.listModules()
            callback(result)
        }
    }

    fun getDumperStatusAsync(callback: (Map<String, Any>?) -> Unit) {
        scope.launch {
            val result = rpcBridge.getDumperStatus()
            callback(result)
        }
    }

    fun analyzeClassAsync(className: String, callback: (Map<String, Any>?) -> Unit) {
        scope.launch {
            val result = rpcBridge.analyzeClass(className)
            callback(result)
        }
    }

    fun findClassesAsync(pattern: String, callback: (List<Map<String, Any>>) -> Unit) {
        scope.launch {
            val result = rpcBridge.findClasses(pattern)
            callback(result)
        }
    }

    fun traceAddressAsync(address: String, name: String? = null, argCount: Int = 0, captureStack: Boolean = false, callback: (Boolean) -> Unit) {
        scope.launch {
            val result = rpcBridge.traceAddress(address, name, argCount, captureStack)
            callback(result)
        }
    }

    fun findPatternAsync(moduleName: String, pattern: String, callback: (List<Map<String, Any>>) -> Unit) {
        scope.launch {
            val result = rpcBridge.findPattern(moduleName, pattern)
            callback(result)
        }
    }

    fun writeMemoryAsync(address: String, hexValue: String, callback: (Boolean) -> Unit = {}) {
        scope.launch {
            val result = rpcBridge.writeMemory(address, hexValue)
            callback(result)
        }
    }

    fun startStalkerAsync(moduleName: String? = null, callback: (Boolean) -> Unit = {}) {
        scope.launch {
            val result = rpcBridge.startStalker(moduleName)
            callback(result)
        }
    }

    fun stopStalkerAsync(callback: (Boolean) -> Unit = {}) {
        scope.launch {
            val result = rpcBridge.stopStalker()
            callback(result)
        }
    }

    fun sendUiEventAsync(controlId: String, value: Any, callback: (Boolean) -> Unit = {}) {
        scope.launch {
            val result = rpcBridge.sendUiEvent(controlId, value)
            callback(result)
        }
    }

    fun cleanup() {
        scope.cancel()
        rpcBridge.cleanup()
    }
}
