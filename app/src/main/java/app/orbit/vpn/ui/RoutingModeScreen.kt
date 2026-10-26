package app.orbit.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R
import app.orbit.vpn.model.RoutingMode

@Composable
fun RoutingModeScreen(viewModel: MainViewModel, onBack: () -> Unit, onConfigureRules: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var dnsDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(stringResource(R.string.routing_mode), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard(
                Icons.Default.Language,
                stringResource(R.string.mode_global),
                stringResource(R.string.mode_global_hint),
                state.mode == RoutingMode.GLOBAL,
            ) { viewModel.setMode(RoutingMode.GLOBAL) }
            ModeCard(
                Icons.Default.Hub,
                stringResource(R.string.mode_rule_long),
                stringResource(R.string.mode_rule_hint),
                state.mode == RoutingMode.RULE,
            ) { viewModel.setMode(RoutingMode.RULE) }
            ModeCard(
                Icons.Default.Bolt,
                stringResource(R.string.mode_direct),
                stringResource(R.string.mode_direct_hint),
                state.mode == RoutingMode.DIRECT,
            ) { viewModel.setMode(RoutingMode.DIRECT) }

            Text(
                stringResource(R.string.quick_actions),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            QuickAction(
                Icons.Default.Tune,
                stringResource(R.string.configure_rules),
                configName(rules.active),
                onConfigureRules,
            )
            QuickAction(
                Icons.Default.Dns,
                stringResource(R.string.settings_dns),
                dnsLabel(state.remoteDns),
            ) { dnsDialog = true }
        }
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
private fun ModeCard(icon: ImageVector, title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth(), highlighted = selected, onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
