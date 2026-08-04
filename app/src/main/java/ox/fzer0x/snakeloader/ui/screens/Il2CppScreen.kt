package ox.fzer0x.snakeloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppViewModel
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Il2CppScreen(
    viewModel: Il2CppViewModel,
    onBack: () -> Unit
) {
    val classes = viewModel.classes.value
    val isLoading = viewModel.isLoading.value
    val searchText = viewModel.searchText.value
    val isRpcAvailable = viewModel.isRpcAvailable.value
    val errorMessage = viewModel.errorMessage.value

    LaunchedEffect(Unit) {
        viewModel.checkConnection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Il2Cpp Inspector", fontWeight = FontWeight.SemiBold)
                        if (!isRpcAvailable) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("OFFLINE", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isRpcAvailable) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("RPC Connection Required", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text("Start Frida and initialize the RPC bridge in the Toolbox first.", fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = searchText,
                onValueChange = { viewModel.searchClasses(it) },
                label = { Text("Search Classes (min 3 chars)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                enabled = isRpcAvailable
            )

            if (errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(classes) { klass ->
                    Il2CppClassCard(klass)
                }
            }
        }
    }
}

@Composable
private fun Il2CppClassCard(klass: Il2CppClass) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = klass.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (klass.parent.isNotEmpty()) {
                Text(text = "Parent: ${klass.parent}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                if (klass.fields.isNotEmpty()) {
                    Text("Fields", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    klass.fields.forEach { field ->
                        Text(field, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (klass.methods.isNotEmpty()) {
                    Text("Methods", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    klass.methods.forEach { method ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(method, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = { }) {
                                Text("HOOK", fontSize = 10.sp)
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "${klass.methods.size} methods, ${klass.fields.size} fields",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
