package app.orbit.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import app.orbit.vpn.R
import app.orbit.vpn.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Composable
fun NodeEditorScreen(node: ProxyNode, onBack: () -> Unit, onSave: (ProxyNode) -> Unit, creating: Boolean = false) {
    var fields by rememberSaveable(node.id) { mutableStateOf(nodeFields(node)) }
    var transport by rememberSaveable(node.id) { mutableStateOf(node.transport?.type ?: "tcp") }
    var security by rememberSaveable(node.id) { mutableStateOf(if (node.tls?.realityPublicKey != null) "reality" else if (node.tls != null) "tls" else "none") }
    var insecure by rememberSaveable(node.id) { mutableStateOf(node.tls?.insecure ?: false) }
    var discard by remember { mutableStateOf(false) }
    val dirty = fields != nodeFields(node) || transport != (node.transport?.type ?: "tcp") ||
        security != (if (node.tls?.realityPublicKey != null) "reality" else if (node.tls != null) "tls" else "none") || insecure != (node.tls?.insecure ?: false)
    val leave = { if (dirty) discard = true else onBack() }
    BackHandler(onBack = leave)
    val edited = runCatching { editedNode(node, fields, transport, security, insecure) }.getOrNull()
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(stringResource(if (creating) R.string.node_create_title else R.string.node_editor_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(enabled = edited != null && (creating || dirty), onClick = { edited?.let(onSave) }) { Text(stringResource(R.string.node_editor_save)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (node.subscriptionId != null) Text(stringResource(R.string.node_editor_subscription), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EditorCard(node.type.label) {
                EditorField("name", fields, { fields = it }, R.string.node_field_name)
                EditorField("server", fields, { fields = it }, R.string.node_field_server)
                EditorField("port", fields, { fields = it }, R.string.node_field_port, numeric = true)
            }
            EditorCard(stringResource(R.string.node_editor_credentials)) {
                if (node.type in listOf(ProxyType.VMESS, ProxyType.VLESS, ProxyType.TUIC)) EditorField("uuid", fields, { fields = it }, R.string.node_field_uuid)
                if (node.type in listOf(ProxyType.SOCKS, ProxyType.HTTP)) EditorField("username", fields, { fields = it }, R.string.node_field_username)
                if (node.type != ProxyType.VMESS && node.type != ProxyType.VLESS) EditorField("password", fields, { fields = it }, R.string.node_field_password, secret = true)
                if (node.type in listOf(ProxyType.SHADOWSOCKS, ProxyType.VMESS)) EditorField("method", fields, { fields = it }, R.string.node_field_method)
                when (node.type) {
                    ProxyType.VMESS -> EditorField("alterId", fields, { fields = it }, R.string.node_field_alter_id, numeric = true)
                    ProxyType.VLESS -> EditorField("flow", fields, { fields = it }, R.string.node_field_flow)
                    ProxyType.SHADOWSOCKS -> {
                        EditorField("plugin", fields, { fields = it }, R.string.node_field_plugin)
                        EditorField("pluginOpts", fields, { fields = it }, R.string.node_field_plugin_opts)
                    }
                    ProxyType.HYSTERIA2 -> EditorField("obfsPassword", fields, { fields = it }, R.string.node_field_obfs, secret = true)
                    ProxyType.TUIC -> {
                        EditorField("congestionControl", fields, { fields = it }, R.string.node_field_congestion)
                        EditorField("udpRelayMode", fields, { fields = it }, R.string.node_field_udp_mode)
                    }
                    else -> Unit
                }
            }
            if (node.type in listOf(ProxyType.VMESS, ProxyType.VLESS, ProxyType.TROJAN)) {
                EditorCard(stringResource(R.string.node_editor_transport)) {
                    EditorChoice(transport, listOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp")) { transport = it }
                    if (transport == "grpc") EditorField("serviceName", fields, { fields = it }, R.string.node_field_service)
                    if (transport !in listOf("tcp", "grpc")) {
                        EditorField("host", fields, { fields = it }, R.string.node_field_host)
                        EditorField("path", fields, { fields = it }, R.string.node_field_path)
                    }
                    if (transport == "xhttp") {
                        EditorField("mode", fields, { fields = it }, R.string.node_field_xhttp_mode)
                        EditorField("extra", fields, { fields = it }, R.string.node_field_extra, multiline = true)
                    }
                }
            }
            if (node.type !in listOf(ProxyType.SHADOWSOCKS, ProxyType.SOCKS)) {
                EditorCard("TLS / Reality") {
                    val choices = when (node.type) {
                        ProxyType.HYSTERIA2, ProxyType.TUIC -> listOf("tls")
                        ProxyType.TROJAN -> listOf("tls", "reality")
                        ProxyType.VLESS -> listOf("none", "tls", "reality")
                        else -> listOf("none", "tls")
                    }
                    EditorChoice(security, choices) { security = it }
                    if (security != "none") {
                        EditorField("serverName", fields, { fields = it }, R.string.node_field_sni)
                        EditorField("alpn", fields, { fields = it }, R.string.node_field_alpn)
                        if (node.type in listOf(ProxyType.VLESS, ProxyType.VMESS, ProxyType.TROJAN, ProxyType.HTTP)) EditorField("fingerprint", fields, { fields = it }, R.string.node_field_fingerprint)
                        if (security == "reality") {
                            EditorField("publicKey", fields, { fields = it }, R.string.node_field_public_key)
                            EditorField("shortId", fields, { fields = it }, R.string.node_field_short_id)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.node_field_insecure), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = insecure, onCheckedChange = { insecure = it })
                        }
                    }
                }
            }
            if (edited == null && dirty) Text(stringResource(R.string.node_editor_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.node_editor_discard)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun EditorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OrbitCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EditorChoice(value: String, choices: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; onChange(item) }) }
        }
    }
}

@Composable
private fun EditorField(key: String, fields: Map<String, String>, onChange: (Map<String, String>) -> Unit, label: Int, secret: Boolean = false, numeric: Boolean = false, multiline: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = fields[key].orEmpty(), onValueChange = { onChange(fields + (key to it)) },
        label = { Text(stringResource(label)) }, singleLine = !multiline,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
        trailingIcon = if (secret) { { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.node_editor_visibility)) } } } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun nodeFields(node: ProxyNode): Map<String, String> = mapOf(
    "name" to node.name, "server" to node.server, "port" to node.port.toString(),
    "uuid" to node.uuid.orEmpty(), "password" to node.password.orEmpty(), "username" to node.username.orEmpty(),
    "method" to node.method.orEmpty(), "alterId" to node.alterId.toString(), "flow" to node.flow.orEmpty(),
    "plugin" to node.plugin.orEmpty(), "pluginOpts" to node.pluginOpts.orEmpty(), "obfsPassword" to node.obfsPassword.orEmpty(),
    "congestionControl" to node.congestionControl.orEmpty(), "udpRelayMode" to node.udpRelayMode.orEmpty(),
    "host" to node.transport?.host.orEmpty(), "path" to node.transport?.path.orEmpty(), "serviceName" to node.transport?.serviceName.orEmpty(),
    "mode" to node.transport?.mode.orEmpty(), "extra" to node.transport?.extra.orEmpty(),
    "serverName" to node.tls?.serverName.orEmpty(), "alpn" to node.tls?.alpn.orEmpty().joinToString(","),
    "fingerprint" to node.tls?.fingerprint.orEmpty(), "publicKey" to node.tls?.realityPublicKey.orEmpty(), "shortId" to node.tls?.realityShortId.orEmpty(),
)

