package app.orbit.vpn.config

import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.ProxyType
import app.orbit.vpn.model.RoutingMode
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig
import app.orbit.vpn.model.TlsSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** Turns a [ProxyNode] plus routing settings into a sing-box JSON config. */
object ConfigBuilder {
    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"

    private val json = Json { prettyPrint = true }

    const val DEFAULT_REMOTE_DNS = "https://1.1.1.1/dns-query"

    /** Placeholder for the lower-case country code in a GeoIP URL template. */
    const val COUNTRY_PLACEHOLDER = "{country}"

    const val DEFAULT_GEOIP_URL = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-{country}.srs"

    const val DEFAULT_GEOIP_UPDATE_HOURS = 24

    /** Placeholder for the AS number (digits only) in an ASN URL template. */
    const val ASN_PLACEHOLDER = "{asn}"

    const val DEFAULT_ASN_URL = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/asn/AS{asn}.srs"

    fun geoipUrl(country: String, template: String = DEFAULT_GEOIP_URL) =
        template.replace(COUNTRY_PLACEHOLDER, country)

    fun geoipFileName(country: String) = "geoip-$country.srs"

    fun asnUrl(asn: Long, template: String = DEFAULT_ASN_URL) = template.replace(ASN_PLACEHOLDER, asn.toString())

    fun asnFileName(asn: Long) = "asn-$asn.srs"

    data class Options(
        val mode: RoutingMode = RoutingMode.RULE,
        /** https://host/path, tls://host, quic://host, udp://host or tcp://host (port optional). */
        val remoteDns: String = DEFAULT_REMOTE_DNS,
        /**
         * Resolve AAAA records. Off by default: many proxy servers have no IPv6 egress, and apps
         * would otherwise prefer IPv6 and fail. The tunnel still captures IPv6 so nothing leaks.
         */
        val ipv6: Boolean = false,
        val logLevel: String = "info",
        /** Absolute path of the core log file, or null to keep logs in memory only. */
        val logFile: String? = null,
        /** When set, the proxy outbound is a local port served by the embedded Xray-core. */
        val xrayLink: LocalLink? = null,
        /**
         * Directory with local `geoip-<cc>.srs` copies, used until sing-box downloads fresh ones,
         * so startup never depends on GitHub being reachable.
         */
        val ruleSetDir: String? = null,
        /** GeoIP rule-set URL with [COUNTRY_PLACEHOLDER]. */
        val geoipUrl: String = DEFAULT_GEOIP_URL,
        /** ASN rule-set URL with [ASN_PLACEHOLDER]. */
        val asnUrl: String = DEFAULT_ASN_URL,
        /** How often sing-box re-downloads GeoIP and ASN rule-sets while running. */
        val geoipUpdateHours: Int = DEFAULT_GEOIP_UPDATE_HOURS,
        /** Rules applied in [RoutingMode.RULE]. */
        val rules: RuleConfig = RuleConfig.DEFAULT,
        /** User rules, checked before [rules] in RULE and GLOBAL modes. */
        val userRules: List<RoutingRule> = emptyList(),
    ) {
        /** Rules in effect for the current mode, in priority order. */
        val activeRules: List<RoutingRule>
            get() = when (mode) {
                RoutingMode.DIRECT -> emptyList()
                RoutingMode.GLOBAL -> userRules.filter { it.enabled }
                RoutingMode.RULE -> (userRules + rules.rules).filter { it.enabled }
            }
    }

    fun build(node: ProxyNode, options: Options = Options()): String =
        json.encodeToString(JsonObject.serializer(), buildJson(node, options))

