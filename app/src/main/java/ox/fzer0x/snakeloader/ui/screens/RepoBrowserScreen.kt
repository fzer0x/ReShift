package ox.fzer0x.snakeloader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoBrowserScreen(
    githubApiService: GitHubApiService,
    scriptManager: ScriptManager,
    onBack: () -> Unit
) {
    var selectedRepository by remember { mutableStateOf<ScriptRepository?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var scripts by remember { mutableStateOf<List<ScriptFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scriptToPreview by remember { mutableStateOf<ScriptFile?>(null) }
    var previewDescription by remember { mutableStateOf<String?>(null) }
    var showAddRepoDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var refreshTrigger by remember { mutableStateOf(0) }
    val repositories = remember(refreshTrigger) { githubApiService.repositories }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    LaunchedEffect(scriptToPreview) {
        if (scriptToPreview != null && scriptToPreview!!.description == null) {
            previewDescription = "Loading metadata..."
            val content = githubApiService.downloadScriptContent(scriptToPreview!!.downloadUrl)
            if (content != null) {
                val metadata = scriptManager.extractMetadata(content)
                val extractedDesc = metadata["description"] as? String
                
                previewDescription = extractedDesc ?: "No description available in file."
                
                if (extractedDesc != null) {
                    scripts = scripts.map { 
                        if (it.path == scriptToPreview?.path) it.copy(description = extractedDesc) 
                        else it 
                    }
                }
            } else {
                previewDescription = "Failed to load script content."
            }
        } else {
            previewDescription = scriptToPreview?.description
        }
    }

    fun loadPath(repo: ScriptRepository, path: String) {
        scope.launch {
            isLoading = true
            errorMessage = null
            githubApiService.fetchRepositoryContents(repo.owner, repo.name, path)
                .onSuccess {
                    scripts = it
                    selectedRepository = repo
                    currentPath = path
                }
                .onFailure { error ->
                    if (path.isNotEmpty()) {
                        githubApiService.fetchRepositoryContents(repo.owner, repo.name, "")
                            .onSuccess {
                                scripts = it
                                selectedRepository = repo
                                currentPath = ""
                            }
                            .onFailure {
                                errorMessage = it.message ?: "Network error"
                            }
                    } else {
                        errorMessage = error.message ?: "Network error"
                    }
                }
            isLoading = false
        }
    }

    fun downloadScript(scriptFile: ScriptFile) {
        scope.launch {
            isLoading = true
            val content = githubApiService.downloadScriptContent(scriptFile.downloadUrl)
            if (content != null) {
                val downloadedScript = DownloadedScript(
                    id = "${scriptFile.repository.owner}/${scriptFile.repository.name}/${scriptFile.path}",
                    name = scriptFile.name,
                    content = content,
                    repository = scriptFile.repository.url,
                    path = scriptFile.path,
                    description = previewDescription ?: scriptFile.description
                )
                scriptManager.addScript(downloadedScript)
                scriptToPreview = null
                onBack()
            }
            isLoading = false
        }
    }

    BackHandler(enabled = selectedRepository != null) {
        if (currentPath.isEmpty()) {
            selectedRepository = null
            scripts = emptyList()
        } else {
            val parent = if (currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
            loadPath(selectedRepository!!, parent)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(selectedRepository?.name ?: "Repositories", fontWeight = FontWeight.SemiBold)
                        if (selectedRepository != null) {
                            Text(currentPath.ifEmpty { "root" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedRepository == null) onBack()
                        else if (currentPath.isEmpty()) {
                            selectedRepository = null
                            scripts = emptyList()
                        } else {
                            val parent = if (currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
                            loadPath(selectedRepository!!, parent)
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (selectedRepository == null) {
                        IconButton(onClick = { showAddRepoDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Repo")
                        }
                    } else {
                        IconButton(onClick = { loadPath(selectedRepository!!, currentPath) }) {
                            Icon(Icons.Default.Refresh, null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
        } else if (selectedRepository == null) {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(repositories) { repo ->
                    OutlinedCard(
                        onClick = { loadPath(repo, repo.defaultPath) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        ListItem(
                            headlineContent = { Text(repo.name, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(repo.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Box(modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            },
                            trailingContent = {
                                if (repo.url.contains(repo.owner) && repo.description == "User added repository") {
                                    IconButton(onClick = {
                                        githubApiService.removeCustomRepository(repo.url)
                                        refreshTrigger++
                                    }) {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                } else {
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        } else {
            if (scripts.isEmpty()) {
                Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No scripts found.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scripts) { file ->
                        OutlinedCard(
                            onClick = {
                                if (file.isDirectory) loadPath(selectedRepository!!, file.path)
                                else scriptToPreview = file
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            ListItem(
                                headlineContent = { Text(file.name, fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Medium) },
                                supportingContent = { file.description?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                                leadingContent = { 
                                    val icon = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description
                                    val color = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    Box(modifier = Modifier.size(32.dp).background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                                    }
                                },
                                trailingContent = {
                                    if (!file.isDirectory) {
                                        IconButton(onClick = { scriptToPreview = file }) {
                                            Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
                                        }
                                    } else {
                                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }

    if (scriptToPreview != null) {
        AlertDialog(
            onDismissRequest = { scriptToPreview = null },
            title = { Text(scriptToPreview!!.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(previewDescription ?: "Reading metadata...", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Size: ${scriptToPreview!!.size / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(scriptToPreview!!.repository.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadScript(scriptToPreview!!) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { scriptToPreview = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddRepoDialog) {
        var owner by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var path by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddRepoDialog = false },
            title = { Text("Add Custom Repository", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Owner (e.g. frida)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Repo Name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Root Path (optional)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (owner.isNotEmpty() && name.isNotEmpty()) {
                            githubApiService.addCustomRepository(owner, name, path)
                            refreshTrigger++
                            showAddRepoDialog = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Add Repo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRepoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
