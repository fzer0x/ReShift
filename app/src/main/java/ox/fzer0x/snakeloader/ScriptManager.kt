package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ScriptManager(private val context: Context) {
    companion object {
        private const val TAG = "ScriptManager"
        private const val SCRIPTS_FILE = "downloaded_scripts.json"
        private const val ASSIGNMENTS_FILE = "app_assignments.json"
        private const val RECENT_FILE = "recent_scripts.json"
        private const val RECENT_APPS_FILE = "recent_apps.json"
        private const val MAX_RECENT = 10
    }

    private val gson = Gson()
    private val scriptsFile = File(context.filesDir, SCRIPTS_FILE)
    private val assignmentsFile = File(context.filesDir, ASSIGNMENTS_FILE)
    private val recentFile = File(context.filesDir, RECENT_FILE)
    private val recentAppsFile = File(context.filesDir, RECENT_APPS_FILE)

    private var _scripts = mutableListOf<DownloadedScript>()
    private var _assignments = mutableMapOf<String, MutableSet<String>>()
    private var _recent = mutableListOf<String>()
    private var _recentApps = mutableListOf<String>()

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        loadAll()
        isInitialized = true
    }

    private fun loadAll() {
        _scripts = loadList(scriptsFile, object : TypeToken<List<DownloadedScript>>() {}.type)
        
        var modified = false
        _scripts.forEachIndexed { index, script ->
            val result = extractMetadata(script.content)
            val metadata = result["metadata"] as ModuleMetadata
            val description = (result["description"] as? String)
            
            val currentPlaceholder = script.description?.contains("Imported from", ignoreCase = true) ?: true
            val newDescription = if (currentPlaceholder && description != null) description else script.description
            
            if (script.metadata != metadata || script.description != newDescription) {
                _scripts[index] = script.copy(metadata = metadata, description = newDescription)
                modified = true
            }
        }
        if (modified) saveToFile(scriptsFile, _scripts)

        val assignmentData = loadMap<String, List<String>>(assignmentsFile, object : TypeToken<Map<String, List<String>>>() {}.type)
        _assignments = assignmentData.mapValues { it.value.toMutableSet() }.toMutableMap()
        _recent = loadList(recentFile, object : TypeToken<List<String>>() {}.type)
        _recentApps = loadList(recentAppsFile, object : TypeToken<List<String>>() {}.type)
    }

    private fun <T> loadList(file: File, type: java.lang.reflect.Type): MutableList<T> {
        return try {
            if (file.exists()) {
                val json = file.readText()
                gson.fromJson(json, type) ?: mutableListOf()
            } else {
                mutableListOf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load list from ${file.name}", e)
            mutableListOf()
        }
    }

    private fun <K, V> loadMap(file: File, type: java.lang.reflect.Type): MutableMap<K, V> {
        return try {
            if (file.exists()) {
                val json = file.readText()
                gson.fromJson(json, type) ?: mutableMapOf()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load map from ${file.name}", e)
            mutableMapOf()
        }
    }

    private fun saveAll() {
        saveToFile(scriptsFile, _scripts)
        saveToFile(assignmentsFile, _assignments.mapValues { it.value.toList() })
        saveToFile(recentFile, _recent)
        saveToFile(recentAppsFile, _recentApps)
    }

    private fun saveToFile(file: File, data: Any) {
        try {
            val json = gson.toJson(data)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to ${file.name}", e)
        }
    }

    fun getScripts(): List<DownloadedScript> = _scripts.toList()

    fun updateScriptContent(scriptId: String, newContent: String) {
        val index = _scripts.indexOfFirst { it.id == scriptId }
        if (index != -1) {
            val script = _scripts[index]
            val result = extractMetadata(newContent)
            val metadata = result["metadata"] as ModuleMetadata
            val description = (result["description"] as? String) ?: script.description
            
            _scripts[index] = script.copy(
                content = newContent,
                metadata = metadata,
                description = description
            )
            saveToFile(scriptsFile, _scripts)
        }
    }

    fun addScript(script: DownloadedScript) {
        val result = extractMetadata(script.content)
        val metadata = result["metadata"] as ModuleMetadata
        val description = (result["description"] as? String) ?: script.description
        
        val enrichedScript = script.copy(metadata = metadata, description = description)
        
        _scripts.removeAll { it.id == enrichedScript.id }
        _scripts.add(enrichedScript)
        saveToFile(scriptsFile, _scripts)
        
        metadata.targetPackages.forEach { pkg ->
            assignScriptToApp(pkg, enrichedScript.id)
        }
    }

    fun importScriptFromUri(uri: android.net.Uri, fileName: String) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                val result = extractMetadata(content)
                val extractedName = result["name"] as? String
                val description = (result["description"] as? String) ?: "Imported from file manager"
                
                val script = DownloadedScript(
                    id = "local/${System.currentTimeMillis()}/$fileName",
                    name = extractedName ?: fileName.removeSuffix(".js"),
                    content = content,
                    repository = "Local Storage",
                    path = fileName,
                    description = description
                )
                addScript(script)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import script from URI", e)
        }
    }

    fun listAssetScripts(): List<String> {
        return try {
            val result = mutableListOf<String>()
            fun scanDir(path: String, relativePath: String = "") {
                val items = context.assets.list(path) ?: emptyArray()
                Log.d(TAG, "Scanning path: $path (relative: $relativePath), found ${items.size} items")
                
                for (item in items) {
                    val itemRelativePath = if (relativePath.isEmpty()) item else "$relativePath/$item"
                    val fullPath = if (path == "ReShiftModules") "ReShiftModules/$item" else "$path/$item"
                    
                    try {
                        val subItems = context.assets.list(fullPath)
                        if (subItems != null && subItems.isNotEmpty()) {
                            Log.d(TAG, "Found subdirectory: $itemRelativePath with ${subItems.size} items")
                            scanDir(fullPath, itemRelativePath)
                        } else if (item.endsWith(".js")) {
                            Log.d(TAG, "Found JS file: $itemRelativePath")
                            result.add(itemRelativePath)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Exception checking $fullPath: ${e.message}")
                        if (item.endsWith(".js")) {
                            result.add(itemRelativePath)
                        }
                    }
                }
            }
            scanDir("ReShiftModules")
            Log.d(TAG, "Total scripts found: ${result.size}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list ReShiftModules", e)
            emptyList()
        }
    }

    fun importScriptFromAsset(fileName: String) {
        try {
            val content = context.assets.open("ReShiftModules/$fileName").bufferedReader().use { it.readText() }
            val result = extractMetadata(content)
            val extractedName = result["name"] as? String
            val metadata = result["metadata"] as ModuleMetadata
            val description = (result["description"] as? String) ?: "Built-in module from ReShift"

            val script = DownloadedScript(
                id = "reshift/$fileName",
                name = extractedName ?: fileName.removeSuffix(".js"),
                content = content,
                repository = "ReShift Modules",
                path = fileName,
                description = description
            )
            addScript(script)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import script from ReShiftModules", e)
        }
    }

    fun deleteScript(scriptId: String) {
        _scripts.removeAll { it.id == scriptId }
        _recent.remove(scriptId)
        _assignments.values.forEach { it.remove(scriptId) }
        saveAll()
    }

    fun setEnabled(scriptId: String, enabled: Boolean) {
        val index = _scripts.indexOfFirst { it.id == scriptId }
        if (index != -1) {
            _scripts[index] = _scripts[index].copy(isEnabled = enabled)
            saveToFile(scriptsFile, _scripts)
        }
    }

    fun getAssignmentsForApp(packageName: String): List<DownloadedScript> {
        val scriptIds = _assignments[packageName] ?: return emptyList()
        return _scripts.filter { it.id in scriptIds && it.isEnabled }
    }

    fun assignScriptToApp(packageName: String, scriptId: String) {
        _assignments.getOrPut(packageName) { mutableSetOf() }.add(scriptId)
        saveToFile(assignmentsFile, _assignments.mapValues { it.value.toList() })
        addToRecent(scriptId)
    }

    fun unassignScriptFromApp(packageName: String, scriptId: String) {
        _assignments[packageName]?.remove(scriptId)
        saveToFile(assignmentsFile, _assignments.mapValues { it.value.toList() })
    }

    private fun addToRecent(scriptId: String) {
        _recent.remove(scriptId)
        _recent.add(0, scriptId)
        if (_recent.size > MAX_RECENT) {
            _recent = _recent.take(MAX_RECENT).toMutableList()
        }
        saveToFile(recentFile, _recent)
    }

    fun getRecent(): List<DownloadedScript> {
        return _recent.mapNotNull { id -> _scripts.find { it.id == id } }
    }

    fun addToRecentApps(packageName: String) {
        _recentApps.remove(packageName)
        _recentApps.add(0, packageName)
        if (_recentApps.size > 5) {
            _recentApps = _recentApps.take(5).toMutableList()
        }
        saveToFile(recentAppsFile, _recentApps)
    }

    fun getRecentApps(): List<String> {
        return _recentApps.toList()
    }

    fun getAppCountForScript(scriptId: String): Int {
        return _assignments.values.count { scriptId in it }
    }

    fun getAppsForScript(scriptId: String): List<String> {
        return _assignments.filter { scriptId in it.value }.map { it.key }
    }

    fun getAppsWithActiveScripts(): Set<String> {
        val activeScriptIds = _scripts.filter { it.isEnabled }.map { it.id }.toSet()
        return _assignments.filter { entry ->
            entry.value.any { it in activeScriptIds }
        }.keys
    }

    fun extractMetadata(content: String): Map<String, Any?> {
        var name: String? = null
        var version: String? = null
        var author: String? = null
        var description: String? = null
        var noEdit = false
        val targetPackages = mutableListOf<String>()

        val lines = content.lines().take(100)
        var inHeaderComment = false
        val headerLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("/*") || trimmedLine.startsWith("*") || (inHeaderComment && trimmedLine.isNotEmpty())) {
                inHeaderComment = true
                headerLines.add(trimmedLine.removePrefix("/*").removePrefix("*").trim())
                if (trimmedLine.contains("*/")) {
                    inHeaderComment = false
                    break
                }
                continue
            }
            if (trimmedLine.startsWith("//")) {
                headerLines.add(trimmedLine.removePrefix("//").trim())
                continue
            }
            if (trimmedLine.isNotEmpty()) break
        }

        for (cleanLine in headerLines) {
            when {
                cleanLine.startsWith("@version", ignoreCase = true) -> {
                    version = cleanLine.substringAfter("@version").trim().removePrefix(":").trim()
                }
                cleanLine.startsWith("@author", ignoreCase = true) -> {
                    author = cleanLine.substringAfter("@author").trim().removePrefix(":").trim()
                }
                cleanLine.startsWith("@description", ignoreCase = true) -> {
                    val potential = cleanLine.substringAfter("@description").trim().removePrefix(":").trim()
                    if (potential.isGoodDescription()) {
                        description = potential
                    }
                }
                cleanLine.equals("@noedit", ignoreCase = true) -> {
                    noEdit = true
                }
                cleanLine.startsWith("@target", ignoreCase = true) -> {
                    val pkg = cleanLine.substringAfter("@target").trim().removePrefix(":").trim()
                    if (pkg.isNotEmpty()) targetPackages.add(pkg)
                }
                !cleanLine.startsWith("@") && cleanLine.isNotEmpty() && name == null -> {
                    name = cleanLine.trim()
                }
                description == null && !cleanLine.startsWith("@") && cleanLine.length > 20 && cleanLine.isGoodDescription() -> {
                    description = cleanLine
                }
            }
        }

        return mapOf(
            "name" to name,
            "metadata" to ModuleMetadata(
                version = version, 
                author = author, 
                targetPackages = targetPackages,
                noEdit = noEdit
            ),
            "description" to description
        )
    }
}
