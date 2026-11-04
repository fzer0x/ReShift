package ox.fzer0x.snakeloader.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen

@Composable
fun ReShiftBootAnimation(
    currentStepText: String,
    progress: Float,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "BootProgress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "BootOrbit")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbitRotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LogoPulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0E12)),
        contentAlignment = Alignment.Center
    ) {
        // Glowing Radial Background
        Box(
            modifier = Modifier
                .size(320.dp)
                .scale(pulseScale)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )

        // Top-Right Skip Button
        if (onSkip != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                OutlinedButton(
                    onClick = onSkip,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Skip",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "SKIP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // LOGO WITH DYNAMIC ORBIT RINGS
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                // Outer Orbit Ring 1 (Clockwise)
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .rotate(rotationAngle)
                        .border(
                            width = 2.dp,
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.tertiary,
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Outer Orbit Ring 2 (Counter-Clockwise)
                Box(
                    modifier = Modifier
                        .size(126.dp)
                        .rotate(-rotationAngle * 1.5f)
                        .border(
                            width = 1.5.dp,
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    SuccessGreen,
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.primary
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Central ReShift Badge Core
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .size(96.dp)
                        .scale(pulseScale)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.ExtraLight,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                ) {
                                    append("RE")
                                }
                                withStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    append("SHIFT")
                                }
                            },
                            fontSize = 16.sp,
                            letterSpacing = 2.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // RE-SHIFT BRAND HEADING
            Text(
                text = "SYSTEM BOOT SEQUENCE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 3.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = currentStepText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.height(20.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // PROGRESS BAR & PERCENTAGE
            Column(
                modifier = Modifier.width(260.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )

                    Surface(
                        color = if (isComplete) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isComplete) "READY" else "INITIALIZING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isComplete) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (onSkip != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(
                        onClick = onSkip,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SKIP",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}
