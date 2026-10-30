package app.orbit.vpn.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.BuildConfig
import app.orbit.vpn.R
import app.orbit.vpn.config.ConfigBuilder
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.xraybridge.Xraybridge

private val DNS_PRESETS = listOf(
    "Cloudflare" to ConfigBuilder.DEFAULT_REMOTE_DNS,
    "Google" to "https://8.8.8.8/dns-query",
    "Quad9" to "https://9.9.9.9/dns-query",
    "AdGuard" to "https://94.140.14.14/dns-query",
)

fun dnsLabel(address: String) = DNS_PRESETS.firstOrNull { it.second == address }?.first ?: address

private val GEOIP_PRESETS = listOf(
    "GitHub" to ConfigBuilder.DEFAULT_GEOIP_URL,
    // Same files through a CDN, for networks where raw.githubusercontent.com is blocked.
    "jsDelivr" to "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-{country}.srs",
)

private val ASN_PRESETS = listOf(
    "GitHub" to ConfigBuilder.DEFAULT_ASN_URL,
    "jsDelivr" to "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@sing/asn/AS{asn}.srs",
)

private fun sourceLabel(presets: List<Pair<String, String>>, url: String) = presets.firstOrNull { it.second == url }?.first ?: url

private val GEOIP_INTERVALS = listOf(
    12 to R.string.geoip_interval_12h,
    24 to R.string.geoip_interval_1d,
    72 to R.string.geoip_interval_3d,
    168 to R.string.geoip_interval_7d,
)

@Composable
private fun geoipIntervalLabel(hours: Int): String =
    GEOIP_INTERVALS.firstOrNull { it.first == hours }?.let { stringResource(it.second) }
        ?: stringResource(R.string.geoip_interval_hours, hours)

