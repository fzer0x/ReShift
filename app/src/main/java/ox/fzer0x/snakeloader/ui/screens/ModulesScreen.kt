package ox.fzer0x.snakeloader.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import ox.fzer0x.snakeloader.DownloadedScript
import ox.fzer0x.snakeloader.ModuleMetadata
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    scriptManager: ScriptManager,
    onNavigateToAssetBrowser: () -> Unit,
    onNavigateToCodeShare: () -> Unit,
    onNavigateToToolbox: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEditor: (String) -> Unit,
) {
    var allScripts by remember { mutableStateOf(scriptManager.getScripts()) }
    var searchQuery by remember { mutableStateOf("") }
    
    var showFabMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    fun refresh() {
        allScripts = scriptManager.getScripts()
    }

    val filteredScripts = remember(allScripts, searchQuery) {
        allScripts.filter { script ->
            script.name.contains(searchQuery, ignoreCase = true) ||
                    (script.description?.contains(searchQuery, ignoreCase = true) ?: false)
        }.sortedByDescending { it.downloadedAt }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            var fileName = "imported_script.js"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            scriptManager.importScriptFromUri(it, fileName)
            refresh()
        }
    }


    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Modules", fontWeight = FontWeight.SemiBold) },
                actions = {
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
        },
        floatingActionButton = {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (showFabMenu) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 80.dp)
                    ) {
                        FabMenuItem(
                            label = "Browse CodeShare",
                            icon = Icons.Default.Public,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            onClick = { 
                                showFabMenu = false
                                onNavigateToCodeShare() 
                            }
                        )
                        FabMenuItem(
                            label = "Import from ReShift",
                            icon = Icons.Default.Extension,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            onClick = { 
                                showFabMenu = false
                                onNavigateToAssetBrowser()
                            }
                        )
                        FabMenuItem(
                            label = "Import from Storage",
                            icon = Icons.Default.FileOpen,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            onClick = { 
                                showFabMenu = false
                                filePickerLauncher.launch("application/javascript")
                            }
                        )
                    }
                }
                
                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = if (showFabMenu) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Add module"
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search scripts...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (filteredScripts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(bottom = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    if (searchQuery.isEmpty()) Icons.Outlined.ExtensionOff else Icons.Outlined.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    if (searchQuery.isEmpty()) "No modules yet" else "No matches found",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    if (searchQuery.isEmpty()) "Tap + to discover or import modules." else "Try a different search term.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    items(filteredScripts, key = { it.id }) { script ->
                        ModuleListItem(
                            script = script, 
                            scriptManager = scriptManager, 
                            onRefresh = { refresh() },
                            onEdit = { 
                                if (script.metadata.noEdit) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("This script is protected and cannot be edited.")
                                    }
                                } else {
                                    onNavigateToEditor(script.id)
                                }
                            }
                        )
                    }
                }
                
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
fun FabMenuItem(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color,
        modifier = Modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ModuleListItem(
    script: DownloadedScript,
    scriptManager: ScriptManager,
    onRefresh: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val appCount = remember(script.id) { scriptManager.getAppCountForScript(script.id) }
    var isEnabled by remember(script.id) { mutableStateOf(script.isEnabled) }
    var showMenu by remember { mutableStateOf(false) }

    val sourceIcon = when {
        script.repository == "ReShift Modules" -> Icons.Default.Extension
        script.repository.contains("frida-codeshare", ignoreCase = true) -> Icons.Default.Public
        else -> Icons.Default.Description
    }

    val sourceColor = when {
        script.repository == "ReShift Modules" -> MaterialTheme.colorScheme.primary
        script.repository.contains("frida-codeshare", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = if (script.metadata.noEdit) Icons.Default.Lock else sourceIcon
                val tint = if (script.metadata.noEdit) MaterialTheme.colorScheme.error else sourceColor
                
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(tint.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
                }
                
                Spacer(Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = script.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (script.metadata.version != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "v${script.metadata.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    val displayRepo = remember(script.repository) {
                        if (script.repository.startsWith("http")) {
                            try {
                                val url = java.net.URL(script.repository)
                                url.host.removePrefix("www.")
                            } catch (_: Exception) {
                                script.repository.take(30) + "..."
                            }
                        } else {
                            script.repository
                        }
                    }

                    Text(
                        text = displayRepo,
                        style = MaterialTheme.typography.labelSmall,
                        color = sourceColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { 
                        isEnabled = it
                        scriptManager.setEnabled(script.id, it)
                        onRefresh()
                    },
                    modifier = Modifier.scale(0.8f)
                )
            }
            
            val hasDescription = !script.description.isNullOrBlank()
            val hasUrl = script.repository.startsWith("http")

            if (hasDescription || hasUrl) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (hasDescription) {
                            Text(
                                text = script.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        if (hasUrl) {
                            if (hasDescription) Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { 
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(script.repository))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            ) {
                                Icon(Icons.Default.Link, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Source: ${script.repository}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                )
                            }
                        }
                    }
                }
            }
            
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.Label, 
                        null, 
                        modifier = Modifier.size(14.dp), 
                        tint = if (appCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (appCount == 1) "Active in 1 app" else "Active in $appCount apps",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (appCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (script.metadata.noEdit) Icons.Default.Lock else Icons.Default.Edit, 
                            null, 
                            modifier = Modifier.size(18.dp),
                            tint = if (script.metadata.noEdit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    scriptManager.deleteScript(script.id)
                                    onRefresh()
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            }
        }
    }
}