    fun buildJson(node: ProxyNode, options: Options): JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("level", options.logLevel)
            options.logFile?.let { put("output", it) }
        }
        putJsonObject("dns") {
            putJsonArray("servers") {
                add(remoteDnsServer(options.remoteDns))
                addJsonObject {
                    put("type", "local")
                    put("tag", "local")
                }
            }
            val dnsRules = options.activeRules.filter { (it.hasDomains || it.packages.isNotEmpty()) && it.policy != Policy.PROXY }
            if (dnsRules.isNotEmpty()) {
                putJsonArray("rules") {
                    // Domains and apps that bypass the proxy resolve locally; rejected ones never resolve.
                    dnsRules.forEach { rule ->
                        addJsonObject {
                            putDomainMatchers(rule)
                            if (rule.packages.isNotEmpty()) putStrings("package_name", rule.packages)
                            if (rule.policy == Policy.REJECT) put("action", "reject") else put("server", "local")
                        }
                    }
                }
            }
            put("final", if (options.mode == RoutingMode.DIRECT) "local" else "remote")
            put("strategy", if (options.ipv6) "prefer_ipv4" else "ipv4_only")
        }
        putJsonArray("inbounds") {
            addJsonObject {
                put("type", "tun")
                put("tag", "tun-in")
                putJsonArray("address") {
                    add("172.19.0.1/30")
                    add("fdfe:dcba:9876::1/126")
                }
                put("mtu", 9000)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "mixed")
            }
        }
        putJsonArray("outbounds") {
            add(options.xrayLink?.let(::xrayOutbound) ?: outbound(node))
            addJsonObject {
                put("type", "direct")
                put("tag", DIRECT_TAG)
            }
        }
        putJsonObject("route") {
            putJsonArray("rules") {
                addJsonObject { put("action", "sniff") }
                addJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                }
                addJsonObject {
                    put("ip_is_private", true)
                    put("outbound", DIRECT_TAG)
                }
                options.activeRules.forEach { rule ->
                    when {
                        rule.geoip != null -> addJsonObject {
                            put("rule_set", "geoip-${rule.geoip}")
                            putPolicy(rule.policy)
                        }
                        rule.packages.isNotEmpty() -> addJsonObject {
                            putStrings("package_name", rule.packages)
                            putPolicy(rule.policy)
                        }
                        else -> {
                            if (rule.hasDomains || rule.ipCidrs.isNotEmpty()) {
                                addJsonObject {
                                    putDomainMatchers(rule)
                                    if (rule.ipCidrs.isNotEmpty()) putStrings("ip_cidr", rule.ipCidrs)
                                    putPolicy(rule.policy)
                                }
                            }
                            // A separate rule with the same policy, so ASNs are OR'ed with the
                            // matchers above rather than combined with them.
                            if (rule.asns.isNotEmpty()) {
                                addJsonObject {
                                    putStrings("rule_set", rule.asns.map { "asn-$it" })
                                    putPolicy(rule.policy)
                                }
                            }
                        }
                    }
                }
                if (options.mode == RoutingMode.RULE) {
                    if (options.rules.finalPolicy == Policy.REJECT) {
                        addJsonObject {
                            putStrings("ip_cidr", listOf("0.0.0.0/0", "::/0"))
                            put("action", "reject")
                        }
                    }
                }
            }
            val countries = options.activeRules.mapNotNull { it.geoip }.distinct()
            val asns = options.activeRules.flatMap { it.asns }.distinct()
            if (countries.isNotEmpty() || asns.isNotEmpty()) {
                putJsonArray("rule_set") {
                    countries.forEach { country ->
                        add(remoteRuleSet("geoip-$country", geoipUrl(country, options.geoipUrl), geoipFileName(country), options))
                    }
                    asns.forEach { asn ->
                        add(remoteRuleSet("asn-$asn", asnUrl(asn, options.asnUrl), asnFileName(asn), options))
                    }
                }
            }
            val finalOutbound = when {
                options.mode == RoutingMode.DIRECT -> DIRECT_TAG
                options.mode == RoutingMode.RULE && options.rules.finalPolicy == Policy.DIRECT -> DIRECT_TAG
                else -> PROXY_TAG
            }
            put("final", finalOutbound)
            put("auto_detect_interface", true)
            put("default_domain_resolver", "local")
        }
        putJsonObject("experimental") {
            putJsonObject("cache_file") { put("enabled", true) }
        }
    }

    /**
     * Downloaded and refreshed by sing-box through the proxy. The local copy in [Options.ruleSetDir]
     * lets the tunnel start without the download: a remote rule-set that can't be fetched at
     * start fails the whole start.
     */
    private fun remoteRuleSet(tag: String, url: String, fileName: String, options: Options): JsonObject = buildJsonObject {
        put("type", "remote")
        put("tag", tag)
        put("format", "binary")
        put("url", url)
        put("update_interval", "${options.geoipUpdateHours}h")
        options.ruleSetDir?.let { File(it, fileName) }
            ?.takeIf { it.exists() }
            ?.let { put("initial_path", it.path) }
        putJsonObject("http_client") { put("detour", PROXY_TAG) }
    }

    private fun remoteDnsServer(address: String): JsonObject = buildJsonObject {
        val scheme = address.substringBefore("://", "udp").lowercase()
        val rest = address.substringAfter("://")
        val hostPort = rest.substringBefore('/')
        val path = rest.substringAfter('/', "").takeIf { it.isNotEmpty() }
        val (host, port) = if (hostPort.startsWith('[')) {
            hostPort.substringAfter('[').substringBefore(']') to hostPort.substringAfter("]:", "").toIntOrNull()
        } else if (hostPort.count { it == ':' } == 1) {
            hostPort.substringBefore(':') to hostPort.substringAfter(':').toIntOrNull()
        } else {
            hostPort to null
        }
        put("type", when (scheme) {
            "https", "tls", "quic", "h3", "udp", "tcp" -> scheme
            else -> throw IllegalArgumentException("unsupported DNS scheme: $scheme")
        })
        put("tag", "remote")
        put("server", host)
        port?.let { put("server_port", it) }
        if (path != null && (scheme == "https" || scheme == "h3")) put("path", "/$path")
        put("detour", PROXY_TAG)
    }

    /**
     * Config for `Xraybridge.urlTest`: one outbound per node, tagged with the node id. Server names
     * resolve through a hosts table ([addresses], looked up beforehand over the physical network),
     * so nothing is resolved through the tunnel. Nodes in [xrayLinks] forward to Xray.
     */
    fun buildUrlTest(nodes: List<ProxyNode>, addresses: Map<String, List<String>>, xrayLinks: Map<String, LocalLink>): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonObject("log") { put("disabled", true) }
                putJsonObject("dns") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("type", "hosts")
                            put("tag", "hosts")
                            putJsonObject("predefined") {
                                addresses.forEach { (host, ips) -> putStrings(host, ips) }
                            }
                        }
                    }
                }
                putJsonObject("route") {
                    put("default_domain_resolver", "hosts")
                    // Makes sing-box pass every socket to the platform for VpnService.protect.
                    put("auto_detect_interface", true)
                }
                putJsonArray("outbounds") {
                    nodes.forEach { node -> add(xrayLinks[node.id]?.let { xrayOutbound(it, node.id) } ?: outbound(node, node.id)) }
                }
            },
        )

    private fun xrayOutbound(link: LocalLink, tag: String = PROXY_TAG): JsonObject = buildJsonObject {
        put("type", "shadowsocks")
        put("tag", tag)
        put("server", "127.0.0.1")
        put("server_port", link.port)
        put("method", LocalLink.METHOD)
        put("password", link.key)
    }

    fun outbound(node: ProxyNode, tag: String = PROXY_TAG): JsonObject = buildJsonObject {
        put("tag", tag)
        put("server", node.server)
        put("server_port", node.port)
        when (node.type) {
            ProxyType.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("method", node.method ?: "aes-256-gcm")
                put("password", node.password.orEmpty())
                node.plugin?.let {
                    put("plugin", normalizePlugin(it))
                    put("plugin_opts", node.pluginOpts.orEmpty())
                }
            }

            ProxyType.VMESS -> {
                put("type", "vmess")
                put("uuid", node.uuid.orEmpty())
                put("security", node.method ?: "auto")
                put("alter_id", node.alterId)
                putTls(node.tls)
                putTransport(node)
            }

            ProxyType.VLESS -> {
                put("type", "vless")
                put("uuid", node.uuid.orEmpty())
                node.flow?.let { put("flow", it) }
                putTls(node.tls)
                putTransport(node)
            }

            ProxyType.TROJAN -> {
                put("type", "trojan")
                put("password", node.password.orEmpty())
                putTls(node.tls ?: TlsSettings(serverName = node.server))
                putTransport(node)
            }

            ProxyType.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("password", node.password.orEmpty())
                node.obfsPassword?.let {
                    putJsonObject("obfs") {
                        put("type", "salamander")
                        put("password", it)
                    }
                }
                putTls(node.tls ?: TlsSettings(serverName = node.server))
            }

            ProxyType.TUIC -> {
                put("type", "tuic")
                put("uuid", node.uuid.orEmpty())
                put("password", node.password.orEmpty())
                node.congestionControl?.let { put("congestion_control", it) }
                node.udpRelayMode?.let { put("udp_relay_mode", it) }
                putTls(node.tls ?: TlsSettings(serverName = node.server, alpn = listOf("h3")))
            }

            ProxyType.SOCKS -> {
                put("type", "socks")
                put("version", "5")
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
            }

            ProxyType.HTTP -> {
                put("type", "http")
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                putTls(node.tls)
            }
        }
    }

    private fun normalizePlugin(plugin: String) = when (plugin) {
        "simple-obfs", "obfs-local" -> "obfs-local"
        else -> plugin
    }

    private fun JsonObjectBuilder.putTls(tls: TlsSettings?) {
        tls ?: return
        putJsonObject("tls") {
            put("enabled", true)
            tls.serverName?.takeIf { it.isNotEmpty() }?.let { put("server_name", it) }
            if (tls.insecure) put("insecure", true)
            if (tls.alpn.isNotEmpty()) putStrings("alpn", tls.alpn)
            val reality = tls.realityPublicKey != null
            val fingerprint = tls.fingerprint ?: if (reality) "chrome" else null
            fingerprint?.let {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", it)
                }
            }
            if (reality) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", tls.realityPublicKey)
                    tls.realityShortId?.let { put("short_id", it) }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putTransport(node: ProxyNode) {
        val transport = node.transport ?: return
        putJsonObject("transport") {
            put("type", transport.type)
            when (transport.type) {
                "ws" -> {
                    transport.path?.let { put("path", it) }
                    transport.host?.let { host -> putJsonObject("headers") { put("Host", host) } }
                }

                "grpc" -> transport.serviceName?.let { put("service_name", it) }
                "http" -> {
                    transport.path?.let { put("path", it) }
                    transport.host?.let { putStrings("host", it.split(',').map(String::trim)) }
                }

                "httpupgrade" -> {
                    transport.path?.let { put("path", it) }
                    transport.host?.let { put("host", it) }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putDomainMatchers(rule: RoutingRule) {
        if (rule.domains.isNotEmpty()) putStrings("domain", rule.domains)
        if (rule.suffixes.isNotEmpty()) putStrings("domain_suffix", rule.suffixes)
        if (rule.keywords.isNotEmpty()) putStrings("domain_keyword", rule.keywords)
    }

    private fun JsonObjectBuilder.putPolicy(policy: Policy) = when (policy) {
        Policy.PROXY -> put("outbound", PROXY_TAG)
        Policy.DIRECT -> put("outbound", DIRECT_TAG)
        Policy.REJECT -> put("action", "reject")
    }

    private fun JsonObjectBuilder.putStrings(key: String, values: List<String>) {
        put(key, JsonArray(values.map(::JsonPrimitive)))
    }
}
