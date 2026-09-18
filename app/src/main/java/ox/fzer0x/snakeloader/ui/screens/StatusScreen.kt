package ox.fzer0x.snakeloader.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.InfoCard
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.components.StatusRow
import ox.fzer0x.snakeloader.ui.components.StatusBadge
import ox.fzer0x.snakeloader.ui.viewmodels.StatusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    scriptManager: ScriptManager,
    fridaManager: FridaManager,
    onNavigateToToolbox: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStalker: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshStatus(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (viewModel.fridaRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.ExtraLight, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))) {
                                    append("RE")
                                }
                                withStyle(SpanStyle(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)) {
                                    append("SHIFT")
                                }
                            },
                            style = MaterialTheme.typography.titleLarge,
                            letterSpacing = 3.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Frida Toolbox") },
                            onClick = {
                                showMenu = false
                                onNavigateToToolbox()
                            },
                            leadingIcon = { Icon(Icons.Default.Terminal, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showMenu = false
                                onNavigateToSettings()
                            },
                            leadingIcon = { Icon(Icons.Default.Settings, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Join Community") },
                            onClick = {
                                showMenu = false
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, "https://t.me/+1FZrr4SqgMg1MDky".toUri())
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            leadingIcon = { Icon(Icons.Default.Group, null) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                QuickActionsRow(
                    isFridaRunning = viewModel.fridaRunning,
                    onStartFrida = { viewModel.startFridaServer(force = viewModel.fridaRunning) },
                    onOpenToolbox = onNavigateToToolbox,
                    onOpenSettings = onNavigateToSettings,
                    isLoading = viewModel.isLoading,
                    hasRoot = viewModel.hasRoot
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader("System Environment")
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            StatusRow(
                                label = "Frida Service",
                                value = if (viewModel.isLoading) "..." else if (viewModel.fridaRunning) "Active" else "Inactive",
                                color = if (viewModel.fridaRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                icon = Icons.Outlined.Dns
                            )
                            if (viewModel.fridaRunning) {
                                Text(
                                    "Version ${viewModel.fridaVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 24.dp)
                                )
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            
                            StatusRow(
                                label = "Injection Mode",
                                value = if (viewModel.runningFridaBinary != "none") {
                                    when (viewModel.runningFridaBinary) {
                                        "cli" -> "RPC"
                                        "inject" -> "Inject"
                                        "server" -> "Server"
                                        else -> "Active"
                                    }
                                } else {
                                    if (viewModel.fridaRunning) {
                                        "Server"
                                    } else {
                                        when (viewModel.selectedFridaBinary) {
                                            "cli" -> "RPC"
                                            "inject" -> "Inject"
                                            "auto" -> "Auto-Detect"
                                            else -> "None"
                                        }
                                    }
                                },
                                color = MaterialTheme.colorScheme.secondary,
                                icon = Icons.Outlined.Layers
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                            StatusRow(
                                label = "Detection Stealth",
                                value = if (viewModel.isStealthModeEnabled) "Active" else "Default",
                                color = if (viewModel.isStealthModeEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                icon = Icons.Outlined.AdminPanelSettings
                            )
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader("Security Configuration")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecurityInfoBox(
                            modifier = Modifier.weight(1f),
                            title = "Root Access",
                            value = if (viewModel.hasRoot) "Granted" else "Restricted",
                            color = if (viewModel.hasRoot) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                        SecurityInfoBox(
                            modifier = Modifier.weight(1f),
                            title = "SELinux",
                            value = if (viewModel.isSelinuxPermissive) "Permissive" else "Enforcing",
                            color = if (viewModel.isSelinuxPermissive) Color(0xFFFF9800) else Color(0xFF4CAF50)
                        )
                    }
                }
            }

            if (viewModel.recentApps.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SectionHeader("Recent Spawn Activity")
                    }
                }
                items(viewModel.recentApps) { app ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        RecentAppListItem(app, scriptManager, fridaManager, scope)
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, start = 16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        "ReShift 1.0.2",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "Magisk Module v${viewModel.moduleVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun QuickActionsRow(
    isFridaRunning: Boolean,
    onStartFrida: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenSettings: () -> Unit,
    isLoading: Boolean,
    hasRoot: Boolean
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AssistChip(
                onClick = onStartFrida,
                enabled = !isLoading && hasRoot,
                label = { Text(if (isFridaRunning) "Restart Frida" else "Start Frida") },
                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(16.dp)) }
            )
        }
        item {
            AssistChip(
                onClick = onOpenToolbox,
                label = { Text("Toolbox") },
                leadingIcon = { Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp)) }
            )
        }
        item {
            AssistChip(
                onClick = onOpenSettings,
                label = { Text("Settings") },
                leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

@Composable
fun SecurityInfoBox(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    color: Color
) {
    OutlinedCard(modifier = modifier, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun RecentAppListItem(app: AppInfo, scriptManager: ScriptManager, fridaManager: FridaManager, scope: kotlinx.coroutines.CoroutineScope) {
    val assignedCount = remember(app.packageName) { scriptManager.getAssignmentsForApp(app.packageName).size }
    val iconBitmap = remember(app.packageName) { 
        try {
            app.icon?.toBitmap()?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    OutlinedCard(
        onClick = {
            scope.launch {
                val scripts = scriptManager.getAssignmentsForApp(app.packageName)
                if (scripts.isNotEmpty()) {
                    val combinedScript = scripts.joinToString("\n\n") { s ->
                        "// --- ${s.name} ---\n${s.content}"
                    }
                    val success = fridaManager.executeScriptContent(app.packageName, combinedScript)
                    if (success) {
                        scriptManager.addToRecentApps(app.packageName)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Android, null, modifier = Modifier.size(20.dp), tint = Color(0xFF3DDC84))
                }
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            
            if (assignedCount > 0) {
                Text(
                    text = "$assignedCount scripts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Icon(
                Icons.Default.PlayArrow, 
                null, 
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
