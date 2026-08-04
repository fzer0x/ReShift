package ox.fzer0x.snakeloader.utils

import android.util.Log
import java.util.regex.Pattern

object InputValidator {
    private const val TAG = "InputValidator"

    private val PACKAGE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    private val GITHUB_REPO_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$")

    private val DANGEROUS_CHARS = setOf(
        ';', '&', '|', '`', '$', '(', ')', '<', '>', '\n', '\r', '\t', '"', '\''
    )

    private val SHELL_ESCAPE_CHARS = setOf(
        ' ', ';', '&', '|', '`', '$', '(', ')', '<', '>', '"', '\'', '\\', '*', '?', '[', ']', '{', '}', '~', '#', '!', '\n', '\r', '\t'
    )

    fun validatePackageName(packageName: String): Boolean {
        if (packageName.isEmpty()) {
            Log.w(TAG, "Package name is empty")
            return false
        }

        if (packageName.length > 256) {
            Log.w(TAG, "Package name too long: ${packageName.length}")
            return false
        }

        if (!PACKAGE_NAME_PATTERN.matcher(packageName).matches()) {
            Log.w(TAG, "Package name format invalid: $packageName")
            return false
        }

        return true
    }

    fun isShellInputSafe(input: String): Boolean {
        for (char in input) {
            if (char in DANGEROUS_CHARS) {
                Log.w(TAG, "Dangerous character detected in input: '$char'")
                return false
            }
        }
        return true
    }

    fun escapeShellInput(input: String): String {
        val escaped = StringBuilder()
        for (char in input) {
            if (char in SHELL_ESCAPE_CHARS) {
                escaped.append('\\')
            }
            escaped.append(char)
        }
        return escaped.toString()
    }

    fun validateGitHubRepo(owner: String, name: String): Boolean {
        if (owner.isEmpty() || name.isEmpty()) {
            Log.w(TAG, "GitHub repo owner or name is empty")
            return false
        }

        if (owner.length > 39 || name.length > 100) {
            Log.w(TAG, "GitHub repo name too long")
            return false
        }

        if (!GITHUB_REPO_PATTERN.matcher(owner).matches()) {
            Log.w(TAG, "GitHub owner name format invalid: $owner")
            return false
        }

        if (!GITHUB_REPO_PATTERN.matcher(name).matches()) {
            Log.w(TAG, "GitHub repo name format invalid: $name")
            return false
        }

        return true
    }

    fun validateIdentifier(identifier: String): Boolean {
        if (identifier.isEmpty()) return false
        if (identifier.length > 100) return false
        
        val pattern = Pattern.compile("^[a-zA-Z0-9_-]+$")
        return pattern.matcher(identifier).matches()
    }

    fun sanitizeFilePath(path: String): String {
        var sanitized = path.replace("../", "")
            .replace("..\\", "")
            .replace("~/", "")
        
        if (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1)
        }
        
        return sanitized
    }

    fun validateNumericRange(number: String, min: Int, max: Int): Boolean {
        return try {
            val value = number.toInt()
            value in min..max
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid numeric value: $number")
            false
        }
    }
}
