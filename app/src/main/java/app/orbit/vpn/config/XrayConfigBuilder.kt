package app.orbit.vpn.config

import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.ProxyType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Loopback hop from sing-box to Xray. Shadowsocks 2022 with a random key authenticates both TCP
 * and UDP, so other apps on the device can't use the port as an open proxy to the server, as they
 * could with an unauthenticated SOCKS inbound (Xray doesn't authenticate SOCKS UDP at all).
 */
data class LocalLink(val port: Int, val key: String) {
    companion object {
        const val METHOD = "2022-blake3-aes-128-gcm"

        fun random(port: Int) = LocalLink(port, Base64.encode(ByteArray(16).also(SecureRandom()::nextBytes)))
    }
}

/**
 * Builds an Xray-core config with a local [LocalLink] inbound and a single outbound for [ProxyNode].
 * sing-box forwards to that port for nodes it cannot handle itself.
 */
object XrayConfigBuilder {
    private val json = Json { prettyPrint = true }

    /**
     * @param serverAddress resolved IP of the server; the original host name is still used for SNI,
     *   so Xray never has to resolve it through the tunnel.
     */
    fun build(node: ProxyNode, link: LocalLink, serverAddress: String = node.server): String =
        json.encodeToString(JsonObject.serializer(), buildJson(node, link, serverAddress))

    fun buildJson(node: ProxyNode, link: LocalLink, serverAddress: String): JsonObject = buildJsonObject {
        putJsonObject("log") { put("loglevel", "warning") }
        putJsonArray("inbounds") {
            add(inbound(link, "local-in"))
        }
        putJsonArray("outbounds") {
            add(outbound(node, serverAddress))
        }
    }

    /** One node of a multi-node config: its own [link] and resolved server [address]. */
    data class Entry(val node: ProxyNode, val link: LocalLink, val address: String)

    /** Config serving several nodes at once, each on its own port: latency tests and auto-select. */
    fun buildMany(entries: List<Entry>, logLevel: String = "none"): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("log") { put("loglevel", logLevel) }
            putJsonArray("inbounds") {
                entries.forEachIndexed { i, entry -> add(inbound(entry.link, "in-$i")) }
            }
            putJsonArray("outbounds") {
                entries.forEachIndexed { i, entry -> add(outbound(entry.node, entry.address, "out-$i")) }
            }
            putJsonObject("routing") {
                putJsonArray("rules") {
                    entries.indices.forEach { i ->
                        addJsonObject {
                            putJsonArray("inboundTag") { add(JsonPrimitive("in-$i")) }
                            put("outboundTag", "out-$i")
                        }
                    }
                }
            }
        },
    )

    private fun inbound(link: LocalLink, tag: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("listen", "127.0.0.1")
        put("port", link.port)
        put("protocol", "shadowsocks")
        putJsonObject("settings") {
            put("method", LocalLink.METHOD)
            put("password", link.key)
            put("network", "tcp,udp")
        }
    }

    private fun outbound(node: ProxyNode, address: String, tag: String = "proxy"): JsonObject = buildJsonObject {
        put("tag", tag)
        when (node.type) {
            ProxyType.VLESS -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    put("address", address)
                    put("port", node.port)
                    put("id", node.uuid.orEmpty())
                    put("encryption", "none")
                    node.flow?.let { put("flow", it) }
                }
            }

            ProxyType.VMESS -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", address)
                            put("port", node.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", node.uuid.orEmpty())
                                    put("security", node.method ?: "auto")
                                }
                            }
                        }
                    }
                }
            }

            ProxyType.TROJAN -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", address)
                            put("port", node.port)
                            put("password", node.password.orEmpty())
                        }
                    }
                }
            }

            else -> throw IllegalArgumentException("${node.type.label} is not supported by Xray mode")
        }
        putJsonObject("streamSettings") { putStream(node) }
    }

    private fun JsonObjectBuilder.putStream(node: ProxyNode) {
        val transport = node.transport
        val network = transport?.type ?: "raw"
        put("network", network)
        val tls = node.tls
        val serverName = tls?.serverName?.takeIf { it.isNotEmpty() } ?: node.server
        when {
            tls == null -> put("security", "none")
            tls.realityPublicKey != null -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", serverName)
                    put("fingerprint", tls.fingerprint ?: "chrome")
                    put("password", tls.realityPublicKey)
                    tls.realityShortId?.let { put("shortId", it) }
                }
            }

            else -> {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    put("serverName", serverName)
                    tls.fingerprint?.let { put("fingerprint", it) }
                    if (tls.alpn.isNotEmpty()) put("alpn", JsonArray(tls.alpn.map(::JsonPrimitive)))
                    if (tls.insecure) put("allowInsecure", true)
                }
            }
        }
        when (network) {
            "xhttp" -> putJsonObject("xhttpSettings") {
                transport?.host?.let { put("host", it) }
                put("path", transport?.path ?: "/")
                put("mode", transport?.mode ?: "auto")
                transport?.extra?.let { put("extra", Json.parseToJsonElement(it).jsonObject) }
            }

            "ws" -> putJsonObject("wsSettings") {
                transport?.path?.let { put("path", it) }
                transport?.host?.let { put("host", it) }
            }

            "grpc" -> putJsonObject("grpcSettings") {
                transport?.serviceName?.let { put("serviceName", it) }
            }

            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                transport?.path?.let { put("path", it) }
                transport?.host?.let { put("host", it) }
            }
        }
    }
}
