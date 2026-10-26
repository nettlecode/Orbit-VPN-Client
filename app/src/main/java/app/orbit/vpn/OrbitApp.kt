package app.orbit.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.orbit.vpn.data.NodeRepository
import app.orbit.vpn.data.SubscriptionUpdater
import app.orbit.vpn.data.TrafficHistory
import app.orbit.vpn.service.ServiceState
import app.orbit.vpn.widget.OrbitWidget
import app.orbit.vpn.widget.VpnTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class OrbitApp : Application() {
    lateinit var repository: NodeRepository
        private set
    lateinit var trafficHistory: TrafficHistory
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = NodeRepository(this)
        trafficHistory = TrafficHistory(this)
        setupLibbox()
        createNotificationChannel()
        SubscriptionUpdater.schedule(this, repository.state.value.autoUpdateSubscriptions)
        observeForWidgets()
    }

    /** Keeps the home screen widget and the Quick Settings tile in sync with the VPN. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun observeForWidgets() {
        GlobalScope.launch(Dispatchers.Default) {
            combine(ServiceState.status, repository.state.map { it.selected?.let { node -> node.id to node.name } }) { a, b -> a to b }
                .distinctUntilChanged()
                .collect {
                    VpnTileService.refresh(this@OrbitApp)
                    runCatching { OrbitWidget.refresh(this@OrbitApp) }
                }
        }
    }

    private fun setupLibbox() {
        val workingDir = File(filesDir, "box").apply { mkdirs() }
        val tempDir = File(cacheDir, "box").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().apply {
                basePath = filesDir.path
                workingPath = workingDir.path
                tempPath = tempDir.path
                // Works around https://github.com/golang/go/issues/68760
                fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ||
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1
                logMaxLines = 300
                crashReportSource = "app"
                appVersion = BuildConfig.VERSION_NAME
            },
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL = "vpn"

        lateinit var instance: OrbitApp
            private set
    }
}
