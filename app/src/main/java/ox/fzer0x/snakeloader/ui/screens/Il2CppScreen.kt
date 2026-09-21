package ox.fzer0x.snakeloader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.ai.AgentExecutionMode
import ox.fzer0x.snakeloader.ai.AiGoalPreset
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.viewmodels.AutonomousAgentState
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppFieldData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppFilter
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppMethodData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppPropertyData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Il2CppScreen(
    viewModel: Il2CppViewModel,
    onBack: () -> Unit,
    onNavigateToSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val selectedApp = viewModel.selectedApp.value
    val showAppPicker = viewModel.showAppPicker.value
    val allApps = viewModel.allApps.value
    val classes = viewModel.filteredClasses.value
    val totalMatches = viewModel.totalMatchesCount.value
    val isDumping = viewModel.isDumping.value
    val statusText = viewModel.statusText.value
    val searchText = viewModel.searchText.value
    val selectedFilter = viewModel.selectedFilter.value
    val errorMessage = viewModel.errorMessage.value
    val generatedCodeDialog = viewModel.generatedCodeDialog.value
    val injectionDelaySeconds = viewModel.injectionDelaySeconds.value

    var showExportMenu by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var showGlobalAiDialog by remember { mutableStateOf(false) }
    var activeAiTarget by remember { mutableStateOf<Pair<Il2CppClassData, Il2CppMethodData>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context)
    }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = "IL2CPP Inspector",
                subtitle = if (selectedApp != null) "${selectedApp.label} (${selectedApp.packageName})" else "No app selected",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showGlobalAiDialog = true }) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Global AI Agent",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { viewModel.loadSavedDump(context) }) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "Load Cache",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(Icons.Default.IosShare, contentDescription = "Export")
                    }
                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export as JSON Dump") },
                            onClick = {
                                showExportMenu = false
                                val path = viewModel.exportDumpAsJson(context)
                                if (path != null) {
                                    Toast.makeText(context, "Exported JSON to: $path", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Export failed: No classes loaded", Toast.LENGTH_SHORT).show()
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Code, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export as C# Header (.cs)") },
                            onClick = {
                                showExportMenu = false
                                val path = viewModel.exportDumpAsCSharp(context)
                                if (path != null) {
                                    Toast.makeText(context, "Exported C# to: $path", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Export failed: No classes loaded", Toast.LENGTH_SHORT).show()
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.DataObject, null) }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // DASHBOARD OVERVIEW METRICS BANNER
            item {
                Spacer(modifier = Modifier.height(2.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetricBadge(
                            icon = Icons.Default.Category,
                            label = "Classes",
                            value = if (totalMatches > 0) "$totalMatches" else "${viewModel.classes.value.size}",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            icon = Icons.Default.Android,
                            label = "Target",
                            value = selectedApp?.label ?: "None",
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            icon = Icons.Default.Storage,
                            label = "Engine",
                            value = "SQLite Cache",
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // UNIFIED CONTROL CENTER CARD (TARGET APP + INJECTION DELAY + LAUNCH & INSPECT)
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // SECTION 1: TARGET APP SELECTOR
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val appIcon = remember(selectedApp) { selectedApp?.icon?.toBitmap() }
                            if (appIcon != null) {
                                Image(
                                    bitmap = appIcon.asImageBitmap(),
                                    contentDescription = selectedApp?.label,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                )
                                            ),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedApp?.label ?: "Select Target Application",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = selectedApp?.packageName ?: "Tap 'Select' to pick an installed app for metadata extraction",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledTonalButton(
                                onClick = { viewModel.openAppPicker() },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Select", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // SECTION 2: INJECTION DELAY CONFIGURATION
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Frida Injection Delay", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${injectionDelaySeconds} Seconds",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Slider(
                                value = injectionDelaySeconds.toFloat(),
                                onValueChange = { viewModel.setInjectionDelay(it.toInt()) },
                                valueRange = 0f..30f,
                                steps = 29,
                                modifier = Modifier.fillMaxWidth()
                            )

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(listOf(0, 3, 5, 7, 10, 15, 20)) { delayOpt ->
                                    FilterChip(
                                        selected = injectionDelaySeconds == delayOpt,
                                        onClick = { viewModel.setInjectionDelay(delayOpt) },
                                        label = { Text("${delayOpt}s", fontSize = 11.sp) },
                                        modifier = Modifier.height(30.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // SECTION 3: ACTION CONTROLS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.startInspection(context) },
                                enabled = selectedApp != null && !isDumping,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isDumping) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Extracting Metadata...", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Launch & Inspect", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.loadSavedDump(context) },
                                enabled = !isDumping,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Cached, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cached Dump", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // STATUS BANNER
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (errorMessage != null) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (errorMessage != null) Icons.Default.ErrorOutline else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: statusText,
                                fontSize = 12.sp,
                                color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isDumping) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                        }
                    }
                }
            }

            // SEARCH BAR & FILTER CHIPS TOOLBAR
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { viewModel.setSearchText(it) },
                        placeholder = { Text("Search classes, methods, fields, or offsets...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchText("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(Il2CppFilter.entries) { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = { Text(filter.displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (totalMatches > classes.size) "Classes: $totalMatches (${classes.size})" else "Classes: $totalMatches",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.pruneDatabaseNoise(context) { count ->
                                        Toast.makeText(context, "Pruned $count noise entries from database!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Prune DB", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Storage,
                                        contentDescription = null,
                                        modifier = Modifier.size(11.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "SQLite",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // PARSED CLASS CARDS LIST OR EMPTY STATE
            if (classes.isEmpty()) {
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(52.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (isDumping) "Extracting metadata from target process memory..." else "No classes matched your filter or search query.",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                if (!isDumping && selectedApp == null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(onClick = { viewModel.openAppPicker() }) {
                                        Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Select Target App")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(classes, key = { index, klass -> "${klass.assembly}::${klass.fullName}_$index" }) { _, klass ->
                    Il2CppClassCard(
                        klass = klass,
                        viewModel = viewModel,
                        onHookMethod = { method ->
                            viewModel.generateHookCode(klass, method)
                        },
                        onAiAssistant = { method ->
                            activeAiTarget = Pair(klass, method)
                            showAiDialog = true
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // APP PICKER DIALOG
    if (showAppPicker) {
        AppPickerDialog(
            apps = allApps,
            onDismiss = { viewModel.closeAppPicker() },
            onSelect = { app -> viewModel.selectApp(app) }
        )
    }

    // GENERATED HOOK DIALOG WITH SCROLLABLE CODE PREVIEW
    if (generatedCodeDialog != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissGeneratedCodeDialog() },
            title = { Text(generatedCodeDialog.first, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copy this Frida snippet or save it directly into your script environment:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    ScrollableCodePreview(
                        code = generatedCodeDialog.second,
                        maxHeight = 320.dp,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Frida Hook", generatedCodeDialog.second))
                            Toast.makeText(context, "Copied hook snippet to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Frida Hook", generatedCodeDialog.second))
                        Toast.makeText(context, "Copied hook snippet to clipboard!", Toast.LENGTH_SHORT).show()
                        viewModel.dismissGeneratedCodeDialog()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Snippet")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissGeneratedCodeDialog() }) {
                    Text("Close")
                }
            }
        )
    }

    // AI HOOK & PATCH ASSISTANT DIALOG
    if (showAiDialog && activeAiTarget != null) {
        val (klass, method) = activeAiTarget!!
        var selectedPreset by remember { mutableStateOf(AiGoalPreset.SPOOF_RETURN) }
        var customGoalText by remember { mutableStateOf("") }
        var aiStatusText by remember { mutableStateOf("") }
        val currentProvider = viewModel.selectedAiProvider.value

        AlertDialog(
            onDismissRequest = { showAiDialog = false },
            icon = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text("AI Agent: ${klass.name}::${method.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // PROVIDER SELECTOR CHIPS
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = currentProvider == "GEMINI",
                                onClick = { viewModel.setAiProvider("GEMINI") },
                                label = { Text("Gemini Cloud", fontSize = 10.sp) },
                                leadingIcon = { Icon(Icons.Default.Cloud, null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = currentProvider == "OLLAMA",
                                onClick = { viewModel.setAiProvider("OLLAMA") },
                                label = { Text("Ollama (Local/PC)", fontSize = 10.sp) },
                                leadingIcon = { Icon(Icons.Default.Dns, null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = currentProvider == "ON_DEVICE_GGUF",
                                onClick = { viewModel.setAiProvider("ON_DEVICE_GGUF") },
                                label = { Text("On-Device GGUF", fontSize = 10.sp) },
                                leadingIcon = { Icon(Icons.Default.Smartphone, null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }

                    if (currentProvider == "ON_DEVICE_GGUF") {
                        val downloadedModels = remember { viewModel.getDownloadedGgufModels(context) }
                        val activePath = viewModel.getOnDeviceModelPath()

                        Text("On-Device LLM Modell (1.5B / 3B / 7B):", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        if (downloadedModels.isEmpty()) {
                            Text(
                                "Kein GGUF Modell auf dem Handy! Bitte in Settings herunterladen.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(downloadedModels) { modelFile ->
                                    val isSelected = activePath == modelFile.absolutePath
                                    val displayName = when {
                                        modelFile.name.contains("7b", ignoreCase = true) -> "Qwen 7B (Uncensored)"
                                        modelFile.name.contains("3b", ignoreCase = true) -> "Qwen 3B (Uncensored)"
                                        modelFile.name.contains("1.5b", ignoreCase = true) -> "Qwen 1.5B"
                                        else -> modelFile.name
                                    }

                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.updateOnDeviceModelPath(modelFile.absolutePath) },
                                        label = { Text(displayName, fontSize = 10.sp) },
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(12.dp)) }
                                        } else null,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text("Select research goal or instruct AI Agent:", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AiGoalPreset.entries.toTypedArray()) { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { selectedPreset = preset },
                                label = { Text(preset.title, fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    if (selectedPreset == AiGoalPreset.CUSTOM) {
                        OutlinedTextField(
                            value = customGoalText,
                            onValueChange = { customGoalText = it },
                            placeholder = { Text("e.g. Set currentHealth to 9999 and skip damage check...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    if (viewModel.isGeneratingAiScript.value) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(viewModel.aiAgentStatus.value, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    } else if (viewModel.aiGeneratedScript.value != null) {
                        Text("Generated Frida Script:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        ScrollableCodePreview(
                            code = viewModel.aiGeneratedScript.value!!,
                            maxHeight = 220.dp,
                            onSave = {
                                val path = viewModel.saveGeneratedScriptAsJs(context, viewModel.aiGeneratedScript.value!!)
                                if (path != null) {
                                    Toast.makeText(context, "Saved JS to: $path", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onShare = {
                                viewModel.shareGeneratedScript(context, viewModel.aiGeneratedScript.value!!)
                            }
                        )
                    }

                    if (aiStatusText.isNotEmpty()) {
                        Text(aiStatusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (viewModel.aiGeneratedScript.value == null) {
                        Button(
                            onClick = {
                                viewModel.generateAiScriptWithGoal(
                                    context = context,
                                    klass = klass,
                                    method = method,
                                    goalPreset = selectedPreset,
                                    customGoal = customGoalText,
                                    onComplete = { _ ->
                                        aiStatusText = "Generated! Ready to deploy & test."
                                    }
                                )
                            },
                            enabled = !viewModel.isGeneratingAiScript.value
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Generate")
                        }
                    } else {
                        Button(
                            onClick = {
                                viewModel.deployAndTestAiScript(
                                    context = context,
                                    klass = klass,
                                    method = method,
                                    scriptCode = viewModel.aiGeneratedScript.value!!,
                                    onStatusUpdate = { status ->
                                        aiStatusText = status
                                    }
                                )
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Deploy & Test")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // GLOBAL APP-LEVEL GEMINI AUTONOMOUS AI AGENT DIALOG
    if (showGlobalAiDialog) {
        var globalInstruction by remember { mutableStateOf("") }
        val currentProvider = viewModel.selectedAiProvider.value

        val agentState = viewModel.autonomousState.value
        val isAgentRunning = viewModel.isAutonomousRunning.value
        val agentLogs = viewModel.agentLogSteps.value
        val currentIteration = viewModel.agentCurrentIteration.value
        val maxIterations = viewModel.agentMaxIterations.value
        val cloudValEnabled = viewModel.autonomousCloudValidationEnabled.value

        var telemetry by remember { mutableStateOf(viewModel.getTelemetryInfo(context)) }
        LaunchedEffect(Unit) {
            while (true) {
                telemetry = viewModel.getTelemetryInfo(context)
                delay(5000)
            }
        }

        val consoleScrollState = rememberScrollState()

        LaunchedEffect(agentLogs.size) {
            if (agentLogs.isNotEmpty()) {
                consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
            }
        }

        Dialog(
            onDismissRequest = {
                if (!isAgentRunning) showGlobalAiDialog = false
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !isAgentRunning
            )
        ) {
            Scaffold(
                modifier = Modifier.systemBarsPadding(),
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("ReShift AI Agent (BETA)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                                val loadedClassCount = viewModel.classes.value.size.coerceAtLeast(viewModel.totalMatchesCount.value)
                                Text(
                                    text = "Target: ${selectedApp?.label ?: "Target App"} ($loadedClassCount C# Classes Dumped)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (isAgentRunning) viewModel.abortAutonomousAgent()
                                showGlobalAiDialog = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close AI Agent")
                            }
                        },
                        actions = {
                            if (isAgentRunning) {
                                IconButton(onClick = { viewModel.abortAutonomousAgent() }) {
                                    Icon(Icons.Default.Stop, contentDescription = "Abort Agent", tint = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                IconButton(onClick = { viewModel.resetAutonomousAgent() }) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset Agent", tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                // GEMINI API KEY MISSING NOTIFICATION DIALOG
                var showApiKeyMissingDialog by remember { mutableStateOf(false) }
                var dismissedApiKeyWarning by remember { mutableStateOf(false) }
                val isGeminiMissingApiKey = currentProvider == "GEMINI" && viewModel.isGeminiApiKeyMissing

                val shouldShowApiKeyWarning = (showApiKeyMissingDialog || (isGeminiMissingApiKey && !dismissedApiKeyWarning)) && !isAgentRunning

                if (shouldShowApiKeyWarning) {
                    AlertDialog(
                        onDismissRequest = {
                            dismissedApiKeyWarning = true
                            showApiKeyMissingDialog = false
                        },
                        icon = {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "Gemini API Key Missing",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "A Gemini API Key is required when using Gemini Cloud for the ReShift AI Agent. Please configure your API key in Settings or select a local provider (Ollama / On-Device GGUF).",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    dismissedApiKeyWarning = true
                                    showApiKeyMissingDialog = false
                                    showGlobalAiDialog = false
                                    onNavigateToSettings?.invoke()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Open Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    dismissedApiKeyWarning = true
                                    showApiKeyMissingDialog = false
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Understood", fontSize = 12.sp)
                            }
                        }
                    )
                }

                // MODAL USER EVALUATION DIALOG (PROMINENT POPUP OVERLAY)
                if (agentState == AutonomousAgentState.WAITING_USER_EVALUATION) {
                    var showRefinementInput by remember { mutableStateOf(false) }
                    var refinedPromptInput by remember { mutableStateOf("") }

                    AlertDialog(
                        onDismissRequest = { /* Modal: require explicit decision */ },
                        icon = {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "Goal achieved in game?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val iterText = if (maxIterations > 0) "Attempt #$currentIteration of $maxIterations" else "Attempt #$currentIteration"
                                Text(
                                    text = "$iterText completed.\nCheck target app: Was the desired result (e.g. Budget/Coins increased to 50M) achieved?",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                if (showRefinementInput) {
                                    OutlinedTextField(
                                        value = refinedPromptInput,
                                        onValueChange = { refinedPromptInput = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Refinement note", fontSize = 11.sp) },
                                        placeholder = { Text("e.g. 'Try hooking get_Coins or AddMoney'", fontSize = 11.sp) },
                                        maxLines = 3,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.submitAgentUserEvaluation(goalAchieved = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("YES! Goal achieved", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            if (!showRefinementInput) {
                                Button(
                                    onClick = { showRefinementInput = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("No, adjust prompt", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        viewModel.submitAgentUserEvaluation(goalAchieved = false, refinedPrompt = refinedPromptInput.ifBlank { null })
                                        showRefinementInput = false
                                        refinedPromptInput = ""
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Start Attempt #${currentIteration + 1}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // TELEMETRY & HARDWARE DASHBOARD CARD
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Telemetry & Hardware", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (telemetry.useVulkanGpu) "Vulkan GPU" else "CPU NEON",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("Free RAM", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                        Text("${telemetry.freeRamMb} / ${telemetry.totalRamMb} MB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("Inference Latency", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                        Text(if (telemetry.lastInferenceTimeMs > 0) "${telemetry.lastInferenceTimeMs} ms" else "Idle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            HorizontalDivider(thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !telemetry.useVulkanGpu,
                                    onClick = { viewModel.setUseVulkanGpu(false) },
                                    label = { Text("CPU NEON", fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Default.Memory, null, modifier = Modifier.size(14.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = telemetry.useVulkanGpu,
                                    onClick = { viewModel.setUseVulkanGpu(true) },
                                    label = { Text("Vulkan GPU", fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Default.ElectricBolt, null, modifier = Modifier.size(14.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // LLM PROVIDER & VALIDATION CONFIGURATION CARD
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("LLM Engine & Validation Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = currentProvider == "GEMINI",
                                        onClick = { viewModel.setAiProvider("GEMINI") },
                                        label = { Text("Gemini Cloud", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.Cloud, null, modifier = Modifier.size(14.dp)) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = currentProvider == "OLLAMA",
                                        onClick = { viewModel.setAiProvider("OLLAMA") },
                                        label = { Text("Ollama Local", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.Dns, null, modifier = Modifier.size(14.dp)) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = currentProvider == "ON_DEVICE_GGUF",
                                        onClick = { viewModel.setAiProvider("ON_DEVICE_GGUF") },
                                        label = { Text("On-Device GGUF", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.Smartphone, null, modifier = Modifier.size(14.dp)) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = cloudValEnabled,
                                    onCheckedChange = { viewModel.setAutonomousCloudValidation(it) }
                                )
                                Text("Enable Cloud Static Analysis Validation", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Smart Database Clean:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.pruneDatabaseNoise(context) { count ->
                                            Toast.makeText(context, "Pruned $count noise entries from database!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Prune DB Noise", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(thickness = 0.5.dp)

                            // AGENT INJECTION MODE SELECTOR (SPAWN vs ATTACH vs AUTO)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Agent Execution Mode:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                val currentMode = viewModel.agentExecutionMode.value
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = currentMode == AgentExecutionMode.AUTO,
                                        onClick = { viewModel.setAgentExecutionMode(AgentExecutionMode.AUTO) },
                                        label = { Text("Auto", fontSize = 10.sp, maxLines = 1) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = currentMode == AgentExecutionMode.SPAWN,
                                        onClick = { viewModel.setAgentExecutionMode(AgentExecutionMode.SPAWN) },
                                        label = { Text("Spawn (-f)", fontSize = 10.sp, maxLines = 1) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = currentMode == AgentExecutionMode.ATTACH,
                                        onClick = { viewModel.setAgentExecutionMode(AgentExecutionMode.ATTACH) },
                                        label = { Text("Attach (-n)", fontSize = 10.sp, maxLines = 1) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            val currentDelay = viewModel.agentInjectionDelaySeconds.value
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Injektions-Verzögerung:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        text = if (currentDelay == 0) "Sofort (0s)" else "${currentDelay}s",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = currentDelay.toFloat(),
                                    onValueChange = { viewModel.setAgentInjectionDelay(it.toInt()) },
                                    valueRange = 0f..60f,
                                    steps = 59,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // AGENT PIPELINE STATUS CARD & CLOSED-LOOP LIVE PROCESS-TIMELINE
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = when (agentState) {
                                AutonomousAgentState.SUCCESS_VALIDATED -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                                AutonomousAgentState.FAILED_MAX_RETRIES, AutonomousAgentState.ABORTED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                AutonomousAgentState.IDLE -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isAgentRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = when (agentState) {
                                                AutonomousAgentState.SUCCESS_VALIDATED -> Icons.Default.CheckCircle
                                                AutonomousAgentState.FAILED_MAX_RETRIES, AutonomousAgentState.ABORTED -> Icons.Default.Error
                                                else -> Icons.Default.Psychology
                                            },
                                            contentDescription = null,
                                            tint = when (agentState) {
                                                AutonomousAgentState.SUCCESS_VALIDATED -> Color(0xFF4CAF50)
                                                AutonomousAgentState.FAILED_MAX_RETRIES, AutonomousAgentState.ABORTED -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = agentState.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (agentState) {
                                            AutonomousAgentState.SUCCESS_VALIDATED -> Color(0xFF4CAF50)
                                            AutonomousAgentState.FAILED_MAX_RETRIES, AutonomousAgentState.ABORTED -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }

                                if (currentIteration > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        val iterBadgeText = if (maxIterations > 0) "Attempt $currentIteration / $maxIterations" else "Attempt #$currentIteration"
                                        Text(
                                            text = iterBadgeText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // SENIOR REPAIR DIAGNOSIS SUMMARY CARD
                            if (viewModel.agentLastRepairDiagnosis.value.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = viewModel.agentLastRepairDiagnosis.value,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }

                            // VISUAL PROCESS-TIMELINE STEPPER
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val steps = listOf(
                                    "Build Prompt" to (agentState != AutonomousAgentState.IDLE),
                                    "Inference" to (agentState != AutonomousAgentState.IDLE && agentState != AutonomousAgentState.GENERATING_INITIAL),
                                    "Pre-Audit" to (agentState == AutonomousAgentState.PRE_VALIDATING || agentState == AutonomousAgentState.INJECTING || agentState == AutonomousAgentState.TESTING_AND_MONITORING || agentState == AutonomousAgentState.SUCCESS_VALIDATED),
                                    "Inject" to (agentState == AutonomousAgentState.INJECTING || agentState == AutonomousAgentState.TESTING_AND_MONITORING || agentState == AutonomousAgentState.SUCCESS_VALIDATED),
                                    "Dual-Stream Monitor" to (agentState == AutonomousAgentState.TESTING_AND_MONITORING || agentState == AutonomousAgentState.SUCCESS_VALIDATED),
                                    "Success/Repair" to (agentState == AutonomousAgentState.SUCCESS_VALIDATED || agentState == AutonomousAgentState.CORRECTING_SCRIPT)
                                )

                                steps.forEachIndexed { idx, (title, isReached) ->
                                    Surface(
                                        color = if (isReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = title,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isReached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (idx < steps.size - 1) {
                                        Text("➔", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }

                            if (isAgentRunning) {
                                if (maxIterations > 0) {
                                    LinearProgressIndicator(
                                        progress = { (currentIteration.coerceIn(1, maxIterations)) / maxIterations.toFloat() },
                                        modifier = Modifier.fillMaxWidth().height(4.dp)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // GOAL INSTRUCTION INPUT
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Reverse Engineering Goal:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item {
                                OutlinedButton(
                                    onClick = { globalInstruction = "Hook libil2cpp.so and bypass all Anti-Cheat, integrity, and security checks." },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Anti-Cheat Bypass", fontSize = 11.sp)
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { globalInstruction = "Create a Frida script for God Mode, infinite health, freeze HP, and boost damage." },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("God Mode & HP", fontSize = 11.sp)
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { globalInstruction = "Create a Frida script for unlimited money, coins, gems, and unlocked store items." },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Unlimited Money", fontSize = 11.sp)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = globalInstruction,
                            onValueChange = { globalInstruction = it },
                            placeholder = { Text("Specify reverse engineering goal (e.g. Spoof return value of IsBanned and lock playerHealth at 9999)...") },
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 3,
                            enabled = !isAgentRunning
                        )
                    }

                    // LIVE GENERATOR PREVIEW USING SCROLLABLE CODE PREVIEW
                    if (viewModel.isGeneratingAiScript.value || (isAgentRunning && !viewModel.aiGeneratedScript.value.isNullOrBlank())) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Live Token Generator Stream:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            ScrollableCodePreview(
                                code = viewModel.aiGeneratedScript.value ?: "// Generating...",
                                maxHeight = 160.dp
                            )
                        }
                    }

                    // MULTI-TAB AGENT INSPECTION SUITE: TERMINAL / FRIDA CONSOLE / SQL DB AST
                    var agentTabSelection by remember { mutableIntStateOf(0) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TabRow(
                            selectedTabIndex = agentTabSelection,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = agentTabSelection == 0,
                                onClick = { agentTabSelection = 0 },
                                text = { Text("Terminal Log", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                icon = { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(14.dp)) }
                            )
                            Tab(
                                selected = agentTabSelection == 1,
                                onClick = { agentTabSelection = 1 },
                                text = { Text("Frida Console", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                icon = { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(14.dp)) }
                            )
                            Tab(
                                selected = agentTabSelection == 2,
                                onClick = { agentTabSelection = 2 },
                                text = { Text("sql.db AST Context", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                icon = { Icon(Icons.Default.Storage, null, modifier = Modifier.size(14.dp)) }
                            )
                        }

                        Surface(
                            color = Color(0xFF0D1117),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 280.dp)
                        ) {
                            Box(modifier = Modifier.padding(10.dp)) {
                                when (agentTabSelection) {
                                    0 -> {
                                        // TAB 0: TERMINAL ACTIVITY LOG
                                        Box(modifier = Modifier.verticalScroll(consoleScrollState)) {
                                            if (agentLogs.isEmpty()) {
                                                Text(
                                                    text = "# Agent terminal ready. Tap 'Launch ReShift AI Agent Loop' below to start.",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF8B949E)
                                                )
                                            } else {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    agentLogs.forEach { step ->
                                                        val (stepColor, badgeBg) = when (step.stepType) {
                                                            "INIT", "GENERATE", "VALIDATE" -> Pair(Color(0xFF58A6FF), Color(0xFF1F6FEB).copy(alpha = 0.2f))
                                                            "INJECT", "MONITOR" -> Pair(Color(0xFFD29922), Color(0xFF9E6A03).copy(alpha = 0.2f))
                                                            "ERROR" -> Pair(Color(0xFFFF7B72), Color(0xFF8E1519).copy(alpha = 0.3f))
                                                            "FIX" -> Pair(Color(0xFFBC8CFF), Color(0xFF6E40C9).copy(alpha = 0.2f))
                                                            "SUCCESS" -> Pair(Color(0xFF3FB950), Color(0xFF238636).copy(alpha = 0.3f))
                                                            else -> Pair(Color(0xFF8B949E), Color(0xFF30363D).copy(alpha = 0.3f))
                                                        }

                                                        Column {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Surface(
                                                                    color = badgeBg,
                                                                    shape = RoundedCornerShape(4.dp)
                                                                ) {
                                                                    Text(
                                                                        text = step.stepType,
                                                                        fontSize = 9.sp,
                                                                        fontFamily = FontFamily.Monospace,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = stepColor,
                                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    text = step.timestamp,
                                                                    fontSize = 10.sp,
                                                                    fontFamily = FontFamily.Monospace,
                                                                    color = Color(0xFF8B949E)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = step.message,
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 11.sp,
                                                                color = stepColor,
                                                                lineHeight = 15.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    1 -> {
                                        // TAB 1: FRIDA LIVE CONSOLE OUTPUT
                                        val consoleText = viewModel.agentLastConsoleOutput.value.ifBlank { "# No Frida Console Output recorded yet during execution." }
                                        Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                            Text(
                                                text = consoleText,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = Color(0xFF7EE787),
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                    2 -> {
                                        // TAB 2: SQL DB AST CONTEXT
                                        val sqlContextText = viewModel.agentLastSqlAstContext.value.ifBlank { "# No sql.db (il2cpp_dumper.db) metadata context queried yet." }
                                        Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                            Text(
                                                text = sqlContextText,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = Color(0xFF79C0FF),
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // INTERACTIVE USER EVALUATION OVERLAY CARD
                    if (agentState == AutonomousAgentState.WAITING_USER_EVALUATION) {
                        var showRefinementInput by remember { mutableStateOf(false) }
                        var refinedPromptInput by remember { mutableStateOf("") }

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val evalCardTitle = if (maxIterations > 0) "Ziel im Spiel erreicht? (Attempt $currentIteration / $maxIterations)" else "Ziel im Spiel erreicht? (Attempt #$currentIteration)"
                                    Text(
                                        text = evalCardTitle,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                Text(
                                    text = "Überprüfe deine Ziel-App. Wurde das gewünschte Ergebnis (z.B. Budget/Coins auf 50 Mio erhöht) erzielt?",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.submitAgentUserEvaluation(goalAchieved = true) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("JA! Ziel erreicht", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = { showRefinementInput = true },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Nein, nachjustieren", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (showRefinementInput) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = refinedPromptInput,
                                            onValueChange = { refinedPromptInput = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("Optional: Hinweis zur Nachjustierung (z.B. 'Versuche get_Coins oder AddMoney')", fontSize = 11.sp) },
                                            maxLines = 2,
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        Button(
                                            onClick = {
                                                viewModel.submitAgentUserEvaluation(goalAchieved = false, refinedPrompt = refinedPromptInput.ifBlank { null })
                                                showRefinementInput = false
                                                refinedPromptInput = ""
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Attempt #${currentIteration + 1} starten & nachjustieren", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // FINAL / VALIDATED SCRIPT PREVIEW
                    if (viewModel.aiGeneratedScript.value != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (agentState == AutonomousAgentState.SUCCESS_VALIDATED) "Validated Frida Script:" else "Current Candidate Script:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (agentState == AutonomousAgentState.SUCCESS_VALIDATED) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                            )

                            ScrollableCodePreview(
                                code = viewModel.aiGeneratedScript.value!!,
                                maxHeight = 240.dp,
                                onSave = {
                                    val path = viewModel.saveGeneratedScriptAsJs(context, viewModel.aiGeneratedScript.value!!)
                                    if (path != null) {
                                        Toast.makeText(context, "Saved & Assigned JS: $path", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onShare = {
                                    viewModel.shareGeneratedScript(context, viewModel.aiGeneratedScript.value!!)
                                }
                            )
                        }
                    }

                    // INTERACTIVE NUDGE BANNER DURING MONITORING
                    if (agentState == AutonomousAgentState.TESTING_AND_MONITORING) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            color = Color(0xFF0288D1).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF0288D1))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.TouchApp,
                                        contentDescription = null,
                                        tint = Color(0xFF0288D1),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Aktion im Spiel ausführen",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = Color(0xFF0288D1)
                                        )
                                        Text(
                                            text = "Tippe oder benutze das Feature in der Ziel-App, um den Hook auszulösen.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Button(
                                    onClick = { viewModel.extendAgentMonitoringWindow(15000L) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("+15s Timer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // ACTION BUTTONS
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isAgentRunning) {
                            Button(
                                onClick = { viewModel.abortAutonomousAgent() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Abort ReShift AI Agent")
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (isGeminiMissingApiKey) {
                                        showApiKeyMissingDialog = true
                                    } else {
                                        viewModel.startAutonomousAgent(
                                            context = context,
                                            userInstruction = globalInstruction.ifBlank { "Analyze target C# classes and implement exact user request" }
                                        )
                                    }
                                },
                                enabled = viewModel.classes.value.isNotEmpty() || viewModel.totalMatchesCount.value > 0 || viewModel.filteredClasses.value.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (agentState == AutonomousAgentState.SUCCESS_VALIDATED) "Re-run ReShift AI Agent" else "Launch ReShift AI Agent Loop"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// SUPERCHARGED CLASS CARD WITH MULTI-TAB EXPANSION AND INLINE SEARCH
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Il2CppClassCard(
    klass: Il2CppClassData,
    viewModel: Il2CppViewModel,
    onHookMethod: (Il2CppMethodData) -> Unit,
    onAiAssistant: (Il2CppMethodData) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var itemSearchQuery by remember { mutableStateOf("") }
    var methodFilterOption by remember { mutableIntStateOf(0) } // 0 = All, 1 = With Offset, 2 = Getters/Setters

    val context = LocalContext.current

    // COLOR ACCENT BASED ON CLASS TYPE
    val (accentColor, typeLabel) = remember(klass) {
        when {
            klass.isEnum -> Pair(Color(0xFFFF9800), "ENUM")
            klass.isInterface -> Pair(Color(0xFF2196F3), "INTERFACE")
            klass.isValueType -> Pair(Color(0xFFAB47BC), "STRUCT")
            else -> Pair(Color(0xFF4CAF50), "CLASS")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = accentColor,
                    size = Size(5.dp.toPx(), size.height)
                )
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            // CLASS HEADER ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BadgePill(typeLabel, accentColor)
                        Spacer(modifier = Modifier.width(6.dp))
                        val isCompilerClass = klass.name.startsWith("<")
                        Text(
                            text = if (isCompilerClass) klass.fullName else klass.name,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (klass.namespace.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "namespace ${klass.namespace}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // QUICK CLASS ACTIONS
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val csharpCode = viewModel.generateCSharpPseudocode(klass)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("C# Stub", csharpCode))
                            Toast.makeText(context, "Copied C# stub to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Code, contentDescription = "Copy C# Stub", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }

                    IconButton(
                        onClick = {
                            viewModel.generateClassHookCode(klass)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Hook All Methods", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    }

                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (klass.parent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Base Class: ${klass.parent}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // STATS SUMMARY ROW
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${klass.methods.size} Methods",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${klass.fields.size} Fields",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (klass.properties.isNotEmpty()) {
                    Text(
                        text = "${klass.properties.size} Properties",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (klass.size > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${klass.size} B",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                if (klass.assembly.isNotEmpty()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = klass.assembly,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // EXPANDED BODY WITH TABS
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)

                val tabs = listOf("Methods (${klass.methods.size})", "Fields (${klass.fields.size})", "Properties (${klass.properties.size})", "C# Code View")

                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (selectedTab) {
                    // TAB 0: METHODS
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = itemSearchQuery,
                                    onValueChange = { itemSearchQuery = it },
                                    placeholder = { Text("Filter methods...", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp)) },
                                    singleLine = true
                                )

                                FilterChip(
                                    selected = methodFilterOption == 1,
                                    onClick = { methodFilterOption = if (methodFilterOption == 1) 0 else 1 },
                                    label = { Text("Offset", fontSize = 10.sp) },
                                    shape = RoundedCornerShape(6.dp)
                                )
                            }

                            val filteredMethods = remember(klass.methods, itemSearchQuery, methodFilterOption) {
                                klass.methods.filter { method ->
                                    val matchesQuery = itemSearchQuery.isBlank() ||
                                            method.name.contains(itemSearchQuery, ignoreCase = true) ||
                                            method.returnType.contains(itemSearchQuery, ignoreCase = true) ||
                                            method.offset.contains(itemSearchQuery, ignoreCase = true)

                                    val matchesFilter = when (methodFilterOption) {
                                        1 -> method.offset != "0x0" && method.offset != "0x00" && method.offset.isNotBlank()
                                        2 -> method.name.startsWith("get_") || method.name.startsWith("set_")
                                        else -> true
                                    }

                                    matchesQuery && matchesFilter
                                }
                            }

                            if (filteredMethods.isEmpty()) {
                                Text(
                                    "No methods matched inner filter.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                filteredMethods.forEach { method ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    // RETURN TYPE BADGE
                                                    val retColor = when (method.returnType.lowercase()) {
                                                        "void" -> Color(0xFF888888)
                                                        "bool", "boolean" -> Color(0xFFFFB74D)
                                                        "int", "int32", "int64", "float", "double" -> Color(0xFF64B5F6)
                                                        "string" -> Color(0xFF81C784)
                                                        else -> Color(0xFF4DD0E1)
                                                    }
                                                    BadgePill(method.returnType, retColor)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = method.name,
                                                        fontSize = 12.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                if (method.params.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "(${method.params.joinToString(", ")})",
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.outline,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                if (method.offset != "0x0" && method.offset.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                        Text(
                                                            text = "RVA Offset: ${method.offset}",
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (method.pointer.isNotBlank() && method.pointer != "0x0") {
                                                            Text(
                                                                text = "Pointer: ${method.pointer}",
                                                                fontSize = 10.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = MaterialTheme.colorScheme.outline,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(6.dp))

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                IconButton(
                                                    onClick = { onAiAssistant(method) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.AutoAwesome, contentDescription = "AI Hook", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                                                }

                                                IconButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        val clip = ClipData.newPlainText("Method Offset", method.offset)
                                                        clipboard.setPrimaryClip(clip)
                                                        Toast.makeText(context, "Copied offset ${method.offset}", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Offset", modifier = Modifier.size(14.dp))
                                                }

                                                Button(
                                                    onClick = { onHookMethod(method) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) {
                                                    Text("HOOK", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // TAB 1: FIELDS
                        1 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = itemSearchQuery,
                                    onValueChange = { itemSearchQuery = it },
                                    placeholder = { Text("Filter fields...", fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp)) },
                                    singleLine = true
                                )

                                val filteredFields = remember(klass.fields, itemSearchQuery) {
                                    if (itemSearchQuery.isBlank()) klass.fields else {
                                        klass.fields.filter {
                                            it.name.contains(itemSearchQuery, ignoreCase = true) ||
                                                    it.type.contains(itemSearchQuery, ignoreCase = true) ||
                                                    it.offset.contains(itemSearchQuery, ignoreCase = true)
                                        }
                                    }
                                }

                                if (filteredFields.isEmpty()) {
                                    Text("No fields matched filter.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                } else {
                                    filteredFields.forEach { field ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = field.type,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color(0xFF29B6F6)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = field.name,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            BadgePill(field.offset, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                }
                            }
                        }

                        // TAB 2: PROPERTIES
                        2 -> {
                            if (klass.properties.isEmpty()) {
                                Text("No properties declared in this class.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    klass.properties.forEach { prop ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = prop.type,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color(0xFFAB47BC)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = prop.name,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            BadgePill("{ get; set; }", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        // TAB 3: C# PSEUDOCODE VIEW
                        3 -> {
                            val csharpCode = remember(klass) { viewModel.generateCSharpPseudocode(klass) }
                            ScrollableCodePreview(
                                code = csharpCode,
                                maxHeight = 280.dp,
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("C# Stub", csharpCode))
                                    Toast.makeText(context, "Copied C# stub to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

// HIGH-TECH SCROLLABLE CODE PREVIEW COMPONENT WITH LINE NUMBERS, DUAL SCROLLING & TOOLBAR
@Composable
private fun ScrollableCodePreview(
    code: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
    showLineNumbers: Boolean = true,
    onCopy: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val lines = remember(code) { code.lines() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF121212))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
    ) {
        // CODE TOOLBAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).background(Color(0xFFFF5F56), CircleShape))
                Box(modifier = Modifier.size(8.dp).background(Color(0xFFFFBD2E), CircleShape))
                Box(modifier = Modifier.size(8.dp).background(Color(0xFF27C93F), CircleShape))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${lines.size} Lines",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF888888)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = {
                        if (onCopy != null) onCopy() else {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                            Toast.makeText(context, "Copied code to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFFCCCCCC), modifier = Modifier.size(14.dp))
                }

                if (onSave != null) {
                    IconButton(
                        onClick = onSave,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = Color(0xFFCCCCCC), modifier = Modifier.size(14.dp))
                    }
                }

                if (onShare != null) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFFCCCCCC), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // DUAL SCROLLING CONTAINER (VERTICAL + HORIZONTAL)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(verticalScroll)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // LINE NUMBERS COLUMN
                if (showLineNumbers) {
                    Column(
                        modifier = Modifier
                            .background(Color(0xFF1A1A1A))
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        lines.indices.forEach { index ->
                            Text(
                                text = "${index + 1}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFF555555),
                                lineHeight = 16.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF2B2B2B))
                    )
                }

                // HORIZONTALLY SCROLLABLE TEXT
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .padding(8.dp)
                ) {
                    Text(
                        text = code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF4EC9B0),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBadge(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BadgePill(
    text: String,
    bgColor: Color,
    textColor: Color = Color.White
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(5.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerDialog(
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onSelect: (AppInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps else {
            apps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Target Application", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search app name or package...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.heightIn(max = 350.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(app) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val iconBitmap = remember(app) { app.icon?.toBitmap() }
                                    if (iconBitmap != null) {
                                        Image(
                                            bitmap = iconBitmap.asImageBitmap(),
                                            contentDescription = app.label,
                                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Icon(Icons.Default.Android, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(app.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
