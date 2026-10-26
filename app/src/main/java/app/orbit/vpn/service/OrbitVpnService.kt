package app.orbit.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.orbit.vpn.OrbitApp
import app.orbit.vpn.R
import app.orbit.vpn.config.ConfigBuilder
import app.orbit.vpn.config.LocalLink
import app.orbit.vpn.config.XrayConfigBuilder
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.ui.MainActivity
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.xraybridge.Xraybridge
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class OrbitVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private val worker = Executors.newSingleThreadExecutor()
    private val monitor by lazy { DefaultNetworkMonitor(this) }
    private val resolver by lazy { LocalResolver(monitor) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private var commandServer: CommandServer? = null
    private var statusClient: CommandClient? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var nodeName = ""
    private val statusHandler = StatusHandler()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                if (ServiceState.status.value == VpnStatus.STARTED) worker.execute(::reload)
                return START_NOT_STICKY
            }

            ACTION_READ_SYSTEM_VPN -> {
                if (ServiceState.status.value == VpnStatus.STARTED) readSystemVpn()
                return START_NOT_STICKY
            }
        }
        if (ServiceState.status.value != VpnStatus.STOPPED) return START_NOT_STICKY
        ServiceState.set(VpnStatus.STARTING)
        ServiceState.clearError()
        startForegroundCompat(getString(R.string.status_connecting))
        worker.execute(::startBox)
        return START_NOT_STICKY
    }

    private fun startBox() {
        try {
            File(filesDir, "box/box.log").delete()
            monitor.start()
            // Also used by latency tests while the tunnel is up.
            Xraybridge.setProtector { fd -> protect(fd) }
            val config = prepareConfig()
            val server = Libbox.newCommandServer(this, this)
            server.start()
            commandServer = server
            server.startOrReloadService(config, OverrideOptions())
            ServiceState.set(VpnStatus.STARTED)
            AutoConnect.setRunning(this, true)
            readSystemVpn()
            startForegroundCompat(getString(R.string.status_connected_to, nodeName))
            startStatusClient()
            ConnectionsMonitor.onServiceStarted()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            ServiceState.fail(e.message)
            // Don't retry a failing start after every reboot.
            AutoConnect.setRunning(this, false)
            worker.execute(::shutdown)
        }
    }

    /**
     * Builds the sing-box config for the selected node, or for auto-select. Nodes that need
     * Xray-core get it started on a local port first, and sing-box forwards to that port.
     */
    private fun prepareConfig(): String {
        val state = OrbitApp.instance.repository.state.value
        val options = ConfigBuilder.Options(
            mode = state.mode,
            remoteDns = state.remoteDns,
            ipv6 = state.ipv6,
            logFile = File(filesDir, "box/box.log").path,
            ruleSetDir = bundledRuleSet().parent,
            geoipUrl = state.geoipUrl,
            asnUrl = state.asnUrl,
            geoipUpdateHours = state.geoipUpdateHours,
            rules = OrbitApp.instance.repository.rules.value.active,
            userRules = state.userRules,
        )
        if (state.autoSelect && state.autoNodes.isNotEmpty()) return prepareAuto(state.autoNodes, options)
        ServiceState.auto(null)
        val node = state.selected ?: error(getString(R.string.error_no_server))
        nodeName = node.name
        val links = startXray(listOf(node).filter { it.requiresXray })
        if (node.requiresXray && node.id !in links) error(getString(R.string.error_resolve, node.server))
        return ConfigBuilder.build(node, options.copy(xrayLinks = links))
    }

    /**
     * Auto-select over [pool]. One invalid server would make the whole start fail, so servers that
     * sing-box or Xray reject, or whose name can't be resolved for Xray, are left out.
     */
    private fun prepareAuto(pool: List<ProxyNode>, base: ConfigBuilder.Options): String {
        nodeName = getString(R.string.auto_select)
        ServiceState.auto(AutoStatus())
        statusHandler.lastRecheck = SystemClock.elapsedRealtime()
        val links = runCatching { startXray(pool.filter { it.requiresXray }) }
            .onFailure { Log.w(TAG, "auto: Xray servers left out", it) }
            .getOrDefault(emptyMap())
        val options = base.copy(xrayLinks = links)
        var nodes = pool.filter { !it.requiresXray || it.id in links }
        var config = if (nodes.isEmpty()) null else ConfigBuilder.buildAuto(nodes, options)
        if (config != null && runCatching { Libbox.checkConfig(config) }.isFailure) {
            nodes = nodes.filter { node ->
                runCatching { Libbox.checkConfig(ConfigBuilder.buildCheck(node, options)) }
                    .onFailure { Log.w(TAG, "auto: ${node.name} left out", it) }
                    .isSuccess
            }
            config = if (nodes.isEmpty()) null else ConfigBuilder.buildAuto(nodes, options)
        }
        return config ?: error(getString(R.string.error_auto_no_servers))
    }

    /** Starts Xray for [nodes], or stops it when there are none. Returns their links by node id. */
    private fun startXray(nodes: List<ProxyNode>): Map<String, LocalLink> {
        if (nodes.isEmpty()) {
            stopXray()
            return emptyMap()
        }
        // Resolve over the physical network: once the tunnel is up, lookups would loop through it.
        val network = monitor.defaultNetwork
        val entries = nodes.mapNotNull { node ->
            val address = runCatching {
                (network?.getAllByName(node.server) ?: InetAddress.getAllByName(node.server))
                    .sortedBy { it is Inet6Address }
                    .first()
                    .hostAddress
            }.getOrNull() ?: return@mapNotNull null
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            XrayConfigBuilder.Entry(node, LocalLink.random(port), address)
        }
        if (entries.isEmpty()) {
            stopXray()
            return emptyMap()
        }
        Xraybridge.start(XrayConfigBuilder.buildMany(entries, logLevel = "warning"))
        return entries.associate { it.node.id to it.link }
    }

    private fun stopXray() {
        if (Xraybridge.isRunning()) runCatching { Xraybridge.stop() }
    }

    private fun bundledRuleSet(): File {
        val file = File(filesDir, "box/geoip-ru.srs")
        if (!file.exists()) {
            assets.open("geoip-ru.srs").use { input -> file.outputStream().use(input::copyTo) }
        }
        return file
    }

    private fun readSystemVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceState.systemVpn(SystemVpn(isAlwaysOn, isLockdownEnabled))
    }

    private fun startStatusClient() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            // Reports auto-select's choice and measurements; empty without a urltest group.
            addCommand(Libbox.CommandGroup)
            statusInterval = 1_000_000_000L
        }
        val client = Libbox.newCommandClient(statusHandler, options)
        runCatching { client.connect() }
            .onSuccess { statusClient = client }
            .onFailure { Log.w(TAG, "status client", it) }
    }

    private fun reload() {
        try {
            serviceReload()
            startForegroundCompat(getString(R.string.status_connected_to, nodeName))
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            ServiceState.fail(e.message)
        }
    }

    private fun stop() {
        val status = ServiceState.status.value
        if (status != VpnStatus.STARTED && status != VpnStatus.STARTING) return
        // Stopped on purpose (by the user, or another VPN took over): don't restore it later.
        AutoConnect.setRunning(this, false)
        ServiceState.set(VpnStatus.STOPPING)
        worker.execute(::shutdown)
    }

    private fun shutdown() {
        runCatching { statusClient?.disconnect() }
        statusClient = null
        ConnectionsMonitor.onServiceStopped()
        commandServer?.let { server ->
            runCatching { server.closeService() }.onFailure { Log.w(TAG, "close service", it) }
            runCatching { server.close() }
        }
        commandServer = null
        stopXray()
        Xraybridge.setProtector(null)
        OrbitApp.instance.trafficHistory.save()
        runCatching { tunFd?.close() }
        tunFd = null
        monitor.stop()
        ServiceState.set(VpnStatus.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() = stop()

    override fun onDestroy() {
        if (ServiceState.status.value != VpnStatus.STOPPED) shutdown()
        worker.shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, stopIntent(this), flags)
        return NotificationCompat.Builder(this, OrbitApp.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_disconnect), stop)
            .build()
    }

    // region CommandServerHandler

    override fun serviceStop() = stop()

    override fun serviceReload() {
        val server = commandServer ?: return
        server.startOrReloadService(prepareConfig(), OverrideOptions())
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String) {
        Log.d(TAG, message)
    }

    override fun connectSSHAgent(): Int = error("not supported")

    // endregion

    // region PlatformInterface

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("missing VPN permission")
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        options.inet4Address.forEach { builder.addAddress(it.address(), it.prefix()) }
        options.inet6Address.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            options.dnsServerAddress.forEach { builder.addDnsServer(it) }
            // Route ranges are pre-computed by sing-box with excludes already subtracted.
            val inet4Routes = options.inet4RouteRange.toList()
            if (inet4Routes.isEmpty()) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                inet4Routes.forEach { builder.addRoute(it.address(), it.prefix()) }
            }
            val inet6Routes = options.inet6RouteRange.toList()
            if (inet6Routes.isNotEmpty()) {
                inet6Routes.forEach { builder.addRoute(it.address(), it.prefix()) }
            } else if (options.inet6Address.hasNext()) {
                builder.addRoute("::", 0)
            }

            val include = options.includePackage.toList()
            val exclude = options.excludePackage.toList()
            try {
                if (include.isNotEmpty()) {
                    include.forEach(builder::addAllowedApplication)
                } else {
                    exclude.forEach(builder::addDisallowedApplication)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "per-app routing", e)
            }
        }

        val pfd = builder.establish() ?: error("VPN permission was revoked")
        tunFd = pfd
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("not supported")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("connection owner not found")
        val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = connectivity.allNetworks.mapNotNull { network ->
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val iface = javaInterfaces.find { it.name == link.interfaceName } ?: return@mapNotNull null
            BoxNetworkInterface().apply {
                name = link.interfaceName
                index = iface.index
                runCatching { mtu = iface.mtu }
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
                gateway = StringArray(
                    link.routes.filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }
                        .mapNotNull { it.hostAddress },
                )
                addresses = StringArray(
                    iface.interfaceAddresses.map {
                        val host = if (it.address is Inet6Address) {
                            Inet6Address.getByAddress(it.address.address).hostAddress
                        } else {
                            it.address.hostAddress
                        }
                        "$host/${it.networkPrefixLength}"
                    },
                )
                var ifFlags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    ifFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (iface.isLoopback) ifFlags = ifFlags or OsConstants.IFF_LOOPBACK
                if (iface.isPointToPoint) ifFlags = ifFlags or OsConstants.IFF_POINTOPOINT
                if (iface.supportsMulticast()) ifFlags = ifFlags or OsConstants.IFF_MULTICAST
                flags = ifFlags
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    @Suppress("DEPRECATION")
    override fun readWIFIState(): WIFIState? {
        val info = getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        val ssid = info.ssid.takeUnless { it == WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"") ?: ""
        return WIFIState(ssid, info.bssid ?: "")
    }

    override fun clearDNSCache() = Unit

    override fun localDNSTransport(): LocalDNSTransport = resolver

    override fun sendNotification(notification: BoxNotification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun registerMyInterface(name: String) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = error("not supported")

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")

    override fun lookupSFTPServer(): String = error("not supported")

    override fun readSystemSSHHostKey(): String = error("not supported")

    override fun tailscaleHostname(): String = Build.MODEL

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")

    // endregion

    private inner class StatusHandler : CommandClientHandler {
        private var lastUp = 0L
        private var lastDown = 0L

        override fun writeStatus(message: StatusMessage) {
            if (!message.trafficAvailable) return
            // Totals restart from zero when the core reloads.
            val up = (message.uplinkTotal - lastUp).let { if (it < 0) message.uplinkTotal else it }
            val down = (message.downlinkTotal - lastDown).let { if (it < 0) message.downlinkTotal else it }
            lastUp = message.uplinkTotal
            lastDown = message.downlinkTotal
            OrbitApp.instance.trafficHistory.record(up, down)
            ServiceState.traffic(
                Traffic(message.uplink, message.downlink, message.uplinkTotal, message.downlinkTotal),
            )
        }

        /** Starts at the tunnel start, when sing-box measures the servers itself. */
        var lastRecheck = 0L

        private fun recheckAuto() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRecheck < AUTO_RECHECK_MS) return
            lastRecheck = now
            val client = statusClient ?: return
            // Not on the callback thread, which delivers the results.
            worker.execute { runCatching { client.urlTest(ConfigBuilder.PROXY_TAG) }.onFailure { Log.w(TAG, "auto recheck", it) } }
        }

        override fun connected() = Unit
        override fun disconnected(message: String?) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) {
            val groups = message ?: return
            while (groups.hasNext()) {
                val group = groups.next()
                if (group.tag != ConfigBuilder.PROXY_TAG || group.type != "urltest") continue
                val delays = buildMap {
                    val items = group.items
                    while (items.hasNext()) {
                        val item = items.next()
                        if (item.urlTestDelay > 0) put(item.tag, item.urlTestDelay)
                    }
                }
                val previous = ServiceState.auto.value
                // Before the first measurement the group just reports its first server.
                val measured = delays.isNotEmpty() || previous?.selectedId != null
                val status = AutoStatus(group.selected.takeIf { measured }, delays)
                // sing-box forgets a server's delay when a connection through it fails, but keeps
                // using it until the next scheduled check; re-measure now so it switches. This also
                // covers the unmeasured first server the group falls back to.
                if (group.selected !in delays) recheckAuto()
                if (ServiceState.auto.value?.selectedId != status.selectedId) {
                    val node = OrbitApp.instance.repository.state.value.nodes.firstOrNull { it.id == status.selectedId }
                    nodeName = listOfNotNull(getString(R.string.auto_select), node?.name).joinToString(" · ")
                    if (ServiceState.status.value == VpnStatus.STARTED) {
                        startForegroundCompat(getString(R.string.status_connected_to, nodeName))
                    }
                }
                ServiceState.auto(status)
            }
        }
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
    }

    companion object {
        private const val TAG = "OrbitVpnService"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_RECHECK_MS = 15_000L
        private const val ACTION_STOP = "app.orbit.vpn.STOP"
        private const val ACTION_RELOAD = "app.orbit.vpn.RELOAD"
        private const val ACTION_READ_SYSTEM_VPN = "app.orbit.vpn.READ_SYSTEM_VPN"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, startIntent(context))
        }

        fun startIntent(context: Context) = Intent(context, OrbitVpnService::class.java)

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        /** Applies the currently selected server and mode to a running tunnel. */
        fun reload(context: Context) {
            if (ServiceState.status.value != VpnStatus.STARTED) return
            context.startService(Intent(context, OrbitVpnService::class.java).setAction(ACTION_RELOAD))
        }

        /** Re-reads the always-on settings, which the user may have changed in the system settings. */
        fun readSystemVpn(context: Context) {
            if (ServiceState.status.value != VpnStatus.STARTED) return
            context.startService(Intent(context, OrbitVpnService::class.java).setAction(ACTION_READ_SYSTEM_VPN))
        }

        fun stopIntent(context: Context) =
            Intent(context, OrbitVpnService::class.java).setAction(ACTION_STOP)
    }
}
