package ox.fzer0x.snakeloader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen

// Unified Design System Shape Tokens
val ReShiftCardShape = RoundedCornerShape(16.dp)
val ReShiftDialogShape = RoundedCornerShape(20.dp)
val ReShiftButtonShape = RoundedCornerShape(12.dp)
val ReShiftChipShape = RoundedCornerShape(8.dp)
val ReShiftBadgeShape = RoundedCornerShape(6.dp)
val ReShiftIconBoxShape = RoundedCornerShape(10.dp)

/**
 * Standard TopAppBar for ReShift screen hierarchy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReShiftTopAppBar(
    title: String,
    subtitle: String? = null,
    isBrandTitle: Boolean = false,
    isActiveStatus: Boolean? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            if (isBrandTitle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActiveStatus != null) {
                        Surface(
                            shape = CircleShape,
                            color = if (isActiveStatus) SuccessGreen else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.ExtraLight,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                        style = MaterialTheme.typography.titleLarge,
                        letterSpacing = 3.sp
                    )
                }
            } else {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = actions
    )
}

/**
 * Standard Section Header across all screens.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
        letterSpacing = 1.2.sp
    )
}

/**
 * Helper Card component matching ReShift design tokens.
 */
@Composable
fun ReShiftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val finalBorder = border ?: CardDefaults.outlinedCardBorder()
    if (onClick != null) {
        OutlinedCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = ReShiftCardShape,
            colors = colors,
            border = finalBorder,
            content = content
        )
    } else {
        OutlinedCard(
            modifier = modifier.fillMaxWidth(),
            shape = ReShiftCardShape,
            colors = colors,
            border = finalBorder,
            content = content
        )
    }
}

/**
 * Standard Status Badge for values, flags, and tags.
 */
@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = ReShiftChipShape,
        modifier = modifier
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Standardized status row for configuration lists and dashboards.
 */
@Composable
fun StatusRow(
    label: String,
    value: String,
    color: Color,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        StatusBadge(text = value, color = color)
    }
}

@Composable
fun BadgePill(
    text: String,
    bgColor: Color,
    textColor: Color = Color.White
) {
    Surface(
        color = bgColor,
        shape = ReShiftBadgeShape
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun InfoCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null
) {
    ReShiftCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(ReShiftIconBoxShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
