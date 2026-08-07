package ox.fzer0x.snakeloader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.OverlayService
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ui.components.StatusRow
import ox.fzer0x.snakeloader.ui.components.StatusBadge
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.viewmodels.SettingsViewModel
import ox.fzer0x.snakeloader.utils.StealthUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = viewModel.fridaManager.settings 
    val fridaManager = viewModel.fridaManager
    
    var isOverlayEnabled by remember { mutableStateOf(viewModel.fridaManager.settings.isOverlayEnabled) }
    var isLogcatOverlayEnabled by remember { mutableStateOf(settingsManager.isLogcatOverlayEnabled) }
    var isFridaOverlayEnabled by remember { mutableStateOf(settingsManager.isFridaOverlayEnabled) }
    var overlayTextSize by remember { mutableStateOf(settingsManager.overlayTextSize) }
    var overlayOpacity by remember { mutableStateOf(settingsManager.overlayOpacity) }

    var isStealthModeEnabled by remember { mutableStateOf(settingsManager.isStealthModeEnabled) }
    var isAppMasked by remember { 
        mutableStateOf(context.packageManager.getComponentEnabledSetting(
            android.content.ComponentName(context, "ox.fzer0x.snakeloader.LauncherMasked")
        ) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    var isSelinuxPermissive by remember { mutableStateOf(false) }
    var isPtraceScopeDisabled by remember { mutableStateOf(false) }
    var isYamaSupported by remember { mutableStateOf(true) }

    var showStealthWarning by remember { mutableStateOf(false) }
    var showInstallationDialog by remember { mutableStateOf(false) }

    fun refreshSystemStatus() {
        scope.launch {
            val status = fridaManager.getFridaStatus()
            isSelinuxPermissive = status["selinux_permissive"] as? Boolean ?: false
            isPtraceScopeDisabled = status["ptrace_scope_disabled"] as? Boolean ?: false
            isYamaSupported = fridaManager.isYamaSupported()
            isStealthModeEnabled = status["stealth_enabled"] as? Boolean ?: false
            viewModel.refreshModuleStatus(context)
        }
    }

    LaunchedEffect(viewModel.isInstalling) {
        if (viewModel.isInstalling) {
            showInstallationDialog = true
        }
    }

    LaunchedEffect(Unit) {
        refreshSystemStatus()
    }

    fun updateOverlayService() {
        if (isOverlayEnabled || isLogcatOverlayEnabled || isFridaOverlayEnabled) {
            if (Settings.canDrawOverlays(context)) {
                context.startForegroundService(Intent(context, OverlayService::class.java))
            }
        } else {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshSystemStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("UI & Experience")
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingSwitchRow(
                            title = "Script Log Overlay",
                            checked = isOverlayEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !Settings.canDrawOverlays(context)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                } else {
                                    isOverlayEnabled = enabled
                                    settingsManager.isOverlayEnabled = enabled
                                    updateOverlayService()
                                }
                            }
                        )
                        SettingSwitchRow(
                            title = "Logcat Overlay",
                            checked = isLogcatOverlayEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !Settings.canDrawOverlays(context)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                } else {
                                    isLogcatOverlayEnabled = enabled
                                    settingsManager.isLogcatOverlayEnabled = enabled
                                    updateOverlayService()
                                }
                            }
                        )
                        SettingSwitchRow(
                            title = "Frida CLI Overlay",
                            checked = isFridaOverlayEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !Settings.canDrawOverlays(context)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                } else {
                                    isFridaOverlayEnabled = enabled
                                    settingsManager.isFridaOverlayEnabled = enabled
                                    updateOverlayService()
                                }
                            }
                        )

                        if ((isOverlayEnabled || isLogcatOverlayEnabled || isFridaOverlayEnabled) && !Settings.canDrawOverlays(context)) {
                            Text(
                                "Permission required for overlays.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        Text("Text Size: ${overlayTextSize.toInt()} sp", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = overlayTextSize,
                            onValueChange = {
                                overlayTextSize = it
                                settingsManager.overlayTextSize = it
                                if (Settings.canDrawOverlays(context)) updateOverlayService()
                            },
                            valueRange = 8f..24f,
                            steps = 16
                        )

                        Text("Opacity: ${(overlayOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = overlayOpacity,
                            onValueChange = {
                                overlayOpacity = it
                                settingsManager.overlayOpacity = it
                                if (Settings.canDrawOverlays(context)) updateOverlayService()
                            },
                            valueRange = 0.1f..1f
                        )
                    }
                }
            }

            item {
                SectionHeader("System Tweaks")
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingSwitchRow(
                            title = "SELinux Permissive",
                            description = "Bypass SELinux security policies",
                            checked = isSelinuxPermissive,
                            onCheckedChange = {
                                fridaManager.setSelinuxPermissive(it)
                                refreshSystemStatus()
                            }
                        )
                        SettingSwitchRow(
                            title = "Disable Ptrace Scope",
                            description = "Allow inter-process debugging",
                            checked = isPtraceScopeDisabled,
                            enabled = isYamaSupported,
                            onCheckedChange = {
                                scope.launch {
                                    if (it) fridaManager.disablePtraceScope() else fridaManager.enablePtraceScope()
                                    refreshSystemStatus()
                                }
                            }
                        )
                        if (!isYamaSupported) {
                            Text("Ptrace not supported by kernel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            item {
                SectionHeader("Anti-Detection")
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingSwitchRow(
                            title = "Global Stealth Mode",
                            description = "Randomize Frida paths & ports",
                            checked = isStealthModeEnabled,
                            onCheckedChange = { if (it) showStealthWarning = true else {
                                isStealthModeEnabled = false
                                settingsManager.isStealthModeEnabled = false
                                fridaManager.updateStealthConfig()
                            }}
                        )
                        SettingSwitchRow(
                            title = "Mask App Icon",
                            description = "Show as 'System Storage'",
                            checked = isAppMasked,
                            onCheckedChange = { 
                                isAppMasked = it
                                StealthUtils.setAppMasked(context, it)
                            }
                        )
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Button(
                            onClick = { StealthUtils.toggleAppIcon(context, true) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                        ) {
                            Icon(Icons.Default.VisibilityOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Hide from Launcher")
                        }
                    }
                }
            }

            item {
                SectionHeader("Core Module")
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatusRow(
                            label = "Status",
                            value = if (viewModel.isModuleInstalled) "Installed" else "Missing",
                            color = if (viewModel.isModuleInstalled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            icon = Icons.Default.Extension
                        )
                        StatusRow(
                            label = "Version",
                            value = viewModel.moduleVersion,
                            color = MaterialTheme.colorScheme.secondary,
                            icon = Icons.Default.Info
                        )
                        StatusRow(
                            label = "Service",
                            value = if (viewModel.isModuleServiceRunning) "Running" else "Stopped",
                            color = if (viewModel.isModuleServiceRunning) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            icon = Icons.Default.PlayCircle
                        )
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        Button(
                            onClick = { viewModel.installModule(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (viewModel.isModuleInstalled) "Update" else "Install")
                        }
                    }
                }
            }

            item {
                SectionHeader("App Update")
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Check for ReShift app updates on GitHub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { 
                                scope.launch {
                                    viewModel.updateManager.checkForUpdates()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Icon(Icons.Default.Update, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Check for Updates")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showInstallationDialog) {
        AlertDialog(
            onDismissRequest = { if (!viewModel.isInstalling) showInstallationDialog = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Module Installation")
                }
            },
            text = {
                Column {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(400.dp),
                        color = Color(0xFF000000),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
                    ) {
                        val scrollState = androidx.compose.foundation.lazy.rememberLazyListState()
                        val lines = viewModel.installationLog.split("\n")
                        
                        LaunchedEffect(lines.size) {
                            if (lines.isNotEmpty()) {
                                scrollState.animateScrollToItem(lines.size - 1)
                            }
                        }

                        LazyColumn(
                            state = scrollState,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            items(lines) { line ->
                                Text(
                                    text = line,
                                    color = Color(0xFF00FF00),
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }
                    
                    if (viewModel.isInstalling) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            color = Color(0xFF00FF00),
                            trackColor = Color(0xFF003300)
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewModel.installSuccess && !viewModel.isInstalling) {
                        Button(
                            onClick = { viewModel.rebootDevice() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reboot")
                        }
                    }
                    
                    TextButton(
                        onClick = { showInstallationDialog = false },
                        enabled = !viewModel.isInstalling
                    ) {
                        Text(if (viewModel.installSuccess) "Close" else "Done")
                    }
                }
            }
        )
    }

    if (showStealthWarning) {
        AlertDialog(
            onDismissRequest = { showStealthWarning = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Enable Stealth Mode?") },
            text = {
                Text("Randomizes Frida paths, ports and names to avoid detection.\n\n" +
                     "Note: Internal CLI and standard PC tools (frida -U) will not work without manual setup.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isStealthModeEnabled = true
                        settingsManager.isStealthModeEnabled = true
                        fridaManager.updateStealthConfig()
                        showStealthWarning = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showStealthWarning = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.scale(0.85f)
        )
    }
}
