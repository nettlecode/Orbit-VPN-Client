package app.orbit.vpn

import app.orbit.vpn.config.ConfigBuilder
import app.orbit.vpn.data.SubscriptionUpdater
import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.SubscriptionUsage
import app.orbit.vpn.parser.LinkParser
import app.orbit.vpn.parser.ShadowrocketConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ShadowrocketConfigParserTest {
    private val lists = mapOf(
        "https://lists.example/ads.list" to """
            # ads
            DOMAIN-SUFFIX,doubleclick.net
            DOMAIN,ads.example.com
            IP-CIDR,203.0.113.0/24,no-resolve
            USER-AGENT,Bad*
        """.trimIndent(),
        "https://lists.example/stream.txt" to """
            .netflix.com
            +.nflxvideo.net
            api.example.org
        """.trimIndent(),
        "https://lists.example/clash.yaml" to """
            payload:
              - DOMAIN-SUFFIX,openai.com
              - 'DOMAIN,chat.openai.com'
        """.trimIndent(),
    )
    private val parser = ShadowrocketConfigParser { url -> lists[url] ?: error("404 $url") }

    private val config = """
        [General]
        bypass-system = true
        skip-proxy = 192.168.0.0/16, 10.0.0.0/8, localhost, *.local
        dns-server = system

        [Rule]
        # comment
        RULE-SET,https://lists.example/ads.list,REJECT
        DOMAIN-SET,https://lists.example/stream.txt,PROXY
        RULE-SET,https://lists.example/clash.yaml,🚀 Proxy
        DOMAIN-SUFFIX,yandex.ru,DIRECT
        DOMAIN-KEYWORD,gosuslugi,DIRECT
        IP-CIDR,17.0.0.0/8,DIRECT,no-resolve
        AND,((PROTOCOL,UDP),(DEST-PORT,443)),REJECT-NO-DROP
        RULE-SET,https://lists.example/missing.list,DIRECT
        GEOIP,LAN,DIRECT
        GEOIP,RU,DIRECT
        FINAL,PROXY

        [URL Rewrite]
        ^http://example.com http://example.org 302
    """.trimIndent()

    @Test
    fun convertsRulesInOrder() {
        val result = parser.parse(config, "test.conf", now = 1)
        assertEquals(Policy.PROXY, result.finalPolicy)
        // AND rule, USER-AGENT inside a list doesn't count; missing list does.
        assertEquals(2, result.skipped)
        val rules = result.rules.map { it.copy(id = "") }
        assertEquals(
            RoutingRule(
                Policy.DIRECT,
                domains = listOf("localhost"),
                suffixes = listOf("local"),
                ipCidrs = listOf("192.168.0.0/16", "10.0.0.0/8"),
                id = "",
            ),
            rules[0],
        )
        assertEquals(RoutingRule(Policy.REJECT, listOf("ads.example.com"), listOf("doubleclick.net"), ipCidrs = listOf("203.0.113.0/24"), id = ""), rules[1])
        // Stream list and the clash list both map to PROXY and merge.
        assertEquals(
            RoutingRule(
                Policy.PROXY,
                domains = listOf("api.example.org", "chat.openai.com"),
                suffixes = listOf("netflix.com", "nflxvideo.net", "openai.com"),
                id = "",
            ),
            rules[2],
        )
        assertEquals(Policy.DIRECT, rules[3].policy)
        assertEquals(listOf("yandex.ru"), rules[3].suffixes)
        assertEquals(listOf("gosuslugi"), rules[3].keywords)
        assertTrue("17.0.0.0/8" in rules[3].ipCidrs)
        assertTrue("fc00::/7" in rules[3].ipCidrs) // GEOIP,LAN merged into the same DIRECT rule
        assertEquals(RoutingRule(Policy.DIRECT, geoip = "ru", id = ""), rules[4])
        assertEquals(5, rules.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTextWithoutRules() {
        parser.parse("just some text", "x")
    }

    @Test
    fun generatedConfigIsWritten() {
        val rules = parser.parse(config, "test.conf")
        val node = LinkParser.parse("trojan://pw@t.example.com:443#t")
        File("build/test-configs").mkdirs()
        File("build/test-configs/imported-rules.json")
            .writeText(ConfigBuilder.build(node, ConfigBuilder.Options(rules = rules)))
        val rejectAll = rules.copy(finalPolicy = Policy.REJECT)
        File("build/test-configs/imported-rules-reject.json")
            .writeText(ConfigBuilder.build(node, ConfigBuilder.Options(rules = rejectAll)))
    }

    /** Real-world check against a local config: SR_CONF=/path/to/default.conf ./gradlew test */
    @Test
    fun parsesExternalConfig() {
        val path = System.getenv("SR_CONF")
        assumeTrue(path != null)
        val text = File(path!!).readText()
        val rules = ShadowrocketConfigParser { error("offline") }.parse(text, "external")
        println("external config: ${rules.rules.size} rules, ${rules.ruleCount} entries, ${rules.skipped} skipped")
        val node = LinkParser.parse("trojan://pw@t.example.com:443#t")
        File("build/test-configs/external-rules.json")
            .writeText(ConfigBuilder.build(node, ConfigBuilder.Options(rules = rules)))
    }

    @Test
    fun parsesSubscriptionUserInfo() {
        val usage = SubscriptionUsage.parse("upload=1024; download=2048; total=10737418240; expire=1767225600")!!
        assertEquals(3072L, usage.used)
        assertEquals(10737418240L, usage.total)
        assertEquals(1767225600L, usage.expire)
        assertEquals(null, SubscriptionUsage.parse(""))
    }

    @Test
    fun parsesIpAsn() {
        val text = """
            [Rule]
            IP-ASN,62041,PROXY,no-resolve
            IP-ASN,AS59930,PROXY
            IP-CIDR,91.105.192.0/23,PROXY
            IP-ASN,abc,DIRECT
            FINAL,DIRECT
        """.trimIndent()
        val result = parser.parse(text, "asn.conf")
        assertEquals(1, result.skipped)
        val rule = result.rules.single()
        assertEquals(listOf(62041L, 59930L), rule.asns)
        assertEquals(listOf("91.105.192.0/23"), rule.ipCidrs)
        assertEquals(listOf("91.105.192.0/23", "AS62041", "AS59930"), rule.values)
    }

    @Test
    fun dropsUnknownAsns() {
        val rules = listOf(
            RoutingRule(Policy.PROXY, asns = listOf(1L, 2L)),
            RoutingRule(Policy.DIRECT, asns = listOf(2L)),
        )
        with(SubscriptionUpdater) {
            assertEquals(listOf(listOf(1L)), rules.withoutAsns(setOf(2L)).map { it.asns })
        }
    }
}
