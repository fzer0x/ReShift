package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.SettingsManager
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.components.StatusBadge
import ox.fzer0x.snakeloader.ui.viewmodels.ProcessViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.ShellViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FridaToolboxScreen(
    fridaManager: FridaManager,
    scriptManager: ScriptManager,
    processViewModel: ProcessViewModel,
    shellViewModel: ShellViewModel,
    onBack: () -> Unit,
    onNavigateToMemoryInspector: (() -> Unit)? = null,
    onNavigateToAdvancedFrida: ((String) -> Unit)? = null,
    onNavigateToIl2CppInspector: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showProcessDialog by remember { mutableStateOf(false) }
    var showShellDialog by remember { mutableStateOf(false) }
    
    var selectedPidForModules by remember { mutableStateOf<String?>(null) }
    var selectedProcessName by remember { mutableStateOf<String?>(null) }
    var showModulesDialog by remember { mutableStateOf(false) }

    var isSelinuxPermissive by remember { mutableStateOf(false) }
    var isPtraceScopeDisabled by remember { mutableStateOf(false) }
    var isYamaSupported by remember { mutableStateOf(true) }
    var isStealthModeEnabled by remember { mutableStateOf(false) }

    fun refreshSettings() {
        scope.launch {
            val status = fridaManager.getFridaStatus()
            isSelinuxPermissive = status["selinux_permissive"] as? Boolean ?: false
            isPtraceScopeDisabled = status["ptrace_scope_disabled"] as? Boolean ?: false
            isYamaSupported = fridaManager.isYamaSupported()
            isStealthModeEnabled = status["stealth_enabled"] as? Boolean ?: false
        }
    }

    LaunchedEffect(Unit) {
        refreshSettings()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Frida Toolbox", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshSettings() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("System Status")
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusItem(
                                label = "SELinux",
                                value = if (isSelinuxPermissive) "Permissive" else "Enforcing",
                                color = if (isSelinuxPermissive) Color(0xFFFF9800) else Color(0xFF4CAF50),
                                icon = if (isSelinuxPermissive) Icons.Default.LockOpen else Icons.Default.Security
                            )
                            StatusItem(
                                label = "Ptrace",
                                value = if (isPtraceScopeDisabled) "Disabled" else "Restricted",
                                color = if (isPtraceScopeDisabled) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                icon = Icons.Default.Visibility
                            )
                            StatusItem(
                                label = "Stealth",
                                value = if (isStealthModeEnabled) "Active" else "Off",
                                color = if (isStealthModeEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                                icon = if (isStealthModeEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("Frida Engine")
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        var selectedBinary by remember { mutableStateOf(fridaManager.getCurrentFridaBinarySelection()) }
                        
                        Text("Binary Mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BinaryChoiceChip(
                                label = "Auto",
                                selected = selectedBinary == "auto",
                                onClick = {
                                    selectedBinary = "auto"
                                    fridaManager.selectedFridaBinary = "auto"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BinaryChoiceChip(
                                label = "RPC",
                                selected = selectedBinary == "cli",
                                onClick = {
                                    selectedBinary = "cli"
                                    fridaManager.selectedFridaBinary = "cli"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BinaryChoiceChip(
                                label = "Inject",
                                selected = selectedBinary == "inject",
                                onClick = {
                                    selectedBinary = "inject"
                                    fridaManager.selectedFridaBinary = "inject"
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val success = fridaManager.startFridaServer()
                                        snackbarHostState.showSnackbar(if (success) "Server started" else "Failed to start server")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Server")
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    fridaManager.fullCleanup(keepServer = false)
                                    scope.launch { snackbarHostState.showSnackbar("All Frida processes killed") }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop Engine")
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("Advanced Tools")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GridActionCard(
                            title = "Memory",
                            subtitle = "Inspect RAM",
                            icon = Icons.Default.Memory,
                            color = MaterialTheme.colorScheme.tertiary,
                            onClick = { onNavigateToMemoryInspector?.invoke() },
                            modifier = Modifier.weight(1f)
                        )
                        GridActionCard(
                            title = "Il2Cpp",
                            subtitle = "Unity Metadata",
                            icon = Icons.Default.Inventory2,
                            color = Color(0xFF4CAF50),
                            onClick = { onNavigateToIl2CppInspector?.invoke() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GridActionCard(
                            title = "Advanced",
                            subtitle = "Engine Opts",
                            icon = Icons.Default.SettingsSuggest,
                            color = MaterialTheme.colorScheme.secondary,
                            onClick = { onNavigateToAdvancedFrida?.invoke("com.example.app") },
                            modifier = Modifier.weight(1f)
                        )
                        Box(modifier = Modifier.weight(1f)) 
                    }
                }
            }

            item {
                SectionHeader("Power Actions")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolboxActionRow(
                        title = "Shell Console",
                        description = "Execute root commands",
                        icon = Icons.Outlined.Terminal,
                        onClick = { showShellDialog = true }
                    )
                    ToolboxActionRow(
                        title = "Process Manager",
                        description = "Manage running apps",
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = {
                            processViewModel.refreshProcesses()
                            showProcessDialog = true
                        }
                    )
                    ToolboxActionRow(
                        title = "Clear Logcat",
                        description = "Flush system logs",
                        icon = Icons.Default.DeleteSweep,
                        onClick = {
                            fridaManager.executeRootCommand("logcat -c")
                            scope.launch { snackbarHostState.showSnackbar("Logcat cleared") }
                        }
                    )
                    ToolboxActionRow(
                        title = "Device Power",
                        description = "Reboot to Loader/Recovery",
                        icon = Icons.Default.PowerSettingsNew,
                        onClick = {
                        },
                        trailing = {
                            Row {
                                IconButton(onClick = { fridaManager.executeRootCommand("reboot bootloader") }) {
                                    Icon(Icons.Default.RestartAlt, "Bootloader", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { fridaManager.executeRootCommand("reboot recovery") }) {
                                    Icon(Icons.Default.Build, "Recovery", tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    )
                }
            }
            
            item { Spacer(Modifier.height(32.dp)) }
        }
        if (showProcessDialog) {
            AlertDialog(
                onDismissRequest = { showProcessDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Running Processes")
                        Spacer(Modifier.weight(1f))
                        if (processViewModel.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { processViewModel.refreshProcesses() }) {
                                Icon(Icons.Default.Refresh, null)
                            }
                        }
                    }
                },
                text = {
                    Box(modifier = Modifier.heightIn(max = 450.dp)) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(processViewModel.runningProcesses) { proc ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ) {
                                    ListItem(
                                        headlineContent = { Text(proc.second, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text("PID: ${proc.first}", style = MaterialTheme.typography.labelSmall) },
                                        trailingContent = {
                                            Row {
                                                IconButton(onClick = {
                                                    selectedPidForModules = proc.first
                                                    selectedProcessName = proc.second
                                                    showModulesDialog = true
                                                    processViewModel.loadModules(proc.first)
                                                }) {
                                                    Icon(Icons.Default.Inventory, "Modules", tint = MaterialTheme.colorScheme.primary)
                                                }
                                                IconButton(onClick = { processViewModel.killProcess(proc.second) }) {
                                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProcessDialog = false }) { Text("Close") }
                }
            )
        }

        if (showModulesDialog) {
            AlertDialog(
                onDismissRequest = { showModulesDialog = false },
                title = { Text("Modules: $selectedProcessName", style = MaterialTheme.typography.titleMedium) },
                text = {
                    if (processViewModel.isLoading) {
                        Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Box(modifier = Modifier.heightIn(max = 400.dp)) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(processViewModel.selectedProcessModules) { module ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.SettingsSuggest, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(12.dp))
                                            Text(module, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModulesDialog = false }) { Text("Close") }
                }
            )
        }

        if (showShellDialog) {
            var command by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showShellDialog = false },
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Root Shell")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            placeholder = { Text("Enter root command...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(onClick = { shellViewModel.executeCommand(command) }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                        Surface(
                            color = Color(0xFF1E1E1E),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 350.dp)
                        ) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                if (shellViewModel.isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Green, strokeWidth = 2.dp)
                                } else {
                                    LazyColumn {
                                        item {
                                            Text(
                                                text = shellViewModel.commandOutput.ifEmpty { "# Waiting for input..." },
                                                style = TextStyle(
                                                    color = Color(0xFF4CAF50),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp,
                                                    lineHeight = 16.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { 
                        shellViewModel.clearOutput()
                        showShellDialog = false 
                    }) { Text("Exit") }
                }
            )
        }
    }
}

@Composable
fun StatusItem(label: String, value: String, color: Color, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusBadge(text = value, color = color)
    }
}

@Composable
fun BinaryChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun GridActionCard(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ToolboxActionRow(title: String, description: String, icon: ImageVector, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
        }
    }
}
