package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.ModuleMetadata
import ox.fzer0x.snakeloader.ScriptManager

private data class AssetMetadata(
    val fileName: String,
    val name: String,
    val version: String?,
    val description: String?,
    val folder: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetBrowserScreen(
    scriptManager: ScriptManager,
    onBack: () -> Unit
) {
    val assets = remember { scriptManager.listAssetScripts() }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }
    var assetToPreview by remember { mutableStateOf<AssetMetadata?>(null) }

    val assetMetadataList = remember(assets, context) {
        assets.map { fileName ->
            try {
                val content = context.assets.open("ReShiftModules/$fileName").bufferedReader().use { it.readText() }
                val result = scriptManager.extractMetadata(content)
                val folder = if (fileName.contains("/")) fileName.substringBeforeLast("/") else ""
                AssetMetadata(
                    fileName = fileName,
                    name = (result["name"] as? String) ?: fileName.removeSuffix(".js"),
                    version = (result["metadata"] as? ModuleMetadata)?.version,
                    description = result["description"] as? String,
                    folder = folder
                )
            } catch (e: Exception) {
                val folder = if (fileName.contains("/")) fileName.substringBeforeLast("/") else ""
                AssetMetadata(
                    fileName = fileName,
                    name = fileName.removeSuffix(".js"),
                    version = null,
                    description = null,
                    folder = folder
                )
            }
        }
    }

    val filteredAssets = remember(searchQuery, assetMetadataList) {
        if (searchQuery.isEmpty()) assetMetadataList
        else assetMetadataList.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.fileName.contains(searchQuery, ignoreCase = true) ||
            (it.description?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    val groupedAssets = remember(filteredAssets) {
        filteredAssets.groupBy { it.folder.ifEmpty { "Root" } }
    }

    fun toggleFolder(folder: String) {
        expandedFolders = if (folder in expandedFolders) {
            expandedFolders - folder
        } else {
            expandedFolders + folder
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReShift Assets", fontWeight = FontWeight.SemiBold) },
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
                placeholder = { Text("Search assets...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (filteredAssets.isEmpty()) {
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
                            "No assets found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupedAssets.forEach { (folder, assetsInFolder) ->
                        val isExpanded = folder in expandedFolders || searchQuery.isNotEmpty()
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggleFolder(folder) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = folder.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        "${assetsInFolder.size} items",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        
                        if (isExpanded) {
                            items(assetsInFolder) { asset ->
                                OutlinedCard(
                                    onClick = { assetToPreview = asset },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(asset.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (asset.version != null) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("v${asset.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                                }
                                            }
                                            if (asset.description != null) {
                                                Text(asset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(onClick = { 
                                            scriptManager.importScriptFromAsset(asset.fileName)
                                            onBack()
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Download, "Import", modifier = Modifier.size(18.dp))
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

    if (assetToPreview != null) {
        AlertDialog(
            onDismissRequest = { assetToPreview = null },
            title = { Text(assetToPreview!!.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(assetToPreview!!.description ?: "No description available.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "path: ${assetToPreview!!.fileName}",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        scriptManager.importScriptFromAsset(assetToPreview!!.fileName)
                        assetToPreview = null
                        onBack()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import Module")
                }
            },
            dismissButton = {
                TextButton(onClick = { assetToPreview = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
