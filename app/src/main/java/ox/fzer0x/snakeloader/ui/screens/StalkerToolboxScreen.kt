package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.ui.components.ReShiftCard
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar
import ox.fzer0x.snakeloader.ui.components.ReShiftButtonShape
import ox.fzer0x.snakeloader.ui.components.ReShiftChipShape
import ox.fzer0x.snakeloader.ui.components.SectionHeader
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StalkerToolboxScreen(
    fridaManager: FridaManager,
    onBack: () -> Unit
) {
    val stalkerManager = fridaManager.stalkerManager
    var isRunning by stalkerManager.isActive
    var moduleFilter by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    
    val rpcManager = fridaManager.getRpcManager()
    var isRpcConnected by remember { mutableStateOf(rpcManager != null) }
    var isConnectingRpc by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rpcManager) {
        isRpcConnected = rpcManager != null
    }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = "Code Flow Stalker",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ReShiftCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Radar, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("CONFIGURATION", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                            }
                            
                            Surface(
                                color = if (isRpcConnected) SuccessGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f),
                                shape = ReShiftChipShape
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(6.dp).background(if (isRpcConnected) SuccessGreen else MaterialTheme.colorScheme.error, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (isRpcConnected) "RPC READY" else "DISCONNECTED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isRpcConnected) SuccessGreen else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        
                        if (!isRpcConnected) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        isConnectingRpc = true
                                        connectionError = null
                                        if (fridaManager.initializeRpc()) {
                                            isRpcConnected = true
                                        } else {
                                            connectionError = "Check if rpc_handler.js is running."
                                        }
                                        isConnectingRpc = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isConnectingRpc,
                                shape = ReShiftButtonShape
                            ) {
                                if (isConnectingRpc) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Establish RPC Bridge", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        
                        connectionError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        }

                        Spacer(Modifier.height(16.dp))
                        
                        OutlinedTextField(
                            value = moduleFilter,
                            onValueChange = { moduleFilter = it },
                            label = { Text("Module Filter (e.g. libunity.so)") },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Capture all modules") },
                            shape = ReShiftButtonShape,
                            singleLine = true
                        )
                        
                        val currentPackageState = fridaManager.currentPackageName.collectAsState()
                        val currentPackage = currentPackageState.value
                        if (!currentPackage.isNullOrEmpty()) {
                            Text(
                                "Target: $currentPackage",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                            )
                        }
                    }
                }
            }

            item {
                ReShiftCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Real-time Trace", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Instrument code flow dynamically", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        Button(
                            onClick = {
                                connectionError = null
                                isRunning = !isRunning
                            },
                            enabled = isRpcConnected || isRunning,
                            shape = ReShiftButtonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRunning) "Stop" else "Start", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                SectionHeader("OPERATIONAL GUIDE")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedInfoItem(Icons.Default.Speed, "Identifies active code paths by monitoring function call frequencies.")
                    OutlinedInfoItem(Icons.Default.Security, "Bypasses basic anti-debugging by observing flow branch points.")
                    OutlinedInfoItem(Icons.Default.Memory, "Resolves symbols using the native DebugSymbol API on-the-fly.")
                }
            }
            
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = ReShiftChipShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Note: Performance varies by hardware. Targeted module filters recommended.",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            if (stalkerManager.hotPaths.isNotEmpty()) {
                item { SectionHeader("HOT PATH ANALYSIS") }
                items(stalkerManager.hotPaths) { hit ->
                    ReShiftCard {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(hit.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hit.address, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${hit.hits} hits", fontWeight = FontWeight.Black, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                LinearProgressIndicator(
                                    progress = { hit.percentage },
                                    modifier = Modifier.width(60.dp).height(4.dp).clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            if (stalkerManager.callTree.isNotEmpty()) {
                item { SectionHeader("CALL HIERARCHY SEGMENTS") }
                items(stalkerManager.callTree) { node ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = (node.depth * 8).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(2.dp, 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(node.name, fontSize = 12.sp, fontWeight = if (node.depth == 0) FontWeight.Bold else FontWeight.Normal)
                            Text(node.address, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OutlinedInfoItem(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
