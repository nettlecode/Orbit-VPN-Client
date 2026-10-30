package app.orbit.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R

@Composable
fun DomainDiagnosticsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var ruleDomain by rememberSaveable { mutableStateOf<String?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.diagnostic.collectAsStateWithLifecycle()
    val running by viewModel.diagnosing.collectAsStateWithLifecycle()
    val domain = ruleDomain
    if (domain != null) {
        BackHandler { ruleDomain = null }
        RuleEditorScreen(
            initial = null, domain = domain,
            onClose = { ruleDomain = null },
            onSave = { viewModel.saveUserRule(it); ruleDomain = null },
            onDelete = {},
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(stringResource(R.string.diagnostic_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DiagnosticCard {
                Text(stringResource(R.string.diagnostic_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    enabled = !running, label = { Text(stringResource(R.string.diagnostic_domain)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.diagnoseDomain(input) },
                    enabled = !running && input.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.diagnostic_run)) }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            result?.let { value ->
                Text(value.domain, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                DiagnosticCard {
                    DiagnosticHeading(stringResource(R.string.diagnostic_dns))
                    Text(value.dnsError ?: value.addresses.joinToString("\n"), color =
                        if (value.dnsError == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                if (value.dnsError == null) {
                    DiagnosticCard {
                        DiagnosticHeading(stringResource(R.string.diagnostic_target, value.addresses.first()))
                        Text(value.httpStatus ?: "${value.stage}: ${value.error}", color =
                            if (value.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.diagnostic_duration, value.elapsedMs), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.diagnostic_http_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DiagnosticCard {
                        DiagnosticHeading(stringResource(R.string.diagnostic_route))
                        val connection = value.connection
                        if (connection == null) {
                            Text(stringResource(R.string.diagnostic_route_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text((connection.chain.ifEmpty { listOf(connection.outbound) }).joinToString(" → ") { tag ->
                                state.nodes.firstOrNull { it.id == tag }?.name ?: tag
                            })
                            Text(connection.outboundType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(stringResource(R.string.diagnostic_rule, connection.rule.ifEmpty {
                                stringResource(R.string.diagnostic_final)
                            }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedButton(onClick = { ruleDomain = value.domain }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostic_add_rule))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(content: @Composable ColumnScope.() -> Unit) {
    OrbitCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun DiagnosticHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
