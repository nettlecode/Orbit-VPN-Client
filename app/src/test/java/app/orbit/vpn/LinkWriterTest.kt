package app.orbit.vpn

import app.orbit.vpn.model.*
import app.orbit.vpn.parser.LinkParser
import app.orbit.vpn.parser.LinkWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkWriterTest {
    private val uuid = "12345678-1234-1234-1234-123456789abc"
    private val tls = TlsSettings("tls.example", listOf("h2", "http/1.1"), true, "chrome")
    private fun roundTrip(node: ProxyNode) {
        val parsed = LinkParser.parse(LinkWriter.write(node))
        assertEquals(node.copy(id = parsed.id, link = parsed.link), parsed)
    }

    @Test fun allProtocolsWithEscapedCredentialsAndIpv6() {
        ProxyType.entries.forEach { type ->
            val base = ProxyNode(name = "Узел + # &", type = type, server = "2001:db8::1", port = 443)
            roundTrip(when (type) {
                ProxyType.SHADOWSOCKS -> base.copy(method = "aes-256-gcm", password = "p+:&@# /", plugin = "v2ray-plugin", pluginOpts = "tls;host=example.org")
                ProxyType.VMESS -> base.copy(uuid = uuid, method = "auto", alterId = 4, tls = tls, transport = TransportSettings("ws", "/a?b=1", "host.example"))
                ProxyType.VLESS -> base.copy(uuid = uuid, flow = "xtls-rprx-vision", tls = tls.copy(realityPublicKey = "a".repeat(43), realityShortId = "abcd"))
                ProxyType.TROJAN -> base.copy(password = "p+:&@# /", tls = tls)
                ProxyType.HYSTERIA2 -> base.copy(password = "p+:&@# /", tls = tls.copy(fingerprint = null), obfsPassword = "o+& #")
                ProxyType.TUIC -> base.copy(uuid = uuid, password = "p+:&@# /", tls = tls.copy(fingerprint = null), congestionControl = "bbr", udpRelayMode = "native")
                ProxyType.SOCKS -> base.copy(username = "user+&@", password = "p+:&@# /")
                ProxyType.HTTP -> base.copy(username = "user+&@", password = "p+:&@# /", tls = tls)
            })
        }
    }

    @Test fun emptySniAndAlpnDoNotAcquireParserDefaults() {
        for (type in listOf(ProxyType.VMESS, ProxyType.VLESS, ProxyType.TUIC, ProxyType.HTTP)) {
            roundTrip(ProxyNode(name = "Test", type = type, server = "example.org", port = 443,
                uuid = if (type != ProxyType.HTTP) uuid else null,
                password = if (type == ProxyType.TUIC) "secret" else null,
                method = if (type == ProxyType.VMESS) "auto" else null,
                tls = TlsSettings(),
                transport = if (type in listOf(ProxyType.VMESS, ProxyType.VLESS)) TransportSettings("ws", host = "different.example") else null))
        }
    }

    @Test fun transportVariantsAndXhttpExtra() {
        for (type in listOf(ProxyType.VMESS, ProxyType.VLESS, ProxyType.TROJAN)) {
            for (transport in listOf(null, TransportSettings("grpc", serviceName = "svc+&"), TransportSettings("http", path = "/h", host = "host"), TransportSettings("httpupgrade", path = "/up", host = "host"), TransportSettings("xhttp", path = "/x", host = "host", mode = "auto", extra = "{\"xPaddingBytes\":\"100-200\"}"))) {
                roundTrip(ProxyNode(name = "Test", type = type, server = "example.org", port = 443,
                    uuid = if (type != ProxyType.TROJAN) uuid else null,
                    password = if (type == ProxyType.TROJAN) "secret" else null,
                    method = if (type == ProxyType.VMESS) "auto" else null,
                    tls = if (type == ProxyType.TROJAN) TlsSettings() else null, transport = transport))
            }
        }
    }
}
