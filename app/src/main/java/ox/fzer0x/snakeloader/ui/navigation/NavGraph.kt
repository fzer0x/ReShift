package ox.fzer0x.snakeloader.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ox.fzer0x.snakeloader.CodeShareApiService
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.GitHubApiService
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.ZygiskManager
import ox.fzer0x.snakeloader.ui.screens.*
import ox.fzer0x.snakeloader.ui.viewmodels.AppsViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.StatusViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.ProcessViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.ShellViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.MemoryInspectorViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.AdvancedFridaViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.SettingsViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.AppDetailsViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppViewModel

sealed class Screen(val route: String) {
    object Status : Screen("status")
    object Modules : Screen("modules")
    object Apps : Screen("apps")
    object Logs : Screen("logs")
    object Settings : Screen("settings")
    object FridaToolbox : Screen("frida_toolbox")
    object RepoBrowser : Screen("repo_browser")
    object CodeShareBrowser : Screen("codeshare_browser")
    object AssetBrowser : Screen("asset_browser")
    object ZygiskSettings : Screen("zygisk_settings")
    object StalkerToolbox : Screen("stalker_toolbox")
    object MemoryInspector : Screen("memory_inspector")
    object Il2CppInspector : Screen("il2cpp_inspector")
    object AdvancedFrida : Screen("advanced_frida/{packageName}") {
        fun createRoute(packageName: String) = "advanced_frida/${Uri.encode(packageName)}"
    }
    object ScriptEditor : Screen("script_editor/{scriptId}") {
        fun createRoute(scriptId: String) = "script_editor/${Uri.encode(scriptId)}"
    }
    object AppDetails : Screen("app_details/{packageName}") {
        fun createRoute(packageName: String) = "app_details/${Uri.encode(packageName)}"
    }
    object ModuleDetails : Screen("module_details/{scriptId}") {
        fun createRoute(scriptId: String) = "module_details/${Uri.encode(scriptId)}"
    }
}

