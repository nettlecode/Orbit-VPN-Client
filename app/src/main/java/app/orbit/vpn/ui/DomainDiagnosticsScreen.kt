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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(stringResource(R.string.diagnostic_title), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.diagnostic_hint))
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            enabled = !running, label = { Text(stringResource(R.string.diagnostic_domain)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.diagnoseDomain(input) }, enabled = !running && input.isNotBlank()) {
            Text(stringResource(R.string.diagnostic_run))
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { value ->
            Text(value.domain, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diagnostic_dns))
            Text(value.dnsError ?: value.addresses.joinToString("\n"))
            if (value.dnsError == null) {
                Text(stringResource(R.string.diagnostic_target, value.addresses.first()))
                Text(value.httpStatus ?: "${value.stage}: ${value.error}", color =
                    if (value.error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.diagnostic_duration, value.elapsedMs))
                Text(stringResource(R.string.diagnostic_http_hint))
                val connection = value.connection
                Text(stringResource(R.string.diagnostic_route))
                if (connection == null) {
                    Text(stringResource(R.string.diagnostic_route_unknown))
                } else {
                    Text((connection.chain.ifEmpty { listOf(connection.outbound) }).joinToString(" → ") { tag ->
                        state.nodes.firstOrNull { it.id == tag }?.name ?: tag
                    })
                    Text(connection.outboundType)
                    Text(stringResource(R.string.diagnostic_rule, connection.rule.ifEmpty {
                        stringResource(R.string.diagnostic_final)
                    }))
                }
            }
            TextButton(onClick = { ruleDomain = value.domain }) {
                Text(stringResource(R.string.diagnostic_add_rule))
            }
        }
    }
}
