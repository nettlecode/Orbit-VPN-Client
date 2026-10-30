package app.orbit.vpn.service

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Snapshot of one tunnelled connection for the UI. */
data class ConnectionInfo(
    val id: String,
    val network: String,
    val destination: String,
    val domain: String,
    val outbound: String,
    val outboundType: String,
    val rule: String,
    val packageName: String?,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val createdAt: Long,
    val closed: Boolean,
    val source: String = "",
    val chain: List<String> = emptyList(),
)

/**
 * Streams connections throughout the VPN session so the journal includes short-lived traffic.
 * Screens call [acquire] / [release] to also reconnect when opened during service startup.
 */
object ConnectionsMonitor {
    private const val TAG = "ConnectionsMonitor"

    private val _connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    /** Active connections, newest first (DNS lookups excluded). */
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    private val _observed = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    val observed: StateFlow<List<ConnectionInfo>> = _observed.asStateFlow()

    private val _seenTotal = MutableStateFlow(0)
    /** Distinct connections seen since monitoring started. Hijacked DNS lookups are not tracked by the core. */
    val seenTotal: StateFlow<Int> = _seenTotal.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor()
    private var users = 0
    private var client: CommandClient? = null
    private var store = Connections()
    private val seen = HashSet<String>()

    @Synchronized
    fun acquire() {
        if (users++ == 0) worker.execute(::connect)
    }

    @Synchronized
    fun release() {
        if (--users == 0 && ServiceState.status.value != VpnStatus.STARTED) worker.execute(::disconnect)
    }

    /** Called by the service when the tunnel (re)starts, so a waiting screen gets connected. */
    @Synchronized
    fun onServiceStarted() {
        worker.execute { disconnect(); connect() }
    }

    fun onServiceStopped() = worker.execute(::disconnect)

    fun close(id: String) = worker.execute { runCatching { client?.closeConnection(id) } }

    fun closeAll() = worker.execute { runCatching { client?.closeConnections() } }

    private fun connect() {
        if (client != null || ServiceState.status.value != VpnStatus.STARTED) return
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandConnections)
            statusInterval = 1_000_000_000L
        }
        val newClient = Libbox.newCommandClient(Handler(), options)
        runCatching { newClient.connect() }
            .onSuccess { client = newClient }
            .onFailure { Log.w(TAG, "connect", it) }
    }

    private fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
        store = Connections()
        seen.clear()
        _observed.value = emptyList()
        _connections.value = emptyList()
        _seenTotal.value = 0
    }

    private class Handler : CommandClientHandler {
        override fun writeConnectionEvents(events: ConnectionEvents) {
            worker.execute { apply(events) }
        }

        override fun connected() = Unit
        override fun disconnected(message: String?) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
        override fun writeStatus(message: StatusMessage?) = Unit
    }

    private fun apply(events: ConnectionEvents) {
        store.applyEvents(events)
        store.filterState(Libbox.ConnectionStateAll.toInt())
        val all = buildList {
            val iterator = store.iterator()
            while (iterator.hasNext()) add(iterator.next())
        }
        all.filter { it.outboundType != "dns" }.forEach { seen.add(it.id) }
        _seenTotal.value = seen.size
        _observed.value = all
            .filter { it.outboundType != "dns" }
            .sortedByDescending { it.createdAt }
            .map { connection ->
                val packages = connection.processInfo?.packageNames()?.toList().orEmpty()
                ConnectionInfo(
                    id = connection.id,
                    source = connection.source,
                    chain = connection.chain()?.toList().orEmpty(),
                    network = connection.network,
                    destination = connection.destination,
                    domain = connection.domain,
                    outbound = connection.outbound,
                    outboundType = connection.outboundType,
                    rule = connection.rule,
                    packageName = packages.firstOrNull(),
                    uplinkTotal = connection.uplinkTotal,
                    downlinkTotal = connection.downlinkTotal,
                    createdAt = connection.createdAt,
                    closed = connection.closedAt != 0L,
                )
            }
        _connections.value = _observed.value.filterNot { it.closed }
    }
}
