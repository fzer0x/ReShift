package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

import ox.fzer0x.snakeloader.utils.ShellExecutor
import ox.fzer0x.snakeloader.utils.InputValidator
import ox.fzer0x.snakeloader.security.CertificatePinner

class GitHubApiService(private val context: Context) {
    companion object {
        private const val TAG = "GitHubApiService"
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val PREFS_NAME = "github_cache_v3"
        private const val CUSTOM_REPOS_KEY = "custom_repositories"
        private const val CACHE_EXPIRATION_MS = 30 * 60 * 1000
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val defaultRepositories = listOf(
        ScriptRepository(
            name = "frida-snippets",
            owner = "iddoeldor",
            description = "Collection of Frida snippets",
            url = "https://github.com/iddoeldor/frida-snippets",
            stars = 2500,
            defaultPath = "scripts"
        ),
        ScriptRepository(
            name = "frida-codeshare-scripts",
            owner = "zengfr",
            description = "Frida codeshare scripts",
            url = "https://github.com/zengfr/frida-codeshare-scripts",
            stars = 0,
            defaultPath = "Android"
        ),
        ScriptRepository(
            name = "medusa",
            owner = "Ch0pin",
            description = "Medusa Frida modules",
            url = "https://github.com/Ch0pin/medusa",
            stars = 0,
            defaultPath = "modules"
        ),
        ScriptRepository(
            name = "frida-scripts",
            owner = "as0ler",
            description = "iOS Frida scripts (hooks)",
            url = "https://github.com/as0ler/frida-scripts",
            stars = 0,
            defaultPath = "hooks"
        ),
        ScriptRepository(
            name = "frida-scripts",
            owner = "0xdea",
            description = "Android Frida snippets by raptor",
            url = "https://github.com/0xdea/frida-scripts",
            stars = 0,
            defaultPath = "android-snippets"
        ),
        ScriptRepository(
            name = "Frida-Android-Scripts",
            owner = "TheCjw",
            description = "Collection of Android Frida scripts",
            url = "https://github.com/TheCjw/Frida-Android-Scripts",
            stars = 0
        ),
        ScriptRepository(
            name = "frida-scripts",
            owner = "interference-security",
            description = "Android/iOS Frida scripts",
            url = "https://github.com/interference-security/frida-scripts",
            stars = 0,
            defaultPath = "android"
        )
    )

    private var _customRepositories = mutableListOf<ScriptRepository>()
    val repositories: List<ScriptRepository> get() = defaultRepositories + _customRepositories

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        loadCustomRepositories()
        isInitialized = true
    }

    private fun loadCustomRepositories() {
        val json = prefs.getString(CUSTOM_REPOS_KEY, null)
        if (json != null) {
            try {
                val array = JSONArray(json)
                _customRepositories.clear()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    _customRepositories.add(
                        ScriptRepository(
                            name = obj.getString("name"),
                            owner = obj.getString("owner"),
                            description = obj.optString("description"),
                            url = obj.getString("url"),
                            stars = 0,
                            defaultPath = obj.optString("defaultPath")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load custom repos", e)
            }
        }
    }

    private fun saveCustomRepositories() {
        val array = JSONArray()
        _customRepositories.forEach { repo ->
            val obj = JSONObject()
            obj.put("name", repo.name)
            obj.put("owner", repo.owner)
            obj.put("description", repo.description)
            obj.put("url", repo.url)
            obj.put("defaultPath", repo.defaultPath)
            array.put(obj)
        }
        prefs.edit().putString(CUSTOM_REPOS_KEY, array.toString()).apply()
    }

    fun addCustomRepository(owner: String, name: String, defaultPath: String = "") {
        if (!InputValidator.validateGitHubRepo(owner, name)) {
            Log.e(TAG, "Invalid GitHub repository parameters: owner=$owner, name=$name")
            return
        }

        val url = "https://github.com/$owner/$name"
        if (_customRepositories.any { it.url == url }) return
        
        _customRepositories.add(
            ScriptRepository(
                name = name,
                owner = owner,
                description = "User added repository",
                url = url,
                stars = 0,
                defaultPath = defaultPath
            )
        )
        saveCustomRepositories()
    }

    fun removeCustomRepository(url: String) {
        _customRepositories.removeAll { it.url == url }
        saveCustomRepositories()
    }

    suspend fun fetchAllRepositories() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Pre-loading all repositories...")
            repositories.forEach { repo ->
                if (!isCacheValid(repo.owner, repo.name, repo.defaultPath)) {
                    Log.d(TAG, "Cache invalid for ${repo.name}, fetching...")
                    fetchRepositoryContents(repo.owner, repo.name, repo.defaultPath, forceRefresh = false)
                } else {
                    Log.d(TAG, "Cache still valid for ${repo.name}")
                }
            }
        }
    }

