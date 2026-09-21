package ox.fzer0x.snakeloader.utils

import com.google.gson.Gson
import ox.fzer0x.snakeloader.DynamicUiManager
import ox.fzer0x.snakeloader.StalkerManager

object JsonMessageProcessor {
    private const val TAG = "JsonMessageProcessor"
    private val gson = Gson()

    fun processLine(line: String, dynamicUiManager: DynamicUiManager, stalkerManager: StalkerManager): Boolean {
        if (!line.contains("{") || !line.contains("\"type\":")) return false

        try {
            val startIdx = line.indexOf("{")
            val endIdx = line.lastIndexOf("}")
            if (startIdx == -1 || endIdx == -1 || endIdx < startIdx) return false

            val jsonStr = line.substring(startIdx, endIdx + 1)
            val map = gson.fromJson(jsonStr, Map::class.java) ?: return false

            val type = map["type"] as? String ?: return false

            return when (type) {
                "ui_config" -> {
                    val controls = map["controls"] as? List<Map<String, Any>>
                    if (controls != null) {
                        dynamicUiManager.updateConfig(controls)
                        true
                    } else false
                }
                "stalker_hits" -> {
                    val hits = map["hits"] as? Map<*, *>
                    val tree = map["tree"] as? List<Map<String, Any>>
                    if (hits != null) {
                        val hitMap = mutableMapOf<String, Int>()
                        hits.forEach { (k, v) ->
                            if (k is String && v is Number) {
                                hitMap[k] = v.toInt()
                            }
                        }
                        stalkerManager.addHits(hitMap, tree)
                        true
                    } else false
                }
                "stalker_symbol" -> {
                    val addr = map["address"] as? String
                    val name = map["name"] as? String
                    if (addr != null && name != null) {
                        stalkerManager.updateSymbol(addr, name)
                        true
                    } else false
                }
                else -> false
            }
        } catch (e: Exception) {
            return false
        }
    }
}
