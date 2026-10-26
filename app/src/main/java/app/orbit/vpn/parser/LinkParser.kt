package app.orbit.vpn.parser

import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.ProxyType
import app.orbit.vpn.model.TlsSettings
import app.orbit.vpn.model.TransportSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses share links (ss://, vmess://, vless://, trojan://, hy2://, tuic://,
 * socks://, http://) and plain or base64-encoded lists of them.
 */
object LinkParser {

    /** Parses any text: one link, several links, or a base64 subscription body. */
    fun parseAll(text: String): List<ProxyNode> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val body = if (!trimmed.contains("://")) decodeBase64(trimmed) ?: trimmed else trimmed
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { parse(it) }.getOrNull() }
            .toList()
    }

    fun parse(link: String): ProxyNode {
        val scheme = link.substringBefore("://", "").lowercase()
        require(scheme.isNotEmpty()) { "not a link" }
        val node = when (scheme) {
            "ss" -> parseShadowsocks(link)
            "vmess" -> parseVmess(link)
            "vless" -> parseVless(link)
            "trojan" -> parseTrojan(link)
            "hysteria2", "hy2" -> parseHysteria2(link)
            "tuic" -> parseTuic(link)
            "socks", "socks5" -> parseSocks(link)
            "http", "https" -> parseHttp(link)
            else -> throw IllegalArgumentException("unsupported scheme: $scheme")
        }
        return node.copy(link = link)
    }

    private fun parseShadowsocks(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        val method: String
        val password: String
        val host: String
        val port: Int
        if (url.port != null) {
            // SIP002: ss://base64(method:password)@host:port or plain method:password for 2022 ciphers
            val userInfo = url.userInfo ?: error("missing user info")
            val decoded = decodeBase64(decode(userInfo))?.takeIf { it.contains(':') } ?: decode(userInfo)
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':')
            host = url.host
            port = url.port
        } else {
            // Legacy: ss://base64(method:password@host:port)
            val decoded = decodeBase64(url.host) ?: error("invalid legacy ss link")
            val inner = SimpleUrl.parse("ss://$decoded")
            val innerUserInfo = inner.userInfo ?: error("missing method")
            method = innerUserInfo.substringBefore(':')
            password = innerUserInfo.substringAfter(':')
            host = inner.host
            port = inner.port ?: error("missing port")
        }
        val plugin = url.query["plugin"]
        return ProxyNode(
            name = url.fragment ?: host,
            type = ProxyType.SHADOWSOCKS,
            server = host,
            port = port,
            method = method,
            password = password,
            plugin = plugin?.substringBefore(';')?.takeIf { it.isNotEmpty() },
            pluginOpts = plugin?.substringAfter(';', "")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseVmess(link: String): ProxyNode {
        val payload = link.substringAfter("://").substringBefore('#')
        val json = decodeBase64(payload) ?: error("invalid vmess payload")
        val obj = Json.parseToJsonElement(json).jsonObject
        fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        val network = field("net") ?: "tcp"
        val host = field("host")
        val path = field("path")
        val transport = when (network) {
            "ws" -> TransportSettings("ws", path = path, host = host)
            "grpc" -> TransportSettings("grpc", serviceName = path)
            "h2", "http" -> TransportSettings("http", path = path, host = host)
            "httpupgrade" -> TransportSettings("httpupgrade", path = path, host = host)
            "xhttp", "splithttp" -> TransportSettings("xhttp", path = path, host = host, mode = field("mode"))
            "tcp", "raw" -> if (field("type") == "http") TransportSettings("http", path = path, host = host) else null
            else -> throw IllegalArgumentException("unsupported transport: $network")
        }
        val tls = if (field("tls") == "tls") {
            TlsSettings(
                serverName = field("sni") ?: host,
                alpn = field("alpn")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                fingerprint = field("fp"),
                insecure = field("allowInsecure") == "1" || field("allowInsecure") == "true",
            )
        } else {
            null
        }
        val server = field("add") ?: error("missing address")
        return ProxyNode(
            name = field("ps") ?: server,
            type = ProxyType.VMESS,
            server = server,
            port = field("port")?.toIntOrNull() ?: error("missing port"),
            uuid = field("id") ?: error("missing id"),
            alterId = field("aid")?.toIntOrNull() ?: 0,
            method = field("scy") ?: "auto",
            transport = transport,
            tls = tls,
        )
    }

    private fun parseVless(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        return ProxyNode(
            name = url.fragment ?: url.host,
            type = ProxyType.VLESS,
            server = url.host,
            port = url.port ?: 443,
            uuid = url.userInfo?.let(::decode) ?: error("missing uuid"),
            flow = url.query["flow"]?.takeIf { it.isNotEmpty() },
            transport = transportFromQuery(url.query),
            tls = tlsFromQuery(url.query, defaultEnabled = false),
        )
    }

    private fun parseTrojan(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        return ProxyNode(
            name = url.fragment ?: url.host,
            type = ProxyType.TROJAN,
            server = url.host,
            port = url.port ?: 443,
            password = url.userInfo?.let(::decode) ?: error("missing password"),
            transport = transportFromQuery(url.query),
            tls = tlsFromQuery(url.query, defaultEnabled = true) ?: TlsSettings(serverName = url.host),
        )
    }

    private fun parseHysteria2(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        val q = url.query
        return ProxyNode(
            name = url.fragment ?: url.host,
            type = ProxyType.HYSTERIA2,
            server = url.host,
            port = url.port ?: 443,
            password = url.userInfo?.let(::decode),
            obfsPassword = q["obfs-password"]?.takeIf { q["obfs"] == "salamander" },
            tls = TlsSettings(
                serverName = q["sni"] ?: q["peer"],
                insecure = q["insecure"].isTruthy(),
                alpn = q["alpn"]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            ),
        )
    }

    private fun parseTuic(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        val q = url.query
        val userInfo = url.userInfo?.let(::decode) ?: error("missing credentials")
        return ProxyNode(
            name = url.fragment ?: url.host,
            type = ProxyType.TUIC,
            server = url.host,
            port = url.port ?: 443,
            uuid = userInfo.substringBefore(':'),
            password = userInfo.substringAfter(':', ""),
            congestionControl = q["congestion_control"] ?: q["congestion-control"],
            udpRelayMode = q["udp_relay_mode"] ?: q["udp-relay-mode"],
            tls = TlsSettings(
                serverName = q["sni"],
                insecure = q["allow_insecure"].isTruthy() || q["insecure"].isTruthy(),
                alpn = q["alpn"]?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf("h3"),
            ),
        )
    }

    private fun parseSocks(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        val (user, pass) = credentials(url.userInfo)
        return ProxyNode(
            name = url.fragment ?: url.host,
            type = ProxyType.SOCKS,
            server = url.host,
            port = url.port ?: 1080,
            username = user,
            password = pass,
        )
    }

    private fun parseHttp(link: String): ProxyNode {
        val url = SimpleUrl.parse(link)
        val (user, pass) = credentials(url.userInfo)
        val secure = link.startsWith("https", ignoreCase = true)
        return ProxyNode(
            name = url.fragment ?: url.host,
            type = ProxyType.HTTP,
            server = url.host,
            port = url.port ?: if (secure) 443 else 80,
            username = user,
            password = pass,
            tls = if (secure) TlsSettings(serverName = url.query["sni"] ?: url.host) else null,
        )
    }

    /** user:pass, either plain or base64-encoded (common for socks links). */
    private fun credentials(userInfo: String?): Pair<String?, String?> {
        if (userInfo.isNullOrEmpty()) return null to null
        val plain = decode(userInfo)
        val text = if (plain.contains(':')) plain else decodeBase64(userInfo)?.takeIf { it.contains(':') } ?: plain
        return text.substringBefore(':') to text.substringAfter(':', "").ifEmpty { null }
    }

    private fun transportFromQuery(q: Map<String, String>): TransportSettings? {
        val host = q["host"]?.takeIf { it.isNotEmpty() }
        val path = q["path"]?.takeIf { it.isNotEmpty() }
        return when (q["type"]) {
            "ws" -> TransportSettings("ws", path = path, host = host)
            "grpc" -> TransportSettings("grpc", serviceName = q["serviceName"] ?: q["service_name"] ?: path)
            "http", "h2" -> TransportSettings("http", path = path, host = host)
            "httpupgrade" -> TransportSettings("httpupgrade", path = path, host = host)
            "xhttp", "splithttp" -> TransportSettings(
                "xhttp",
                path = path,
                host = host,
                mode = q["mode"]?.takeIf { it.isNotEmpty() },
                extra = xhttpExtra(q),
            )
            null, "", "tcp", "raw", "none" -> null
            else -> throw IllegalArgumentException("unsupported transport: ${q["type"]}")
        }
    }

    /** Merges the `extra` JSON with standalone share-link params such as x_padding_bytes. */
    private fun xhttpExtra(q: Map<String, String>): String? {
        val extra = q["extra"]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .orEmpty()
            .toMutableMap()
        q["x_padding_bytes"]?.takeIf { it.isNotEmpty() && "xPaddingBytes" !in extra }
            ?.let { extra["xPaddingBytes"] = JsonPrimitive(it) }
        return extra.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() }
    }

    private fun tlsFromQuery(q: Map<String, String>, defaultEnabled: Boolean): TlsSettings? {
        val security = q["security"]
        val enabled = when (security) {
            "tls", "reality", "xtls" -> true
            "none" -> false
            else -> defaultEnabled
        }
        if (!enabled) return null
        return TlsSettings(
            serverName = listOf("sni", "peer", "host").firstNotNullOfOrNull { q[it]?.takeIf(String::isNotEmpty) },
            alpn = q["alpn"]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            insecure = q["allowInsecure"].isTruthy() || q["insecure"].isTruthy(),
            fingerprint = q["fp"]?.takeIf { it.isNotEmpty() },
            realityPublicKey = q["pbk"]?.takeIf { security == "reality" },
            realityShortId = q["sid"]?.takeIf { security == "reality" },
        )
    }

    private fun String?.isTruthy() = this == "1" || this.equals("true", ignoreCase = true)

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64(value: String): String? {
        val clean = value.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        val decoders = listOf(
            Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
        )
        for (decoder in decoders) {
            val bytes = runCatching { decoder.decode(clean) }.getOrNull() ?: continue
            val text = bytes.toString(Charsets.UTF_8)
            if (text.none { it == '�' }) return text
        }
        return null
    }
}

