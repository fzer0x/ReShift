package ox.fzer0x.snakeloader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ox.fzer0x.snakeloader.UpdateManager

@Composable
fun AppUpdateDialog(
    state: UpdateManager.UpdateState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state == UpdateManager.UpdateState.Idle || 
        state == UpdateManager.UpdateState.Checking || 
        state == UpdateManager.UpdateState.UpToDate || 
        state is UpdateManager.UpdateState.Error) return

    AlertDialog(
        onDismissRequest = { if (state !is UpdateManager.UpdateState.Downloading && state !is UpdateManager.UpdateState.Installing) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = when (state) {
                    is UpdateManager.UpdateState.UpdateAvailable -> "App Update Available"
                    is UpdateManager.UpdateState.Downloading -> "Downloading Update"
                    is UpdateManager.UpdateState.Installing -> "Installing Update"
                    is UpdateManager.UpdateState.Success -> "Update Successful"
                    else -> "ReShift Update"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is UpdateManager.UpdateState.Checking -> {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        Text("Looking for the latest version...")
                    }
                    is UpdateManager.UpdateState.UpdateAvailable -> {
                        Text(
                            text = "A new version of ReShift is available!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        StatusBadge(
                            text = "v${state.versionName} (${state.versionCode})",
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "This update will be installed automatically using root access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateManager.UpdateState.Downloading -> {
                        val progressPercent = (state.progress * 100).toInt()
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .padding(vertical = 16.dp),
                            strokeCap = StrokeCap.Round
                        )
                        Text(
                            text = "Downloading... $progressPercent%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    is UpdateManager.UpdateState.Installing -> {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        Text("Installing APK with root access...")
                    }
                    is UpdateManager.UpdateState.Success -> {
                        Text("ReShift has been updated successfully.")
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateManager.UpdateState.UpdateAvailable -> {
                    Button(onClick = onDownload, shape = ReShiftButtonShape) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download & Install", fontWeight = FontWeight.Bold)
                    }
                }
                is UpdateManager.UpdateState.Success -> {
                    Button(onClick = onDismiss, shape = ReShiftButtonShape) {
                        Text("Close", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state is UpdateManager.UpdateState.UpdateAvailable) {
                TextButton(onClick = onDismiss, shape = ReShiftButtonShape) {
                    Text("Later")
                }
            }
        },
        shape = ReShiftDialogShape
    )
}
