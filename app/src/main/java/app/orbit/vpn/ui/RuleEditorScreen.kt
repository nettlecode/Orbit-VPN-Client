package app.orbit.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.vpn.R
import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingRule

private enum class EditorType { SUFFIX, KEYWORD, IP, APP }

private val DOMAIN = Regex("""^[a-z0-9_-]+(\.[a-z0-9_-]+)*$""")
private val KEYWORD = Regex("""^[a-z0-9._-]+$""")
private val CIDR = Regex("""^[0-9a-f:.]+(/\d{1,3})?$""")
private val ASN = Regex("""^as(\d{1,10})$""")

/** Creates or edits a user rule: domains, keywords, IP ranges or apps, with a policy. */
@Composable
fun RuleEditorScreen(
    initial: RoutingRule?,
    onClose: () -> Unit,
    onSave: (RoutingRule) -> Unit,
    onDelete: (String) -> Unit,
    domain: String = "",
) {
    val context = LocalContext.current
    var type by remember {
        mutableStateOf(
            when {
                initial == null -> EditorType.SUFFIX
                initial.packages.isNotEmpty() -> EditorType.APP
                initial.keywords.isNotEmpty() -> EditorType.KEYWORD
                initial.ipCidrs.isNotEmpty() || initial.asns.isNotEmpty() -> EditorType.IP
                else -> EditorType.SUFFIX
            },
        )
    }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var text by remember {
        mutableStateOf(
            (initial?.let { it.suffixes + it.domains + it.keywords + it.ipCidrs + it.asns.map { asn -> "AS$asn" } }.orEmpty())
                .joinToString("\n").ifEmpty { domain },
        )
    }
    var packages by remember { mutableStateOf(initial?.packages?.toSet().orEmpty()) }
    var policy by remember { mutableIntStateOf((initial?.policy ?: Policy.PROXY).ordinal) }
    var appQuery by remember { mutableStateOf("") }

    val values = text.split('\n', ',', ' ').map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }
    val pattern = when (type) {
        EditorType.SUFFIX -> DOMAIN
        EditorType.KEYWORD -> KEYWORD
        EditorType.IP -> CIDR
        EditorType.APP -> null
    }
    // The IP type also takes AS numbers, written as AS62041.
    val invalid = pattern?.let { p -> values.filterNot { p.matches(it) || (type == EditorType.IP && ASN.matches(it)) } }.orEmpty()
    val canSave = if (type == EditorType.APP) packages.isNotEmpty() else values.isNotEmpty() && invalid.isEmpty()

    fun build() = RoutingRule(
        policy = Policy.entries[policy],
        suffixes = if (type == EditorType.SUFFIX) values else emptyList(),
        keywords = if (type == EditorType.KEYWORD) values else emptyList(),
        ipCidrs = if (type == EditorType.IP) values.filterNot(ASN::matches) else emptyList(),
        asns = if (type == EditorType.IP) values.mapNotNull { ASN.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.distinct() else emptyList(),
        packages = if (type == EditorType.APP) packages.toList() else emptyList(),
        name = name.trim().ifEmpty { null },
        enabled = initial?.enabled ?: true,
        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
    )

    val apps = produceState<List<AppEntry>?>(null, type) {
        if (type == EditorType.APP) value = Apps.launchable(context)
    }.value

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(
                stringResource(if (initial == null) R.string.rule_add else R.string.rule_edit),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (initial != null) {
                TextButton(onClick = { onDelete(initial.id) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Label(stringResource(R.string.rule_type))
                PillTabs(
                    listOf(
                        stringResource(R.string.rule_type_domain),
                        stringResource(R.string.rule_type_keyword),
                        stringResource(R.string.rule_kind_ip),
                        stringResource(R.string.rule_kind_app),
                    ),
                    type.ordinal,
                    { type = EditorType.entries[it] },
                )
            }
            item {
                Label(stringResource(R.string.rule_policy))
                PillTabs(
                    Policy.entries.map { policyLabel(it) },
                    policy,
                    { policy = it },
                )
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (type != EditorType.APP) {
                item {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = {
                            Text(
                                stringResource(
                                    when (type) {
                                        EditorType.SUFFIX -> R.string.rule_values_domain
                                        EditorType.KEYWORD -> R.string.rule_values_keyword
                                        else -> R.string.rule_values_ip
                                    },
                                ),
                            )
                        },
                        placeholder = {
                            Text(
                                when (type) {
                                    EditorType.SUFFIX -> "google.com\nyoutube.com"
                                    EditorType.KEYWORD -> "google"
                                    else -> "149.154.160.0/20"
                                },
                            )
                        },
                        isError = invalid.isNotEmpty(),
                        supportingText = {
                            Text(
                                if (invalid.isNotEmpty()) {
                                    stringResource(R.string.rule_invalid, invalid.take(3).joinToString(", "))
                                } else {
                                    stringResource(
                                        when (type) {
                                            EditorType.SUFFIX -> R.string.rule_values_domain_hint
                                            EditorType.IP -> R.string.rule_values_ip_hint
                                            else -> R.string.rule_values_hint
                                        },
                                    )
                                },
                            )
                        },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item {
                    TextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        placeholder = { Text(stringResource(R.string.search_apps)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (apps == null) {
                    item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                } else {
                    // Selected apps first, then the rest alphabetically.
                    val filtered = apps
                        .filter { appQuery.isBlank() || it.label.contains(appQuery, true) || it.packageName.contains(appQuery, true) }
                        .sortedByDescending { it.packageName in packages }
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in packages
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { packages = if (checked) packages - app.packageName else packages + app.packageName }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onSave(build()) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(R.string.rule_save), modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
    )
}
