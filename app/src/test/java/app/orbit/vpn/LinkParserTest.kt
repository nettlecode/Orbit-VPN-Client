package app.orbit.vpn

import app.orbit.vpn.model.ProxyType
import app.orbit.vpn.parser.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

class LinkParserTest {

    @Test
    fun shadowsocksSip002() {
        val userInfo = Base64.UrlSafe.encode("chacha20-ietf-poly1305:secret".toByteArray()).trimEnd('=')
        val node = LinkParser.parse("ss://$userInfo@1.2.3.4:8388#My%20SS")
        assertEquals(ProxyType.SHADOWSOCKS, node.type)
        assertEquals("1.2.3.4", node.server)
        assertEquals(8388, node.port)
        assertEquals("chacha20-ietf-poly1305", node.method)
        assertEquals("secret", node.password)
        assertEquals("My SS", node.name)
    }

    @Test
    fun shadowsocks2022PlainUserInfo() {
        val node = LinkParser.parse("ss://2022-blake3-aes-128-gcm:YctPZ6U7xPPcU%2Bgp3u%2B0tx%2F@example.com:443#ss22")
        assertEquals("2022-blake3-aes-128-gcm", node.method)
        assertEquals("YctPZ6U7xPPcU+gp3u+0tx/", node.password)
    }

    @Test
    fun shadowsocksLegacy() {
        val body = Base64.Default.encode("aes-256-gcm:pass@example.org:443".toByteArray())
        val node = LinkParser.parse("ss://$body#legacy")
        assertEquals("example.org", node.server)
        assertEquals(443, node.port)
        assertEquals("aes-256-gcm", node.method)
        assertEquals("pass", node.password)
    }

    @Test
    fun shadowsocksPlugin() {
        val userInfo = Base64.Default.encode("aes-128-gcm:pw".toByteArray())
        val node = LinkParser.parse("ss://$userInfo@h.example:80/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#p")
        assertEquals("obfs-local", node.plugin)
        assertEquals("obfs=http;obfs-host=bing.com", node.pluginOpts)
    }

    @Test
    fun vmess() {
        val json = """{"v":"2","ps":"VM","add":"vm.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"vm.example.com"}"""
        val node = LinkParser.parse("vmess://" + Base64.Default.encode(json.toByteArray()))
        assertEquals(ProxyType.VMESS, node.type)
        assertEquals("VM", node.name)
        assertEquals(443, node.port)
        assertEquals("ws", node.transport?.type)
        assertEquals("/ws", node.transport?.path)
        assertEquals("cdn.example.com", node.transport?.host)
        assertEquals("vm.example.com", node.tls?.serverName)
    }

    @Test
    fun vlessReality() {
        val node = LinkParser.parse(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.5:443?encryption=none&flow=xtls-rprx-vision" +
                "&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc" +
                "&sid=6ba85179e30d4fc2&type=tcp#%F0%9F%87%A9%F0%9F%87%AA%20Germany",
        )
        assertEquals(ProxyType.VLESS, node.type)
        assertEquals("xtls-rprx-vision", node.flow)
        assertEquals("www.microsoft.com", node.tls?.serverName)
        assertEquals("chrome", node.tls?.fingerprint)
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", node.tls?.realityPublicKey)
        assertEquals("6ba85179e30d4fc2", node.tls?.realityShortId)
        assertEquals("🇩🇪 Germany", node.name)
        assertNull(node.transport)
    }

    @Test
    fun vlessGrpcIpv6() {
        val node = LinkParser.parse("vless://uuid@[2001:db8::1]:8443?security=tls&type=grpc&serviceName=svc#g")
        assertEquals("2001:db8::1", node.server)
        assertEquals(8443, node.port)
        assertEquals("grpc", node.transport?.type)
        assertEquals("svc", node.transport?.serviceName)
        assertTrue(node.tls != null)
    }

    @Test
    fun vlessXhttp() {
        val node = LinkParser.parse(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@x.example.com:443?encryption=none&extra=" +
                "%7B%22mode%22%3A%22stream-up%22%7D&fp=random&host=&mode=stream-up&path=%2Fxh" +
                "&security=tls&type=xhttp&x_padding_bytes=100-1000#xh",
        )
        assertTrue(node.requiresXray)
        assertEquals("xhttp", node.transport?.type)
        assertEquals("/xh", node.transport?.path)
        assertNull(node.transport?.host)
        assertEquals("stream-up", node.transport?.mode)
        assertEquals("""{"mode":"stream-up","xPaddingBytes":"100-1000"}""", node.transport?.extra)
        assertNull(node.tls?.serverName)
        assertEquals("random", node.tls?.fingerprint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTransportIsRejected() {
        LinkParser.parse("vless://uuid@h.example.com:443?security=tls&type=kcp#k")
    }

    @Test
    fun trojanDefaultsToTls() {
        val node = LinkParser.parse("trojan://p%40ss@t.example.com:443?sni=t.example.com#tr")
        assertEquals(ProxyType.TROJAN, node.type)
        assertEquals("p@ss", node.password)
        assertEquals("t.example.com", node.tls?.serverName)
    }

    @Test
    fun hysteria2() {
        val node = LinkParser.parse("hy2://letmein@hy.example.com:8443/?sni=hy.example.com&obfs=salamander&obfs-password=ob&insecure=1#hy")
        assertEquals(ProxyType.HYSTERIA2, node.type)
        assertEquals("letmein", node.password)
        assertEquals("ob", node.obfsPassword)
        assertTrue(node.tls!!.insecure)
    }

    @Test
    fun tuic() {
        val node = LinkParser.parse("tuic://uuid-1:pw@tu.example.com:443?congestion_control=bbr&udp_relay_mode=native&sni=tu.example.com&alpn=h3#tu")
        assertEquals("uuid-1", node.uuid)
        assertEquals("pw", node.password)
        assertEquals("bbr", node.congestionControl)
        assertEquals(listOf("h3"), node.tls?.alpn)
    }

    @Test
    fun socksWithBase64Credentials() {
        val creds = Base64.Default.encode("user:pass".toByteArray())
        val node = LinkParser.parse("socks://$creds@10.0.0.1:1080#s")
        assertEquals("user", node.username)
        assertEquals("pass", node.password)
    }

    @Test
    fun base64Subscription() {
        val body = listOf(
            "trojan://pw@a.example.com:443#A",
            "not a link",
            "hy2://pw@b.example.com:443#B",
        ).joinToString("\n")
        val nodes = LinkParser.parseAll(Base64.Default.encode(body.toByteArray()))
        assertEquals(listOf("A", "B"), nodes.map { it.name })
    }
}
