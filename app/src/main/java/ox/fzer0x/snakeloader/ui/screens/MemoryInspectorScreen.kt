package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.HookInfo
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.ReShiftDialogShape
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen
import ox.fzer0x.snakeloader.ui.viewmodels.MemoryInspectorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryInspectorScreen(
    viewModel: MemoryInspectorViewModel,
    onBack: () -> Unit
) {
    val hooks = viewModel.hooks.value
    val isLoading = viewModel.isLoading.value
    val errorMessage = viewModel.errorMessage.value
    val memoryDump = viewModel.memoryDump.value
    val registryInfo = viewModel.registryInfo.value
    val isRpcAvailable = viewModel.isRpcAvailable.value

    var selectedTab by remember { mutableStateOf(0) }
    var showMemoryDumpDialog by remember { mutableStateOf(false) }
    var showMemoryWriteDialog by remember { mutableStateOf(false) }
    var dumpAddress by remember { mutableStateOf("0x0") }
    var dumpSize by remember { mutableStateOf("256") }
    var writeAddress by remember { mutableStateOf("") }
    var writeValue by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshHooks()
        viewModel.getRegistryStatus()
    }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = "Memory Inspector",
                onBack = onBack,
                actions = {
                    Surface(
                        color = if (isRpcAvailable) SuccessGreen.copy(alpha = 0.12f) else Color.Gray.copy(alpha = 0.12f),
                        shape = ReShiftChipShape,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(if (isRpcAvailable) SuccessGreen else Color.Gray, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isRpcAvailable) "RPC" else "IDLE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isRpcAvailable) SuccessGreen else Color.Gray)
                        }
                    }
                    IconButton(onClick = { viewModel.refreshHooks() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Hooks", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Scanner", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("State", fontWeight = FontWeight.Bold) }
                )
            }

            when (selectedTab) {
                0 -> HooksTab(
                    hooks = hooks,
                    isLoading = isLoading,
                    onToggleHook = { viewModel.toggleHook(it) }
                )
                1 -> ScannerTab(
                    viewModel = viewModel,
                    onDumpMemory = { addr, size ->
                        dumpAddress = addr
                        dumpSize = size.toString()
                        showMemoryDumpDialog = true
                    },
                    onWriteMemory = { addr ->
                        writeAddress = addr
                        showMemoryWriteDialog = true
                    }
                )
                2 -> RegistryTab(
                    registryInfo = registryInfo
                )
            }

            if (errorMessage != null) {
                ErrorBanner(errorMessage)
            }
        }
    }

    if (showMemoryDumpDialog) {
        MemoryDumpDialog(
            initialAddress = dumpAddress,
            initialSize = dumpSize,
            onConfirm = { addr, size ->
                try {
                    val sizeInt = size.toIntOrNull() ?: 256
                    viewModel.dumpMemoryRegion(addr, sizeInt)
                } catch (_: Exception) {
                }
                showMemoryDumpDialog = false
            },
            onDismiss = { showMemoryDumpDialog = false }
        )
    }

    if (showMemoryWriteDialog) {
        MemoryWriteDialog(
            initialAddress = writeAddress,
            initialValue = writeValue,
            onConfirm = { addr, value ->
                viewModel.writeMemory(addr, value)
                showMemoryWriteDialog = false
            },
            onDismiss = { showMemoryWriteDialog = false }
        )
    }
}

