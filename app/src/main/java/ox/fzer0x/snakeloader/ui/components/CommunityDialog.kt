package ox.fzer0x.snakeloader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun CommunityDialog(
    onDismiss: (Boolean) -> Unit,
    onJoin: (Boolean) -> Unit
) {
    var dontShowAgain by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { onDismiss(dontShowAgain) }) {
        Surface(
            shape = ReShiftDialogShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraLight, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))) {
                            append("JOIN ")
                        }
                        withStyle(SpanStyle(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)) {
                            append("COMMUNITY")
                        }
                    },
                    style = MaterialTheme.typography.titleLarge,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Join Telegram for community support, live chat and script sharing!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ReShiftChipShape)
                        .clickable { dontShowAgain = !dontShowAgain }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Text(
                        "Don't show again",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onJoin(dontShowAgain) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ReShiftButtonShape,
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("Join Channel", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    
                    TextButton(
                        onClick = { onDismiss(dontShowAgain) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ReShiftButtonShape
                    ) {
                        Text(
                            "Later", 
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