@Composable
fun ReShiftNavGraph(
    navController: NavHostController,
    fridaManager: FridaManager,
    githubApiService: GitHubApiService,
    codeShareApiService: CodeShareApiService,
    scriptManager: ScriptManager,
    settingsManager: SettingsManager,
    zygiskManager: ZygiskManager,
    modifier: Modifier = Modifier
) {
    val statusViewModel: StatusViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return StatusViewModel(fridaManager, scriptManager, settingsManager) as T
            }
        }
    )

    val appsViewModel: AppsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppsViewModel(scriptManager) as T
            }
        }
    )

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(fridaManager, settingsManager) as T
            }
        }
    )

    val processViewModel: ProcessViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProcessViewModel(fridaManager) as T
            }
        }
    )

    val shellViewModel: ShellViewModel = viewModel()

    val memoryInspectorViewModel: MemoryInspectorViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val viewModel = MemoryInspectorViewModel(fridaManager.getRpcManager(), fridaManager)
                fridaManager.getDetectedHooks().forEach { hookName ->
                    viewModel.addDetectedHook(hookName)
                }
                return viewModel as T
            }
        }
    )

    val advancedFridaViewModel: AdvancedFridaViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val viewModel = AdvancedFridaViewModel(settingsManager)
                viewModel.setSelectedFridaBinary(fridaManager.selectedFridaBinary)
                return viewModel as T
            }
        }
    )

    val il2cppViewModel: Il2CppViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return Il2CppViewModel(fridaManager) as T
            }
        }
    )

    NavHost(
        navController = navController,
        startDestination = Screen.Status.route,
        modifier = modifier
    ) {
        composable(Screen.Status.route) {
            StatusScreen(statusViewModel, scriptManager, fridaManager, onNavigateToToolbox = {
                navController.navigate(Screen.FridaToolbox.route)
            }, onNavigateToSettings = {
                navController.navigate(Screen.Settings.route)
            }, onNavigateToStalker = {
                navController.navigate(Screen.StalkerToolbox.route)
            })
        }
        composable(Screen.Modules.route) {
            ModulesScreen(
                scriptManager = scriptManager,
                onNavigateToAssetBrowser = {
                    navController.navigate(Screen.AssetBrowser.route)
                },
                onNavigateToCodeShare = {
                    navController.navigate(Screen.CodeShareBrowser.route)
                },
                onNavigateToToolbox = {
                    navController.navigate(Screen.FridaToolbox.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToEditor = { scriptId ->
                    navController.navigate(Screen.ScriptEditor.createRoute(scriptId))
                }
            )
        }
        composable(Screen.Apps.route) {
            AppsScreen(appsViewModel, scriptManager, onNavigateToDetails = { packageName ->
                navController.navigate(Screen.AppDetails.createRoute(packageName))
            }, onNavigateToToolbox = {
                navController.navigate(Screen.FridaToolbox.route)
            }, onNavigateToSettings = {
                navController.navigate(Screen.Settings.route)
            })
        }
        composable(Screen.Logs.route) {
            LogsScreen(onNavigateToSettings = {
                navController.navigate(Screen.Settings.route)
            })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(settingsViewModel, onBack = {
                navController.popBackStack()
            })
        }
        composable(Screen.FridaToolbox.route) {
            FridaToolboxScreen(
                fridaManager, 
                scriptManager, 
                processViewModel, 
                shellViewModel, 
                onBack = {
                    navController.popBackStack()
                },
                onNavigateToMemoryInspector = {
                    navController.navigate(Screen.MemoryInspector.route)
                },
                onNavigateToAdvancedFrida = { packageName ->
                    navController.navigate(Screen.AdvancedFrida.createRoute(packageName))
                },
                onNavigateToIl2CppInspector = {
                    navController.navigate(Screen.Il2CppInspector.route)
                }
            )
        }
        composable(Screen.RepoBrowser.route) {
            RepoBrowserScreen(githubApiService, scriptManager, onBack = {
                navController.popBackStack()
            })
        }
        composable(Screen.CodeShareBrowser.route) {
            CodeShareBrowserScreen(codeShareApiService, scriptManager, onBack = {
                navController.popBackStack()
            })
        }
        composable(Screen.AssetBrowser.route) {
            AssetBrowserScreen(scriptManager, onBack = {
                navController.popBackStack()
            })
        }
        composable(Screen.ZygiskSettings.route) {
            ZygiskSettingsScreen(zygiskManager)
        }
        composable(Screen.StalkerToolbox.route) {
            StalkerToolboxScreen(fridaManager, onBack = {
                navController.popBackStack()
            })
        }
        composable(
            route = Screen.AppDetails.route,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
            val appDetailsViewModel: AppDetailsViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return AppDetailsViewModel(packageName, scriptManager, settingsManager) as T
                    }
                }
            )
            AppDetailsScreen(packageName, scriptManager, fridaManager, appDetailsViewModel, onBack = {
                navController.popBackStack()
            })
        }
        composable(
            route = Screen.ModuleDetails.route,
            arguments = listOf(navArgument("scriptId") { type = NavType.StringType })
        ) { backStackEntry ->
            val scriptId = backStackEntry.arguments?.getString("scriptId") ?: return@composable
            ModuleDetailsScreen(scriptId, scriptManager, fridaManager, onBack = {
                navController.popBackStack()
            }, onNavigateToEditor = {
                navController.navigate(Screen.ScriptEditor.createRoute(scriptId))
            })
        }
        composable(
            route = Screen.ScriptEditor.route,
            arguments = listOf(navArgument("scriptId") { type = NavType.StringType })
        ) { backStackEntry ->
            val scriptId = backStackEntry.arguments?.getString("scriptId") ?: return@composable
            ScriptEditorScreen(scriptId, scriptManager, onBack = {
                navController.popBackStack()
            })
        }
        composable(Screen.MemoryInspector.route) {
            MemoryInspectorScreen(
                viewModel = memoryInspectorViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.Il2CppInspector.route) {
            Il2CppScreen(
                viewModel = il2cppViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(
            route = Screen.AdvancedFrida.route,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
            AdvancedFridaScreen(
                fridaManager = fridaManager,
                viewModel = advancedFridaViewModel,
                packageName = packageName,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
