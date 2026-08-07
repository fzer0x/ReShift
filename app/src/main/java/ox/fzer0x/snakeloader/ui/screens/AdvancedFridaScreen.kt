package ox.fzer0x.snakeloader.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.FridaScript
import ox.fzer0x.snakeloader.ui.components.*
import ox.fzer0x.snakeloader.ui.viewmodels.AdvancedFridaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedFridaScreen(
    fridaManager: FridaManager,
    viewModel: AdvancedFridaViewModel,
    packageName: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showScriptSelector by remember { mutableStateOf(false) }
    var showPackageSelector by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.setSelectedFridaBinary(fridaManager.selectedFridaBinary)
        scope.launch {
            viewModel.setAvailableBinaries(fridaManager.getAvailableBinaries())
            viewModel.setFridaStatus(fridaManager.getFridaStatus())
        }
        
        if (viewModel.currentPackage.value.isEmpty()) {
            viewModel.setCurrentPackage(packageName)
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            allApps = loadInstalledApps(context, includeSystemApps = true)
        }
    }

    val currentApp = remember(viewModel.currentPackage.value, allApps) {
        allApps.find { it.packageName == viewModel.currentPackage.value }
    }

    val filteredApps = if (searchQuery.isEmpty()) {
        allApps
    } else {
        allApps.filter { 
            it.label.contains(searchQuery, ignoreCase = true) || 
            it.packageName.contains(searchQuery, ignoreCase = true) 
        }
    }

    fun loadAssetScript(filename: String): String {
        return try {
            context.assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    val assetScripts = remember {
        try {
            context.assets.list("")?.filter { it.endsWith(".js") }?.map { 
                Pair(it, it.removeSuffix(".js").replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } })
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val availableScripts = assetScripts + viewModel.customScriptContents.value.keys.map { Pair(it, it) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            var fileName = "custom_script.js"
            var fileContent = ""
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            context.contentResolver.openInputStream(it)?.use { inputStream ->
                fileContent = inputStream.bufferedReader().readText()
            }
            viewModel.addCustomScript(fileName, fileContent)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Advanced Config", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        scope.launch { viewModel.setFridaStatus(fridaManager.getFridaStatus()) }
                    }) {
                        Icon(Icons.Default.Refresh, null)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                FridaStatusHeader(viewModel.fridaStatus.value)
            }

            item {
                SectionHeader("1. TARGET SELECTION")
                TargetAppCard(
                    app = currentApp,
                    packageName = viewModel.currentPackage.value,
                    onClick = { showPackageSelector = true }
                )
            }

            item {
                SectionHeader("2. ENVIRONMENT OPTIONS")
                ConfigurationSection(
                    viewModel = viewModel,
                    fridaManager = fridaManager
                )
            }

            item {
                SectionHeader("3. BINARY MANAGEMENT")
                BinaryManagementSection(viewModel, fridaManager)
            }

            item {
                SectionHeader("4. DEPLOYMENT")
                
                OutlinedButton(
                    onClick = {
                        viewModel.setExecuting(true)
                        scope.launch {
                            val success = fridaManager.executeFullToolchain(viewModel.currentPackage.value)
                            viewModel.setExecuting(false)
                            snackbarHostState.showSnackbar(if (success) "Toolchain started!" else "Toolchain failed")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !viewModel.isExecuting.value,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (viewModel.isExecuting.value) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Build, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Full Toolchain", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(12.dp))
                
                Button(
                    onClick = { showScriptSelector = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !viewModel.isExecuting.value,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (viewModel.isExecuting.value) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.FlashOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Multi-Script Manager", fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (showPackageSelector) {
        PackageSelectorDialog(
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            filteredApps = filteredApps,
            selectedPackage = viewModel.currentPackage.value,
            onSelect = {
                viewModel.setCurrentPackage(it)
                showPackageSelector = false
                searchQuery = ""
            },
            onDismiss = { showPackageSelector = false }
        )
    }

    if (showScriptSelector) {
        ScriptSelectorDialog(
            availableScripts = availableScripts,
            selectedScripts = viewModel.selectedScripts.value,
            onSelectionChanged = { viewModel.setSelectedScripts(it) },
            onExecute = { scripts ->
                viewModel.setExecuting(true)
                scope.launch {
                    val fridaScripts = scripts.map { scriptName ->
                        val scriptContent = viewModel.customScriptContents.value[scriptName] ?: loadAssetScript(scriptName)
                        val priority = when (scriptName) {
                            "rpc_handler.js" -> 3
                            "memory_dumper.js" -> 2
                            else -> 0
                        }
                        FridaScript(name = scriptName, content = scriptContent, priority = priority)
                    }
                    val success = fridaManager.executeMultipleScripts(fridaScripts, viewModel.currentPackage.value, viewModel.selectedMode.value)
                    viewModel.setExecuting(false)
                    snackbarHostState.showSnackbar(if (success) "Scripts injected!" else "Failed")
                }
                showScriptSelector = false
            },
            onDismiss = { showScriptSelector = false },
            onImportScript = { filePickerLauncher.launch("application/javascript") }
        )
    }
}

@Composable
private fun FridaStatusHeader(status: Map<String, Any>) {
    val runningBinary = status["running_binary"] as? String ?: "none"
    val isSmooth = status["is_smooth"] as? Boolean ?: false
    val serverRunning = status["server_running"] as? Boolean ?: false
    val selectedMode = status["selected_mode"] as? String ?: "auto"

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    label = "RUNNING",
                    value = runningBinary.uppercase(),
                    color = if (runningBinary != "none") Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                )
                StatusBadge(
                    label = "STRATEGY",
                    value = selectedMode.uppercase(),
                    color = MaterialTheme.colorScheme.primary
                )
                StatusBadge(
                    label = "SRV",
                    value = if (serverRunning) "ON" else "OFF",
                    color = if (serverRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
                StatusBadge(
                    label = "",
                    value = if (isSmooth) "OK" else "ERR",
                    color = if (isSmooth) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }
            
            if (selectedMode != "auto" && runningBinary != "none" && runningBinary != selectedMode && runningBinary != "server") {
                Text(
                    "Note: Selected $selectedMode but $runningBinary is currently active.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, value: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(value, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TargetAppCard(app: AppInfo?, packageName: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app?.icon != null) {
                Image(
                    bitmap = app.icon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(modifier = Modifier.size(40.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Apps, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app?.label ?: "Select Target", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(packageName.ifEmpty { "None selected" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ConfigurationSection(viewModel: AdvancedFridaViewModel, fridaManager: FridaManager) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConfigGroup("STRATEGY") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrategyChip("Combined", viewModel.selectedMode.value == "combined", modifier = Modifier.weight(1f)) { viewModel.setSelectedMode("combined") }
                    StrategyChip("Sequential", viewModel.selectedMode.value == "sequential", modifier = Modifier.weight(1f)) { viewModel.setSelectedMode("sequential") }
                }
            }

            ConfigGroup("BINARY") {
                val available = viewModel.availableBinaries.value
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StrategyChip("Auto", viewModel.selectedFridaBinary.value == "auto", modifier = Modifier.weight(1f)) {
                        viewModel.setSelectedFridaBinary("auto")
                        fridaManager.selectedFridaBinary = "auto"
                    }
                    StrategyChip(
                        label = "CLI",
                        selected = viewModel.selectedFridaBinary.value == "cli", 
                        available = available["cli"] ?: false,
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.setSelectedFridaBinary("cli")
                        fridaManager.selectedFridaBinary = "cli"
                    }
                    StrategyChip(
                        label = "Inject", 
                        selected = viewModel.selectedFridaBinary.value == "inject", 
                        available = available["inject"] ?: false,
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.setSelectedFridaBinary("inject")
                        fridaManager.selectedFridaBinary = "inject"
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            LaunchParametersSection(
                useExceptorOff = viewModel.useExceptorOff.value,
                onExceptorOffChange = { viewModel.toggleExceptorOff(it) },
                useRuntimeV8 = viewModel.useRuntimeV8.value,
                onRuntimeV8Change = { viewModel.toggleRuntimeV8(it) },
                useNoPause = viewModel.useNoPause.value,
                onNoPauseChange = { viewModel.toggleNoPause(it) },
                useDebugLog = viewModel.useDebugLog.value,
                onDebugLogChange = { viewModel.toggleDebugLog(it) }
            )
        }
    }
}

@Composable
private fun StrategyChip(label: String, selected: Boolean, available: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { 
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(label, fontSize = 12.sp)
                if (!available) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Block, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        enabled = available,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun PackageSelectorDialog(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filteredApps: List<AppInfo>,
    selectedPackage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target Application") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    placeholder = { Text("Filter...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filteredApps) { app ->
                        Surface(
                            onClick = { onSelect(app.packageName) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedPackage == app.packageName) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedPackage == app.packageName, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(app.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text(app.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScriptSelectorDialog(
    availableScripts: List<Pair<String, String>>,
    selectedScripts: List<String>,
    onSelectionChanged: (List<String>) -> Unit,
    onExecute: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onImportScript: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Multi-Script Management") },
        text = {
            Column {
                OutlinedButton(
                    onClick = onImportScript,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import .js File")
                }
                Text("Select modules to include:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(availableScripts) { (scriptName, displayName) ->
                        val isSelected = selectedScripts.contains(scriptName)
                        Surface(
                            onClick = {
                                if (isSelected) onSelectionChanged(selectedScripts - scriptName)
                                else onSelectionChanged(selectedScripts + scriptName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExecute(selectedScripts) }, enabled = selectedScripts.isNotEmpty()) {
                Text("Deploy & Inject")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BinaryManagementSection(viewModel: AdvancedFridaViewModel, fridaManager: FridaManager) {
    val status = viewModel.fridaStatus.value
    val availableCli = status["available_cli"] as? Boolean ?: false
    val availableInject = status["available_inject"] as? Boolean ?: false
    val fridaVersion = status["frida_version"] as? String ?: "Unknown"

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("FRIDA COMPONENTS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("v$fridaVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            if (viewModel.isDownloading.value) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                Text(viewModel.downloadProgress.value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BinaryActionChip(
                    label = "Server",
                    isInstalled = fridaManager.binaryManager.isServerAvailable(),
                    onDownload = { viewModel.downloadBinary(fridaManager, "server") },
                    modifier = Modifier.weight(1f),
                    enabled = !viewModel.isDownloading.value
                )
                BinaryActionChip(
                    label = "Inject/CLI",
                    isInstalled = availableInject || availableCli,
                    onDownload = { viewModel.downloadBinary(fridaManager, "cli") },
                    modifier = Modifier.weight(1f),
                    enabled = !viewModel.isDownloading.value
                )
            }
        }
    }
}

@Composable
private fun BinaryActionChip(label: String, isInstalled: Boolean, onDownload: () -> Unit, modifier: Modifier, enabled: Boolean) {
    Surface(
        onClick = { if (!isInstalled && enabled) onDownload() },
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isInstalled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isInstalled) MaterialTheme.colorScheme.outline.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Download,
                null,
                modifier = Modifier.size(14.dp),
                tint = if (isInstalled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