/** Lenient URL splitter: share links often contain characters java.net.URI rejects. */
internal data class SimpleUrl(
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val query: Map<String, String>,
    val fragment: String?,
) {
    companion object {
        fun parse(link: String): SimpleUrl {
            var rest = link.substringAfter("://")
            val fragment = rest.substringAfter('#', "").takeIf { it.isNotEmpty() }
                ?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8").trim() }
            rest = rest.substringBefore('#')
            val queryString = rest.substringAfter('?', "")
            rest = rest.substringBefore('?').trimEnd('/')
            val at = rest.lastIndexOf('@')
            val userInfo = if (at >= 0) rest.substring(0, at) else null
            val hostPort = if (at >= 0) rest.substring(at + 1) else rest
            val host: String
            val port: Int?
            if (hostPort.startsWith('[')) {
                host = hostPort.substringAfter('[').substringBefore(']')
                port = hostPort.substringAfter("]:", "").toIntOrNull()
            } else if (hostPort.count { it == ':' } == 1) {
                host = hostPort.substringBefore(':')
                port = hostPort.substringAfter(':').toIntOrNull()
            } else {
                host = hostPort
                port = null
            }
            val query = queryString.split('&')
                .filter { it.isNotEmpty() }
                .associate {
                    val key = it.substringBefore('=')
                    val value = URLDecoder.decode(it.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
                    key to value
                }
            return SimpleUrl(userInfo, host, port, query, fragment)
        }
    }
}
