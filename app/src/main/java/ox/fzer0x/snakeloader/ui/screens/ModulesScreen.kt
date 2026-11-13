package ox.fzer0x.snakeloader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.DownloadedScript
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ModuleMetadata
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.BadgePill
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.components.findActivity
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen
import ox.fzer0x.snakeloader.ui.theme.WarningOrange
import java.io.File
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    scriptManager: ScriptManager,
    fridaManager: FridaManager,
    onNavigateToAssetBrowser: () -> Unit,
    onNavigateToCodeShare: () -> Unit,
    onNavigateToToolbox: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEditor: (String) -> Unit,
    onNavigateToDetails: (String) -> Unit,
) {
    var allScripts by remember { mutableStateOf(scriptManager.getScripts()) }
    var showFabMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun refresh() {
        allScripts = scriptManager.getScripts()
    }

    LaunchedEffect(Unit) {
        scriptManager.initialize()
        refresh()
    }

    val sortedScripts = remember(allScripts) {
        allScripts.sortedByDescending { it.downloadedAt }
    }

    val activeCount = remember(allScripts) { allScripts.count { it.isEnabled } }
    val targetedAppsCount = remember(allScripts) { scriptManager.getAppsWithActiveScripts().size }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            var fileName = "imported_script.js"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            scriptManager.importScriptFromUri(it, fileName)
            refresh()
            scope.launch {
                snackbarHostState.showSnackbar("Module '$fileName' imported successfully.")
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReShiftTopAppBar(
                title = "Modules",
                subtitle = "${allScripts.size} modules installed",
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Close") },
                            onClick = {
                                showMenu = false
                                context.findActivity()?.finishAndRemoveTask()
                            },
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = "Close ReShift", tint = MaterialTheme.colorScheme.error) }
                        )
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // STATS BAR
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // STATS OVERVIEW CARDS ROW
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniStatBadge(
                        label = "Total Modules",
                        value = "${allScripts.size}",
                        icon = Icons.Default.Extension,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MiniStatBadge(
                        label = "Active Now",
                        value = "$activeCount",
                        icon = Icons.Default.PowerSettingsNew,
                        color = if (activeCount > 0) SuccessGreen else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    MiniStatBadge(
                        label = "Target Apps",
                        value = "$targetedAppsCount",
                        icon = Icons.AutoMirrored.Filled.Label,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (sortedScripts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(bottom = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.ExtensionOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "No modules found",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap + to discover or import modules.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    items(sortedScripts, key = { it.id }) { script ->
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
                            },
                            onNavigateToDetails = { onNavigateToDetails(script.id) },
                            onDuplicate = {
                                val newScript = scriptManager.duplicateScript(script.id)
                                refresh()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Duplicated to '${newScript?.name}'")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniStatBadge(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = ReShiftChipShape,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
        shape = ReShiftButtonShape,
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

private fun shareModule(context: Context, script: DownloadedScript) {
    if (script.content.isBlank()) {
        Toast.makeText(context, "Module content is empty", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val sanitizedName = script.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = if (sanitizedName.endsWith(".js")) sanitizedName else "$sanitizedName.js"
        val cacheFile = File(context.cacheDir, fileName)
        cacheFile.writeText(script.content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            cacheFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, script.name)
            putExtra(Intent.EXTRA_TEXT, script.content)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Module"))
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share module: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun installApkFromAssets(context: Context, apkPath: String) {
    try {
        val cleanPath = apkPath.trim().removePrefix("assets/").removePrefix("/")
        val fileName = if (cleanPath.contains("/")) cleanPath.substringAfterLast("/") else cleanPath

        val candidatePaths = listOf(
            cleanPath,
            "assets/$cleanPath",
            "ReShiftModules/$cleanPath",
            "ReShiftModules/GAMES/$cleanPath",
            fileName
        )

        var inputStream: java.io.InputStream? = null
        for (candidate in candidatePaths) {
            try {
                inputStream = context.assets.open(candidate)
                break
            } catch (_: Exception) {}
        }

        if (inputStream == null) {
            Toast.makeText(context, "APK file not found in assets: $cleanPath", Toast.LENGTH_LONG).show()
            return
        }

        val installerDir = File(java.io.File(context.cacheDir, "installer").apply { mkdirs() }, "")
        val apkFile = java.io.File(context.cacheDir, "installer/$fileName").apply { parentFile?.mkdirs() }
        apkFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }

        Toast.makeText(context, "Preparing installation of $fileName...", Toast.LENGTH_SHORT).show()

        var rootInstalled = false
        try {
            val rootCmd = "pm install -r \"${apkFile.absolutePath}\""
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", rootCmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                rootInstalled = true
                Toast.makeText(context, "Successfully installed $fileName (Root)", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}

        if (!rootInstalled) {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error installing APK: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleListItem(
    script: DownloadedScript,
    scriptManager: ScriptManager,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onNavigateToDetails: () -> Unit,
    onDuplicate: () -> Unit
) {
    val context = LocalContext.current
    val appCount = remember(script.id) { scriptManager.getAppCountForScript(script.id) }
    var isEnabled by remember(script.id) { mutableStateOf(script.isEnabled) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // DYNAMIC CATEGORY & ICON DETERMINATION
    val folderName = remember(script.path) {
        if (script.path.contains("/")) script.path.substringBefore("/") else null
    }

    val categoryName = remember(script) {
        script.metadata.category ?: folderName ?: when {
            script.repository == "ReShift Modules" -> "ReShift Core"
            script.repository.contains("frida-codeshare", ignoreCase = true) -> "CodeShare"
            script.repository == "Hardware Spoofer" -> "Spoofer"
            else -> "Custom Module"
        }
    }

    val (moduleIcon, moduleColor) = remember(script, categoryName) {
        when {
            script.metadata.noEdit -> Pair(Icons.Default.Lock, Color(0xFFE53935))
            script.name.contains("MOD", ignoreCase = true) || categoryName.contains("MOD", ignoreCase = true) -> Pair(Icons.Default.SportsEsports, Color(0xFF7C4DFF))
            script.name.contains("Dump", ignoreCase = true) || categoryName.contains("DEVELOPMENT", ignoreCase = true) -> Pair(Icons.Default.Terminal, Color(0xFF0288D1))
            script.name.contains("Bypass", ignoreCase = true) || script.name.contains("SSL", ignoreCase = true) -> Pair(Icons.Default.Shield, Color(0xFF2E7D32))
            script.repository == "ReShift Modules" -> Pair(Icons.Default.AutoAwesome, Color(0xFF00B0FF))
            script.repository.contains("frida-codeshare", ignoreCase = true) -> Pair(Icons.Default.Public, Color(0xFFFF6D00))
            script.repository == "Hardware Spoofer" -> Pair(Icons.Default.Fingerprint, Color(0xFFD81B60))
            else -> Pair(Icons.Default.Extension, Color(0xFF5C6BC0))
        }
    }

    // CHECK FOR WARNING CONTENT
    val warningText = remember(script) {
        val desc = script.description ?: ""
        val content = script.content.take(1500)
        when {
            desc.contains("Do not use on your real Account", ignoreCase = true) || content.contains("real account", ignoreCase = true) ->
                "Do not use on your real account! High ban risk."
            desc.contains("Zygisk Mode", ignoreCase = true) || content.contains("Zygisk", ignoreCase = true) ->
                "Requires Zygisk injection mode for full functionality."
            desc.contains("Root", ignoreCase = true) ->
                "Requires root privileges on host device."
            else -> null
        }
    }

    val borderStroke = if (isEnabled) {
        BorderStroke(1.5.dp, SuccessGreen.copy(alpha = 0.8f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }

    ReShiftCard(
        border = borderStroke,
        onClick = onNavigateToDetails
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // TOP HEADER ROW
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ICON AVATAR BOX
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(moduleColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = moduleIcon,
                        contentDescription = null,
                        tint = moduleColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // TITLE & BADGES
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = script.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (script.metadata.version != null) {
                            BadgePill(
                                text = "v${script.metadata.version}",
                                bgColor = MaterialTheme.colorScheme.primaryContainer,
                                textColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        if (script.metadata.author != null) {
                            Text(
                                text = "by ${script.metadata.author}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }

                        val displayRepo = remember(script.repository) {
                            if (script.repository.startsWith("http")) {
                                try {
                                    val url = URL(script.repository)
                                    url.host.removePrefix("www.")
                                } catch (_: Exception) {
                                    script.repository
                                }
                            } else {
                                script.repository
                            }
                        }

                        Text(
                            text = displayRepo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // ENABLE SWITCH
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        scriptManager.setEnabled(script.id, checked)
                        onRefresh()
                    },
                    modifier = Modifier.scale(0.85f)
                )
            }

            // TAGS ROW
            val apkPath = remember(script) {
                script.metadata.apkPath ?: (scriptManager.extractMetadata(script.content)["metadata"] as? ModuleMetadata)?.apkPath
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = moduleColor.copy(alpha = 0.1f),
                    shape = ReShiftChipShape
                ) {
                    Text(
                        text = categoryName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = moduleColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (folderName != null && folderName != categoryName) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = ReShiftChipShape
                    ) {
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (apkPath != null) {
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = { installApkFromAssets(context, apkPath) },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF3DDC84).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFF3DDC84).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = "Install Mod Menu",
                                tint = Color(0xFF3DDC84),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Install Mod Menu",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = Color(0xFF3DDC84),
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // DESCRIPTION & DETAILS BOX
            val hasDescription = !script.description.isNullOrBlank()
            val hasTargetPkgs = script.metadata.targetPackages.isNotEmpty()
            val hasUrl = script.repository.startsWith("http")

            if (hasDescription || hasTargetPkgs || warningText != null || hasUrl) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = ReShiftChipShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        // WARNING BANNER IF PRESENT
                        if (warningText != null) {
                            Surface(
                                color = WarningOrange.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = WarningOrange,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = warningText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarningOrange,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // DESCRIPTION TEXT
                        if (hasDescription) {
                            val descText = script.description
                            val isLong = descText.length > 120

                            Text(
                                text = descText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (isExpanded || !isLong) 10 else 3,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (isLong) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isExpanded = !isExpanded }
                                        .padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isExpanded) "Show Less" else "Read More",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // TARGET PACKAGES
                        if (hasTargetPkgs) {
                            if (hasDescription) Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.TrackChanges,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Target: ",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = script.metadata.targetPackages.joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // REPOSITORY SOURCE URL LINK
                        if (hasUrl) {
                            if (hasDescription || hasTargetPkgs) Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, script.repository.toUri())
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            ) {
                                Icon(Icons.Default.Link, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Source: ${script.repository}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            // BOTTOM ACTION BAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // APP SCOPE INDICATOR & CONFIG BUTTON
                Surface(
                    onClick = onNavigateToDetails,
                    shape = ReShiftChipShape,
                    color = if (appCount > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Label,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (appCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (appCount == 1) "Active in 1 app" else if (appCount > 1) "Active in $appCount apps" else "No apps assigned",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (appCount > 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (appCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // ACTION BUTTONS
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // EDIT CODE BUTTON
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (script.metadata.noEdit) Icons.Default.Lock else Icons.Default.Code,
                            contentDescription = "Edit Code",
                            modifier = Modifier.size(18.dp),
                            tint = if (script.metadata.noEdit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // DUPLICATE BUTTON
                    IconButton(
                        onClick = onDuplicate,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Script",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // MORE OPTIONS DROPDOWN MENU
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Actions",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Configure Scope Apps") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToDetails()
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Edit Code") },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                leadingIcon = { Icon(if (script.metadata.noEdit) Icons.Default.Lock else Icons.Default.Code, null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Duplicate Module") },
                                onClick = {
                                    showMenu = false
                                    onDuplicate()
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Share Module") },
                                onClick = {
                                    showMenu = false
                                    shareModule(context, script)
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Delete Module") },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            }
        }
    }

    // DELETE CONFIRMATION DIALOG
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Delete Module?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Are you sure you want to delete '${script.name}'?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (appCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Warning: This module is currently assigned to $appCount app(s). Deleting it will remove all app assignments.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scriptManager.deleteScript(script.id)
                        onRefresh()
                        Toast.makeText(context, "Deleted '${script.name}'", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ReShiftButtonShape
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    shape = ReShiftButtonShape
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
