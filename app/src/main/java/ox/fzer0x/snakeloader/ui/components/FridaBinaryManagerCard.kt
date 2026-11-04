package ox.fzer0x.snakeloader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.FridaBinaryDownloader
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen
import ox.fzer0x.snakeloader.ui.theme.WarningOrange

data class FridaBinaryItem(
    val key: String,
    val name: String,
    val strategyLabel: String,
    val path: String,
    val isInstalled: Boolean,
    val version: String
)

@Composable
fun FridaBinaryManagerCard(
    fridaManager: FridaManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf("") }
    var latestGitHubRelease by remember { mutableStateOf<String?>(null) }
    var isFetchingVersion by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val binaryManager = fridaManager.binaryManager
    val stealthConfig = fridaManager.stealthConfig

    val binaries = remember(refreshTrigger) {
        listOf(
            FridaBinaryItem(
                key = "server",
                name = "frida-server",
                strategyLabel = "Server Strategy",
                path = stealthConfig.getActiveServerPath(),
                isInstalled = binaryManager.isServerAvailable(),
                version = binaryManager.getBinaryVersion("server")
            ),
            FridaBinaryItem(
                key = "inject",
                name = "frida-inject",
                strategyLabel = "Inject Strategy",
                path = stealthConfig.getActiveInjectPath(),
                isInstalled = binaryManager.isInjectAvailable(),
                version = binaryManager.getBinaryVersion("inject")
            ),
            FridaBinaryItem(
                key = "cli",
                name = "frida (C-CLI)",
                strategyLabel = "RPC (CLI) Strategy",
                path = stealthConfig.getActiveCliPath(),
                isInstalled = binaryManager.isCliAvailable(),
                version = binaryManager.getBinaryVersion("cli")
            ),
            FridaBinaryItem(
                key = "gadget",
                name = "frida-gadget.so",
                strategyLabel = "Zygisk / Gadget Strategy",
                path = "/data/adb/reshift/frida-gadget.so",
                isInstalled = binaryManager.isGadgetAvailable(),
                version = binaryManager.getBinaryVersion("gadget")
            )
        )
    }

    fun fetchLatestRelease() {
        scope.launch {
            isFetchingVersion = true
            val downloader = FridaBinaryDownloader(fridaManager.context, stealthConfig)
            val version = downloader.getLatestVersion()
            latestGitHubRelease = version ?: "v17.18.0"
            isFetchingVersion = false
        }
    }

    LaunchedEffect(Unit) {
        fetchLatestRelease()
    }

    fun downloadSingleBinary(type: String) {
        scope.launch {
            isDownloading = true
            downloadProgress = "Fetching release metadata..."
            val downloader = FridaBinaryDownloader(fridaManager.context, stealthConfig)
            val targetVersion = latestGitHubRelease ?: downloader.getLatestVersion() ?: "v17.18.0"

            downloadProgress = "Downloading $type ($targetVersion)..."
            val success = downloader.downloadAndInstall(targetVersion, type) { progress ->
                downloadProgress = progress
            }

            if (success) {
                downloadProgress = "Installed $type successfully!"
                refreshTrigger++
            } else {
                downloadProgress = "Failed to install $type"
            }
            isDownloading = false
        }
    }

    fun downloadAllBinaries() {
        scope.launch {
            isDownloading = true
            downloadProgress = "Fetching latest GitHub release..."
            val downloader = FridaBinaryDownloader(fridaManager.context, stealthConfig)
            val targetVersion = latestGitHubRelease ?: downloader.getLatestVersion() ?: "v17.18.0"

            val types = listOf("server", "inject", "cli", "gadget")
            var allSuccess = true

            for (type in types) {
                downloadProgress = "Downloading $type ($targetVersion)..."
                val success = downloader.downloadAndInstall(targetVersion, type) { progress ->
                    downloadProgress = progress
                }
                if (!success) allSuccess = false
            }

            if (allSuccess) {
                downloadProgress = "All Frida binaries updated to $targetVersion!"
            } else {
                downloadProgress = "Some binaries failed to install"
            }
            refreshTrigger++
            isDownloading = false
        }
    }

    ReShiftCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Frida Binary Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "On-Demand Releases & Engine Control",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                if (isFetchingVersion) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Surface(
                        onClick = { fetchLatestRelease() },
                        shape = ReShiftChipShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = latestGitHubRelease ?: "v17.18.0",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            if (isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    Text(downloadProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }

            // List of 4 binaries
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                binaries.forEach { bin ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ReShiftChipShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(bin.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (bin.isInstalled) SuccessGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (bin.isInstalled) bin.version else "Missing",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (bin.isInstalled) SuccessGreen else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(bin.path, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                            }

                            Spacer(Modifier.width(8.dp))

                            OutlinedButton(
                                onClick = { downloadSingleBinary(bin.key) },
                                enabled = !isDownloading,
                                shape = ReShiftChipShape,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    if (bin.isInstalled) Icons.Default.Update else Icons.Default.Download,
                                    null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (bin.isInstalled) "Update" else "Get", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { downloadAllBinaries() },
                enabled = !isDownloading,
                modifier = Modifier.fillMaxWidth(),
                shape = ReShiftButtonShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.DownloadForOffline, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Download / Update All Binaries (${latestGitHubRelease ?: "Latest"})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
