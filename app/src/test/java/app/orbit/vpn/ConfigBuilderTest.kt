package app.orbit.vpn

import app.orbit.vpn.config.ConfigBuilder
import app.orbit.vpn.config.LocalLink
import app.orbit.vpn.config.XrayConfigBuilder
import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingMode
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.parser.LinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.encoding.Base64

/**
 * Generates configs for every protocol. They are also written to
 * build/test-configs so they can be validated with `sing-box check`.
 */
class ConfigBuilderTest {
    private val links = mapOf(
        "ss" to "ss://" + Base64.Default.encode("aes-256-gcm:pw".toByteArray()) + "@1.2.3.4:8388#ss",
        "ss-obfs" to "ss://" + Base64.Default.encode("aes-128-gcm:pw".toByteArray()) +
            "@1.2.3.4:80/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#p",
        "vmess-ws-tls" to "vmess://" + Base64.Default.encode(
            """{"v":"2","ps":"VM","add":"vm.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"ws","host":"cdn.example.com","path":"/ws","tls":"tls"}""".toByteArray(),
        ),
        "vless-reality" to "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.5:443?flow=xtls-rprx-vision" +
            "&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2#r",
        "vless-grpc" to "vless://b831381d-6324-4d53-ad4f-8cda48b30811@g.example.com:443?security=tls&type=grpc&serviceName=svc#g",
        "trojan-httpupgrade" to "trojan://pw@t.example.com:443?type=httpupgrade&path=%2Fup&host=t.example.com#t",
        "hy2" to "hy2://pw@hy.example.com:8443?sni=hy.example.com&obfs=salamander&obfs-password=ob&insecure=1#hy",
        "tuic" to "tuic://b831381d-6324-4d53-ad4f-8cda48b30811:pw@tu.example.com:443?congestion_control=bbr#tu",
        "socks" to "socks5://u:p@10.0.0.1:1080#s",
        "https" to "https://u:p@h.example.com:443#h",
    )

    @Test
    fun generatesConfigsForAllProtocols() {
        val outDir = File("build/test-configs").apply { mkdirs() }
        for ((name, link) in links) {
            val node = LinkParser.parse(link)
            for (mode in RoutingMode.entries) {
                val config = ConfigBuilder.build(node, ConfigBuilder.Options(mode = mode))
                File(outDir, "$name-${mode.name.lowercase()}.json").writeText(config)
            }
        }
        val node = LinkParser.parse(links.getValue("ss"))
        val dnsVariants = listOf(
            "https://1.1.1.1/dns-query", "tls://8.8.8.8", "quic://dns.adguard-dns.com", "h3://1.1.1.1/dns-query",
            "udp://8.8.8.8", "tcp://[2606:4700:4700::1111]:53", "9.9.9.9",
        )
        dnsVariants.forEachIndexed { index, dns ->
            val config = ConfigBuilder.build(node, ConfigBuilder.Options(remoteDns = dns))
            File(outDir, "dns-$index.json").writeText(config)
        }
    }

    /** Written to build/test-configs/xray-*.json for `xray run -test`. */
    @Test
    fun generatesXrayConfigs() {
        val outDir = File("build/test-configs").apply { mkdirs() }
        val xrayLinks = mapOf(
            "vless-xhttp-tls" to "vless://b831381d-6324-4d53-ad4f-8cda48b30811@x.example.com:443?encryption=none" +
                "&extra=%7B%22mode%22%3A%22stream-up%22%7D&fp=random&mode=stream-up&path=%2Fxh&security=tls" +
                "&type=xhttp&x_padding_bytes=100-1000#xh",
            "vless-xhttp-reality" to "vless://b831381d-6324-4d53-ad4f-8cda48b30811@x.example.com:443?security=reality" +
                "&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2" +
                "&type=xhttp&path=%2F#r",
            "trojan-xhttp" to "trojan://pw@t.example.com:443?type=xhttp&path=%2Ft&mode=packet-up#t",
            "vmess-xhttp" to "vmess://" + Base64.Default.encode(
                """{"v":"2","ps":"VM","add":"vm.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"xhttp","path":"/v","tls":"tls"}""".toByteArray(),
            ),
        )
        val localLink = LocalLink.random(10808)
        for ((name, link) in xrayLinks) {
            val node = LinkParser.parse(link)
            File(outDir, "xray-$name.json").writeText(XrayConfigBuilder.build(node, localLink, "203.0.113.10"))
            val singBox = ConfigBuilder.build(node, ConfigBuilder.Options(xrayLink = localLink))
            File(outDir, "via-xray-$name.json").writeText(singBox)
            val outbound = Json.parseToJsonElement(singBox).jsonObject.getValue("outbounds").jsonArray[0].jsonObject
            assertEquals("shadowsocks", outbound.getValue("type").jsonPrimitive.content)
        }
    }

