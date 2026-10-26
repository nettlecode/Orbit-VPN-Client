package app.orbit.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R
import app.orbit.vpn.service.ConnectionsMonitor
import app.orbit.vpn.service.ServiceState
import app.orbit.vpn.service.VpnStatus
import io.nekohasekai.libbox.Libbox
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun TrafficScreen(viewModel: MainViewModel, onOpenConnections: () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val historyVersion by viewModel.trafficHistory.version.collectAsStateWithLifecycle()
    val connections by ConnectionsMonitor.connections.collectAsStateWithLifecycle()
    val seenTotal by ConnectionsMonitor.seenTotal.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableIntStateOf(0) }
    val connected = status == VpnStatus.STARTED

    DisposableEffect(Unit) {
        ConnectionsMonitor.acquire()
        onDispose { ConnectionsMonitor.release() }
    }

    val history = viewModel.trafficHistory
    val series = remember(period, historyVersion) {
        when (period) {
            1 -> history.today()
            2 -> history.days(7)
            3 -> history.days(30)
            else -> emptyList()
        }
    }
    val today = remember(historyVersion) { history.todayTotal() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(stringResource(R.string.tab_traffic))
        PillTabs(
            listOf(
                stringResource(R.string.period_now),
                stringResource(R.string.period_day),
                stringResource(R.string.period_week),
                stringResource(R.string.period_month),
            ),
            period,
            { period = it },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OrbitCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp)) {
                    val (downValue, upValue) = if (period == 0) {
                        (if (connected) Libbox.formatBitrate(traffic.downlink * 8) else "—") to
                            (if (connected) Libbox.formatBitrate(traffic.uplink * 8) else "—")
                    } else {
                        Libbox.formatBytes(series.sumOf { it.second }) to Libbox.formatBytes(series.sumOf { it.first })
                    }
                    Legend(Modifier.weight(1f), MaterialTheme.colorScheme.primary, stringResource(R.string.home_download), downValue)
                    Legend(Modifier.weight(1f), Upload, stringResource(R.string.home_upload), upValue)
                }
            }

            OrbitCard(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
                    if (period == 0) {
                        if (connected) {
                            SpeedChart(
                                samples,
                                ServiceState.MAX_SAMPLES,
                                { Libbox.formatBitrate(it * 8).replace(" ", " ") },
                                listOf("−2:00", "−1:00", stringResource(R.string.chart_now)),
                            )
                        } else {
                            Text(
                                stringResource(R.string.traffic_connect_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 60.dp, horizontal = 8.dp),
                            )
                        }
                    } else {
                        TrafficBars(series, { Libbox.formatBytes(it).replace(" ", " ") }, periodLabels(period))
                    }
                }
            }

            OrbitCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.traffic_today), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            Libbox.formatBytes(today.first + today.second),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SplitBar(today.second, today.first)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "↓ ${Libbox.formatBytes(today.second)}   ↑ ${Libbox.formatBytes(today.first)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            StatRow(Icons.Default.SwapHoriz, stringResource(R.string.connections_active), if (connected) connections.size.toString() else "—", onOpenConnections)
            StatRow(Icons.Default.History, stringResource(R.string.connections_seen), if (connected) seenTotal.toString() else "—", null)
        }
    }
}

@Composable
private fun Legend(modifier: Modifier, color: Color, label: String, value: String) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SplitBar(down: Long, up: Long) {
    val total = (down + up).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (down > 0) Box(Modifier.weight(down.toFloat() / total).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        if (up > 0) Box(Modifier.weight(up.toFloat() / total).fillMaxHeight().background(Upload))
        if (down + up == 0L) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StatRow(icon: ImageVector, title: String, value: String, onClick: (() -> Unit)?) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (onClick != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** First, middle and last x-axis labels for the bar charts. */
private fun periodLabels(period: Int): List<String> {
    if (period == 1) return listOf("00:00", "12:00", "23:00")
    val days = if (period == 2) 7 else 30
    val format = SimpleDateFormat("d MMM", Locale.getDefault())
    fun daysAgo(n: Int) = format.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)
    return listOf(daysAgo(days - 1), daysAgo(days / 2), daysAgo(0))
}
