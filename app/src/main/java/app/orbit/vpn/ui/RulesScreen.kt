package app.orbit.vpn.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R
import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig
import app.orbit.vpn.model.RuleKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: MainViewModel, onPickRulesFile: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val store by viewModel.rules.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var openConfigId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<RoutingRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<RoutingRule?>(null) }
    var addSheet by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var newConfigDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<RuleConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<RuleConfig?>(null) }

    // Null while the main list is shown, or if the open config was deleted.
    val openConfig = openConfigId?.let { id -> store.all.firstOrNull { it.id == id } }

    if (creating || editing != null) {
        val close = {
            creating = false
            editing = null
        }
        BackHandler(onBack = close)
        RuleEditorScreen(
            initial = editing,
            onClose = close,
            onSave = { rule ->
                if (openConfig != null) viewModel.saveConfigRule(openConfig.id, rule) else viewModel.saveUserRule(rule)
                close()
            },
            onDelete = { id ->
                if (openConfig != null) viewModel.removeConfigRule(openConfig.id, id) else viewModel.removeUserRule(id)
                close()
            },
        )
        return
    }

    if (openConfig != null) {
        BackHandler { openConfigId = null }
        RuleConfigScreen(
            config = openConfig,
            active = openConfig.id == store.activeId,
            busy = busy,
            onBack = { openConfigId = null },
            onActivate = { viewModel.activateRuleConfig(openConfig.id) },
            onAddRule = { creating = true },
            onOpenRule = { rule -> if (!openConfig.isDefault && rule.fitsEditor()) editing = rule else details = rule },
            onToggleRule = { rule, enabled -> viewModel.setConfigRuleEnabled(openConfig.id, rule.id, enabled) },
            onEdit = { editingConfig = openConfig },
            onReimport = { viewModel.reimportRules(openConfig) },
            onDelete = { pendingDelete = openConfig },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.tab_rules)) {
                IconButton(onClick = { addSheet = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.action_add_short))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "configs-title") {
                    GroupHeader(stringResource(R.string.rules_configs), stringResource(R.string.rules_configs_hint))
                }
                items(store.all, key = { "c-" + it.id }) { config ->
                    ConfigRow(
                        config = config,
                        active = config.id == store.activeId,
                        busy = busy,
                        onActivate = { viewModel.activateRuleConfig(config.id) },
                        onOpen = { openConfigId = config.id },
                        onEdit = { editingConfig = config },
                        onReimport = { viewModel.reimportRules(config) },
                        onDelete = { pendingDelete = config },
                    )
                }

                item(key = "user-title") {
                    GroupHeader(stringResource(R.string.rules_mine), stringResource(R.string.rules_mine_hint))
                }
                if (state.userRules.isEmpty()) {
                    item(key = "user-empty") {
                        AddRuleCard(stringResource(R.string.rules_mine_empty)) { creating = true }
                    }
                }
                items(state.userRules, key = { "u-" + it.id }) { rule ->
                    RuleRow(rule, onToggle = { viewModel.setUserRuleEnabled(rule.id, it) }) { editing = rule }
                }
            }
        }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                SheetItem(Icons.Default.Rule, stringResource(R.string.rule_add_mine)) {
                    addSheet = false
                    creating = true
                }
                SheetItem(Icons.Default.CreateNewFolder, stringResource(R.string.config_new)) {
                    addSheet = false
                    newConfigDialog = true
                }
                SheetItem(Icons.Default.Link, stringResource(R.string.rules_import_url)) {
                    addSheet = false
                    urlDialog = true
                }
                SheetItem(Icons.Default.FileOpen, stringResource(R.string.rules_import_file)) {
                    addSheet = false
                    onPickRulesFile()
                }
            }
        }
    }
    if (urlDialog) {
        TextInputDialog(
            title = stringResource(R.string.rules_import_url),
            placeholder = "https://…/config.conf",
            confirm = stringResource(R.string.action_import),
            onDismiss = { urlDialog = false },
            onConfirm = {
                urlDialog = false
                viewModel.importRules(it)
            },
        )
    }
    if (newConfigDialog) {
        ConfigDialog(
            initial = null,
            onDismiss = { newConfigDialog = false },
            onSave = { name, policy ->
                newConfigDialog = false
                openConfigId = viewModel.createRuleConfig(name, policy)
            },
        )
    }
    editingConfig?.let { config ->
        ConfigDialog(
            initial = config,
            onDismiss = { editingConfig = null },
            onSave = { name, policy ->
                editingConfig = null
                viewModel.editRuleConfig(config.id, name, policy)
            },
        )
    }
    pendingDelete?.let { config ->
        ConfirmDialog(
            title = stringResource(R.string.config_delete_title),
            text = config.name,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                if (openConfigId == config.id) openConfigId = null
                viewModel.removeRuleConfig(config.id)
            },
        )
    }
    details?.let { rule ->
        RuleDetailsDialog(
            rule,
            onDelete = openConfig?.takeUnless { it.isDefault }?.let { config ->
                {
                    viewModel.removeConfigRule(config.id, rule.id)
                    details = null
                }
            },
        ) { details = null }
    }
}

