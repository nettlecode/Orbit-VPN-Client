package app.orbit.vpn

import app.orbit.vpn.data.ServerSort
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.ProxyType
import app.orbit.vpn.ui.Latency
import app.orbit.vpn.ui.arranged
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSortTest {
    private val nodes = listOf("c", "a", "d", "b", "e").map {
        ProxyNode(id = it, name = "🇩🇪 ${it.uppercase()}", type = ProxyType.SHADOWSOCKS, server = "s", port = 1)
    }
    private val latency = mapOf(
        "a" to Latency.Ok(300),
        "b" to Latency.Failed,
        "c" to Latency.Ok(120),
        "e" to Latency.Testing,
    )

    private fun List<ProxyNode>.ids() = joinToString("") { it.id }

    @Test
    fun keepsOrderByDefault() {
        assertEquals("cadbe", nodes.arranged(ServerSort.DEFAULT, latency, hideFailed = false).ids())
    }

    @Test
    fun sortsByLatencyWithFailedLast() {
        assertEquals("cadeb", nodes.arranged(ServerSort.LATENCY, latency, hideFailed = false).ids())
    }

    @Test
    fun sortsByNameIgnoringFlag() {
        assertEquals("abcde", nodes.arranged(ServerSort.NAME, latency, hideFailed = false).ids())
    }

    @Test
    fun hidesFailedExceptActive() {
        assertEquals("cade", nodes.arranged(ServerSort.DEFAULT, latency, hideFailed = true).ids())
        assertEquals("cadbe", nodes.arranged(ServerSort.DEFAULT, latency, hideFailed = true, keep = setOf("b")).ids())
    }
}