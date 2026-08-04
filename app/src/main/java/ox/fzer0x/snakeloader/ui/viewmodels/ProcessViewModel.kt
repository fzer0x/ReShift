package ox.fzer0x.snakeloader.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ox.fzer0x.snakeloader.FridaManager

class ProcessViewModel(
    private val fridaManager: FridaManager
) : BaseViewModel() {

    var runningProcesses by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    var selectedProcessModules by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshProcesses() {
        launchWithLoading {
            runningProcesses = fridaManager.getRunningProcesses().filter { it.second.contains(".") }
        }
    }

    fun loadModules(pid: String) {
        launchWithLoading {
            selectedProcessModules = fridaManager.getModulesForPid(pid)
        }
    }

    fun killProcess(packageName: String) {
        launchWithLoading {
            fridaManager.killApp(packageName)
            refreshProcesses()
        }
    }
}
