package app.orbit.vpn.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProxyType(val label: String, val udpOnly: Boolean = false) {
    SHADOWSOCKS("Shadowsocks"),
    VMESS("VMess"),
    VLESS("VLESS"),
    TROJAN("Trojan"),
    HYSTERIA2("Hysteria2", udpOnly = true),
    TUIC("TUIC", udpOnly = true),
    SOCKS("SOCKS5"),
    HTTP("HTTP"),
}

@Serializable
data class TransportSettings(
    /** ws, grpc, http, httpupgrade, xhttp */
    val type: String,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    /** XHTTP mode: auto, packet-up, stream-up, stream-one. */
    val mode: String? = null,
    /** XHTTP "extra" settings as a raw JSON object. */
    val extra: String? = null,
)

@Serializable
data class TlsSettings(
    val serverName: String? = null,
    val alpn: List<String> = emptyList(),
    val insecure: Boolean = false,
    /** uTLS fingerprint: chrome, firefox, safari, ... */
    val fingerprint: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
)

/**
 * A single proxy server. Protocol-specific fields are nullable and only
 * meaningful for the protocols that use them.
 */
@Serializable
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ProxyType,
    val server: String,
    val port: Int,
    val uuid: String? = null,
    val password: String? = null,
    val username: String? = null,
    /** Shadowsocks cipher or VMess security. */
    val method: String? = null,
    val alterId: Int = 0,
    val flow: String? = null,
    val plugin: String? = null,
    val pluginOpts: String? = null,
    val obfsPassword: String? = null,
    val congestionControl: String? = null,
    val udpRelayMode: String? = null,
    val transport: TransportSettings? = null,
    val tls: TlsSettings? = null,
    val link: String? = null,
    /** Owning subscription; null for manually added servers. */
    val subscriptionId: String? = null,
) {
    /** Transports that sing-box lacks are served by the embedded Xray-core. */
    val requiresXray: Boolean get() = transport?.type == "xhttp"
}

@Serializable
enum class RoutingMode {
    /** Everything goes through the proxy. */
    GLOBAL,

    /** Private and Russian destinations go direct, the rest through the proxy. */
    RULE,

    /** Everything goes direct (tunnel stays up, useful for testing). */
    DIRECT,
}
