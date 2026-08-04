package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeShareBrowserScreen(
    codeShareApiService: CodeShareApiService,
    scriptManager: ScriptManager,
    onBack: () -> Unit
) {
    var projects by remember { mutableStateOf<List<CodeShareProject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var projectToPreview by remember { mutableStateOf<CodeShareProject?>(null) }
    var previewSource by remember { mutableStateOf<String?>(null) }
    var previewDetails by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableStateOf(1) }
    var maxPage by remember { mutableStateOf(1) }
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    val filteredProjects = remember(projects, searchQuery) {
        if (searchQuery.isEmpty()) projects
        else projects.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.author.contains(searchQuery, ignoreCase = true)
        }
    }

    fun loadProjects(query: String = "", page: Int = 1) {
        focusManager.clearFocus()
        scope.launch {
            isLoading = true
            errorMessage = null
            codeShareApiService.fetchProjects(query, page)
                .onSuccess { (newProjects, pages) ->
                    projects = newProjects
                    maxPage = pages
                    currentPage = page
                }
                .onFailure {
                    errorMessage = it.message ?: "CodeShare error"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadProjects()
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    LaunchedEffect(projectToPreview) {
        if (projectToPreview != null) {
            previewDetails = "Loading..."
            codeShareApiService.fetchProjectDetails(projectToPreview!!.author, projectToPreview!!.slug)
                .onSuccess { json ->
                    previewSource = json.optString("source")
                    previewDetails = json.optString("project_name", projectToPreview!!.name)
                }
                .onFailure {
                    previewDetails = "Error: ${it.message}"
                }
        }
    }

    fun downloadProject(project: CodeShareProject) {
        scope.launch {
            isLoading = true
            codeShareApiService.fetchProjectDetails(project.author, project.slug)
                .onSuccess { json ->
                    val source = json.optString("source")
                    if (source.isNotEmpty()) {
                        val downloadedScript = DownloadedScript(
                            id = "codeshare/${project.author}/${project.slug}",
                            name = project.name,
                            content = source,
                            repository = "https://codeshare.frida.re/@${project.author}/${project.slug}",
                            path = project.slug,
                            description = project.description.ifBlank { "Imported from Frida CodeShare" }
                        )
                        scriptManager.addScript(downloadedScript)
                        projectToPreview = null
                        onBack()
                    } else {
                        errorMessage = "Script empty"
                    }
                }
                .onFailure {
                    errorMessage = it.message ?: "Download failed"
                }
            isLoading = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Frida CodeShare", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
                placeholder = { Text("Search CodeShare...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { 
                                searchQuery = ""
                                loadProjects("", 1)
                            }) {
                                Icon(Icons.Default.Close, null)
                            }
                            TextButton(onClick = { loadProjects(searchQuery, 1) }) {
                                Text("Search")
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { loadProjects(searchQuery, 1) }),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                }
            } else if (filteredProjects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No projects found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                        if (searchQuery.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { loadProjects(searchQuery, 1) }, shape = RoundedCornerShape(12.dp)) {
                                Text("Search Globally")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredProjects) { project ->
                        OutlinedCard(
                            onClick = { projectToPreview = project },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            ListItem(
                                headlineContent = { Text(project.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("@${project.author}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) },
                                leadingContent = {
                                    Box(modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                },
                                trailingContent = {
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }

                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { loadProjects(searchQuery, currentPage - 1) },
                                    enabled = currentPage > 1
                                ) {
                                    Icon(Icons.Default.NavigateBefore, null)
                                }

                                Text(
                                    "Page $currentPage / $maxPage",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                IconButton(
                                    onClick = { loadProjects(searchQuery, currentPage + 1) },
                                    enabled = currentPage < maxPage
                                ) {
                                    Icon(Icons.Default.NavigateNext, null)
                                }
                            }
                        }
                    }
                    
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }

    if (projectToPreview != null) {
        AlertDialog(
            onDismissRequest = { projectToPreview = null },
            title = { Text(projectToPreview!!.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(previewDetails ?: "Loading info...", style = MaterialTheme.typography.bodyMedium)
                    if (previewSource != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "Preview: ${previewSource!!.take(100)}...",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadProject(projectToPreview!!) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import Script")
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToPreview = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
