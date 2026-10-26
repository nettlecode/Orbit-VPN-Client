package app.orbit.vpn.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.orbit.vpn.OrbitApp
import app.orbit.vpn.R
import app.orbit.vpn.service.OrbitVpnService
import app.orbit.vpn.service.ServiceState
import app.orbit.vpn.service.VpnStatus
import app.orbit.vpn.ui.Flags
import app.orbit.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Quick Settings tile that toggles the VPN and shows the selected server. */
class VpnTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val repository = (application as OrbitApp).repository
        listening = scope.launch {
            combine(ServiceState.status, repository.state) { status, state -> status to state.selected?.name }
                .collect { (status, name) -> render(status, name) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun render(status: VpnStatus, serverName: String?) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            VpnStatus.STARTED, VpnStatus.STARTING -> Tile.STATE_ACTIVE
            VpnStatus.STOPPED, VpnStatus.STOPPING -> if (serverName == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                VpnStatus.STARTING -> getString(R.string.status_connecting)
                VpnStatus.STOPPING -> getString(R.string.status_disconnecting)
                else -> serverName?.let(Flags::stripFlag) ?: getString(R.string.no_server)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (ServiceState.status.value) {
            VpnStatus.STARTED, VpnStatus.STARTING -> OrbitVpnService.stop(this)
            VpnStatus.STOPPING -> Unit
            VpnStatus.STOPPED -> {
                // VPN consent can only be requested from an activity.
                if (VpnService.prepare(this) != null) {
                    openApp()
                } else {
                    unlockAndRun { OrbitVpnService.start(this) }
                }
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to call onStartListening so the tile reflects a state change. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, VpnTileService::class.java)) }
        }
    }
}
