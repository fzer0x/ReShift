package ox.fzer0x.snakeloader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ox.fzer0x.snakeloader.UpdateManager
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen

@Composable
fun AppUpdateDialog(
    state: UpdateManager.UpdateState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state == UpdateManager.UpdateState.Idle) return
    if (state is UpdateManager.UpdateState.Checking && !state.isManual) return
    if (state is UpdateManager.UpdateState.UpToDate && !state.isManual) return
    if (state is UpdateManager.UpdateState.Error && !state.isManual) return

    AlertDialog(
        onDismissRequest = {
            if (state !is UpdateManager.UpdateState.Downloading && state !is UpdateManager.UpdateState.Installing) {
                onDismiss()
            }
        },
        icon = {
            val icon = when (state) {
                is UpdateManager.UpdateState.UpToDate -> Icons.Default.CheckCircle
                is UpdateManager.UpdateState.Error -> Icons.Default.ErrorOutline
                else -> Icons.Default.SystemUpdate
            }
            val tint = when (state) {
                is UpdateManager.UpdateState.UpToDate -> SuccessGreen
                is UpdateManager.UpdateState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = when (state) {
                    is UpdateManager.UpdateState.Checking -> "Check for Updates"
                    is UpdateManager.UpdateState.UpdateAvailable -> "App Update Available"
                    is UpdateManager.UpdateState.UpToDate -> "ReShift is Up to Date"
                    is UpdateManager.UpdateState.Downloading -> "Downloading Update"
                    is UpdateManager.UpdateState.Installing -> "Installing Update"
                    is UpdateManager.UpdateState.Success -> "Update Successful"
                    is UpdateManager.UpdateState.Error -> "Update Check Failed"
                    is UpdateManager.UpdateState.Idle -> "ReShift Update"
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
                        Text("Looking for the latest release on GitHub...")
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
                    is UpdateManager.UpdateState.UpToDate -> {
                        Text(
                            text = "ReShift is up to date!",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "You are already using the latest version of ReShift.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateManager.UpdateState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
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
                is UpdateManager.UpdateState.UpToDate,
                is UpdateManager.UpdateState.Success,
                is UpdateManager.UpdateState.Error -> {
                    Button(onClick = onDismiss, shape = ReShiftButtonShape) {
                        Text("OK", fontWeight = FontWeight.Bold)
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
            } else if (state is UpdateManager.UpdateState.Checking) {
                TextButton(onClick = onDismiss, shape = ReShiftButtonShape) {
                    Text("Cancel")
                }
            }
        },
        shape = ReShiftDialogShape
    )
}