internal fun editedNode(node: ProxyNode, fields: Map<String, String>, transport: String, security: String, insecure: Boolean): ProxyNode {
    fun text(key: String) = fields[key].orEmpty().trim()
    fun optional(key: String) = text(key).ifEmpty { null }
    fun secret(key: String) = fields[key]?.takeIf { it.isNotEmpty() }
    require(text("name").isNotBlank())
    require(text("server").isNotBlank() && text("server").none { it.isWhitespace() || it in "/?#@[]" })
    val port = text("port").toInt(); require(port in 1..65535)
    if (node.type in listOf(ProxyType.VMESS, ProxyType.VLESS, ProxyType.TUIC)) {
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(text("uuid")))
        UUID.fromString(text("uuid"))
    }
    if (node.type in listOf(ProxyType.SHADOWSOCKS, ProxyType.TROJAN, ProxyType.TUIC, ProxyType.HYSTERIA2)) require(secret("password") != null)
    if (node.type in listOf(ProxyType.SHADOWSOCKS, ProxyType.VMESS)) require(optional("method") != null)
    if (node.type in listOf(ProxyType.SOCKS, ProxyType.HTTP) && secret("password") != null) require(optional("username") != null)
    val alterId = text("alterId").toInt(); require(alterId >= 0)
    if (security == "reality") {
        require(Regex("[A-Za-z0-9_-]{43}").matches(text("publicKey")))
        require(text("shortId").length % 2 == 0 && Regex("[0-9a-fA-F]{0,16}").matches(text("shortId")))
    }
    if (transport == "xhttp") {
        require(text("mode") in listOf("", "auto", "packet-up", "stream-up", "stream-one"))
        optional("extra")?.let { require(Json.parseToJsonElement(it) is JsonObject) }
    }
    return node.copy(
        name = text("name"), server = text("server"), port = port, uuid = optional("uuid"), password = secret("password"),
        username = optional("username"), method = optional("method"), alterId = alterId, flow = optional("flow"),
        plugin = optional("plugin"), pluginOpts = optional("pluginOpts"), obfsPassword = secret("obfsPassword"),
        congestionControl = optional("congestionControl"), udpRelayMode = optional("udpRelayMode"),
        transport = if (transport == "tcp") null else TransportSettings(
            transport, path = if (transport != "grpc") optional("path") else null,
            host = if (transport != "grpc") optional("host") else null,
            serviceName = if (transport == "grpc") optional("serviceName") else null,
            mode = if (transport == "xhttp") optional("mode") else null, extra = if (transport == "xhttp") optional("extra") else null,
        ),
        tls = if (security == "none") null else TlsSettings(
            serverName = optional("serverName"), alpn = text("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            insecure = insecure, fingerprint = optional("fingerprint"),
            realityPublicKey = if (security == "reality") optional("publicKey") else null,
            realityShortId = if (security == "reality") optional("shortId") else null,
        ),
    )
}