@Composable
private fun HooksTab(
    hooks: List<HookInfo>,
    isLoading: Boolean,
    onToggleHook: (String) -> Unit
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
    } else if (hooks.isEmpty()) {
        EmptyStateBox("No active hooks detected")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(hooks) { hook ->
                ReShiftCard {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = hook.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (hook.active) "LINKED" else "SUSPENDED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = if (hook.active) SuccessGreen else Color.Gray,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Switch(
                            checked = hook.active,
                            onCheckedChange = { onToggleHook(hook.name) },
                            modifier = Modifier.scale(0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerTab(
    viewModel: MemoryInspectorViewModel,
    onDumpMemory: (String, Int) -> Unit,
    onWriteMemory: (String) -> Unit
) {
    val scanResults = viewModel.scanResults.value
    val isLoading = viewModel.isLoading.value
    val memoryDump = viewModel.memoryDump.value
    
    var searchPattern by remember { mutableStateOf("") }
    var selectedModule by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = searchPattern,
            onValueChange = { searchPattern = it },
            label = { Text("Search Pattern (Hex: 00 AA BB ??)") },
            modifier = Modifier.fillMaxWidth(),
            shape = ReShiftButtonShape,
            trailingIcon = {
                IconButton(onClick = { viewModel.performScan(searchPattern, selectedModule) }) {
                    Icon(Icons.Default.Search, null)
                }
            },
            singleLine = true
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onDumpMemory("0x0", 256) },
                modifier = Modifier.weight(1f),
                shape = ReShiftButtonShape
            ) {
                Icon(Icons.Default.Memory, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dump", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            
            OutlinedButton(
                onClick = { },
                modifier = Modifier.weight(1f),
                shape = ReShiftButtonShape
            ) {
                Text(if (selectedModule.isEmpty()) "All Modules" else selectedModule, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Bold)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
        }

        if (memoryDump.isNotEmpty()) {
            MemoryDumpView(memoryDump)
        }

        if (scanResults.isNotEmpty()) {
            Text("Matches: ${scanResults.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scanResults) { result ->
                    val address = result["address"] as? String ?: ""
                    ReShiftCard(
                        onClick = { onDumpMemory(address, 64) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = address, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { onWriteMemory(address) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryDumpView(dump: Map<String, String>) {
    ReShiftCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ADDR: ${dump["address"]}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("SIZE: ${dump["size"]}B", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("RAW HEX", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Surface(
                color = Color(0xFF1E1E1E),
                shape = ReShiftChipShape,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = dump["hex"] ?: "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SuccessGreen,
                    modifier = Modifier.padding(8.dp),
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("ASCII STRING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Surface(
                color = Color(0xFF1E1E1E),
                shape = ReShiftChipShape,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = dump["ascii"] ?: "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF2196F3),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun RegistryTab(registryInfo: Map<String, Any>?) {
    if (registryInfo == null) {
        EmptyStateBox("No system state data")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionHeader("ENGINE REGISTRY") }
            
            registryInfo.forEach { (key, value) ->
                when (value) {
                    is Map<*, *> -> {
                        item {
                            Text(
                                text = key.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                            )
                        }
                        items(value.toList()) { (subKey, subValue) ->
                            RegistryItem(subKey.toString(), subValue.toString(), isNested = true)
                        }
                    }
                    is List<*> -> {
                        item {
                            Text(
                                text = key.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                            )
                        }
                        if (value.isEmpty()) {
                            item { RegistryItem("None", "-", isNested = true) }
                        } else {
                            items(value) { item ->
                                RegistryItem(item.toString(), "LOADED", isNested = true)
                            }
                        }
                    }
                    else -> {
                        item {
                            RegistryItem(key, value.toString())
                        }
                    }
                }
            }
            
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun RegistryItem(key: String, value: String, isNested: Boolean = false) {
    Surface(
        color = if (isNested) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
        shape = ReShiftChipShape,
        border = BorderStroke(
            1.dp, 
            if (isNested) MaterialTheme.colorScheme.outline.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        modifier = if (isNested) Modifier.padding(start = 12.dp) else Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = key,
                fontSize = if (isNested) 11.sp else 12.sp,
                fontWeight = if (isNested) FontWeight.Medium else FontWeight.Bold,
                modifier = Modifier.weight(1f),
                color = if (isNested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isNested) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyStateBox(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inbox, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
        shape = ReShiftChipShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MemoryDumpDialog(
    initialAddress: String,
    initialSize: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf(initialAddress) }
    var size by remember { mutableStateOf(initialSize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Memory Scan Region", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address (Hex)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ReShiftButtonShape
                )
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it },
                    label = { Text("Size (Bytes)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ReShiftButtonShape
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(address, size) }, shape = ReShiftButtonShape) {
                Text("Execute Scan", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = ReShiftButtonShape) {
                Text("Cancel")
            }
        },
        shape = ReShiftDialogShape
    )
}

@Composable
private fun MemoryWriteDialog(
    initialAddress: String,
    initialValue: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf(initialAddress) }
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write Memory", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address (Hex)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ReShiftButtonShape
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value (Hex)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ReShiftButtonShape
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(address, value) }, shape = ReShiftButtonShape) {
                Text("Write", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = ReShiftButtonShape) {
                Text("Cancel")
            }
        },
        shape = ReShiftDialogShape
    )
}
