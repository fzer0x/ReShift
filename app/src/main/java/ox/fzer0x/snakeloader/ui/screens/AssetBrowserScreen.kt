package ox.fzer0x.snakeloader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.GitHubApiService
import ox.fzer0x.snakeloader.ModuleMetadata
import ox.fzer0x.snakeloader.ReShiftModuleFile
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.BadgePill
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.ReShiftDialogShape
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar

private data class ModuleMetadataDisplay(
    val file: ReShiftModuleFile,
    val name: String,
    val version: String?,
    val description: String?,
    val content: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetBrowserScreen(
    githubApiService: GitHubApiService,
    scriptManager: ScriptManager,
    onBack: () -> Unit
) {
    var rawModules by remember { mutableStateOf<List<ReShiftModuleFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var moduleToPreview by remember { mutableStateOf<ReShiftModuleFile?>(null) }
    var previewMetadata by remember { mutableStateOf<ModuleMetadataDisplay?>(null) }
    var isFetchingPreview by remember { mutableStateOf(false) }

    fun loadModules(forceRefresh: Boolean = false) {
        scope.launch {
            isLoading = true
            errorMessage = null
            githubApiService.fetchReShiftModules(forceRefresh)
                .onSuccess { files ->
                    rawModules = files
                    // Folders start collapsed by default
                    expandedFolders = emptySet()
                    isLoading = false
                }
                .onFailure { error ->
                    errorMessage = error.message ?: "Failed to load ReShift modules"
                    isLoading = false
                }
        }
    }

    LaunchedEffect(Unit) {
        loadModules()
    }

    LaunchedEffect(moduleToPreview) {
        val target = moduleToPreview
        if (target != null) {
            isFetchingPreview = true
            val content = githubApiService.downloadScriptContent(target.downloadUrl)
            if (content != null) {
                val metadataMap = scriptManager.extractMetadata(content)
                val extractedName = metadataMap["name"] as? String
                val version = (metadataMap["metadata"] as? ModuleMetadata)?.version
                val description = metadataMap["description"] as? String
                previewMetadata = ModuleMetadataDisplay(
                    file = target,
                    name = extractedName ?: target.fileName.removeSuffix(".js"),
                    version = version,
                    description = description,
                    content = content
                )
            } else {
                previewMetadata = ModuleMetadataDisplay(
                    file = target,
                    name = target.fileName.removeSuffix(".js"),
                    version = null,
                    description = "Failed to load script content.",
                    content = null
                )
            }
            isFetchingPreview = false
        } else {
            previewMetadata = null
        }
    }

    fun importModule(relativePath: String, content: String) {
        scriptManager.importScriptFromReShift(relativePath, content)
        Toast.makeText(context, "Module imported successfully!", Toast.LENGTH_SHORT).show()
        onBack()
    }

    fun importModuleDirect(file: ReShiftModuleFile) {
        scope.launch {
            val content = githubApiService.downloadScriptContent(file.downloadUrl)
            if (content != null) {
                importModule(file.path, content)
            } else {
                Toast.makeText(context, "Failed to download module content", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filteredModules = remember(searchQuery, rawModules) {
        if (searchQuery.isEmpty()) rawModules
        else rawModules.filter {
            it.fileName.contains(searchQuery, ignoreCase = true) ||
                    it.path.contains(searchQuery, ignoreCase = true) ||
                    it.folder.contains(searchQuery, ignoreCase = true)
        }
    }

    val groupedModules = remember(filteredModules) {
        filteredModules.groupBy { it.folder.ifEmpty { "Root" } }
    }

    fun toggleFolder(folder: String) {
        expandedFolders = if (folder in expandedFolders) {
            expandedFolders - folder
        } else {
            expandedFolders + folder
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReShiftTopAppBar(
                title = "ReShift Modules",
                subtitle = "fzer0x/ReShift GitHub Repository",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { loadModules(forceRefresh = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search modules...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = ReShiftButtonShape
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Fetching modules from GitHub...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Error Loading Modules",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { loadModules(forceRefresh = true) },
                            shape = ReShiftButtonShape
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
            } else if (filteredModules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No modules found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groupedModules.forEach { (folder, modulesInFolder) ->
                        val isExpanded = folder in expandedFolders || searchQuery.isNotEmpty()
                        item {
                            Surface(
                                onClick = { toggleFolder(folder) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isExpanded) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                border = if (isExpanded) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                } else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(
                                                if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.uppercase(),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            letterSpacing = 1.1.sp
                                        )
                                        Text(
                                            text = if (modulesInFolder.size == 1) "1 module" else "${modulesInFolder.size} modules",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    BadgePill(
                                        text = "${modulesInFolder.size}",
                                        bgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        textColor = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        if (isExpanded) {
                            items(modulesInFolder) { module ->
                                PaddingBox(modifier = Modifier.padding(start = 6.dp)) {
                                    ReShiftCard(
                                        onClick = { moduleToPreview = module }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                        RoundedCornerShape(8.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Extension,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    module.fileName.removeSuffix(".js"),
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    module.path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                            IconButton(
                                                onClick = { importModuleDirect(module) },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(Icons.Default.Download, "Import", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }

    if (moduleToPreview != null) {
        AlertDialog(
            onDismissRequest = { moduleToPreview = null },
            title = {
                Text(
                    previewMetadata?.name ?: moduleToPreview!!.fileName.removeSuffix(".js"),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    if (isFetchingPreview) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Loading metadata from GitHub...", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        val desc = previewMetadata?.description ?: "No description available."
                        Text(desc, style = MaterialTheme.typography.bodyMedium)
                        if (previewMetadata?.version != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Version: v${previewMetadata!!.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = ReShiftChipShape) {
                        Text(
                            "path: app/src/main/assets/ReShiftModules/${moduleToPreview!!.path}",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val content = previewMetadata?.content
                        if (content != null) {
                            importModule(moduleToPreview!!.path, content)
                            moduleToPreview = null
                        } else {
                            importModuleDirect(moduleToPreview!!)
                            moduleToPreview = null
                        }
                    },
                    enabled = !isFetchingPreview,
                    shape = ReShiftButtonShape
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import Module", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { moduleToPreview = null }, shape = ReShiftButtonShape) {
                    Text("Cancel")
                }
            },
            shape = ReShiftDialogShape
        )
    }
}

@Composable
private fun PaddingBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
    }
}
