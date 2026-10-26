package app.orbit.vpn.widget

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.orbit.vpn.OrbitApp
import app.orbit.vpn.R
import app.orbit.vpn.service.OrbitVpnService
import app.orbit.vpn.service.ServiceState
import app.orbit.vpn.service.VpnStatus
import app.orbit.vpn.ui.Flags
import app.orbit.vpn.ui.MainActivity
import androidx.compose.ui.graphics.Color

/** Home screen widget: power toggle, status and current server. */
class OrbitWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        // Collected as state so a running Glance session recomposes on every change.
        val status by ServiceState.status.collectAsState()
        val appState by (context.applicationContext as OrbitApp).repository.state.collectAsState()
        val node = appState.selected
        val on = status == VpnStatus.STARTED
        val busy = status == VpnStatus.STARTING || status == VpnStatus.STOPPING

        val primaryText = ColorProvider(day = Color(0xFF15191E), night = Color(0xFFE6E9EC))
        val secondaryText = ColorProvider(day = Color(0xFF6B7480), night = Color(0xFF9AA3AD))
        val green = ColorProvider(day = Color(0xFF19B46B), night = Color(0xFF2FD486))

        Row(
            GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.widget_bg))
                .clickable(toggleAction(context, status, node != null))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier
                    .size(44.dp)
                    .background(ImageProvider(if (on || busy) R.drawable.widget_circle_on else R.drawable.widget_circle_off)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(R.drawable.ic_power),
                    contentDescription = context.getString(R.string.tile_label),
                    colorFilter = ColorFilter.tint(if (on || busy) ColorProvider(Color.White, Color.White) else secondaryText),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    context.getString(
                        when (status) {
                            VpnStatus.STOPPED -> R.string.status_disconnected
                            VpnStatus.STARTING -> R.string.status_connecting
                            VpnStatus.STARTED -> R.string.status_connected
                            VpnStatus.STOPPING -> R.string.status_disconnecting
                        },
                    ),
                    style = TextStyle(color = if (on) green else primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    node?.let { listOfNotNull(Flags.emojiFor(it.name), Flags.stripFlag(it.name)).joinToString(" ") }
                        ?: context.getString(R.string.no_server),
                    style = TextStyle(color = secondaryText, fontSize = 13.sp),
                    maxLines = 1,
                )
            }
        }
    }

    companion object {
        /** Re-renders all widget instances; cheap when none are placed. */
        suspend fun refresh(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(OrbitWidget::class.java).isNotEmpty()) {
                OrbitWidget().updateAll(context)
            }
        }
    }
}

/**
 * Connect needs VPN consent once, which only an activity can ask for; otherwise the widget
 * talks to the service directly.
 */
internal fun toggleAction(context: Context, status: VpnStatus, hasServer: Boolean): Action {
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    return when (status) {
        VpnStatus.STARTED, VpnStatus.STARTING -> actionStartService(OrbitVpnService.stopIntent(context))
        VpnStatus.STOPPING -> openApp
        VpnStatus.STOPPED ->
            if (!hasServer || VpnService.prepare(context) != null) {
                openApp
            } else {
                actionStartService(OrbitVpnService.startIntent(context), isForegroundService = true)
            }
    }
}

class OrbitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OrbitWidget()
}