    @Test
    fun userRulesComeFirstAndRespectEnabled() {
        val node = LinkParser.parse(links.getValue("ss"))
        val userRules = listOf(
            RoutingRule(Policy.DIRECT, packages = listOf("ru.sberbankmobile", "com.whatsapp"), id = "apps"),
            RoutingRule(Policy.REJECT, suffixes = listOf("ads.example.com"), id = "ads"),
            RoutingRule(Policy.PROXY, ipCidrs = listOf("149.154.160.0/20"), id = "tg", enabled = false),
            RoutingRule(Policy.PROXY, keywords = listOf("google"), geoip = null, id = "kw"),
        )
        val outDir = File("build/test-configs").apply { mkdirs() }
        for (mode in RoutingMode.entries) {
            val config = ConfigBuilder.build(node, ConfigBuilder.Options(mode = mode, userRules = userRules))
            File(outDir, "user-rules-${mode.name.lowercase()}.json").writeText(config)
            val routeRules = Json.parseToJsonElement(config).jsonObject.getValue("route").jsonObject.getValue("rules").jsonArray
            val text = routeRules.toString()
            assertEquals(mode != RoutingMode.DIRECT, "ru.sberbankmobile" in text)
            assertTrue("149.154.160.0/20" !in text) // disabled
            assertEquals(mode == RoutingMode.RULE, "geoip-ru" in text)
        }
        // User rules precede config rules: the app rule appears before the built-in RU domains.
        val rule = ConfigBuilder.build(node, ConfigBuilder.Options(userRules = userRules))
        assertTrue(rule.indexOf("ru.sberbankmobile") < rule.indexOf("xn--p1ai"))
    }

    @Test
    fun ruleModeRoutesRussiaDirect() {
        val node = LinkParser.parse(links.getValue("hy2"))
        val config = Json.parseToJsonElement(ConfigBuilder.build(node)).jsonObject
        val route = config.getValue("route").jsonObject
        assertEquals("proxy", route.getValue("final").jsonPrimitive.content)
        val ruleSets = route.getValue("rule_set").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        assertEquals(listOf("geoip-ru"), ruleSets)
    }

    @Test
    fun urlTestConfigsAreWritten() {
        val ss = LinkParser.parse("ss://YWVzLTI1Ni1nY206cHc=@ss.example.com:8388#ss")
        val hy2 = LinkParser.parse("hy2://pw@1.2.3.4:443?sni=hy.example.com#hy")
        val xhttp = LinkParser.parse("vless://11111111-2222-3333-4444-555555555555@x.example.com:443?type=xhttp&security=tls&path=%2Fx#x")
        val box = ConfigBuilder.buildUrlTest(
            listOf(ss, hy2, xhttp),
            mapOf("ss.example.com" to listOf("5.6.7.8"), "x.example.com" to listOf("9.9.9.9")),
            mapOf(xhttp.id to LocalLink.random(20001)),
        )
        val outbounds = Json.parseToJsonElement(box).jsonObject.getValue("outbounds").jsonArray
        assertEquals(listOf(ss.id, hy2.id, xhttp.id), outbounds.map { it.jsonObject.getValue("tag").jsonPrimitive.content })
        assertEquals("shadowsocks", outbounds[2].jsonObject.getValue("type").jsonPrimitive.content)
        File("build/test-configs").mkdirs()
        File("build/test-configs/urltest.json").writeText(box)
        File("build/test-configs/urltest-xray.json")
            .writeText(XrayConfigBuilder.buildUrlTest(listOf(XrayConfigBuilder.TestEntry(xhttp, LocalLink.random(20001), "9.9.9.9"))))
    }

    @Test
    fun geoipSourceAndIntervalAreConfigurable() {
        val node = LinkParser.parse(links.getValue("ss"))
        val config = ConfigBuilder.build(
            node,
            ConfigBuilder.Options(
                geoipUrl = "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-{country}.srs",
                geoipUpdateHours = 72,
            ),
        )
        File("build/test-configs").mkdirs()
        File("build/test-configs/geoip-custom.json").writeText(config)
        val ruleSet = Json.parseToJsonElement(config).jsonObject.getValue("route").jsonObject
            .getValue("rule_set").jsonArray[0].jsonObject
        assertEquals(
            "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-ru.srs",
            ruleSet.getValue("url").jsonPrimitive.content,
        )
        assertEquals("72h", ruleSet.getValue("update_interval").jsonPrimitive.content)
    }

    @Test
    fun asnRulesUseRuleSets() {
        val node = LinkParser.parse(links.getValue("ss"))
        val userRules = listOf(
            RoutingRule(Policy.DIRECT, ipCidrs = listOf("203.0.113.0/24"), asns = listOf(62041L, 59930L)),
        )
        val dir = File("build/test-configs/rule-sets").apply { mkdirs() }
        val config = ConfigBuilder.build(
            node,
            ConfigBuilder.Options(mode = RoutingMode.GLOBAL, userRules = userRules, ruleSetDir = dir.absolutePath),
        )
        File("build/test-configs/asn.json").writeText(config)
        val route = Json.parseToJsonElement(config).jsonObject.getValue("route").jsonObject
        val rules = route.getValue("rules").jsonArray.map { it.jsonObject }
        // CIDRs and ASNs become two rules with the same outbound, so they are OR'ed.
        assertTrue(rules.any { it["ip_cidr"]?.jsonArray?.map { v -> v.jsonPrimitive.content } == listOf("203.0.113.0/24") })
        val asnRule = rules.single { "rule_set" in it }
        assertEquals(listOf("asn-62041", "asn-59930"), asnRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("direct", asnRule.getValue("outbound").jsonPrimitive.content)
        val sets = route.getValue("rule_set").jsonArray.map { it.jsonObject }
        assertEquals(
            "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/asn/AS62041.srs",
            sets.single { it.getValue("tag").jsonPrimitive.content == "asn-62041" }.getValue("url").jsonPrimitive.content,
        )
    }
}
