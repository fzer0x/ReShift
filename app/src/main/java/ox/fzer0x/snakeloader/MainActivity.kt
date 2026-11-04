package ox.fzer0x.snakeloader

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ox.fzer0x.snakeloader.ui.navigation.Screen
import ox.fzer0x.snakeloader.ui.navigation.ReShiftNavGraph
import ox.fzer0x.snakeloader.ui.theme.ReShiftTheme
import ox.fzer0x.snakeloader.ui.MainViewModel
import ox.fzer0x.snakeloader.ui.components.CommunityDialog
import ox.fzer0x.snakeloader.ui.components.AppUpdateDialog
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ox.fzer0x.snakeloader.ui.viewmodels.StatusViewModel
import ox.fzer0x.snakeloader.ui.components.ReShiftBootAnimation
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftDialogShape

class MainActivity : ComponentActivity() {

    init {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(false)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
            }

            ReShiftTheme {
                val viewModel: MainViewModel = koinViewModel()
                val scope = rememberCoroutineScope()
                val fridaManager = viewModel.fridaManager
                val githubApiService = viewModel.githubApiService
                val codeShareApiService = viewModel.codeShareApiService
                val scriptManager = viewModel.scriptManager
                val settingsManager = viewModel.settingsManager
                val zygiskManager = viewModel.zygiskManager
                val updateManager = viewModel.updateManager

                val statusViewModel: StatusViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return StatusViewModel(fridaManager, scriptManager, settingsManager) as T
                        }
                    }
                )

                var isBooting by remember { mutableStateOf(true) }
                var bootStepText by remember { mutableStateOf("[1/5] Initializing ReShift Boot Engine...") }
                var bootProgress by remember { mutableFloatStateOf(0.05f) }

                var showModuleDialog by remember { mutableStateOf<ModuleDialogType?>(null) }
                var showTelegramDialog by remember {
                    mutableStateOf(!settingsManager.isTelegramDialogDismissed) 
                }

                val updateState by updateManager.updateState.collectAsState()

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    
                    launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            bootStepText = "[1/5] Checking Root Access Environment..."
                            bootProgress = 0.15f
                        }
                        val hasRoot = fridaManager.hasRootAccess(forceCheck = true)

                        withContext(Dispatchers.Main) {
                            bootStepText = "[2/5] Initializing Repositories & Script Engine..."
                            bootProgress = 0.35f
                        }
                        githubApiService.initialize()
                        scriptManager.initialize()

                        withContext(Dispatchers.Main) {
                            bootStepText = "[3/5] Verifying ReShift Root Module Status..."
                            bootProgress = 0.55f
                        }
                        val moduleManager = ModuleManager(this@MainActivity, settingsManager)
                        val isInstalled = moduleManager.isModuleInstalled()
                        val installedVersionCode = moduleManager.getModuleVersionCode()
                        val assetVersionCode = moduleManager.getAssetModuleVersionCode()

                        withContext(Dispatchers.Main) {
                            bootStepText = "[4/5] Loading Frida Engine & Home Status Environment..."
                            bootProgress = 0.75f
                        }
                        if (hasRoot && !fridaManager.isFridaServerRunning()) {
                            fridaManager.startFridaServer()
                        }

                        statusViewModel.refreshStatus(this@MainActivity, force = true)

                        withContext(Dispatchers.Main) {
                            bootStepText = "[5/5] System Boot Complete"
                            bootProgress = 1.0f
                        }
                        delay(350)

                        withContext(Dispatchers.Main) {
                            if (!isInstalled) {
                                showModuleDialog = ModuleDialogType.REQUIRED
                            } else if (installedVersionCode < assetVersionCode) {
                                showModuleDialog = ModuleDialogType.UPDATE
                            }
                            isBooting = false
                        }

                        updateManager.checkForUpdates()
                    }
                }



                if ((settingsManager.isOverlayEnabled || settingsManager.isLogcatOverlayEnabled) && Settings.canDrawOverlays(this)) {
                    startService(Intent(this, OverlayService::class.java))
                }

                val navController = rememberNavController()
                val items = listOf(
                    NavigationItem("Home", Screen.Status.route, Icons.Default.Home),
                    NavigationItem("Modules", Screen.Modules.route, Icons.Default.ViewModule),
                    NavigationItem("Apps", Screen.Apps.route, Icons.Default.Apps),
                    NavigationItem("Zygisk", Screen.ZygiskSettings.route, Icons.Default.BugReport),
                    NavigationItem("Logs", Screen.Logs.route, Icons.AutoMirrored.Filled.ListAlt)
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination

                        val showBottomBar = items.any { it.route == currentDestination?.route }

                        if (showBottomBar) {
                            NavigationBar {
                                items.forEach { item ->
                                    NavigationBarItem(
                                        icon = { Icon(item.icon, contentDescription = item.label) },
                                        label = { Text(item.label) },
                                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                        onClick = {
                                            navController.navigate(item.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    ReShiftNavGraph(
                        navController = navController,
                        fridaManager = fridaManager,
                        githubApiService = githubApiService,
                        codeShareApiService = codeShareApiService,
                        scriptManager = scriptManager,
                        settingsManager = settingsManager,
                        zygiskManager = zygiskManager,
                        updateManager = updateManager,
                        statusViewModel = statusViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )

                    showModuleDialog?.let { type ->
                        AlertDialog(
                            onDismissRequest = { showModuleDialog = null },
                            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary) },
                            title = { Text(if (type == ModuleDialogType.REQUIRED) "Module Required" else "Update Available") },
                            text = { 
                                Text(if (type == ModuleDialogType.REQUIRED) 
                                    "ReShift Root module is not installed. Please install it in Settings to enable all features."
                                    else "A new version of ReShift Root module is available. Please update it in Settings.")
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showModuleDialog = null
                                        navController.navigate(Screen.Settings.route)
                                    },
                                    shape = ReShiftButtonShape
                                ) {
                                    Text("Go to Settings")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showModuleDialog = null },
                                    shape = ReShiftButtonShape
                                ) {
                                    Text("Later")
                                }
                            },
                            shape = ReShiftDialogShape
                        )
                    }

                    if (showTelegramDialog) {
                        CommunityDialog(
                            onDismiss = { dontShow ->
                                if (dontShow) settingsManager.isTelegramDialogDismissed = true
                                showTelegramDialog = false
                            },
                            onJoin = { dontShow ->
                                if (dontShow) settingsManager.isTelegramDialogDismissed = true
                                showTelegramDialog = false
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, "https://t.me/+1FZrr4SqgMg1MDky".toUri())
                                    startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        )
                    }

                    AppUpdateDialog(
                        state = updateState,
                        onDownload = {
                            (updateState as? UpdateManager.UpdateState.UpdateAvailable)?.let {
                                scope.launch {
                                    updateManager.downloadAndInstall(it.downloadUrl)
                                }
                            }
                        },
                        onDismiss = {
                            updateManager.resetState()
                        }
                    )

                    AnimatedVisibility(
                        visible = isBooting,
                        enter = fadeIn(),
                        exit = fadeOut(animationSpec = tween(600))
                    ) {
                        ReShiftBootAnimation(
                            currentStepText = bootStepText,
                            progress = bootProgress,
                            isComplete = bootProgress >= 1f,
                            onSkip = { isBooting = false }
                        )
                    }
                }
            }
        }
    }
}

enum class ModuleDialogType {
    REQUIRED, UPDATE
}

data class NavigationItem(val label: String, val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
