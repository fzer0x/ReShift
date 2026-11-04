package ox.fzer0x.snakeloader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.decompiler.*
import ox.fzer0x.snakeloader.ui.components.*
import ox.fzer0x.snakeloader.ui.viewmodels.DecompilerMode
import ox.fzer0x.snakeloader.ui.viewmodels.DexDecompilerViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.SymbolFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexDecompilerScreen(
    viewModel: DexDecompilerViewModel,
    fridaManager: FridaManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val selectedApp by viewModel.selectedApp.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val decompilerMode by viewModel.decompilerMode.collectAsState()
    val symbolFilter by viewModel.symbolFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val dexClasses by viewModel.dexClasses.collectAsState()
    val packageTree by viewModel.packageTree.collectAsState()
    val expandedPackages by viewModel.expandedPackages.collectAsState()
    val selectedDexClass by viewModel.selectedDexClass.collectAsState()
    val selectedDexMethod by viewModel.selectedDexMethod.collectAsState()

    val elfLibraries by viewModel.elfLibraries.collectAsState()
    val selectedElfLibrary by viewModel.selectedElfLibrary.collectAsState()
    val selectedElfSymbol by viewModel.selectedElfSymbol.collectAsState()

    val hookOptions by viewModel.hookOptions.collectAsState()
    val generatedScript by viewModel.generatedScript.collectAsState()
    val r2Commands by viewModel.r2Commands.collectAsState()

    var showAppPicker by remember { mutableStateOf(false) }
    var showHookStudioDialog by remember { mutableStateOf(false) }
    var showInspectorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context)
    }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = "DEX & Native Decompiler",
                subtitle = "Ghidra / Radare2 & DEX Inspector",
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Ultra-Compact Target Application & Mode Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showAppPicker = true }
                    ) {
                        if (selectedApp?.icon != null) {
                            Image(
                                bitmap = selectedApp!!.icon!!.toBitmap(32, 32).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = selectedApp?.label ?: "Select Application",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedApp?.packageName ?: "No package",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Compact Mode Selector Segmented Buttons
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.height(32.dp)) {
                            SegmentedButton(
                                selected = decompilerMode == DecompilerMode.DEX_CLASSES,
                                onClick = { viewModel.setMode(context, DecompilerMode.DEX_CLASSES) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) {
                                Text("DEX", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            SegmentedButton(
                                selected = decompilerMode == DecompilerMode.NATIVE_ELF,
                                onClick = { viewModel.setMode(context, DecompilerMode.NATIVE_ELF) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) {
                                Text("ELF (.so)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = { showAppPicker = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Apps, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Compact Search & Frida Studio Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(context, it) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                    placeholder = { Text("Search classes, methods, offsets...", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.setSearchQuery(context, "") },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Clear, null, modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                if (generatedScript != null) {
                    FilledTonalButton(
                        onClick = { showHookStudioDialog = true },
                        modifier = Modifier.height(48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Build, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Studio", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (decompilerMode == DecompilerMode.NATIVE_ELF) {
                Spacer(modifier = Modifier.height(4.dp))
                // Compact Symbol Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SymbolFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = symbolFilter == filter,
                            onClick = { viewModel.setSymbolFilter(context, filter) },
                            label = { Text(filter.label, fontSize = 10.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // Compact Status Line
            Text(
                text = statusMessage,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                if (decompilerMode == DecompilerMode.DEX_CLASSES) {
                    // DEX Classes Package Hierarchy Tree List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (packageTree != null && searchQuery.isBlank()) {
                            item {
                                PackageTreeNodeItem(
                                    node = packageTree!!,
                                    expandedPackages = expandedPackages,
                                    selectedDexClass = selectedDexClass,
                                    onToggleExpand = { viewModel.togglePackageExpansion(it) },
                                    onSelectClass = { viewModel.selectDexClass(it) },
                                    onSelectMethod = { method ->
                                        viewModel.selectDexMethod(method)
                                        showInspectorDialog = true
                                    }
                                )
                            }
                        } else {
                            items(dexClasses) { klass ->
                                DexClassCard(
                                    klass = klass,
                                    isSelected = klass.fullName == selectedDexClass?.fullName,
                                    onSelect = { viewModel.selectDexClass(klass) },
                                    onSelectMethod = { method ->
                                        viewModel.selectDexMethod(method)
                                        showInspectorDialog = true
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Native ELF Shared Libraries List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(elfLibraries) { lib ->
                            ElfLibraryCard(
                                library = lib,
                                isSelected = lib.name == selectedElfLibrary?.name,
                                selectedSymbol = selectedElfSymbol,
                                onSelectLibrary = {
                                    viewModel.selectElfLibrary(context, lib) {
                                        showInspectorDialog = true
                                    }
                                },
                                onSelectSymbol = { sym ->
                                    viewModel.selectElfSymbol(sym)
                                    showInspectorDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        DecompilerAppPickerDialog(
            apps = installedApps,
            onSelect = {
                viewModel.selectApp(context, it)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showInspectorDialog) {
        DecompilerInspectorDialog(
            isNativeMode = (decompilerMode == DecompilerMode.NATIVE_ELF),
            dexClass = selectedDexClass,
            dexMethod = selectedDexMethod,
            elfLibrary = selectedElfLibrary,
            elfSymbol = selectedElfSymbol,
            r2Commands = r2Commands,
            onDismiss = { showInspectorDialog = false }
        )
    }

    if (showHookStudioDialog && generatedScript != null) {
        FridaHookStudioDialog(
            script = generatedScript!!,
            options = hookOptions,
            onOptionsChanged = { viewModel.updateHookOptions(it) },
            r2Commands = r2Commands,
            onInject = {
                viewModel.injectGeneratedScript(fridaManager) { success ->
                    Toast.makeText(context, if (success) "Script Injected Successfully!" else "Frida Injection Failed", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showHookStudioDialog = false }
        )
    }
}

@Composable
fun DecompilerModeCard(
    title: String,
    subtitle: String,
    badge: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(ReShiftCardShape)
            .clickable { onClick() },
        shape = ReShiftCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = ReShiftBadgeShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = badge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DexClassCard(
    klass: DexClassData,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onSelectMethod: (DexMethodData) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = ReShiftCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = klass.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (klass.isInterface) "INTERFACE" else if (klass.isEnum) "ENUM" else "CLASS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = klass.fullName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Extends: ${klass.superClass.substringAfterLast('.')}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary
            )

            if (klass.methods.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Methods (${klass.methods.size}):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    klass.methods.take(6).forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { onSelectMethod(m) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${m.accessFlags} ${m.returnType} ${m.name}(${m.paramTypes.joinToString()})",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "INSPECT",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ElfLibraryCard(
    library: ElfModuleData,
    isSelected: Boolean,
    selectedSymbol: ElfSymbolData?,
    onSelectLibrary: () -> Unit,
    onSelectSymbol: (ElfSymbolData) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectLibrary() },
        shape = ReShiftCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.secondary) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = library.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = library.architecture,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable { onSelectLibrary() }
                    ) {
                        Text(
                            text = "INSPECT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = "${library.fileSizeMb} MB | Exports: ${library.exportsCount} | Imports: ${library.importsCount}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (library.symbols.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Symbols & Offsets (${library.symbols.size}):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    library.symbols.take(6).forEach { sym ->
                        val isSymSelected = sym.offsetHex == selectedSymbol?.offsetHex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSymSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { onSelectSymbol(sym) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sym.demangledName,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Offset: ${sym.offsetHex} | Bind: ${sym.bind} | Type: ${sym.type}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "ASM / C",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DecompilerInspectorDialog(
    isNativeMode: Boolean,
    dexClass: DexClassData?,
    dexMethod: DexMethodData?,
    elfLibrary: ElfModuleData?,
    elfSymbol: ElfSymbolData?,
    r2Commands: List<String>,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = if (!isNativeMode) listOf("Java", "Smali", "XREFs") else listOf("ARM64 ASM", "Pseudo-C", "Hex Dump", "R2 Frida")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ReShiftDialogShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (!isNativeMode) "${dexClass?.name}.${dexMethod?.name}()" else "${elfSymbol?.demangledName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = if (!isNativeMode) "${dexClass?.fullName}" else "Library: ${elfLibrary?.name} @ ${elfSymbol?.offsetHex}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                SecondaryScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    containerColor = Color.Transparent
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        if (!isNativeMode) {
                            when (selectedTab) {
                                0 -> {
                                    val javaCode = dexMethod?.getEffectiveCodePreview(dexClass?.fullName ?: "") ?: "// No Java preview"
                                    Text(text = DecompilerSyntaxHighlighter.highlightJavaCode(javaCode), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                1 -> {
                                    val smaliCode = dexMethod?.getEffectiveSmaliDisassembly(dexClass?.fullName ?: "") ?: "// No Smali disassembly"
                                    Text(text = DecompilerSyntaxHighlighter.highlightSmaliCode(smaliCode), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                2 -> {
                                    val xrefsList = dexMethod?.xrefs?.ifEmpty { listOf("callee: L${dexClass?.fullName?.replace('.', '/')};->${dexMethod.name}", "caller: L${dexClass?.fullName?.replace('.', '/')};->onCreate") }
                                    Text(text = xrefsList?.joinToString("\n") ?: "// No XREFs found", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFFFA500))
                                }
                            }
                        } else {
                            when (selectedTab) {
                                0 -> {
                                    val arm64Code = elfSymbol?.disassemblyLines?.joinToString("\n") { line ->
                                        "${line.addressHex}  ${line.bytesHex.padEnd(12)}  ${line.mnemonic.padEnd(6)} ${line.operands}${if (line.comment != null) "  ${line.comment}" else ""}"
                                    } ?: "// No disassembly"
                                    Text(text = DecompilerSyntaxHighlighter.highlightArm64Code(arm64Code), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                                1 -> {
                                    val pseudoC = elfSymbol?.pseudoCode ?: "// No pseudo-code generated"
                                    Text(text = DecompilerSyntaxHighlighter.highlightJavaCode(pseudoC), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                2 -> Text(text = elfSymbol?.hexDumpPreview ?: "// No hex preview", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFFFD700))
                                3 -> Text(text = r2Commands.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFFFA500))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun FridaHookStudioDialog(
    script: String,
    options: FridaHookOptions,
    onOptionsChanged: (FridaHookOptions) -> Unit,
    r2Commands: List<String>,
    onInject: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ReShiftDialogShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Frida & R2 Hook Studio",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Surface(
                        shape = ReShiftBadgeShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "INTERACTIVE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Customization Controls
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Log Function Arguments", fontSize = 12.sp)
                        Switch(
                            checked = options.logArgs,
                            onCheckedChange = { onOptionsChanged(options.copy(logArgs = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Log Backtrace / StackTrace", fontSize = 12.sp)
                        Switch(
                            checked = options.logStackTrace,
                            onCheckedChange = { onOptionsChanged(options.copy(logStackTrace = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Override Return Value", fontSize = 12.sp)
                        Switch(
                            checked = options.overrideReturn,
                            onCheckedChange = { onOptionsChanged(options.copy(overrideReturn = it)) }
                        )
                    }

                    if (options.overrideReturn) {
                        OutlinedTextField(
                            value = options.customReturnValue,
                            onValueChange = { onOptionsChanged(options.copy(customReturnValue = it)) },
                            label = { Text("Custom Return Value (e.g., true, false, 0x1)", fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Patch Native Memory (ARM64 RET)", fontSize = 12.sp)
                        Switch(
                            checked = options.memoryPatching,
                            onCheckedChange = { onOptionsChanged(options.copy(memoryPatching = it)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Generated Code Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = DecompilerSyntaxHighlighter.highlightJavaCode(script),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Frida Snippet", script))
                        Toast.makeText(context, "Copied to Clipboard!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy JS")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onInject()
                            onDismiss()
                        },
                        shape = ReShiftButtonShape
                    ) {
                        Text("Inject via Frida")
                    }
                }
            }
        }
    }
}

@Composable
fun DecompilerAppPickerDialog(
    apps: List<AppInfo>,
    onSelect: (AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ReShiftDialogShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Select Application",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(apps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(app) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon.toBitmap(36, 36).asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )
                            } else {
                                Icon(Icons.Default.Apps, null, modifier = Modifier.size(36.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(text = app.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(text = app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun PackageTreeNodeItem(
    node: DexPackageNode,
    expandedPackages: Set<String>,
    selectedDexClass: DexClassData?,
    onToggleExpand: (String) -> Unit,
    onSelectClass: (DexClassData) -> Unit,
    onSelectMethod: (DexMethodData) -> Unit,
    depth: Int = 0
) {
    val isExpanded = expandedPackages.contains(node.fullPackagePath)

    Column(modifier = Modifier.padding(start = (depth * 8).dp)) {
        if (node.name.isNotEmpty() && node.name != "root") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .clickable { onToggleExpand(node.fullPackagePath) }
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = node.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${node.totalClassCount}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (isExpanded || node.name.isEmpty() || node.name == "root") {
            node.subpackages.forEach { sub ->
                PackageTreeNodeItem(
                    node = sub,
                    expandedPackages = expandedPackages,
                    selectedDexClass = selectedDexClass,
                    onToggleExpand = onToggleExpand,
                    onSelectClass = onSelectClass,
                    onSelectMethod = onSelectMethod,
                    depth = if (node.name.isEmpty() || node.name == "root") 0 else depth + 1
                )
            }
            node.classes.forEach { cls ->
                DexClassCard(
                    klass = cls,
                    isSelected = cls.fullName == selectedDexClass?.fullName,
                    onSelect = { onSelectClass(cls) },
                    onSelectMethod = onSelectMethod
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