/** Rules of one config, with search and, unless built-in, editing. */
@Composable
private fun RuleConfigScreen(
    config: RuleConfig,
    active: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onActivate: () -> Unit,
    onAddRule: () -> Unit,
    onOpenRule: (RoutingRule) -> Unit,
    onToggleRule: (RoutingRule, Boolean) -> Unit,
    onEdit: () -> Unit,
    onReimport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val editable = !config.isDefault
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    val kinds = listOf(null, RuleKind.DOMAIN, RuleKind.IP, RuleKind.APP)
    fun visible(rule: RoutingRule): Boolean {
        val kind = kinds[filter]
        if (kind != null && rule.kind != kind) return false
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return rule.name?.lowercase()?.contains(q) == true ||
            rule.values.any { it.contains(q) } ||
            rule.packages.any { Apps.label(context, it).lowercase().contains(q) }
    }
    val rules = config.rules.filter(::visible)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(
                configName(config),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                IconButton(onClick = onAddRule) { Icon(Icons.Default.Add, stringResource(R.string.rule_add)) }
                ConfigMenu(config, busy, onEdit, onReimport, onDelete)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_rules)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PillTabs(
            listOf(
                stringResource(R.string.filter_all),
                stringResource(R.string.rule_kind_domain),
                stringResource(R.string.rule_kind_ip),
                stringResource(R.string.rule_kind_app),
            ),
            filter,
            { filter = it },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                OrbitCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onActivate).padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = active, onClick = onActivate)
                            Text(stringResource(R.string.config_use), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (editable) Modifier.clickable(onClick = onEdit) else Modifier)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.config_final), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(policyLabel(config.finalPolicy), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (!editable) {
                    Text(
                        stringResource(R.string.config_builtin_readonly),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            }
            if (config.rules.isEmpty() && editable) {
                item(key = "empty") { AddRuleCard(stringResource(R.string.config_empty), onAddRule) }
            }
            items(rules, key = { it.id }) { rule ->
                RuleRow(rule, toggleable = editable, onToggle = { onToggleRule(rule, it) }) { onOpenRule(rule) }
            }
        }
    }
}

@Composable
private fun ConfigRow(
    config: RuleConfig,
    active: Boolean,
    busy: Boolean,
    onActivate: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onReimport: () -> Unit,
    onDelete: () -> Unit,
) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onActivate)
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    configName(config),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(configSummary(config), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (config.isDefault) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                ConfigMenu(config, busy, onEdit, onReimport, onDelete)
            }
        }
    }
}

@Composable
private fun ConfigMenu(config: RuleConfig, busy: Boolean, onEdit: () -> Unit, onReimport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.config_edit)) }, onClick = {
                menu = false
                onEdit()
            })
            if (config.sourceUrl != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rules_reimport)) }, enabled = !busy, onClick = {
                    menu = false
                    onReimport()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                menu = false
                onDelete()
            })
        }
    }
}

