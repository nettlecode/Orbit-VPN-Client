package app.orbit.vpn.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.saveable.rememberSaveable
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R
import app.orbit.vpn.model.Policy
import app.orbit.vpn.service.ConnectionInfo
import app.orbit.vpn.service.ConnectionsMonitor
import io.nekohasekai.libbox.Libbox

@Composable
fun ConnectionsScreen(onBack: () -> Unit) {
    val connections by ConnectionsMonitor.connections.collectAsStateWithLifecycle()
    val observed by ConnectionsMonitor.observed.collectAsStateWithLifecycle()
    var journal by rememberSaveable { mutableStateOf(false) }
    val visible = if (journal) observed else connections
    DisposableEffect(Unit) {
        ConnectionsMonitor.acquire()
        onDispose { ConnectionsMonitor.release() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(
                stringResource(R.string.connections_title, visible.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (connections.isNotEmpty()) {
                TextButton(onClick = ConnectionsMonitor::closeAll) { Text(stringResource(R.string.connections_close_all)) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !journal, onClick = { journal = false }, label = { Text(stringResource(R.string.connections_active)) })
            FilterChip(selected = journal, onClick = { journal = true }, label = { Text(stringResource(R.string.connections_journal)) })
        }
        if (journal) Text(
            stringResource(R.string.connections_journal_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (visible.isEmpty()) {
            Text(
                stringResource(if (journal) R.string.connections_journal_empty else R.string.connections_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { ConnectionRow(it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionRow(connection: ConnectionInfo) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    if (details) ConnectionDetails(connection, onDismiss = { details = false })
    OrbitCard(Modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier
                    .combinedClickable(onClick = { details = true }, onLongClick = { menu = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pkg = connection.packageName
                if (pkg != null) AppIcon(pkg, 36.dp) else IconTile(Icons.Default.Language, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        connection.domain.ifEmpty { connection.destination },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(pkg?.let { Apps.label(context, it) }, connection.network.uppercase()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.connections_rule, connection.rule.ifEmpty { stringResource(R.string.connections_final) }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (connection.closed) Text(stringResource(R.string.connections_closed), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    OutboundBadge(connection)
                    Text(
                        "↓${Libbox.formatBytes(connection.downlinkTotal)} ↑${Libbox.formatBytes(connection.uplinkTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            DropdownMenu(expanded = menu && !connection.closed, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_close)) },
                    onClick = {
                        menu = false
                        ConnectionsMonitor.close(connection.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun OutboundBadge(connection: ConnectionInfo) {
    val policy = when {
        connection.outboundType == "block" -> Policy.REJECT
        connection.outbound == "direct" -> Policy.DIRECT
        else -> Policy.PROXY
    }
    val (fg, bg) = when (policy) {
        Policy.PROXY -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        Policy.DIRECT -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
        Policy.REJECT -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
    }
    Text(
        policyLabel(policy),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier.background(bg, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ConnectionDetails(connection: ConnectionInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(connection.domain.ifEmpty { connection.destination }) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connections_rule, connection.rule.ifEmpty { stringResource(R.string.connections_final) }))
                    Text(stringResource(R.string.connections_destination, connection.destination))
                    Text(stringResource(R.string.connections_source, connection.source))
                    Text(stringResource(R.string.connections_route, connection.chain.ifEmpty { listOf(connection.outbound) }.joinToString(" → ")))
                    Text(connection.network.uppercase())
                    Text(stringResource(R.string.connections_started, DateFormat.getDateTimeInstance().format(Date(connection.createdAt))))
                    if (connection.closed) Text(stringResource(R.string.connections_closed))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
