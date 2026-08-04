package ox.fzer0x.snakeloader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ox.fzer0x.snakeloader.LogManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Scripts" to Icons.Default.Description,
        "Logcat" to Icons.AutoMirrored.Filled.List,
        "Frida CLI" to Icons.Default.Terminal
    )
    
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    var selectedLevel by remember { mutableStateOf("All") }
    var autoScrollEnabled by remember { mutableStateOf(true) }

    val scriptLogs = LogManager.logs
    val logcatLogs = LogManager.logcatEntries
    val fridaLogs = LogManager.fridaLogs
    
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ox.fzer0x.snakeloader.LogcatReader.start()
    }

    val currentLogs = remember(selectedTab, searchQuery, selectedLevel, scriptLogs, logcatLogs, fridaLogs) {
        val baseLogs = when (selectedTab) {
            0 -> scriptLogs
            1 -> logcatLogs
            2 -> fridaLogs
            else -> scriptLogs
        }
        
        baseLogs.filter { entry ->
            val matchesSearch = if (searchQuery.isEmpty()) true else {
                entry.message.contains(searchQuery, ignoreCase = true) || 
                (entry.tag?.contains(searchQuery, ignoreCase = true) == true) ||
                entry.packageName.contains(searchQuery, ignoreCase = true)
            }

            val matchesLevel = when (selectedLevel) {
                "Errors" -> entry.level == LogManager.LogLevel.ERROR
                "Warns" -> entry.level == LogManager.LogLevel.WARN
                "Success" -> entry.level == LogManager.LogLevel.SUCCESS
                "Debug" -> entry.level == LogManager.LogLevel.DEBUG
                else -> true
            }

            matchesSearch && matchesLevel
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(currentLogs.size) {
        if (autoScrollEnabled && currentLogs.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        if (isSearchExpanded) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                                placeholder = { Text("Filter logs...") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                ),
                                maxLines = 1,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        isSearchExpanded = false
                                        focusManager.clearFocus()
                                    }) {
                                        Icon(Icons.Default.Close, null)
                                    }
                                }
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text("System Logs", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    },
                    actions = {
                        if (!isSearchExpanded) {
                            IconButton(onClick = { isSearchExpanded = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                        IconButton(onClick = { autoScrollEnabled = !autoScrollEnabled }) {
                            Icon(
                                if (autoScrollEnabled) Icons.Default.VerticalAlignTop else Icons.Default.VerticalAlignBottom,
                                contentDescription = "Toggle auto-scroll",
                                tint = if (autoScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share current view") },
                                onClick = {
                                    showMenu = false
                                    shareLogs(context, tabs[selectedTab].first, currentLogs, timeFormat)
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All") },
                                onClick = { 
                                    showMenu = false
                                    when (selectedTab) {
                                        0 -> LogManager.clearLogs()
                                        1 -> LogManager.clearLogcat()
                                        2 -> LogManager.clearFridaLogs()
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSettings()
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                        }
                    }
                )
                
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, (title, icon) ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(title, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        )
                    }
                }
                
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val levels = listOf("All", "Errors", "Warns", "Success", "Debug")
                    items(levels) { level ->
                        FilterChip(
                            selected = selectedLevel == level,
                            onClick = { selectedLevel = level },
                            label = { Text(level) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (currentLogs.isEmpty()) {
            EmptyLogsView(padding, selectedTab, searchQuery.isNotEmpty() || selectedLevel != "All")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val displayLogs = if (selectedTab == 2) currentLogs.reversed() else currentLogs

                items(displayLogs, key = { it.id }) { entry ->
                    LogCard(entry, timeFormat, onShare = {
                        shareSingleLog(context, entry, timeFormat)
                    })
                }
            }
        }
    }
}

private fun shareSingleLog(context: Context, entry: LogManager.LogEntry, timeFormat: SimpleDateFormat) {
    val timestamp = timeFormat.format(Date(entry.timestamp))
    val text = "[$timestamp] [${entry.level}] ${entry.tag ?: entry.packageName}: ${entry.message}"

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Log from Snakeloader")
    }
    context.startActivity(Intent.createChooser(intent, "Share Log"))
}

private fun shareLogs(context: Context, title: String, logs: List<LogManager.LogEntry>, timeFormat: SimpleDateFormat) {
    if (logs.isEmpty()) return

    val sb = StringBuilder()
    sb.append("ReShift Snakeloader - $title Logs\n")
    sb.append("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
    sb.append("------------------------------------------\n\n")

    logs.forEach { entry ->
        val timestamp = timeFormat.format(Date(entry.timestamp))
        sb.append("[$timestamp] [${entry.level}] ${entry.tag ?: entry.packageName}: ${entry.message}\n")
    }

    val fileName = "snakeloader_${title.lowercase().replace(" ", "_")}_logs.txt"
    val file = File(context.cacheDir, fileName)
    file.writeText(sb.toString())

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "ReShift Snakeloader Logs - $title")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share All Logs"))
}

@Composable
fun EmptyLogsView(padding: PaddingValues, selectedTab: Int, isFiltering: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isFiltering) Icons.Outlined.SearchOff else Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isFiltering) "No logs match your filter." else when (selectedTab) {
                    0 -> "No script logs recorded."
                    1 -> "No Logcat entries captured."
                    2 -> "Frida CLI is idle. No output captured."
                    else -> ""
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = if (isFiltering) "Try adjusting your search or level filters." 
                       else "Logs will appear here once injection starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun LogCard(entry: LogManager.LogEntry, timeFormat: SimpleDateFormat, onShare: () -> Unit) {
    val levelColor = when (entry.level) {
        LogManager.LogLevel.ERROR -> Color(0xFFE57373)
        LogManager.LogLevel.WARN -> Color(0xFFFFB74D)
        LogManager.LogLevel.SUCCESS -> Color(0xFF81C784)
        LogManager.LogLevel.DEBUG -> Color(0xFF64B5F6)
        LogManager.LogLevel.VERBOSE -> Color(0xFFB0BEC5)
        LogManager.LogLevel.INFO -> MaterialTheme.colorScheme.primary
    }

    val levelIcon = when (entry.level) {
        LogManager.LogLevel.ERROR -> Icons.Default.Error
        LogManager.LogLevel.WARN -> Icons.Default.Warning
        LogManager.LogLevel.SUCCESS -> Icons.Default.CheckCircle
        LogManager.LogLevel.DEBUG -> Icons.Default.BugReport
        else -> Icons.Default.Info
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { onShare() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(levelColor)
                    .align(Alignment.CenterVertically)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            levelIcon,
                            contentDescription = null,
                            tint = levelColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = entry.tag ?: entry.packageName.split(".").lastOrNull() ?: "System",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = levelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = timeFormat.format(Date(entry.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
