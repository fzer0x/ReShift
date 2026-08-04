package ox.fzer0x.snakeloader.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import ox.fzer0x.snakeloader.utils.ShellExecutor

object SecurityStateManager {
    private const val TAG = "SecurityStateManager"
    private const val PREFS_NAME = "security_state"
    private const val KEY_SECURITY_MODIFIED = "security_modified"
    private const val KEY_MODIFIED_TIMESTAMP = "modified_timestamp"
    private const val KEY_ORIGINAL_SELINUX = "original_selinux"
    private const val KEY_ORIGINAL_PTRACE = "original_ptrace"

    private lateinit var prefs: SharedPreferences
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isInitialized = true
        
        Log.d(TAG, "SecurityStateManager initialized")
        
        if (isSecurityModified()) {
            Log.w(TAG, "Detected security modifications from previous run, attempting restoration...")
            restoreSecurityState()
        }
    }

    fun setSecurityModified() {
        if (!isInitialized) {
            Log.e(TAG, "SecurityStateManager not initialized")
            return
        }

        val originalSelinux = getSelinuxState()
        val originalPtrace = getPtraceScope()

        prefs.edit()
            .putBoolean(KEY_SECURITY_MODIFIED, true)
            .putLong(KEY_MODIFIED_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_ORIGINAL_SELINUX, originalSelinux)
            .putString(KEY_ORIGINAL_PTRACE, originalPtrace)
            .apply()

        Log.d(TAG, "Security state marked as modified. Original SELinux: $originalSelinux, Ptrace: $originalPtrace")
    }

    fun clearSecurityModified() {
        if (!isInitialized) {
            Log.e(TAG, "SecurityStateManager not initialized")
            return
        }

        prefs.edit()
            .putBoolean(KEY_SECURITY_MODIFIED, false)
            .remove(KEY_MODIFIED_TIMESTAMP)
            .remove(KEY_ORIGINAL_SELINUX)
            .remove(KEY_ORIGINAL_PTRACE)
            .apply()

        Log.d(TAG, "Security modification marker cleared")
    }

    fun isSecurityModified(): Boolean {
        if (!isInitialized) return false
        
        val isModified = prefs.getBoolean(KEY_SECURITY_MODIFIED, false)
        if (isModified) {
            val timestamp = prefs.getLong(KEY_MODIFIED_TIMESTAMP, 0)
            val elapsedMinutes = (System.currentTimeMillis() - timestamp) / (1000 * 60)
            Log.w(TAG, "Security state marked as modified $elapsedMinutes minutes ago")
        }
        return isModified
    }

    fun getModificationTimestamp(): Long {
        if (!isInitialized) return 0
        return prefs.getLong(KEY_MODIFIED_TIMESTAMP, 0)
    }

    fun restoreSecurityState(): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SecurityStateManager not initialized")
            return false
        }

        if (!isSecurityModified()) {
            Log.d(TAG, "No security restoration needed")
            return true
        }

        Log.w(TAG, "Restoring security state after potential crash")

        try {
            val originalSelinux = prefs.getString(KEY_ORIGINAL_SELINUX, "Enforcing") ?: "Enforcing"
            if (originalSelinux.equals("Enforcing", ignoreCase = true)) {
                Log.d(TAG, "Restoring SELinux to Enforcing")
                ShellExecutor.execute("setenforce 1", useRoot = true)
            } else {
                Log.d(TAG, "SELinux was originally Permissive, leaving as-is")
            }

            val originalPtrace = prefs.getString(KEY_ORIGINAL_PTRACE, "1") ?: "1"
            Log.d(TAG, "Restoring ptrace_scope to $originalPtrace")
            ShellExecutor.execute("echo $originalPtrace > /proc/sys/kernel/yama/ptrace_scope", useRoot = true)

            val currentSelinux = getSelinuxState()
            val currentPtrace = getPtraceScope()

            val selinuxRestored = currentSelinux.equals(originalSelinux, ignoreCase = true)
            val ptraceRestored = currentPtrace == originalPtrace

            if (selinuxRestored && ptraceRestored) {
                Log.i(TAG, "Security state successfully restored")
                clearSecurityModified()
                return true
            } else {
                Log.e(TAG, "Security restoration incomplete. SELinux: $currentSelinux (expected: $originalSelinux), Ptrace: $currentPtrace (expected: $originalPtrace)")
                clearSecurityModified()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore security state", e)
            clearSecurityModified()
            return false
        }
    }

    private fun getSelinuxState(): String {
        return try {
            val result = ShellExecutor.execute("getenforce", useRoot = true)
            if (result.isSuccess) {
                result.stdout.trim()
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SELinux state", e)
            "Unknown"
        }
    }

    private fun getPtraceScope(): String {
        return try {
            val result = ShellExecutor.execute("cat /proc/sys/kernel/yama/ptrace_scope", useRoot = true)
            if (result.isSuccess) {
                result.stdout.trim()
            } else {
                "1"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ptrace_scope", e)
            "1"
        }
    }

    fun forceRestoreToSafeDefaults(): Boolean {
        Log.w(TAG, "Force restoring security to safe defaults")
        
        try {
            ShellExecutor.execute("setenforce 1", useRoot = true)
            
            ShellExecutor.execute("echo 1 > /proc/sys/kernel/yama/ptrace_scope", useRoot = true)
            
            clearSecurityModified()
            
            Log.i(TAG, "Security force-restored to safe defaults")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force restore security", e)
            return false
        }
    }
}
