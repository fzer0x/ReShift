package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.ReShiftDialogShape
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.components.StatusBadge
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen
import ox.fzer0x.snakeloader.ui.theme.WarningOrange
import ox.fzer0x.snakeloader.ui.viewmodels.DumperEngine
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
    onNavigateToIl2CppInspector: ((DumperEngine) -> Unit)? = null,
    onNavigateToStalker: (() -> Unit)? = null,
    onNavigateToDexDecompiler: (() -> Unit)? = null,
    onNavigateToDeviceSpoofer: (() -> Unit)? = null
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
    var isFridaRunning by remember { mutableStateOf(false) }
    var runningBinary by remember { mutableStateOf("none") }
    var fridaVersion by remember { mutableStateOf("") }
    var selectedBinary by remember { mutableStateOf(fridaManager.getCurrentFridaBinarySelection()) }

    fun refreshSettings() {
        scope.launch {
            val status = fridaManager.getFridaStatus()
            isSelinuxPermissive = status["selinux_permissive"] as? Boolean ?: false
            isPtraceScopeDisabled = status["ptrace_scope_disabled"] as? Boolean ?: false
            isYamaSupported = fridaManager.isYamaSupported()
            isStealthModeEnabled = status["stealth_enabled"] as? Boolean ?: false
            isFridaRunning = fridaManager.isFridaServerRunning()
            runningBinary = fridaManager.getRunningFridaBinary()
            fridaVersion = status["frida_version"] as? String ?: ""
            selectedBinary = fridaManager.getCurrentFridaBinarySelection()
        }
    }

    LaunchedEffect(Unit) {
        refreshSettings()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReShiftTopAppBar(
                title = "Frida Toolbox",
                onBack = onBack,
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
            // SECTION 1: FRIDA ENGINE CONTROL
            item {
                SectionHeader("Frida Engine Control")
                ReShiftCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            if (isFridaRunning) SuccessGreen else MaterialTheme.colorScheme.error,
                                            CircleShape
                                        )
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    val modeTitle = when (selectedBinary) {
                                        "inject" -> if (isFridaRunning) "Frida Inject Active" else "Frida Inject Stopped"
                                        "cli" -> if (isFridaRunning) "Frida RPC (CLI) Active" else "Frida RPC (CLI) Stopped"
                                        "gadget" -> if (isFridaRunning) "Frida Gadget Active" else "Frida Gadget Stopped"
                                        "auto" -> if (isFridaRunning) "Frida Engine Active" else "Frida Engine Stopped"
                                        else -> if (isFridaRunning) "Frida Server Active" else "Frida Server Stopped"
                                    }
                                    Text(
                                        text = modeTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isFridaRunning) {
                                            val activeMode = if (selectedBinary != "auto") selectedBinary else runningBinary
                                            val modeName = when (activeMode) {
                                                "inject" -> "Inject Mode"
                                                "cli" -> "RPC (CLI) Mode"
                                                "gadget" -> "Zygisk (Gadget) Mode"
                                                "server" -> "Server Mode"
                                                else -> "Auto Strategy"
                                            }
                                            if (fridaVersion.isNotEmpty()) "$modeName • v$fridaVersion" else modeName
                                        } else "Engine offline",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            StatusBadge(
                                text = if (isFridaRunning) {
                                    val badgeText = if (selectedBinary != "auto") selectedBinary.uppercase() else runningBinary.uppercase()
                                    if (badgeText != "NONE") badgeText else "ACTIVE"
                                } else "STOPPED",
                                color = if (isFridaRunning) SuccessGreen else MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = "Execution Mode Strategy",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                BinaryChoiceChip(
                                    label = "Auto",
                                    selected = selectedBinary == "auto",
                                    onClick = {
                                        selectedBinary = "auto"
                                        fridaManager.selectedFridaBinary = "auto"
                                        refreshSettings()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                BinaryChoiceChip(
                                    label = "Server",
                                    selected = selectedBinary == "server",
                                    onClick = {
                                        selectedBinary = "server"
                                        fridaManager.selectedFridaBinary = "server"
                                        refreshSettings()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                BinaryChoiceChip(
                                    label = "Inject",
                                    selected = selectedBinary == "inject",
                                    onClick = {
                                        selectedBinary = "inject"
                                        fridaManager.selectedFridaBinary = "inject"
                                        refreshSettings()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                BinaryChoiceChip(
                                    label = "RPC (CLI)",
                                    selected = selectedBinary == "cli",
                                    onClick = {
                                        selectedBinary = "cli"
                                        fridaManager.selectedFridaBinary = "cli"
                                        refreshSettings()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                BinaryChoiceChip(
                                    label = "Zygisk (Gadget)",
                                    selected = selectedBinary == "gadget",
                                    onClick = {
                                        selectedBinary = "gadget"
                                        fridaManager.selectedFridaBinary = "gadget"
                                        refreshSettings()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val success = fridaManager.startFridaServer(force = true)
                                        refreshSettings()
                                        snackbarHostState.showSnackbar(if (success) "Frida Server started" else "Failed to start server")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = ReShiftButtonShape
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Start Server", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    fridaManager.fullCleanup(keepServer = false)
                                    refreshSettings()
                                    scope.launch { snackbarHostState.showSnackbar("Frida Engine stopped & cleaned") }
                                },
                                modifier = Modifier.weight(1f),
                                shape = ReShiftButtonShape,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stop Engine", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // SECTION 2: SYSTEM ENVIRONMENT & SECURITY
            item {
                SectionHeader("System Security & Environment")
                ReShiftCard {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        SecuritySettingRow(
                            title = "SELinux Mode",
                            subtitle = if (isSelinuxPermissive) "Permissive (Kernel security hooks bypassed)" else "Enforcing (Standard system security active)",
                            value = if (isSelinuxPermissive) "Permissive" else "Enforcing",
                            color = if (isSelinuxPermissive) WarningOrange else SuccessGreen,
                            icon = if (isSelinuxPermissive) Icons.Default.LockOpen else Icons.Default.Security,
                            onClick = {
                                scope.launch {
                                    val target = !isSelinuxPermissive
                                    fridaManager.setSelinuxPermissive(target)
                                    refreshSettings()
                                    snackbarHostState.showSnackbar("SELinux set to ${if (target) "Permissive" else "Enforcing"}")
                                }
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SecuritySettingRow(
                            title = "Ptrace Scope",
                            subtitle = if (isPtraceScopeDisabled) "Disabled (Process memory attachment permitted)" else "Restricted (YAMA ptrace restriction enabled)",
                            value = if (isPtraceScopeDisabled) "Disabled" else "Restricted",
                            color = if (isPtraceScopeDisabled) SuccessGreen else WarningOrange,
                            icon = Icons.Default.Visibility,
                            onClick = {
                                scope.launch {
                                    if (isPtraceScopeDisabled) {
                                        fridaManager.enablePtraceScope()
                                    } else {
                                        fridaManager.disablePtraceScope()
                                    }
                                    refreshSettings()
                                    snackbarHostState.showSnackbar("Ptrace scope updated")
                                }
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SecuritySettingRow(
                            title = "Stealth Engine Mode",
                            subtitle = if (isStealthModeEnabled) "Active (Anti-detection & binary cloak active)" else "Default (Standard Frida binary signature)",
                            value = if (isStealthModeEnabled) "Active" else "Default",
                            color = if (isStealthModeEnabled) SuccessGreen else MaterialTheme.colorScheme.outline,
                            icon = if (isStealthModeEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            onClick = null
                        )
                    }
                }
            }

            // SECTION 3: CORE ANALYSIS & INSTRUMENTATION TOOLS
            item {
                SectionHeader("Instrumentation & Analysis Tools")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GridActionCard(
                            title = "Il2Cpp Dumper",
                            subtitle = "Unity Metadata & Classes",
                            icon = Icons.Default.Inventory2,
                            color = SuccessGreen,
                            onClick = { onNavigateToIl2CppInspector?.invoke(DumperEngine.IL2CPP) },
                            modifier = Modifier.weight(1f)
                        )
                        GridActionCard(
                            title = "UE4 Dumper",
                            subtitle = "Unreal Engine 4/5 SDK",
                            icon = Icons.Default.ViewInAr,
                            color = WarningOrange,
                            onClick = { onNavigateToIl2CppInspector?.invoke(DumperEngine.UE4) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GridActionCard(
                            title = "DEX & ELF Inspector",
                            subtitle = "Ghidra / Radare2 Decompiler",
                            icon = Icons.Default.Code,
                            color = MaterialTheme.colorScheme.tertiary,
                            onClick = { onNavigateToDexDecompiler?.invoke() },
                            modifier = Modifier.weight(1f)
                        )
                        GridActionCard(
                            title = "Hardware Spoofer",
                            subtitle = "Fingerprint Anonymizer",
                            icon = Icons.Default.PhoneAndroid,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = { onNavigateToDeviceSpoofer?.invoke() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        GridActionCard(
                            title = "Advanced Config",
                            subtitle = "Runtime Options, Environment & Frida Toolchain",
                            icon = Icons.Default.SettingsSuggest,
                            color = MaterialTheme.colorScheme.secondary,
                            onClick = { onNavigateToAdvancedFrida?.invoke("com.example.app") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // SECTION 4: POWER UTILITIES & SYSTEM ACTIONS
            item {
                SectionHeader("Power Utilities & Actions")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolboxActionRow(
                        title = "Process & Module Manager",
                        description = "Inspect running apps & loaded .so libraries",
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = {
                            processViewModel.refreshProcesses()
                            showProcessDialog = true
                        }
                    )
                    ToolboxActionRow(
                        title = "Interactive Root Shell",
                        description = "Execute su terminal commands with live output",
                        icon = Icons.Outlined.Terminal,
                        onClick = { showShellDialog = true }
                    )
                    ToolboxActionRow(
                        title = "Flush Logcat Logs",
                        description = "Clear Android logcat buffers",
                        icon = Icons.Default.DeleteSweep,
                        onClick = {
                            fridaManager.executeRootCommand("logcat -c")
                            scope.launch { snackbarHostState.showSnackbar("Logcat cleared") }
                        }
                    )
                    PowerControlCard(fridaManager = fridaManager)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        // DIALOG: PROCESS MANAGER & MODULE INSPECTOR
        if (showProcessDialog) {
            var processSearchQuery by remember { mutableStateOf("") }
            val filteredProcesses = remember(processSearchQuery, processViewModel.runningProcesses) {
                if (processSearchQuery.isEmpty()) {
                    processViewModel.runningProcesses
                } else {
                    processViewModel.runningProcesses.filter {
                        it.first.contains(processSearchQuery, ignoreCase = true) ||
                        it.second.toString().contains(processSearchQuery)
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { showProcessDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Running Processes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (processViewModel.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { processViewModel.refreshProcesses() }) {
                                Icon(Icons.Default.Refresh, null)
                            }
                        }
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = processSearchQuery,
                            onValueChange = { processSearchQuery = it },
                            placeholder = { Text("Search process or PID...") },
                            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = ReShiftButtonShape
                        )

                        Box(modifier = Modifier.heightIn(max = 380.dp)) {
                            if (filteredProcesses.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                    Text("No matching processes found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(filteredProcesses) { proc ->
                                        ReShiftCard {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(proc.first, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, lineHeight = 18.sp)
                                                    Text("PID: ${proc.second}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = {
                                                    selectedPidForModules = proc.second.toString()
                                                    selectedProcessName = proc.first
                                                    showModulesDialog = true
                                                    processViewModel.loadModules(proc.second)
                                                }) {
                                                    Icon(Icons.Default.Inventory, "View Loaded SO Modules", tint = MaterialTheme.colorScheme.primary)
                                                }
                                                IconButton(onClick = { processViewModel.killProcess(proc.first) }) {
                                                    Icon(Icons.Default.Close, "Terminate App", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProcessDialog = false }, shape = ReShiftButtonShape) { Text("Close", fontWeight = FontWeight.Bold) }
                },
                shape = ReShiftDialogShape
            )
        }

        // DIALOG: MODULES INSPECTOR (.SO LIBRARIES)
        if (showModulesDialog) {
            var moduleSearchQuery by remember { mutableStateOf("") }
            val filteredModules = remember(moduleSearchQuery, processViewModel.selectedProcessModules) {
                if (moduleSearchQuery.isEmpty()) {
                    processViewModel.selectedProcessModules
                } else {
                    processViewModel.selectedProcessModules.filter { it.contains(moduleSearchQuery, ignoreCase = true) }
                }
            }

            AlertDialog(
                onDismissRequest = { showModulesDialog = false },
                title = {
                    Column {
                        Text("Loaded Modules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(selectedProcessName ?: "PID $selectedPidForModules", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = moduleSearchQuery,
                            onValueChange = { moduleSearchQuery = it },
                            placeholder = { Text("Filter .so libraries...") },
                            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = ReShiftButtonShape
                        )

                        if (processViewModel.isLoading) {
                            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Box(modifier = Modifier.heightIn(max = 350.dp)) {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(filteredModules) { module ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = ReShiftChipShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        ) {
                                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Code, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(10.dp))
                                                Text(module, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, lineHeight = 16.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModulesDialog = false }, shape = ReShiftButtonShape) { Text("Close", fontWeight = FontWeight.Bold) }
                },
                shape = ReShiftDialogShape
            )
        }

        // DIALOG: ROOT SHELL CONSOLE
        if (showShellDialog) {
            var command by remember { mutableStateOf("") }
            val quickCommands = listOf("pgrep frida", "getenforce", "ps -A | grep frida", "netstat -tuln", "getprop ro.build.version.release")

            AlertDialog(
                onDismissRequest = { showShellDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Root Terminal Console", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(quickCommands) { cmd ->
                                SuggestionChip(
                                    onClick = {
                                        command = cmd
                                        shellViewModel.executeCommand(cmd)
                                    },
                                    label = { Text(cmd, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                    shape = ReShiftChipShape
                                )
                            }
                        }

                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            placeholder = { Text("Enter root command...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = ReShiftButtonShape,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (command.isNotBlank()) shellViewModel.executeCommand(command)
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )

                        Surface(
                            color = Color(0xFF181818),
                            shape = ReShiftButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 320.dp)
                        ) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                if (shellViewModel.isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = SuccessGreen, strokeWidth = 2.dp)
                                } else {
                                    LazyColumn {
                                        item {
                                            Text(
                                                text = shellViewModel.commandOutput.ifEmpty { "# Terminal Ready. Execute a command above." },
                                                style = TextStyle(
                                                    color = SuccessGreen,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 15.sp
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
                    }, shape = ReShiftButtonShape) { Text("Close", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { shellViewModel.clearOutput() }, shape = ReShiftButtonShape) { Text("Clear Output") }
                },
                shape = ReShiftDialogShape
            )
        }
    }
}

@Composable
fun SecuritySettingRow(
    title: String,
    subtitle: String,
    value: String,
    color: Color,
    icon: ImageVector,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(color.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusBadge(text = value, color = color)
    }
}

@Composable
fun BinaryChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = ReShiftChipShape,
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
fun GridActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null
) {
    ReShiftCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(color.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }

                if (badgeText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
fun ToolboxActionRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ReShiftCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun PowerControlCard(fridaManager: FridaManager) {
    ReShiftCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Device Power Control",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Reboot System, Bootloader or Recovery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { fridaManager.executeRootCommand("reboot") },
                    modifier = Modifier.weight(1f),
                    shape = ReShiftChipShape,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reboot", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { fridaManager.executeRootCommand("reboot bootloader") },
                    modifier = Modifier.weight(1f),
                    shape = ReShiftChipShape,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("Bootloader", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { fridaManager.executeRootCommand("reboot recovery") },
                    modifier = Modifier.weight(1f),
                    shape = ReShiftChipShape,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("Recovery", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FridaToolboxPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SectionHeader("Frida Engine Control")
                    ReShiftCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(SuccessGreen, CircleShape)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Frida Server Active",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Inject Mode • v16.1.4",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                StatusBadge(text = "INJECT", color = SuccessGreen)
                            }

                            Spacer(Modifier.height(14.dp))

                            Text(
                                text = "Execution Mode Strategy",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BinaryChoiceChip(label = "Auto", selected = false, onClick = {}, modifier = Modifier.weight(1f))
                                BinaryChoiceChip(label = "RPC (CLI)", selected = false, onClick = {}, modifier = Modifier.weight(1f))
                                BinaryChoiceChip(label = "Inject", selected = true, onClick = {}, modifier = Modifier.weight(1f))
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                    shape = ReShiftButtonShape
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Start Server", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                    shape = ReShiftButtonShape,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Stop Engine", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    SectionHeader("System Security & Environment")
                    ReShiftCard {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            SecuritySettingRow(
                                title = "SELinux Mode",
                                subtitle = "Permissive (Kernel security hooks bypassed)",
                                value = "Permissive",
                                color = WarningOrange,
                                icon = Icons.Default.LockOpen,
                                onClick = {}
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            SecuritySettingRow(
                                title = "Ptrace Scope",
                                subtitle = "Disabled (Process memory attachment permitted)",
                                value = "Disabled",
                                color = SuccessGreen,
                                icon = Icons.Default.Visibility,
                                onClick = {}
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            SecuritySettingRow(
                                title = "Stealth Engine Mode",
                                subtitle = "Default (Standard Frida binary signature)",
                                value = "Default",
                                color = MaterialTheme.colorScheme.outline,
                                icon = Icons.Default.Visibility,
                                onClick = null
                            )
                        }
                    }
                }

                item {
                    SectionHeader("Instrumentation & Analysis Tools")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GridActionCard(
                                title = "Il2Cpp Dumper",
                                subtitle = "Unity Metadata & Classes",
                                icon = Icons.Default.Inventory2,
                                color = SuccessGreen,
                                onClick = {},
                                modifier = Modifier.weight(1f)
                            )
                            GridActionCard(
                                title = "UE4 Dumper",
                                subtitle = "Unreal Engine 4/5 SDK",
                                icon = Icons.Default.ViewInAr,
                                color = WarningOrange,
                                onClick = {},
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GridActionCard(
                                title = "DEX & ELF Inspector",
                                subtitle = "Ghidra / Radare2 Decompiler",
                                icon = Icons.Default.Code,
                                color = MaterialTheme.colorScheme.tertiary,
                                onClick = {},
                                modifier = Modifier.weight(1f)
                            )
                            GridActionCard(
                                title = "Hardware Spoofer",
                                subtitle = "Fingerprint Anonymizer",
                                icon = Icons.Default.PhoneAndroid,
                                color = MaterialTheme.colorScheme.primary,
                                onClick = {},
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            GridActionCard(
                                title = "Advanced Config",
                                subtitle = "Runtime Options, Environment & Frida Toolchain",
                                icon = Icons.Default.SettingsSuggest,
                                color = MaterialTheme.colorScheme.secondary,
                                onClick = {},
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
