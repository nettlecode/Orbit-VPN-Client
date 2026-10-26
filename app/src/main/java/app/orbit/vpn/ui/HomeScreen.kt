package app.orbit.vpn.ui

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.RoutingMode
import app.orbit.vpn.service.VpnStatus
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenRoutingMode: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val startedAt by viewModel.startedAt.collectAsStateWithLifecycle()
    val latency by viewModel.latency.collectAsStateWithLifecycle()
    val selected = state.selected

    LaunchedEffect(selected?.id) {
        if (selected != null && latency[selected.id] == null) viewModel.testNode(selected)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(Modifier.padding(top = 14.dp, bottom = 8.dp))

        PowerButton(
            status = status,
            startedAt = startedAt,
            enabled = selected != null,
            onClick = {
                when (status) {
                    VpnStatus.STOPPED -> onConnect()
                    VpnStatus.STARTED, VpnStatus.STARTING -> viewModel.disconnect()
                    VpnStatus.STOPPING -> Unit
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        ServerCard(selected, onOpenProfiles)

        Spacer(Modifier.height(12.dp))
        val connected = status == VpnStatus.STARTED
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                Modifier.weight(1f),
                Icons.Default.ArrowDownward,
                stringResource(R.string.home_download),
                if (connected) bitrate(traffic.downlink) else null,
                MaterialTheme.colorScheme.primary,
            )
            StatTile(
                Modifier.weight(1f),
                Icons.Default.ArrowUpward,
                stringResource(R.string.home_upload),
                if (connected) bitrate(traffic.uplink) else null,
                Upload,
            )
            val ping = selected?.let { latency[it.id] }
            StatTile(
                Modifier.weight(1f),
                Icons.Default.Timer,
                stringResource(R.string.home_latency),
                (ping as? Latency.Ok)?.let { it.millis.toString() to "ms" },
                MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(
                Modifier.weight(1f),
                Icons.Default.Hub,
                stringResource(R.string.home_mode),
                stringResource(modeLabel(state.mode)),
                onOpenRoutingMode,
            )
            ActionTile(
                Modifier.weight(1f),
                Icons.Default.DataUsage,
                stringResource(R.string.home_session),
                if (connected) {
                    "↓ ${Libbox.formatBytes(traffic.downlinkTotal)}  ↑ ${Libbox.formatBytes(traffic.uplinkTotal)}"
                } else {
                    "—"
                },
                null,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

fun modeLabel(mode: RoutingMode) = when (mode) {
    RoutingMode.RULE -> R.string.mode_rule_long
    RoutingMode.GLOBAL -> R.string.mode_global
    RoutingMode.DIRECT -> R.string.mode_direct
}

/** Splits "42.6 Mbps" into value and unit for the stat tiles. */
private fun bitrate(bytesPerSecond: Long): Pair<String, String> {
    val text = Libbox.formatBitrate(bytesPerSecond * 8)
    return text.substringBeforeLast(' ') to text.substringAfterLast(' ', "")
}

@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(40.dp),
        )
        Text(
            stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PowerButton(status: VpnStatus, startedAt: Long, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = status == VpnStatus.STARTED
    val busy = status == VpnStatus.STARTING || status == VpnStatus.STOPPING
    val accent by animateColorAsState(if (active || busy) colors.primary else colors.outline, label = "accent")
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val face = colors.surfaceContainerLowest

    Box(
        Modifier
            .padding(vertical = 8.dp)
            .size(250.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled && status != VpnStatus.STOPPING,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            // Soft halo rings around the button face.
            drawCircle(accent.copy(alpha = 0.06f), radius)
            drawCircle(accent.copy(alpha = 0.10f), radius * 0.86f)
            drawCircle(face, radius * 0.72f)
            val ringWidth = 10.dp.toPx()
            val ringRadius = radius * 0.72f - ringWidth / 2
            if (busy) {
                drawCircle(accent.copy(alpha = 0.18f), ringRadius, style = Stroke(ringWidth))
                drawArc(
                    accent,
                    startAngle = rotation,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(ringWidth, cap = StrokeCap.Round),
                )
            } else {
                drawCircle(accent, ringRadius, style = Stroke(ringWidth))
            }
        }
        // Keep labels inside the inner ring: face diameter is 72% of the button minus the ring.
        Column(Modifier.widthIn(max = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = accent, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when (status) {
                        VpnStatus.STOPPED -> R.string.status_disconnected
                        VpnStatus.STARTING -> R.string.status_connecting
                        VpnStatus.STARTED -> R.string.status_connected
                        VpnStatus.STOPPING -> R.string.status_disconnecting
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) colors.primary else colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (active) {
                Text(
                    DateUtils.formatElapsedTime(rememberElapsed(startedAt) / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else if (status == VpnStatus.STOPPED) {
                Text(
                    stringResource(if (enabled) R.string.home_tap_to_connect else R.string.no_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun rememberElapsed(startedAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return (now - startedAt).coerceAtLeast(0)
}

@Composable
private fun ServerCard(node: ProxyNode?, onClick: () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            FlagAvatar(node?.name.orEmpty())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node?.let { Flags.stripFlag(it.name) } ?: stringResource(R.string.no_server),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    node?.let(::protocolLabel) ?: stringResource(R.string.home_choose_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun protocolLabel(node: ProxyNode): String = buildString {
    append(node.type.label)
    val transport = node.transport
    when {
        node.tls?.realityPublicKey != null -> append(" · Reality")
        transport != null -> append(" · ").append(transport.type.uppercase())
    }
}

@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, label: String, value: Pair<String, String>?, tint: Color) {
    OrbitCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(value?.first ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(value?.second.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionTile(modifier: Modifier, icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    OrbitCard(modifier, onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
