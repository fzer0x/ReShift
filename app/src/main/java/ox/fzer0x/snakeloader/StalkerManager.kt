package ox.fzer0x.snakeloader

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

data class StalkerHit(
    val address: String,
    val name: String,
    val hits: Int,
    val percentage: Float = 0f
)

data class StalkerNode(
    val name: String,
    val address: String,
    val depth: Int,
    val children: MutableList<StalkerNode> = mutableListOf()
)

class StalkerManager {
    private val _hitCounts = mutableStateMapOf<String, Int>()
    private val _symbolCache = mutableStateMapOf<String, String>()
    
    val hotPaths = mutableStateListOf<StalkerHit>()
    val callTree = mutableStateListOf<StalkerNode>()
    var isActive = mutableStateOf(false)

    fun addHits(hits: Map<String, Int>, tree: List<Map<String, Any>>? = null) {
        hits.forEach { (addr, count) ->
            val current = _hitCounts[addr] ?: 0
            _hitCounts[addr] = current + count
        }
        
        tree?.let { updateCallTree(it) }
        updateHotPaths()
    }

    private fun updateCallTree(segments: List<Map<String, Any>>) {
        segments.forEach { segment ->
            if (segment["type"] == "enter") {
                val node = StalkerNode(
                    name = segment["name"] as? String ?: "Unknown",
                    address = segment["addr"] as? String ?: "",
                    depth = (segment["depth"] as? Number)?.toInt() ?: 0
                )
                if (callTree.size > 100) callTree.removeAt(0)
                callTree.add(node)
            }
        }
    }

    fun updateSymbol(address: String, name: String) {
        _symbolCache[address] = name
    }

    private fun updateHotPaths() {
        val totalHits = _hitCounts.values.sum().toFloat()
        if (totalHits == 0f) return

        val sorted = _hitCounts.toList()
            .sortedByDescending { it.second }
            .take(20)
            .map { (addr, count) ->
                StalkerHit(
                    address = addr,
                    name = _symbolCache[addr] ?: addr,
                    hits = count,
                    percentage = count / totalHits
                )
            }

        hotPaths.clear()
        hotPaths.addAll(sorted)
    }

    fun reset() {
        _hitCounts.clear()
        _symbolCache.clear()
        hotPaths.clear()
        isActive.value = false
    }
}
