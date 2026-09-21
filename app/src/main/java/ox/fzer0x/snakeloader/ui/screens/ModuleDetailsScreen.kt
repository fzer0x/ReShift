package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.DownloadedScript
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.components.StatusBadge
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleDetailsScreen(
    scriptId: String,
    scriptManager: ScriptManager,
    fridaManager: FridaManager,
    onBack: () -> Unit,
    onNavigateToEditor: () -> Unit
) {
    val scripts = scriptManager.getScripts()
    val script = remember(scriptId, scripts) { scripts.find { s: DownloadedScript -> s.id == scriptId } } ?: return
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var assignedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        installedApps = loadInstalledApps(context, includeSystemApps = true).sortedBy { it.label }
        assignedApps = scriptManager.getAppsForScript(scriptId)
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = script.name,
                subtitle = script.repository,
                onBack = onBack,
                actions = {
                    if (!script.metadata.noEdit) {
                        IconButton(onClick = onNavigateToEditor) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Script")
                        }
                    } else {
                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp).size(20.dp))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ReShiftCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("MODULE INFO", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                        }
                        
                        if (script.description != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(script.description, style = MaterialTheme.typography.bodyMedium)
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(script.metadata.version ?: "1.0", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Author", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(script.metadata.author ?: "Unknown", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            
            if (script.metadata.targetPackages.isNotEmpty()) {
                item {
                    SectionHeader("RECOMMENDED FOR")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        script.metadata.targetPackages.forEach { pkg ->
                            SuggestionChip(
                                onClick = { searchQuery = pkg },
                                label = { Text(pkg, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                shape = ReShiftChipShape
                            )
                        }
                    }
                }
            }
            
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("SCOPE APPLICATIONS")
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = ReShiftButtonShape
                )
            }
            
            items(filteredApps, key = { it.packageName }) { app ->
                val isAssigned = app.packageName in assignedApps
                val isTargeted = app.packageName in script.metadata.targetPackages
                
                ReShiftCard(
                    border = if (isAssigned) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconBitmap = remember(app.packageName) { 
                            try { app.icon?.toBitmap()?.asImageBitmap() } catch (_: Exception) { null }
                        }
                        
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Android, null, modifier = Modifier.size(20.dp), tint = SuccessGreen)
                            }
                        }

                        Spacer(Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(app.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isTargeted) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                                        Text("SUGGESTED", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 7.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAssigned) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val scripts = scriptManager.getAssignmentsForApp(app.packageName)
                                            if (scripts.isNotEmpty()) {
                                                val combinedScript = scripts.joinToString("\n\n") { s ->
                                                    "// --- ${s.name} ---\n${s.content}"
                                                }
                                                fridaManager.executeScriptContent(app.packageName, combinedScript)
                                                scriptManager.addToRecentApps(app.packageName)
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Launch", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            
                            Switch(
                                checked = isAssigned,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        scriptManager.assignScriptToApp(app.packageName, scriptId)
                                    } else {
                                        scriptManager.unassignScriptFromApp(app.packageName, scriptId)
                                    }
                                    assignedApps = if (checked) assignedApps + app.packageName else assignedApps - app.packageName
                                },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    }
                }
            }
            
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
