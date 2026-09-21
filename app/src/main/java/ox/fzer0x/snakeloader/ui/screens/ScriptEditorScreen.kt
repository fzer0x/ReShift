package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.DownloadedScript
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.components.ReShiftTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    scriptId: String,
    scriptManager: ScriptManager,
    onBack: () -> Unit
) {
    val scripts = scriptManager.getScripts()
    val script = remember(scriptId, scripts) { scripts.find { s: DownloadedScript -> s.id == scriptId } }
    
    if (script?.metadata?.noEdit == true) {
        LaunchedEffect(Unit) {
            onBack()
        }
        return
    }

    var content by remember(script) { mutableStateOf(script?.content ?: "") }
    var hasChanges by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = script?.name ?: "Editor",
                subtitle = script?.repository,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            scriptManager.updateScriptContent(scriptId, content)
                            hasChanges = false
                        },
                        enabled = hasChanges
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = if (hasChanges) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TextField(
                value = content,
                onValueChange = {
                    content = it
                    hasChanges = true
                },
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
    }
}