@Composable
fun SettingsScreen(viewModel: MainViewModel, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onDiagnostics: () -> Unit, onCoreLogs: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val lockedBackup by viewModel.lockedBackup.collectAsStateWithLifecycle()
    val wrongPassword by viewModel.wrongPassword.collectAsStateWithLifecycle()
    val systemVpn by viewModel.systemVpn.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    // Back from the system VPN settings, where always-on may have changed.
    LifecycleResumeEffect(Unit) {
        viewModel.readSystemVpn()
        onPauseOrDispose {}
    }
    var exportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var dnsDialog by remember { mutableStateOf(false) }
    var geoipSourceDialog by remember { mutableStateOf(false) }
    var geoipIntervalDialog by remember { mutableStateOf(false) }
    var asnSourceDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(stringResource(R.string.settings))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsGroup {
                SwitchRow(
                    Icons.Default.RestartAlt,
                    stringResource(R.string.settings_restore_connection),
                    stringResource(R.string.settings_restore_connection_hint),
                    state.restoreConnection,
                    viewModel::setRestoreConnection,
                )
                Divider()
                // Android keeps these per app in its VPN settings; only a running tunnel can read them.
                SettingRow(
                    Icons.Default.VpnLock,
                    stringResource(R.string.settings_always_on),
                    when {
                        systemVpn?.lockdown == true -> stringResource(R.string.always_on_lockdown)
                        systemVpn?.alwaysOn == true -> stringResource(R.string.always_on_no_lockdown)
                        else -> stringResource(R.string.settings_always_on_hint, stringResource(R.string.app_name))
                    },
                ) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                }
            }

            SettingsGroup {
                SettingRow(Icons.Default.Dns, stringResource(R.string.settings_dns), dnsLabel(state.remoteDns)) { dnsDialog = true }
                Divider()
                SwitchRow(
                    Icons.Default.SettingsEthernet,
                    stringResource(R.string.settings_ipv6),
                    stringResource(R.string.settings_ipv6_hint),
                    state.ipv6,
                    viewModel::setIpv6,
                )
            }

            SettingsGroup {
                SettingRow(Icons.Default.Public, stringResource(R.string.geoip_source), sourceLabel(GEOIP_PRESETS, state.geoipUrl)) {
                    geoipSourceDialog = true
                }
                Divider()
                SettingRow(Icons.Default.Hub, stringResource(R.string.asn_source), sourceLabel(ASN_PRESETS, state.asnUrl)) {
                    asnSourceDialog = true
                }
                Divider()
                SettingRow(Icons.Default.Schedule, stringResource(R.string.geoip_interval), geoipIntervalLabel(state.geoipUpdateHours)) {
                    geoipIntervalDialog = true
                }
            }

            SettingsGroup {
                SwitchRow(
                    Icons.Default.Update,
                    stringResource(R.string.settings_auto_update),
                    stringResource(R.string.settings_auto_update_hint),
                    state.autoUpdateSubscriptions,
                    viewModel::setAutoUpdate,
                )
                if (state.subscriptions.isNotEmpty()) {
                    Divider()
                    SettingRow(Icons.Default.Sync, stringResource(R.string.settings_update_now), null, onClick = viewModel::updateAllSubscriptions)
                }
            }

            SettingsGroup {
                SettingRow(Icons.Default.Backup, stringResource(R.string.backup_export), stringResource(R.string.backup_export_hint)) {
                    exportDialog = true
                }
                Divider()
                SettingRow(Icons.Default.Restore, stringResource(R.string.backup_import), stringResource(R.string.backup_import_hint), onClick = onImportBackup)
            }

            SettingsGroup {
                SettingRow(Icons.Default.Widgets, stringResource(R.string.add_widget), stringResource(R.string.add_widget_hint)) {
                    Shortcuts.pinWidget(context)
                }
                if (Shortcuts.canAddTile) {
                    Divider()
                    SettingRow(Icons.Default.ToggleOn, stringResource(R.string.add_tile), stringResource(R.string.add_tile_hint)) {
                        Shortcuts.addTile(context)
                    }
                }
            }

            SettingsGroup {
                SettingRow(Icons.Default.Dns, stringResource(R.string.diagnostic_title), null, onDiagnostics)
                Divider()
                SettingRow(Icons.Default.Info, stringResource(R.string.core_logs_title), null, onCoreLogs)
            }

            SettingsGroup {
                SettingRow(
                    Icons.Default.Info,
                    "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    "sing-box ${Libbox.version()} · Xray ${Xraybridge.version()}",
                )
                Divider()
                val available = update.release
                if (available != null) {
                    SettingRow(Icons.Default.SystemUpdate, stringResource(R.string.update_available, available.version), stringResource(R.string.update_banner_hint), onClick = viewModel::showUpdate)
                } else {
                    SettingRow(Icons.Default.SystemUpdate, stringResource(R.string.settings_check_updates), null, onClick = { viewModel.checkForUpdates() })
                }
                Divider()
                SwitchRow(
                    Icons.Default.Update,
                    stringResource(R.string.settings_auto_check_updates),
                    stringResource(R.string.settings_auto_check_updates_hint),
                    state.checkUpdates,
                    viewModel::setCheckUpdates,
                )
            }
        }
    }

    if (exportDialog) {
        ExportBackupDialog(
            onDismiss = { exportDialog = false },
            onExport = { password ->
                exportDialog = false
                viewModel.setExportPassword(password)
                onExportBackup()
            },
        )
    }
    if (lockedBackup != null) {
        UnlockBackupDialog(busy = busy, wrongPassword = wrongPassword, onDismiss = viewModel::cancelUnlock, onUnlock = viewModel::unlockBackup)
    }
    pendingRestore?.let { backup ->
        val date = DateUtils.formatDateTime(context, backup.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_restore_text,
                        date,
                        backup.state.nodes.size,
                        backup.state.subscriptions.size,
                        backup.rules.configs.size,
                        backup.state.userRules.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text(stringResource(R.string.backup_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    if (geoipSourceDialog) {
        ListSourceDialog(
            title = stringResource(R.string.geoip_source),
            hint = stringResource(R.string.geoip_source_hint),
            customHint = stringResource(R.string.geoip_source_custom_hint),
            presets = GEOIP_PRESETS,
            placeholder = ConfigBuilder.COUNTRY_PLACEHOLDER,
            current = state.geoipUrl,
            onDismiss = { geoipSourceDialog = false },
            onSelect = {
                geoipSourceDialog = false
                viewModel.setGeoipUrl(it)
            },
        )
    }
    if (asnSourceDialog) {
        ListSourceDialog(
            title = stringResource(R.string.asn_source),
            hint = stringResource(R.string.asn_source_hint),
            customHint = stringResource(R.string.asn_source_custom_hint),
            presets = ASN_PRESETS,
            placeholder = ConfigBuilder.ASN_PLACEHOLDER,
            current = state.asnUrl,
            onDismiss = { asnSourceDialog = false },
            onSelect = {
                asnSourceDialog = false
                viewModel.setAsnUrl(it)
            },
        )
    }
    if (geoipIntervalDialog) {
        AlertDialog(
            onDismissRequest = { geoipIntervalDialog = false },
            title = { Text(stringResource(R.string.geoip_interval)) },
            text = {
                Column {
                    GEOIP_INTERVALS.forEach { (hours, label) ->
                        val select = {
                            geoipIntervalDialog = false
                            viewModel.setGeoipUpdateHours(hours)
                        }
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = hours == state.geoipUpdateHours, onClick = select),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = hours == state.geoipUpdateHours, onClick = select)
                            Text(stringResource(label))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { geoipIntervalDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    if (dnsDialog) {
        DnsDialog(
            current = state.remoteDns,
            onDismiss = { dnsDialog = false },
            onSelect = {
                dnsDialog = false
                viewModel.setRemoteDns(it)
            },
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SettingRow(icon: ImageVector, title: String, summary: String?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun DnsDialog(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val isPreset = DNS_PRESETS.any { it.second == current }
    var custom by remember { mutableStateOf(if (isPreset) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dns)) },
        text = {
            Column {
                DNS_PRESETS.forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == current, onClick = { onSelect(value) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = { onSelect(value) })
                        Column {
                            Text(label)
                            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.settings_dns_custom)) },
                    placeholder = { Text("tls://1.1.1.1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(custom) }, enabled = DNS_ADDRESS.matches(custom.trim())) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private val DNS_ADDRESS = Regex("""^((https|tls|quic|h3|udp|tcp)://)?[\w.:\[\]-]+(/\S*)?$""")

/** Asks for an optional password; an empty one exports the backup unencrypted. */
@Composable
private fun ExportBackupDialog(onDismiss: () -> Unit, onExport: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val mismatch = password != repeat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_password_hint), style = MaterialTheme.typography.bodyMedium)
                PasswordField(password, { password = it }, stringResource(R.string.backup_password))
                if (password.isNotEmpty()) {
                    PasswordField(
                        repeat,
                        { repeat = it },
                        stringResource(R.string.backup_password_repeat),
                        error = if (mismatch && repeat.isNotEmpty()) stringResource(R.string.backup_password_mismatch) else null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(password.ifEmpty { null }) }, enabled = password.isEmpty() || !mismatch) {
                Text(stringResource(if (password.isEmpty()) R.string.backup_save_plain else R.string.backup_save_encrypted))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun UnlockBackupDialog(busy: Boolean, wrongPassword: Boolean, onDismiss: () -> Unit, onUnlock: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_unlock_text), style = MaterialTheme.typography.bodyMedium)
                PasswordField(
                    password,
                    { password = it },
                    stringResource(R.string.backup_password),
                    error = if (wrongPassword && !busy) stringResource(R.string.backup_wrong_password) else null,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(password) }, enabled = password.isNotEmpty() && !busy) {
                Text(stringResource(R.string.backup_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Rule-set list source: a preset or a custom URL template containing [placeholder]. */
@Composable
private fun ListSourceDialog(
    title: String,
    hint: String,
    customHint: String,
    presets: List<Pair<String, String>>,
    placeholder: String,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val isPreset = presets.any { it.second == current }
    var custom by remember { mutableStateOf(if (isPreset) "" else current) }
    val valid = SOURCE_URL.matches(custom.trim()) && placeholder in custom
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                presets.forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == current, onClick = { onSelect(value) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = { onSelect(value) })
                        Column {
                            Text(label)
                            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.geoip_source_custom)) },
                    placeholder = { Text("https://…/$placeholder.srs") },
                    isError = custom.isNotBlank() && !valid,
                    supportingText = { Text(customHint) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(custom.trim()) }, enabled = valid) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private val SOURCE_URL = Regex("""^https?://\S+$""")
