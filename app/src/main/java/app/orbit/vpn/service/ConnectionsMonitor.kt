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
)

/**
 * Streams the core's connection list while at least one screen needs it.
 * Screens call [acquire] / [release]; the command client is connected only in between.
 */
object ConnectionsMonitor {
    private const val TAG = "ConnectionsMonitor"

    private val _connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    /** Active connections, newest first (DNS lookups excluded). */
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

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
        if (--users == 0) worker.execute(::disconnect)
    }

    /** Called by the service when the tunnel (re)starts, so a waiting screen gets connected. */
    @Synchronized
    fun onServiceStarted() {
        if (users > 0) worker.execute { disconnect(); connect() }
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
        _connections.value = all
            .filter { it.outboundType != "dns" && it.closedAt == 0L }
            .sortedByDescending { it.createdAt }
            .map { connection ->
                val packages = connection.processInfo?.packageNames()?.toList().orEmpty()
                ConnectionInfo(
                    id = connection.id,
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
    }
}
