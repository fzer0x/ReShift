package ox.fzer0x.snakeloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

data class CodeShareProject(
    val name: String,
    val author: String,
    val slug: String,
    val description: String,
    val likes: Int = 0,
    val views: String = ""
)

class CodeShareApiService(private val context: Context) {
    companion object {
        private const val TAG = "CodeShareApiService"
        private const val BASE_URL = "https://codeshare.frida.re"
        private const val API_BASE_URL = "$BASE_URL/api/project"
    }

    suspend fun fetchProjects(query: String = "", page: Int = 1): Result<Pair<List<CodeShareProject>, Int>> {
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = if (query.isNotEmpty()) URLEncoder.encode(query, "UTF-8") else ""
                val urlString = if (query.isEmpty()) {
                    "$BASE_URL/browse?page=$page"
                } else {
                    "$BASE_URL/search/?query=$encodedQuery&page=$page"
                }

                Log.d(TAG, "Fetching: $urlString")
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                if (connection.responseCode == 200) {
                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "Fetched HTML length: ${html.length}")
                    if (html.length < 500) {
                        Log.d(TAG, "HTML preview: $html")
                    }
                    val projects = parseBrowseHtml(html)
                    val maxPage = parseMaxPage(html)
                    Log.d(TAG, "Parsed ${projects.size} projects, maxPage: $maxPage")
                    Result.success(Pair(projects, maxPage))
                } else {
                    Log.e(TAG, "HTTP error: ${connection.responseCode}")
                    Result.failure(Exception("HTTP ${connection.responseCode}: Failed to fetch CodeShare"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch projects", e)
                Result.failure(e)
            }
        }
    }

    private fun parseMaxPage(html: String): Int {
        var max = 1
        try {
            val pagePattern = Pattern.compile("page=(\\d+)")
            val matcher = pagePattern.matcher(html)
            while (matcher.find()) {
                val p = matcher.group(1)?.toIntOrNull() ?: 1
                if (p > max) max = p
            }
        } catch (ignore: Exception) {
        }
        return max
    }

    private fun parseBrowseHtml(html: String): List<CodeShareProject> {
        val projects = mutableListOf<CodeShareProject>()
        
        val pattern = Pattern.compile("href\\s*=\\s*['\"](?:https://codeshare\\.frida\\.re)?/@([^/\"'\\s?]+)/([^/\"'\\s?]+)/?['\"][^>]*>(.*?)</a>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        
        while (matcher.find()) {
            val author = matcher.group(1) ?: ""
            val slug = matcher.group(2) ?: ""
            var name = matcher.group(3) ?: ""
            
            if (author.isNotEmpty() && slug.isNotEmpty() && 
                slug != "browse" && slug != "api" && slug != "login" && slug != "signup") {
                
                name = name.replace(Regex("<[^>]*>"), "").trim()
                
                if (name.isNotEmpty() && 
                    !name.equals("Project Page", ignoreCase = true) && 
                    !name.equals("Read More", ignoreCase = true)) {
                    
                    var description = ""
                    val endOfLink = matcher.end()
                    val searchRegion = html.substring(endOfLink, minOf(endOfLink + 3000, html.length))
                    
                    val pMatcher = Pattern.compile("<p>(.*?)</p>|<div>(.*?)</div>|<span class=\"summary\">(.*?)</span>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(searchRegion)
                    if (pMatcher.find()) {
                        description = (pMatcher.group(1) ?: pMatcher.group(2) ?: pMatcher.group(3) ?: "")
                            .replace(Regex("<[^>]*>"), "")
                            .trim()
                    }
                    
                    if (description.isEmpty()) {
                        val fallbackMatcher = Pattern.compile(">\\s*(.*?)\\s*<", Pattern.DOTALL).matcher(searchRegion)
                        if (fallbackMatcher.find()) {
                            val candidate = fallbackMatcher.group(1)?.trim() ?: ""
                            if (candidate.length > 20 && !candidate.contains("<")) {
                                description = candidate
                            }
                        }
                    }
                    
                    projects.add(
                        CodeShareProject(
                            name = name,
                            author = author,
                            slug = "$author/$slug",
                            description = description
                        )
                    )
                }
            }
        }
        
        val distinctProjects = projects.distinctBy { it.slug }
        Log.d(TAG, "Final project count: ${distinctProjects.size}")
        return distinctProjects
    }

    suspend fun fetchProjectDetails(author: String, slug: String): Result<JSONObject> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanSlug = if (slug.contains("/")) slug.substringAfter("/") else slug
                val urlString = "$API_BASE_URL/$author/$cleanSlug/"
                
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "ReShift/1.0")
                
                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(JSONObject(json))
                } else {
                    Result.failure(Exception("HTTP ${connection.responseCode}: Failed to fetch project details"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch project details", e)
                Result.failure(e)
            }
        }
    }
}
