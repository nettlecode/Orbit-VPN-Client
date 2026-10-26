package app.orbit.vpn.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import app.orbit.vpn.config.ConfigBuilder
import app.orbit.vpn.config.LocalLink
import app.orbit.vpn.config.XrayConfigBuilder
import app.orbit.vpn.model.ProxyNode
import io.nekohasekai.xraybridge.Xraybridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket

sealed interface Latency {
    data object Testing : Latency
    data class Ok(val millis: Long) : Latency
    data object Failed : Latency
}

object LatencyTester {
    private const val TAG = "LatencyTester"
    private const val TEST_URL = "https://www.gstatic.com/generate_204"
    private const val TIMEOUT_MS = 5000

    private val IP_LITERAL = Regex("""^[0-9.]+$|:""")

    /**
     * Real delay through each server: an HTTPS request sent via a temporary core instance.
     * Server names are resolved over the physical network and the core's sockets are protected,
     * so results are the same whether or not the tunnel is up. Blocking, call off the main thread.
     */
    fun test(context: Context, nodes: List<ProxyNode>): Map<String, Latency> {
        if (nodes.isEmpty()) return emptyMap()
        val network = physicalNetwork(context)
        val addresses = nodes.map { it.server }.distinct().filterNot { IP_LITERAL.containsMatchIn(it) }
            .mapNotNull { host -> resolve(network, host)?.let { host to it } }
            .toMap()
        val testable = nodes.filter { IP_LITERAL.containsMatchIn(it.server) || it.server in addresses }
        val delays = measure(testable, addresses)
        return nodes.associate { node ->
            val delay = delays[node.id] ?: -1
            node.id to if (delay > 0) Latency.Ok(delay.toLong()) else Latency.Failed
        }
    }

    /**
     * Delays in ms (-1 on failure) by node id. One invalid node makes the whole core instance fail
     * to start, so a failed batch is split in halves until the culprit is isolated; config errors
     * surface immediately, so this costs little.
     */
    private fun measure(nodes: List<ProxyNode>, addresses: Map<String, List<String>>): Map<String, Int> {
        if (nodes.isEmpty()) return emptyMap()
        val xrayEntries = nodes.filter { it.requiresXray }.map { node ->
            XrayConfigBuilder.TestEntry(node, LocalLink.random(freePort()), addresses[node.server]?.first() ?: node.server)
        }
        val result = runCatching {
            Xraybridge.urlTest(
                ConfigBuilder.buildUrlTest(nodes, addresses, xrayEntries.associate { it.node.id to it.link }),
                if (xrayEntries.isEmpty()) "" else XrayConfigBuilder.buildUrlTest(xrayEntries),
                nodes.joinToString(",") { it.id },
                TEST_URL,
                TIMEOUT_MS,
            )
        }
        result.getOrNull()?.let { json ->
            return (Json.parseToJsonElement(json) as JsonObject).mapValues { (id, value) ->
                // A number is the delay, a string the reason the request failed.
                value.jsonPrimitive.intOrNull ?: -1.also { Log.i(TAG, "$id: ${value.jsonPrimitive.content}") }
            }
        }
        if (nodes.size == 1) {
            Log.w(TAG, "latency test failed for ${nodes[0].name}", result.exceptionOrNull())
            return emptyMap()
        }
        val half = nodes.size / 2
        return measure(nodes.subList(0, half), addresses) + measure(nodes.subList(half, nodes.size), addresses)
    }

    /** IPv4 first: many servers and networks have no working IPv6. */
    private fun resolve(network: Network?, host: String): List<String>? = runCatching {
        (network?.getAllByName(host) ?: InetAddress.getAllByName(host))
            .sortedBy { it is Inet6Address }
            .mapNotNull { it.hostAddress }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun physicalNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}
