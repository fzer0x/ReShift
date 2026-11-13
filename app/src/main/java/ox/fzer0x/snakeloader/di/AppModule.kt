package ox.fzer0x.snakeloader.di

import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel
import ox.fzer0x.snakeloader.*
import ox.fzer0x.snakeloader.decompiler.DexDecompilerEngine
import ox.fzer0x.snakeloader.spoofer.DeviceSpooferManager
import ox.fzer0x.snakeloader.ui.MainViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.DeviceSpooferViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.DexDecompilerViewModel

val appModule = module {
    single { SettingsManager(get()) }
    single { StealthConfigManager(get(), get()) }
    single { BinaryManager(get(), get()) }
    single { DynamicUiManager() }
    single { StalkerManager() }
    single { FridaManager(get(), get(), get(), get(), get(), get()) }
    single { GitHubApiService(get()) }
    single { CodeShareApiService(get()) }
    single { ScriptManager(get()) }
    single { ZygiskManager(get(), get(), get()) }
    single { ModuleManager(get(), get()) }
    single { UpdateManager(get()) }
    single { DexDecompilerEngine(get()) }
    single { DeviceSpooferManager(get()) }
    single { RpcBridge(get()) }
    single { ox.fzer0x.snakeloader.data.PatchTransactionManager(get()) }
    single { ox.fzer0x.snakeloader.data.PatchRepository(get(), get()) }
    single { ox.fzer0x.snakeloader.data.LivePatchMonitor(get()) }
    single { ox.fzer0x.snakeloader.ui.TrainerOverlayController(get(), get(), get()) }
    
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { DexDecompilerViewModel(get(), get()) }
    viewModel { DeviceSpooferViewModel(get(), get()) }
}
