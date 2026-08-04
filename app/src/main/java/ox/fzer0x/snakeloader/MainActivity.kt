package ox.fzer0x.snakeloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
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
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                val fridaManager = viewModel.fridaManager
                val githubApiService = viewModel.githubApiService
                val codeShareApiService = viewModel.codeShareApiService
                val scriptManager = viewModel.scriptManager
                val settingsManager = viewModel.settingsManager
                val zygiskManager = viewModel.zygiskManager

                var showModuleDialog by remember { mutableStateOf<ModuleDialogType?>(null) }
                var showTelegramDialog by remember { 
                    mutableStateOf(!settingsManager.isTelegramDialogDismissed) 
                }
                var dontShowAgain by remember { mutableStateOf(false) }

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
                        githubApiService.initialize()
                        scriptManager.initialize()
                        
                        // Small delay to allow root environment to initialize
                        kotlinx.coroutines.delay(2000)
                        
                        val moduleManager = ModuleManager(this@MainActivity, settingsManager)
                        if (!moduleManager.isModuleInstalled()) {
                            showModuleDialog = ModuleDialogType.REQUIRED
                        } else {
                            val installedVersionCode = moduleManager.getModuleVersionCode()
                            val assetVersionCode = moduleManager.getAssetModuleVersionCode()
                            if (installedVersionCode in 1 until assetVersionCode) {
                                showModuleDialog = ModuleDialogType.UPDATE
                            }
                        }
                    }
                }

                LaunchedEffect(fridaManager) {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(fridaManager)
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
                        modifier = Modifier.padding(innerPadding)
                    )

                    showModuleDialog?.let { type ->
                        AlertDialog(
                            onDismissRequest = { showModuleDialog = null },
                            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary) },
                            title = { Text(if (type == ModuleDialogType.REQUIRED) "Module Required" else "Update Available") },
                            text = { 
                                Text(if (type == ModuleDialogType.REQUIRED) 
                                    "The ReShift Root module is not installed. Please install it in Settings to enable all features." 
                                    else "A new version of the ReShift Root module is available. Please update it in Settings.") 
                            },
                            confirmButton = {
                                Button(onClick = {
                                    showModuleDialog = null
                                    navController.navigate(Screen.Settings.route)
                                }) {
                                    Text("Go to Settings")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showModuleDialog = null }) {
                                    Text("Later")
                                }
                            }
                        )
                    }

                    if (showTelegramDialog) {
                        AlertDialog(
                            onDismissRequest = { showTelegramDialog = false },
                            icon = { Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.primary) },
                            title = { Text("Join our Community") },
                            text = {
                                Column {
                                    Text("Join our Telegram channel for the latest modules, updates, and support!")
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { dontShowAgain = !dontShowAgain }
                                    ) {
                                        Checkbox(
                                            checked = dontShowAgain,
                                            onCheckedChange = { dontShowAgain = it }
                                        )
                                        Text("Don't show again", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    if (dontShowAgain) settingsManager.isTelegramDialogDismissed = true
                                    showTelegramDialog = false
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, "https://t.me/+5Z7yekVc3uBkYWMy".toUri())
                                        startActivity(intent)
                                    } catch (_: Exception) {}
                                }) {
                                    Text("Join Channel")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    if (dontShowAgain) settingsManager.isTelegramDialogDismissed = true
                                    showTelegramDialog = false
                                }) {
                                    Text("Later")
                                }
                            }
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
