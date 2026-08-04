package ox.fzer0x.snakeloader.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ox.fzer0x.snakeloader.utils.ShellExecutor

class ShellViewModel : BaseViewModel() {
    var lastCommand by mutableStateOf("")
        private set
    
    var commandOutput by mutableStateOf("")
        private set

    fun executeCommand(command: String) {
        launchWithLoading {
            lastCommand = command
            val result = ShellExecutor.execute(command, useRoot = true)
            commandOutput = if (result.isSuccess) {
                result.stdout.ifEmpty { "[Success (No Output)]" }
            } else {
                "Error (${result.exitCode}):\n${result.stderr}"
            }
        }
    }

    fun clearOutput() {
        commandOutput = ""
    }
}
