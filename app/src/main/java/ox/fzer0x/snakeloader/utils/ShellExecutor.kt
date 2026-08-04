package ox.fzer0x.snakeloader.utils

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellExecutor {
    private const val TAG = "ShellExecutor"

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    fun execute(command: String, useRoot: Boolean = false, validateInput: Boolean = false): CommandResult {
        if (validateInput) {
            if (!InputValidator.isShellInputSafe(command)) {
                Log.e(TAG, "Command contains dangerous characters, blocking execution")
                return CommandResult(-1, "", "Command blocked: dangerous characters detected")
            }
        }

        return try {
            val process = if (useRoot) {
                ProcessBuilder("su", "-c", command).start()
            } else {
                ProcessBuilder("sh", "-c", command).start()
            }
            
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val outThread = Thread {
                try {
                    var line: String?
                    while (outReader.readLine().also { line = it } != null) {
                        stdout.append(line).append("\n")
                    }
                } catch (e: Exception) {}
            }
            
            val errThread = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        stderr.append(line).append("\n")
                    }
                } catch (e: Exception) {}
            }
            
            outThread.start()
            errThread.start()
            
            val exitCode = process.waitFor()
            outThread.join(2000)
            errThread.join(2000)
            
            CommandResult(
                exitCode = exitCode,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: $command", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    fun executeStreaming(
        command: String,
        useRoot: Boolean = false,
        onLine: (String) -> Unit
    ): CommandResult {
        return try {
            val process = if (useRoot) {
                ProcessBuilder("su", "-c", command).start()
            } else {
                ProcessBuilder("sh", "-c", command).start()
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val outThread = Thread {
                try {
                    var line: String?
                    while (outReader.readLine().also { line = it } != null) {
                        stdout.append(line).append("\n")
                        onLine(line!!)
                    }
                } catch (e: Exception) {}
            }

            val errThread = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        stderr.append(line).append("\n")
                        onLine("[ERR] $line")
                    }
                } catch (e: Exception) {}
            }

            outThread.start()
            errThread.start()

            val exitCode = process.waitFor()
            outThread.join(2000)
            errThread.join(2000)

            CommandResult(
                exitCode = exitCode,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: $command", e)
            val errorMsg = e.message ?: "Unknown error"
            onLine("[EXCEPTION] $errorMsg")
            CommandResult(-1, "", errorMsg)
        }
    }

    fun executeSimple(command: String, useRoot: Boolean = false): String {
        return execute(command, useRoot).stdout
    }
}
