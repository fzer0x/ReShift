package ox.fzer0x.snakeloader

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class DynamicControl(val id: String, val label: String) {
    data class Slider(val controlId: String, val controlLabel: String, val min: Float, val max: Float, val initial: Float) : DynamicControl(controlId, controlLabel)
    data class Switch(val controlId: String, val controlLabel: String, val initial: Boolean) : DynamicControl(controlId, controlLabel)
    data class Button(val controlId: String, val controlLabel: String) : DynamicControl(controlId, controlLabel)
}

class DynamicUiManager {
    private val _controls = mutableStateMapOf<String, DynamicControl>()
    val controls: Map<String, DynamicControl> get() = _controls

    private val _controlValues = mutableStateMapOf<String, Any>()
    val controlValues: Map<String, Any> get() = _controlValues

    private val _uiEvents = MutableStateFlow<Pair<String, Any>?>(null)
    val uiEvents = _uiEvents.asStateFlow()

    fun updateConfig(controlsList: List<Map<String, Any>>) {
        _controls.clear()
        controlsList.forEach { map ->
            val id = map["id"] as? String ?: return@forEach
            val label = map["label"] as? String ?: id
            val type = map["type"] as? String ?: "button"

            when (type) {
                "slider" -> {
                    val min = (map["min"] as? Number)?.toFloat() ?: 0f
                    val max = (map["max"] as? Number)?.toFloat() ?: 100f
                    val initial = (map["initial"] as? Number)?.toFloat() ?: min
                    _controls[id] = DynamicControl.Slider(id, label, min, max, initial)
                    _controlValues[id] = initial
                }
                "switch" -> {
                    val initial = map["initial"] as? Boolean ?: false
                    _controls[id] = DynamicControl.Switch(id, label, initial)
                    _controlValues[id] = initial
                }
                "button" -> {
                    _controls[id] = DynamicControl.Button(id, label)
                }
            }
        }
    }

    fun handleUserInteraction(id: String, value: Any) {
        _controlValues[id] = value
        _uiEvents.value = id to value
    }

    fun reset() {
        _controls.clear()
        _controlValues.clear()
        _uiEvents.value = null
    }
}