    private fun getCacheKey(owner: String, repo: String, path: String): String {
        return "${owner}_${repo}_${path.replace("/", "_")}"
    }

    private fun isCacheValid(owner: String, repo: String, path: String): Boolean {
        val key = getCacheKey(owner, repo, path)
        val lastUpdate = prefs.getLong("${key}_time", 0)
        return (System.currentTimeMillis() - lastUpdate) < CACHE_EXPIRATION_MS
    }

    private fun saveToCache(owner: String, repo: String, path: String, json: String) {
        val key = getCacheKey(owner, repo, path)
        prefs.edit()
            .putString(key, json)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    private fun getFromCache(owner: String, repo: String, path: String): String? {
        val key = getCacheKey(owner, repo, path)
        return prefs.getString(key, null)
    }

    suspend fun fetchRepositoryContents(
        owner: String, 
        repo: String, 
        path: String = "", 
        forceRefresh: Boolean = false
    ): Result<List<ScriptFile>> {
        return withContext(Dispatchers.IO) {
            if (!forceRefresh && isCacheValid(owner, repo, path)) {
                getFromCache(owner, repo, path)?.let { cachedJson ->
                    Log.d(TAG, "Returning cached contents for $repo/$path")
                    return@withContext try {
                        val scripts = parseRepositoryContents(cachedJson, owner, repo, path)
                        Result.success(scripts)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse cached JSON", e)
                        Result.failure(e)
                    }
                }
            }

            val cleanPath = path.trim('/')
            val urlString = if (cleanPath.isEmpty()) {
                "$GITHUB_API_BASE/repos/$owner/$repo/contents"
            } else {
                "$GITHUB_API_BASE/repos/$owner/$repo/contents/$cleanPath"
            }

            try {
                Log.d(TAG, "Standard Request: $urlString")
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                
                if (connection is HttpsURLConnection) {
                    CertificatePinner.configureConnectionWithPinning(connection, "api.github.com")
                }
                
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "ReShift/1.0")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val initialFiles = parseRepositoryContents(response, owner, repo, path)
                    
                    var readmeUrl = initialFiles.find { it.name.equals("README.md", ignoreCase = true) }?.downloadUrl
                    
                    if (readmeUrl == null && path.isNotEmpty()) {
                        Log.d(TAG, "README not found in $path, checking root...")
                        val rootContentsUrl = "$GITHUB_API_BASE/repos/$owner/$repo/contents"
                        val rootResponse = try {
                            val rootConn = URL(rootContentsUrl).openConnection() as HttpURLConnection
                            
                            if (rootConn is HttpsURLConnection) {
                                CertificatePinner.configureConnectionWithPinning(rootConn, "api.github.com")
                            }
                            
                            rootConn.setRequestProperty("User-Agent", "ReShift/1.0")
                            if (rootConn.responseCode == 200) rootConn.inputStream.bufferedReader().use { it.readText() } else null
                        } catch (e: Exception) { executeRootCurl(rootContentsUrl) }
                        
                        if (rootResponse != null) {
                            val rootFiles = parseRepositoryContents(rootResponse, owner, repo, "")
                            readmeUrl = rootFiles.find { it.name.equals("README.md", ignoreCase = true) }?.downloadUrl
                        }
                    }

                    val enrichedFiles = if (readmeUrl != null) {
                        fetchReadmeAndEnrichScripts(initialFiles, readmeUrl)
                    } else {
                        initialFiles
                    }
                    
                    val enrichedJson = convertScriptsToJson(enrichedFiles)
                    saveToCache(owner, repo, path, enrichedJson)
                    
                    return@withContext Result.success(enrichedFiles)
                } else if (responseCode == 403) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (error.contains("rate limit exceeded")) {
                        getFromCache(owner, repo, path)?.let {
                            Log.w(TAG, "Rate limit hit, returning expired cache for $repo")
                            return@withContext Result.success(parseRepositoryContents(it, owner, repo, path))
                        }
                        return@withContext Result.failure(Exception("GitHub API rate limit exceeded. Please wait a while."))
                    }
                }
                
                Log.w(TAG, "Standard request failed ($responseCode), trying root fallback...")
                val rootResponse = executeRootCurl(urlString)
                if (rootResponse != null) {
                    val initialFiles = parseRepositoryContents(rootResponse, owner, repo, path)
                    val readmeUrl = initialFiles.find { it.name.equals("README.md", ignoreCase = true) }?.downloadUrl
                    val enrichedFiles = if (readmeUrl != null) {
                        fetchReadmeAndEnrichScripts(initialFiles, readmeUrl)
                    } else {
                        initialFiles
                    }
                    val enrichedJson = convertScriptsToJson(enrichedFiles)
                    saveToCache(owner, repo, path, enrichedJson)
                    return@withContext Result.success(enrichedFiles)
                }

                Result.failure(Exception("HTTP $responseCode: Failed to fetch contents"))
            } catch (e: Exception) {
                Log.e(TAG, "Standard fetch failed: ${e.message}, trying root fallback...")
                val rootResponse = executeRootCurl(urlString)
                if (rootResponse != null) {
                    return@withContext try {
                        val initialFiles = parseRepositoryContents(rootResponse, owner, repo, path)
                        val readmeUrl = initialFiles.find { it.name.equals("README.md", ignoreCase = true) }?.downloadUrl
                        val enrichedFiles = if (readmeUrl != null) fetchReadmeAndEnrichScripts(initialFiles, readmeUrl) else initialFiles
                        saveToCache(owner, repo, path, convertScriptsToJson(enrichedFiles))
                        Result.success(enrichedFiles)
                    } catch (parseEx: Exception) {
                        Result.failure(parseEx)
                    }
                }
                getFromCache(owner, repo, path)?.let {
                    Log.w(TAG, "Network failed, returning expired cache for $repo")
                    return@withContext Result.success(parseRepositoryContents(it, owner, repo, path))
                }
                Result.failure(e)
            }
        }
    }

    private suspend fun fetchReadmeAndEnrichScripts(scripts: List<ScriptFile>, readmeUrl: String): List<ScriptFile> {
        val readmeContent = downloadScriptContent(readmeUrl) ?: return scripts
        Log.d(TAG, "Parsing README for descriptions...")
        
        return scripts.map { script ->
            if (script.isDirectory || script.name.endsWith(".md")) script
            else {
                val description = extractDescriptionFromReadme(script.name, readmeContent)
                if (description != null) script.copy(description = description) else script
            }
        }
    }

    private fun extractDescriptionFromReadme(fileName: String, readme: String): String? {
        val lines = readme.lines()
        val fileNameWithoutExt = fileName.removeSuffix(".js")
        
        for (line in lines) {
            if (line.contains("|") && (line.contains(fileName, ignoreCase = true) || line.contains(fileNameWithoutExt, ignoreCase = true))) {
                val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.size >= 2) {
                    val fileIndex = cells.indexOfFirst { it.contains(fileName, ignoreCase = true) || it.contains(fileNameWithoutExt, ignoreCase = true) }
                    if (fileIndex != -1) {
                        if (fileIndex + 1 < cells.size) {
                            val desc = cells[fileIndex + 1].trim().trim('*', '_', '`', ' ')
                            if (desc.isGoodDescription()) return desc.take(200)
                        }
                        if (fileIndex > 0) {
                            val desc = cells[fileIndex - 1].trim().trim('*', '_', '`', ' ')
                            if (desc.isGoodDescription()) return desc.take(200)
                        }
                    }
                }
            }
        }

        for (line in lines) {
            val trimmedLine = line.trim().removePrefix("-").removePrefix("*").trim()
            if (trimmedLine.isEmpty()) continue

            if (trimmedLine.contains(fileName, ignoreCase = true) || trimmedLine.contains(fileNameWithoutExt, ignoreCase = true)) {
                val mdLinkRegex = Regex("\\[(.*?)\\]\\(.*?\\)\\s+(.*)")
                val match = mdLinkRegex.find(trimmedLine)
                if (match != null) {
                    val namePart = match.groupValues[1]
                    val descPart = match.groupValues[2]
                    if (namePart.contains(fileNameWithoutExt, ignoreCase = true) && descPart.isGoodDescription()) {
                        return descPart.trim().trim('*', '_', '`', ' ').take(200)
                    }
                }

                val cleanLine = trimmedLine.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
                val separators = listOf(":", " - ", " – ", " — ", " | ")
                for (sep in separators) {
                    if (cleanLine.contains(sep)) {
                        val parts = cleanLine.split(sep, limit = 2)
                        if (parts[0].contains(fileNameWithoutExt, ignoreCase = true)) {
                            val desc = parts[1].trim().trim('*', '_', '`', ' ')
                            if (desc.isGoodDescription()) return desc.take(200)
                        }
                    }
                }
            }
        }
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#") && (line.contains(fileName, ignoreCase = true) || line.contains(fileNameWithoutExt, ignoreCase = true))) {
                for (j in i + 1 until minOf(i + 5, lines.size)) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        if (nextLine.isGoodDescription()) return nextLine.take(200)
                        else break
                    }
                }
            }
        }

        return null
    }

    private fun convertScriptsToJson(scripts: List<ScriptFile>): String {
        val array = JSONArray()
        scripts.forEach { script ->
            val obj = JSONObject()
            obj.put("name", script.name)
            obj.put("path", script.path)
            obj.put("download_url", script.downloadUrl)
            obj.put("size", script.size)
            obj.put("type", if (script.isDirectory) "dir" else "file")
            obj.put("description", script.description ?: "")
            array.put(obj)
        }
        return array.toString()
    }

    private fun executeRootCurl(url: String): String? {
        val command = "curl -s -H 'Accept: application/vnd.github+json' -H 'User-Agent: JS-INJECT/1.0' '$url'"
        val result = ShellExecutor.execute(command, useRoot = true)
        
        return if (result.isSuccess && result.stdout.isNotEmpty()) {
            Log.d(TAG, "Root curl successful")
            result.stdout
        } else {
            Log.e(TAG, "Root curl failed: ${result.stderr}")
            null
        }
    }

    private fun parseRepositoryContents(json: String, owner: String, repo: String, currentPath: String): List<ScriptFile> {
        if (json.isEmpty()) return emptyList()
        
        if (!json.trim().startsWith("[")) {
            val jsonObject = try { JSONObject(json) } catch (e: Exception) { null }
            if (jsonObject?.has("message") == true) {
                throw Exception(jsonObject.getString("message"))
            }
            return emptyList()
        }

        val files = mutableListOf<ScriptFile>()
        val jsonArray = JSONArray(json)
        val repoInfo = repositories.find { it.owner == owner && it.name == repo }
            ?: ScriptRepository(repo, owner, "", "", 0)

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val type = item.optString("type")
            val name = item.optString("name")
            val path = item.optString("path")
            val description = item.optString("description").takeIf { it.isGoodDescription() }
            
            if (type == "file" && (name.endsWith(".js") || name.equals("README.md", ignoreCase = true))) {
                val downloadUrl = item.optString("download_url")
                val size = item.optLong("size")
                val validatedDesc = description?.takeIf { it.isGoodDescription() }
                files.add(ScriptFile(name, path, downloadUrl, size, repoInfo, false, validatedDesc))
            } else if (type == "dir") {
                val validatedDesc = description?.takeIf { it.isGoodDescription() }
                files.add(ScriptFile(name, path, "", 0, repoInfo, true, validatedDesc))
            }
        }
        
        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun downloadScriptContent(downloadUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                if (connection is HttpsURLConnection) {
                    val hostname = url.host
                    CertificatePinner.configureConnectionWithPinning(connection, hostname)
                }
                
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "ReShift/1.0")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                if (connection.responseCode == 200) {
                    return@withContext connection.inputStream.bufferedReader().use { it.readText() }
                }
                
                executeRootCurl(downloadUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed, trying root fallback...")
                executeRootCurl(downloadUrl)
            }
        }
    }
}
