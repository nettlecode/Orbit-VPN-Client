package app.orbit.vpn.parser

import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.ProxyType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import kotlin.io.encoding.Base64

/** Encodes edited nodes using the same share-link fields understood by [LinkParser]. */
object LinkWriter {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun write(node: ProxyNode): String {
        val t = node.transport
        val tls = node.tls
        if (node.type == ProxyType.VMESS) {
            val json = buildJsonObject {
                put("v", "2"); put("ps", node.name); put("add", node.server); put("port", node.port)
                put("id", node.uuid); put("aid", node.alterId); put("scy", node.method ?: "auto")
                put("net", t?.type ?: "tcp")
                t?.let {
                    put("path", if (it.type == "grpc") it.serviceName else it.path)
                    put("host", it.host); put("mode", it.mode); put("extra", it.extra)
                }
                tls?.let {
                    put("tls", "tls"); put("sni", it.serverName.orEmpty()); put("alpn", it.alpn.joinToString(","))
                    put("fp", it.fingerprint); put("allowInsecure", if (it.insecure) "1" else "0")
                }
            }
            return "vmess://" + Base64.Default.encode(json.toString().toByteArray())
        }
        val query = linkedMapOf<String, String>()
        fun param(key: String, value: String?) { if (!value.isNullOrEmpty()) query[key] = value }
        tls?.let {
            param("security", if (it.realityPublicKey != null) "reality" else "tls")
            query["sni"] = it.serverName.orEmpty(); query["alpn"] = it.alpn.joinToString(","); param("fp", it.fingerprint)
            param("pbk", it.realityPublicKey); param("sid", it.realityShortId)
            if (it.insecure) query["insecure"] = "1"
        } ?: run { query["security"] = "none" }
        t?.let {
            param("type", it.type); param("path", it.path); param("host", it.host)
            param("serviceName", it.serviceName); param("mode", it.mode); param("extra", it.extra)
        }
        param("flow", node.flow)
        param("congestion_control", node.congestionControl); param("udp_relay_mode", node.udpRelayMode)
        node.obfsPassword?.let { query["obfs"] = "salamander"; query["obfs-password"] = it }
        node.plugin?.let { query["plugin"] = it + (node.pluginOpts?.let { opts -> ";$opts" } ?: "") }
        val scheme = when (node.type) {
            ProxyType.SHADOWSOCKS -> "ss"
            ProxyType.VLESS -> "vless"
            ProxyType.TROJAN -> "trojan"
            ProxyType.HYSTERIA2 -> "hysteria2"
            ProxyType.TUIC -> "tuic"
            ProxyType.SOCKS -> "socks"
            ProxyType.HTTP -> if (tls != null) "https" else "http"
            ProxyType.VMESS -> error("handled above")
        }
        val credentials = when (node.type) {
            ProxyType.SHADOWSOCKS -> Base64.Default.encode("${node.method}:${node.password}".toByteArray())
            ProxyType.VLESS -> encode(node.uuid.orEmpty())
            ProxyType.TUIC -> encode("${node.uuid}:${node.password}")
            ProxyType.SOCKS, ProxyType.HTTP -> node.username?.let { encode(it) + ":" + encode(node.password.orEmpty()) }
            else -> encode(node.password.orEmpty())
        }
        val host = if (':' in node.server) "[${node.server}]" else node.server
        return "$scheme://" + (credentials?.let { "$it@" } ?: "") + "$host:${node.port}" +
            query.entries.joinToString("&", prefix = "?") { (key, value) -> "$key=${encode(value)}" } + "#${encode(node.name)}"
    }
}