/** Creates a config (when [initial] is null) or edits its name and fallback policy. */
@Composable
private fun ConfigDialog(initial: RuleConfig?, onDismiss: () -> Unit, onSave: (String, Policy) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var policy by remember { mutableIntStateOf((initial?.finalPolicy ?: Policy.PROXY).ordinal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.config_new else R.string.config_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.config_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text(
                        stringResource(R.string.config_final),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                    PillTabs(Policy.entries.map { policyLabel(it) }, policy, { policy = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, Policy.entries[policy]) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.rule_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
fun configName(config: RuleConfig): String =
    if (config.isDefault) stringResource(R.string.rules_builtin_short) else config.name

@Composable
private fun configSummary(config: RuleConfig): String {
    if (config.isDefault) return stringResource(R.string.rules_builtin)
    val context = LocalContext.current
    return buildString {
        append(context.getString(R.string.config_summary, config.rules.size, config.ruleCount))
        if (config.skipped > 0) append(context.getString(R.string.rules_skipped, config.skipped))
        if (config.importedAt > 0) {
            append("\n")
            append(DateUtils.formatDateTime(context, config.importedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
        }
    }
}

/** The rule editor handles one matcher type per rule; other (imported) rules are view-only. */
private fun RoutingRule.fitsEditor(): Boolean =
    geoip == null && listOf(domains + suffixes, keywords, ipCidrs + asns.map { "AS$it" }, packages).count { it.isNotEmpty() } == 1

@Composable
private fun AddRuleCard(text: String, onClick: () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.Add)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GroupHeader(title: String, hint: String) {
    Column(Modifier.padding(top = 10.dp, start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun policyLabel(policy: Policy) = stringResource(
    when (policy) {
        Policy.PROXY -> R.string.policy_proxy
        Policy.DIRECT -> R.string.policy_direct
        Policy.REJECT -> R.string.policy_reject
    },
)

@Composable
private fun ruleTitle(rule: RoutingRule): String {
    rule.name?.takeIf { it.isNotBlank() }?.let { return it }
    val context = LocalContext.current
    rule.geoip?.let { return "GeoIP: ${it.uppercase()}" }
    val values = if (rule.packages.isNotEmpty()) rule.packages.map { Apps.label(context, it) } else rule.values
    val first = values.firstOrNull().orEmpty()
    return if (values.size > 1) stringResource(R.string.rule_title_more, first, values.size - 1) else first
}

@Composable
private fun RuleRow(rule: RoutingRule, toggleable: Boolean = true, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                rule.packages.isNotEmpty() -> AppIcon(rule.packages.first(), 40.dp)
                rule.geoip != null -> FlagOrIcon(rule.geoip)
                rule.kind == RuleKind.DOMAIN -> IconTile(Icons.Default.Language)
                else -> IconTile(Icons.Default.Router)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ruleTitle(rule), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${kindLabel(rule)} · ${policyLabel(rule.policy)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (rule.policy) {
                        Policy.REJECT -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (toggleable) Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun FlagOrIcon(country: String) {
    val flag = remember(country) { Flags.emojiFor(country.uppercase()) }
    if (flag != null) FlagAvatar(country.uppercase(), 40.dp) else IconTile(Icons.Default.Public)
}

@Composable
private fun kindLabel(rule: RoutingRule): String = when {
    rule.geoip != null -> stringResource(R.string.rule_kind_geoip)
    rule.kind == RuleKind.APP -> stringResource(R.string.rule_kind_app)
    rule.kind == RuleKind.DOMAIN -> stringResource(R.string.rule_kind_domain)
    rule.ipCidrs.isEmpty() && rule.asns.isNotEmpty() -> "ASN"
    else -> stringResource(R.string.rule_kind_ip)
} + if (rule.size > 1 && rule.geoip == null) " (${rule.size})" else ""

@Composable
private fun RuleDetailsDialog(rule: RoutingRule, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kindLabel(rule)} · ${policyLabel(rule.policy)}") },
        text = {
            val shown = rule.values.take(200)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                shown.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (rule.values.size > shown.size) {
                    Text(
                        stringResource(R.string.rule_more_values, rule.values.size - shown.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = onDelete?.let { delete ->
            {
                TextButton(onClick = delete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
