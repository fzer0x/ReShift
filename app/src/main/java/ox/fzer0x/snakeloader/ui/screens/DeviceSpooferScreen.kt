package ox.fzer0x.snakeloader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ox.fzer0x.snakeloader.spoofer.DeviceProfile
import ox.fzer0x.snakeloader.spoofer.DeviceSpooferPresets
import ox.fzer0x.snakeloader.ui.components.*
import ox.fzer0x.snakeloader.ui.theme.CyanAccent
import ox.fzer0x.snakeloader.ui.theme.PurpleAccent
import ox.fzer0x.snakeloader.ui.theme.SuccessGreen
import ox.fzer0x.snakeloader.ui.viewmodels.DeviceSpooferViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSpooferScreen(
    viewModel: DeviceSpooferViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            ReShiftTopAppBar(
                title = "HARDWARE SPOOFER",
                subtitle = "Dynamic Fingerprint Anonymizer",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.randomizeCurrentProfile() }) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Randomize Profile",
                            tint = CyanAccent
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // 1. Engine Master Toggle & Status Card
            item {
                ReShiftCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (uiState.isEngineEnabled) SuccessGreen else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.isEngineEnabled) "Spoofer Engine Active" else "Spoofer Engine Disabled",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "LSPosed / Xposed ART Hooks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = uiState.isEngineEnabled,
                            onCheckedChange = { viewModel.toggleEngine(it) }
                        )
                    }
                }
            }

            // 2. Preset Quick Selection Chips
            item {
                Column {
                    SectionHeader(title = "PRESET PROFILES")
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(DeviceSpooferPresets.ALL_PRESETS) { preset ->
                            val activeProfile = uiState.selectedProfile
                            val isSelected = activeProfile != null &&
                                    (activeProfile.model == preset.model || activeProfile.name.contains(preset.name))

                            Surface(
                                onClick = { viewModel.applyPreset(preset) },
                                shape = ReShiftChipShape,
                                color = if (isSelected) CyanAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        tint = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Active Profile Detail Cards ("Kästen")
            val profile = uiState.selectedProfile
            if (profile != null) {
                // Header Card
                item {
                    ReShiftCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = PurpleAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "ACTIVE FINGERPRINT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = PurpleAccent,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Button(
                                    onClick = { viewModel.randomizeCurrentProfile() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanAccent,
                                        contentColor = Color.Black
                                    ),
                                    shape = ReShiftButtonShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Randomize", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${profile.manufacturer} ${profile.model} • Android ${profile.releaseVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Box 1: Anonymized Unique Identifiers & Google Ads ID
                item {
                    SectionHeader(title = "SPOOFED IDENTIFIERS & AD TRACKING")
                }

                item {
                    ReShiftCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AdsClick, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Google Advertising & System IDs",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            SpooferIdentifierBox(
                                label = "Google Advertising ID (GAID)",
                                value = profile.advertisingId,
                                context = context
                            )

                            SpooferIdentifierBox(
                                label = "Android ID",
                                value = profile.androidId,
                                context = context
                            )

                            SpooferIdentifierBox(
                                label = "GSF ID (Google Services Framework)",
                                value = profile.gsfId,
                                context = context
                            )

                            SpooferIdentifierBox(
                                label = "IMEI",
                                value = profile.imei,
                                context = context
                            )

                            SpooferIdentifierBox(
                                label = "DRM Device ID",
                                value = profile.drmDeviceId,
                                context = context
                            )

                            SpooferIdentifierBox(
                                label = "Serial Number",
                                value = profile.serial,
                                context = context
                            )

                            SpooferIdentifierBox(
                                label = "MAC Address",
                                value = profile.macAddress,
                                context = context
                            )
                        }
                    }
                }

                // Box 2: Hardware Specifications
                item {
                    SectionHeader(title = "HARDWARE SPECIFICATIONS")
                }

                item {
                    ReShiftCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Device Hardware & Board",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            SpooferFieldRow(label = "Brand / Manufacturer", value = "${profile.brand} / ${profile.manufacturer}")
                            SpooferFieldRow(label = "Model", value = profile.model)
                            SpooferFieldRow(label = "Device / Product", value = "${profile.device} (${profile.product})")
                            SpooferFieldRow(label = "Hardware / Board", value = "${profile.hardware} / ${profile.board}")
                        }
                    }
                }

                // Box 3: OS Build & Fingerprint
                item {
                    SectionHeader(title = "SYSTEM & BUILD FINGERPRINT")
                }

                item {
                    ReShiftCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Android OS & Build Fingerprint",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            SpooferFieldRow(label = "Android Version", value = "Android ${profile.releaseVersion} (API ${profile.sdkInt})")
                            SpooferFieldRow(label = "Security Patch", value = profile.securityPatch)

                            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(
                                    text = "Build Fingerprint",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = ReShiftChipShape,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = profile.fingerprint,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = CyanAccent,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                copyToClipboard(context, "Build Fingerprint", profile.fingerprint)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copy Fingerprint",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Box 4: GPU & Render Engine
                item {
                    SectionHeader(title = "GRAPHICS ENGINE")
                }

                item {
                    ReShiftCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "OpenGL & GPU Renderer",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            SpooferFieldRow(label = "GL Vendor", value = profile.glVendor)
                            SpooferFieldRow(label = "GL Renderer", value = profile.glRenderer)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SpooferIdentifierBox(
    label: String,
    value: String,
    context: Context
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = CyanAccent,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { copyToClipboard(context, label, value) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpooferFieldRow(
    label: String,
    value: String,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            color = if (isMonospace) CyanAccent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f)
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
}
