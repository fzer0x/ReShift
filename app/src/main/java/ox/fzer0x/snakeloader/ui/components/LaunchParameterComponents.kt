package ox.fzer0x.snakeloader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LaunchParametersSection(
    useExceptorOff: Boolean,
    onExceptorOffChange: (Boolean) -> Unit,
    useRuntimeV8: Boolean,
    onRuntimeV8Change: (Boolean) -> Unit,
    useNoPause: Boolean,
    onNoPauseChange: (Boolean) -> Unit,
    useDebugLog: Boolean,
    onDebugLogChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "LAUNCH PARAMETERS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ParameterToggle(
                    label = "Exceptor Off",
                    description = "--exceptor off",
                    checked = useExceptorOff,
                    onCheckedChange = onExceptorOffChange
                )
                ParameterToggle(
                    label = "Runtime V8",
                    description = "--runtime=v8",
                    checked = useRuntimeV8,
                    onCheckedChange = onRuntimeV8Change
                )
                ParameterToggle(
                    label = "No Pause",
                    description = "--no-pause",
                    checked = useNoPause,
                    onCheckedChange = onNoPauseChange
                )
                ParameterToggle(
                    label = "Debug Log",
                    description = "--debug",
                    checked = useDebugLog,
                    onCheckedChange = onDebugLogChange
                )
            }
        }
    }
}

@Composable
fun ParameterToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
fun ConfigGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label.uppercase(), 
            style = MaterialTheme.typography.labelMedium, 
            color = MaterialTheme.colorScheme.primary, 
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        content()
    }
}
