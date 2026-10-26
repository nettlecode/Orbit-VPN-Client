package app.orbit.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import app.orbit.vpn.OrbitApp

/**
 * Remembers whether the tunnel should be up, so it can be restored after a reboot or an app
 * update (both end the process without the user stopping the VPN).
 */
object AutoConnect {
    private const val PREFS = "service"
    private const val KEY_RUNNING = "running"

    fun setRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun wasRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
}

/** Reconnects after a reboot or an app update if the VPN was on and the setting allows it. */
class AutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val state = (context.applicationContext as OrbitApp).repository.state.value
        if (!state.restoreConnection || !AutoConnect.wasRunning(context) || state.nodes.isEmpty()) return
        // Consent can only be asked for in an activity; without it there is nothing to restore.
        if (VpnService.prepare(context) != null) return
        Log.i("AutoConnect", "restoring the connection after ${intent.action}")
        OrbitVpnService.start(context)
    }
}
