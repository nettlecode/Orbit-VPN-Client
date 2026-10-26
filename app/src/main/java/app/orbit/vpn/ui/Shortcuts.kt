package app.orbit.vpn.ui

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import app.orbit.vpn.R
import app.orbit.vpn.widget.OrbitWidgetReceiver
import app.orbit.vpn.widget.VpnTileService

/** System prompts that add the widget to the home screen and the tile to Quick Settings. */
object Shortcuts {
    val canAddTile: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun pinWidget(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(context, OrbitWidgetReceiver::class.java), null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestTile(context: Context) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, VpnTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_notification),
            context.mainExecutor,
        ) {}
    }

    fun addTile(context: Context) {
        if (canAddTile) requestTile(context)
    }
}
