package ox.fzer0x.snakeloader

import android.content.Context
import android.content.SharedPreferences
import ox.fzer0x.snakeloader.security.EncryptionManager

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("reshift_settings", Context.MODE_PRIVATE)
    private val encryptionAvailable = EncryptionManager.isAvailable()

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean("is_overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("is_overlay_enabled", value).apply()

    var isLogcatOverlayEnabled: Boolean
        get() = prefs.getBoolean("is_logcat_overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("is_logcat_overlay_enabled", value).apply()

    var isFridaOverlayEnabled: Boolean
        get() = prefs.getBoolean("is_frida_overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("is_frida_overlay_enabled", value).apply()

    var overlayTextSize: Float
        get() = prefs.getFloat("overlay_text_size", 12f)
        set(value) = prefs.edit().putFloat("overlay_text_size", value).apply()
        
    var overlayOpacity: Float
        get() = prefs.getFloat("overlay_opacity", 0.7f)
        set(value) = prefs.edit().putFloat("overlay_opacity", value).apply()

    var isStealthModeEnabled: Boolean
        get() {
            if (prefs.contains("stealth_mode_enabled") && !prefs.contains("stealth_mode_enabled_encrypted")) {
                val oldBoolean = prefs.getBoolean("stealth_mode_enabled", false)
                if (encryptionAvailable) {
                    val encrypted = EncryptionManager.encryptBoolean(oldBoolean)
                    prefs.edit().putString("stealth_mode_enabled_encrypted", encrypted).remove("stealth_mode_enabled").apply()
                    return oldBoolean
                }
                return oldBoolean
            }
            
            val encrypted = prefs.getString("stealth_mode_enabled_encrypted", null)
            return if (encrypted != null && encryptionAvailable) {
                EncryptionManager.decryptBoolean(encrypted)
            } else {
                prefs.getBoolean("stealth_mode_enabled", false)
            }
        }
        set(value) {
            val encrypted = if (encryptionAvailable) {
                EncryptionManager.encryptBoolean(value)
            } else {
                value.toString()
            }
            prefs.edit().putString("stealth_mode_enabled_encrypted", encrypted).remove("stealth_mode_enabled").apply()
        }

    var randomizedBinaryName: String
        get() = getBinaryNameForType("default")
        set(value) = setBinaryNameForType("default", value)

    var randomizedServerName: String
        get() = getBinaryNameForType("server")
        set(value) = setBinaryNameForType("server", value)

    var randomizedInjectName: String
        get() = getBinaryNameForType("inject")
        set(value) = setBinaryNameForType("inject", value)

    var randomizedCliName: String
        get() = getBinaryNameForType("cli")
        set(value) = setBinaryNameForType("cli", value)

    private fun getBinaryNameForType(type: String): String {
        val key = if (type == "default") "randomized_binary_name" else "randomized_${type}_name"
        val encryptedKey = "${key}_encrypted"

        if (prefs.contains(key) && !prefs.contains(encryptedKey)) {
            val oldString = prefs.getString(key, "") ?: ""
            if (encryptionAvailable && oldString.isNotEmpty()) {
                val encrypted = EncryptionManager.encrypt(oldString)
                prefs.edit().putString(encryptedKey, encrypted).remove(key).apply()
                return oldString
            }
            return oldString
        }
        
        val encrypted = prefs.getString(encryptedKey, null)
        return if (encrypted != null && encryptionAvailable) {
            EncryptionManager.decrypt(encrypted)
        } else {
            prefs.getString(key, "") ?: ""
        }
    }

    private fun setBinaryNameForType(type: String, value: String) {
        val key = if (type == "default") "randomized_binary_name" else "randomized_${type}_name"
        val encryptedKey = "${key}_encrypted"
        
        val encrypted = if (encryptionAvailable) {
            EncryptionManager.encrypt(value)
        } else {
            value
        }
        prefs.edit().putString(encryptedKey, encrypted).remove(key).apply()
    }

    var randomizedPort: Int
        get() {
            if (prefs.contains("randomized_port") && !prefs.contains("randomized_port_encrypted")) {
                val oldInt = prefs.getInt("randomized_port", 0)
                if (encryptionAvailable && oldInt != 0) {
                    val encrypted = EncryptionManager.encryptInt(oldInt)
                    prefs.edit().putString("randomized_port_encrypted", encrypted).remove("randomized_port").apply()
                    return oldInt
                }
                return oldInt
            }
            
            val encrypted = prefs.getString("randomized_port_encrypted", null)
            return if (encrypted != null && encryptionAvailable) {
                EncryptionManager.decryptInt(encrypted)
            } else {
                prefs.getInt("randomized_port", 0)
            }
        }
        set(value) {
            val encrypted = if (encryptionAvailable) {
                EncryptionManager.encryptInt(value)
            } else {
                value.toString()
            }
            prefs.edit().putString("randomized_port_encrypted", encrypted).remove("randomized_port").apply()
        }

    var isIconHidden: Boolean
        get() {
            if (prefs.contains("icon_hidden") && !prefs.contains("icon_hidden_encrypted")) {
                val oldBoolean = prefs.getBoolean("icon_hidden", false)
                if (encryptionAvailable) {
                    val encrypted = EncryptionManager.encryptBoolean(oldBoolean)
                    prefs.edit().putString("icon_hidden_encrypted", encrypted).remove("icon_hidden").apply()
                    return oldBoolean
                }
                return oldBoolean
            }
            
            val encrypted = prefs.getString("icon_hidden_encrypted", null)
            return if (encrypted != null && encryptionAvailable) {
                EncryptionManager.decryptBoolean(encrypted)
            } else {
                prefs.getBoolean("icon_hidden", false)
            }
        }
        set(value) {
            val encrypted = if (encryptionAvailable) {
                EncryptionManager.encryptBoolean(value)
            } else {
                value.toString()
            }
            prefs.edit().putString("icon_hidden_encrypted", encrypted).remove("icon_hidden").apply()
        }

    var useExceptorOff: Boolean
        get() = prefs.getBoolean("use_exceptor_off", false)
        set(value) = prefs.edit().putBoolean("use_exceptor_off", value).apply()

    var useRuntimeV8: Boolean
        get() = prefs.getBoolean("use_runtime_v8", true)
        set(value) = prefs.edit().putBoolean("use_runtime_v8", value).apply()

    var useNoPause: Boolean
        get() = prefs.getBoolean("use_no_pause", false)
        set(value) = prefs.edit().putBoolean("use_no_pause", value).apply()

    var useDebugLog: Boolean
        get() = prefs.getBoolean("use_debug_log", false)
        set(value) = prefs.edit().putBoolean("use_debug_log", value).apply()

    var isZygiskEnabled: Boolean
        get() {
            val encrypted = prefs.getString("zygisk_enabled_encrypted", null)
            return if (encrypted != null && encryptionAvailable) {
                EncryptionManager.decryptBoolean(encrypted)
            } else {
                prefs.getBoolean("is_zygisk_enabled", false)
            }
        }
        set(value) {
            val encrypted = if (encryptionAvailable) {
                EncryptionManager.encryptBoolean(value)
            } else {
                value.toString()
            }
            prefs.edit().putString("zygisk_enabled_encrypted", encrypted).apply()
        }

    var isTelegramDialogDismissed: Boolean
        get() = prefs.getBoolean("telegram_dialog_dismissed", false)
        set(value) = prefs.edit().putBoolean("telegram_dialog_dismissed", value).apply()

    var geminiApiKey: String
        get() {
            val encrypted = prefs.getString("gemini_api_key_encrypted", null)
            return if (encrypted != null && encryptionAvailable) {
                EncryptionManager.decrypt(encrypted)
            } else {
                prefs.getString("gemini_api_key", "") ?: ""
            }
        }
        set(value) {
            val encrypted = if (encryptionAvailable) {
                EncryptionManager.encrypt(value)
            } else {
                value
            }
            prefs.edit().putString("gemini_api_key_encrypted", encrypted).remove("gemini_api_key").apply()
        }

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-3.6-flash") ?: "gemini-3.6-flash"
        set(value) = prefs.edit().putString("gemini_model", value).apply()

    var aiAutoCorrectionEnabled: Boolean
        get() = prefs.getBoolean("ai_auto_correction_enabled", true)
        set(value) = prefs.edit().putBoolean("ai_auto_correction_enabled", value).apply()

    var aiProvider: String
        get() = prefs.getString("ai_provider", "GEMINI") ?: "GEMINI"
        set(value) = prefs.edit().putString("ai_provider", value).apply()

    var ollamaBaseUrl: String
        get() = prefs.getString("ollama_base_url", "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        set(value) = prefs.edit().putString("ollama_base_url", value).apply()

    var ollamaModel: String
        get() = prefs.getString("ollama_model", "richardyoung/qwen2.5-7b-instruct-abliterated:latest") ?: "richardyoung/qwen2.5-7b-instruct-abliterated:latest"
        set(value) = prefs.edit().putString("ollama_model", value).apply()

    var onDeviceModelPath: String
        get() = prefs.getString("on_device_model_path", "") ?: ""
        set(value) = prefs.edit().putString("on_device_model_path", value).apply()

    var useVulkanGpu: Boolean
        get() = prefs.getBoolean("use_vulkan_gpu", true)
        set(value) = prefs.edit().putBoolean("use_vulkan_gpu", value).apply()
}
