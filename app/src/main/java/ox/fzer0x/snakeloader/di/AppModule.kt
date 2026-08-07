package ox.fzer0x.snakeloader.di

import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel
import ox.fzer0x.snakeloader.*
import ox.fzer0x.snakeloader.ui.MainViewModel

val appModule = module {
    single { SettingsManager(get()) }
    single { StealthConfigManager(get(), get()) }
    single { BinaryManager(get(), get()) }
    single { FridaManager(get(), get(), get()) }
    single { GitHubApiService(get()) }
    single { CodeShareApiService(get()) }
    single { ScriptManager(get()) }
    single { ZygiskManager(get(), get(), get()) }
    single { ModuleManager(get(), get()) }
    single { UpdateManager(get()) }
    
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
