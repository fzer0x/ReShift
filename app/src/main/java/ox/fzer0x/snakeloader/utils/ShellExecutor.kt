package ox.fzer0x.snakeloader.utils

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellExecutor {
    private const val TAG = "ShellExecutor"

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Explicitly requests Superuser (root) permission from Magisk / KernelSU / APatch via libsu.
     * Triggers the system superuser prompt dialog if permissions have not yet been granted.
     */
    fun requestRootAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain superuser shell via libsu", e)
            false
        }
    }

    /**
     * Checks whether superuser root access is available and granted.
     */
    fun isRootAvailable(): Boolean {
        val granted = Shell.isAppGrantedRoot()
        if (granted == true) return true
        return requestRootAccess()
    }

    fun execute(command: String, useRoot: Boolean = false, validateInput: Boolean = false): CommandResult {
        if (validateInput) {
            if (!InputValidator.isShellInputSafe(command)) {
                Log.e(TAG, "Command contains dangerous characters, blocking execution")
                return CommandResult(-1, "", "Command blocked: dangerous characters detected")
            }
        }

        if (useRoot) {
            return try {
                if (Shell.isAppGrantedRoot() != true) {
                    requestRootAccess()
                }
                val shellResult = Shell.cmd(command).exec()
                CommandResult(
                    exitCode = shellResult.code,
                    stdout = shellResult.out.joinToString("\n").trim(),
                    stderr = shellResult.err.joinToString("\n").trim()
                )
            } catch (e: Exception) {
                Log.w(TAG, "libsu execution failed, attempting ProcessBuilder fallback for: $command", e)
                executeProcessBuilder(command, useRoot = true)
            }
        }

        return executeProcessBuilder(command, useRoot = false)
    }

    private fun executeProcessBuilder(command: String, useRoot: Boolean): CommandResult {
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
                } catch (_: Exception) {}
            }
            
            val errThread = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        stderr.append(line).append("\n")
                    }
                } catch (_: Exception) {}
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
        if (useRoot) {
            return try {
                if (Shell.isAppGrantedRoot() != true) {
                    requestRootAccess()
                }
                val stdout = StringBuilder()
                val stderr = StringBuilder()

                val stdoutCallback = object : CallbackList<String>() {
                    override fun onAddElement(s: String) {
                        stdout.append(s).append("\n")
                        onLine(s)
                    }
                }

                val stderrCallback = object : CallbackList<String>() {
                    override fun onAddElement(s: String) {
                        stderr.append(s).append("\n")
                        onLine("[ERR] $s")
                    }
                }

                val result = Shell.cmd(command).to(stdoutCallback, stderrCallback).exec()
                CommandResult(
                    exitCode = result.code,
                    stdout = stdout.toString().trim(),
                    stderr = stderr.toString().trim()
                )
            } catch (e: Exception) {
                Log.w(TAG, "libsu streaming failed, using fallback for: $command", e)
                executeStreamingProcessBuilder(command, useRoot = true, onLine)
            }
        }

        return executeStreamingProcessBuilder(command, useRoot = false, onLine)
    }

    private fun executeStreamingProcessBuilder(
        command: String,
        useRoot: Boolean,
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
                } catch (_: Exception) {}
            }

            val errThread = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        stderr.append(line).append("\n")
                        onLine("[ERR] $line")
                    }
                } catch (_: Exception) {}
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
