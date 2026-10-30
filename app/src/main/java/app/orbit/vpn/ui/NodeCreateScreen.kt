package app.orbit.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.vpn.R
import app.orbit.vpn.model.*
import java.util.UUID

@Composable
fun NodeCreateScreen(onBack: () -> Unit, onSave: (ProxyNode) -> Unit) {
    var protocol by rememberSaveable { mutableStateOf<ProxyType?>(null) }
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val type = protocol
    if (type != null) {
        val node = remember(id, type) { newLocalNode(type, id) }
        NodeEditorScreen(node, onBack = onBack, onSave = onSave, creating = true)
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(stringResource(R.string.node_create_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.node_create_protocol), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProxyType.entries.forEach { item ->
                OrbitCard(Modifier.fillMaxWidth(), onClick = { protocol = item }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

internal fun newLocalNode(type: ProxyType, id: String): ProxyNode = ProxyNode(
    id = id, name = "", type = type, server = "",
    port = when (type) {
        ProxyType.SHADOWSOCKS -> 8388
        ProxyType.SOCKS -> 1080
        ProxyType.HTTP -> 8080
        else -> 443
    },
    method = when (type) {
        ProxyType.SHADOWSOCKS -> "aes-256-gcm"
        ProxyType.VMESS -> "auto"
        else -> null
    },
    tls = when (type) {
        ProxyType.TROJAN, ProxyType.HYSTERIA2 -> TlsSettings()
        ProxyType.TUIC -> TlsSettings(alpn = listOf("h3"))
        else -> null
    },
)
