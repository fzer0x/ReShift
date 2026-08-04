package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.*
import ox.fzer0x.snakeloader.ui.viewmodels.AppDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailsScreen(
    packageName: String,
    scriptManager: ScriptManager,
    fridaManager: FridaManager,
    viewModel: AppDetailsViewModel,
    onBack: () -> Unit
) {
    val assignedScripts by viewModel.assignedScripts
    val allScripts by viewModel.allScripts
    val scriptRunning by fridaManager.isScriptRunning.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val appLabel = remember {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    fun launchApp() {
        scope.launch {
            val combinedScript = assignedScripts.joinToString("\n\n") { script ->
                "${script.content}"
            }
            val success = fridaManager.executeScriptContent(packageName, combinedScript)
            if (success) {
                scriptManager.addToRecentApps(packageName)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(appLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    if (scriptRunning) {
                        scope.launch { fridaManager.stopScript() }
                    } else launchApp() 
                },
                containerColor = if (scriptRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (scriptRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(if (scriptRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionHeader("Active Modules") }
            
            if (assignedScripts.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No modules assigned to this app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                items(assignedScripts) { script ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        ListItem(
                            headlineContent = { Text(script.name, fontWeight = FontWeight.Bold) },
                            supportingContent = { 
                                Column {
                                    Text(script.repository, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    if (!script.description.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            script.description, 
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    viewModel.unassignScript(script.id)
                                }) {
                                    Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
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

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Module")
                }
            }
            
            item { Spacer(Modifier.height(80.dp)) }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Assign Module") },
                text = {
                    val available = allScripts.filter { it.id !in assignedScripts.map { s -> s.id } }
                    if (available.isEmpty()) {
                        Text("No more modules available to add.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(available) { script ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            viewModel.assignScript(script.id)
                                            showAddDialog = false
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Extension, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Text(script.name, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}
