package ox.fzer0x.snakeloader.ui

import androidx.lifecycle.ViewModel
import ox.fzer0x.snakeloader.CodeShareApiService
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.GitHubApiService
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.ZygiskManager

class MainViewModel(
    val fridaManager: FridaManager,
    val githubApiService: GitHubApiService,
    val codeShareApiService: CodeShareApiService,
    val scriptManager: ScriptManager,
    val settingsManager: SettingsManager,
    val zygiskManager: ZygiskManager
) : ViewModel() {

    override fun onCleared() {
        super.onCleared()
    }
}
