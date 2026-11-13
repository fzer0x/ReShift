package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
import ox.fzer0x.snakeloader.data.*
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.ReShiftDialogShape
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
    val isRpcAvailable = viewModel.isRpcAvailable.value
    val patches by viewModel.patches.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var selectedAobResultForPatch by remember { mutableStateOf<AobScanResult?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshHooks()
        viewModel.checkRpcStatus()
    }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = "Memory Inspector & Patch Engine",
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
                            Text(if (isRpcAvailable) "RPC" else "OFFLINE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isRpcAvailable) SuccessGreen else Color.Gray)
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
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("AOB Scanner", fontWeight = FontWeight.Bold) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Patches (${patches.size})", fontWeight = FontWeight.Bold) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Hooks", fontWeight = FontWeight.Bold) })
            }

            when (selectedTab) {
                0 -> AobScannerTab(
                    viewModel = viewModel,
                    onCreatePatch = { selectedAobResultForPatch = it }
                )
                1 -> SavedPatchesTab(
                    patches = patches,
                    onTogglePatch = { viewModel.togglePatch(it) },
                    onRemovePatch = { viewModel.removePatch(it) }
                )
                2 -> HooksTab(
                    hooks = hooks,
                    isLoading = isLoading,
                    onToggleHook = { viewModel.addDetectedHook(it) }
                )
            }

            if (errorMessage != null) {
                ErrorBanner(errorMessage)
            }
        }
    }

    selectedAobResultForPatch?.let { result ->
        CreatePatchFromAobDialog(
            result = result,
            onConfirm = { name, preset, customHex ->
                viewModel.convertAobResultToPatch(result, "target_app", name, preset, customHex)
                selectedAobResultForPatch = null
            },
            onDismiss = { selectedAobResultForPatch = null }
        )
    }
}

@Composable
private fun AobScannerTab(
    viewModel: MemoryInspectorViewModel,
    onCreatePatch: (AobScanResult) -> Unit
) {
    val scanResults = viewModel.scanResults.value
    val isScanning = viewModel.isAobScanning.value
    val statusMessage = viewModel.aobStatusMessage.value

    var patternInput by remember { mutableStateOf("4F ?? 05 D2 80 00 00 39") }
    var moduleInput by remember { mutableStateOf("libil2cpp.so") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = patternInput,
            onValueChange = { patternInput = it },
            label = { Text("AOB Pattern (Wildcard ?? supported)") },
            placeholder = { Text("e.g. 4F ?? 05 D2 80 00 00 39") },
            modifier = Modifier.fillMaxWidth(),
            shape = ReShiftButtonShape,
            singleLine = true
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = moduleInput,
                onValueChange = { moduleInput = it },
                label = { Text("Target Module") },
                placeholder = { Text("libil2cpp.so / empty for all") },
                modifier = Modifier.weight(1f),
                shape = ReShiftButtonShape,
                singleLine = true
            )

            if (isScanning) {
                Button(
                    onClick = { viewModel.cancelAobScan() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ReShiftButtonShape
                ) {
                    Icon(Icons.Default.Cancel, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { viewModel.startAobScan(moduleInput.ifBlank { null }, patternInput) },
                    shape = ReShiftButtonShape
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan AOB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }

        if (scanResults.isNotEmpty()) {
            Text("Matched Memory Addresses (${scanResults.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scanResults) { result ->
                    ReShiftCard {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(result.rvaOffsetHex, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("${result.moduleName} @ ${result.addressHex}", fontSize = 11.sp, color = Color.Gray)
                                if (result.matchedBytesHex.isNotBlank()) {
                                    Text("Bytes: ${result.matchedBytesHex}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                                }
                            }

                            Button(
                                onClick = { onCreatePatch(result) },
                                shape = ReShiftButtonShape,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Patch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPatchesTab(
    patches: List<MemoryPatch>,
    onTogglePatch: (String) -> Unit,
    onRemovePatch: (String) -> Unit
) {
    if (patches.isEmpty()) {
        EmptyStateBox("No saved memory patches. Create one via AOB Scanner or IL2CPP Method Inspector.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(patches) { patch ->
                ReShiftCard {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(patch.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                PatchStatusBadge(patch.status)
                            }
                            Text("${patch.moduleName} + ${patch.rvaOffsetHex}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                            Text("Patch: ${patch.patchBytesHex}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                            patch.originalBytesHex?.let {
                                Text("Original: $it", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = patch.status == PatchStatus.ACTIVE,
                                onCheckedChange = { onTogglePatch(patch.id) },
                                modifier = Modifier.scale(0.75f)
                            )
                            IconButton(onClick = { onRemovePatch(patch.id) }) {
                                Icon(Icons.Default.Delete, "Delete", tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchStatusBadge(status: PatchStatus) {
    val (color, label) = when (status) {
        PatchStatus.ACTIVE -> SuccessGreen to "ACTIVE"
        PatchStatus.INACTIVE -> Color.Gray to "OFF"
        PatchStatus.UNVERIFIED -> Color(0xFFFFB300) to "UNVERIFIED"
        PatchStatus.CONFLICT -> MaterialTheme.colorScheme.error to "CONFLICT"
        PatchStatus.ERROR -> MaterialTheme.colorScheme.error to "ERROR"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = ReShiftChipShape
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(hooks) { hook ->
                ReShiftCard {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
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
private fun CreatePatchFromAobDialog(
    result: AobScanResult,
    onConfirm: (name: String, preset: PatchPreset, customHex: String) -> Unit,
    onDismiss: () -> Unit
) {
    var patchName by remember { mutableStateOf("Patch @ ${result.rvaOffsetHex}") }
    var selectedPreset by remember { mutableStateOf(PatchPreset.NOP) }
    var customHex by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ReShiftDialogShape,
        title = { Text("Create Memory Patch", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${result.moduleName} + ${result.rvaOffsetHex}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = patchName,
                    onValueChange = { patchName = it },
                    label = { Text("Patch Name") },
                    shape = ReShiftButtonShape,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("ARM64 Opcode Preset:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                PatchPreset.entries.forEach { preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset }
                        )
                        Text(preset.label, fontSize = 13.sp)
                    }
                }

                if (selectedPreset == PatchPreset.CUSTOM) {
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { customHex = it },
                        label = { Text("Custom Hex Bytes (e.g. 1F 20 03 D5)") },
                        shape = ReShiftButtonShape,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(patchName, selectedPreset, customHex) },
                shape = ReShiftButtonShape
            ) {
                Text("Apply & Save Patch", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EmptyStateBox(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = ReShiftButtonShape
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
