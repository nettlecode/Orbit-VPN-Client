package app.orbit.vpn.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.vpn.R
import app.orbit.vpn.data.Updates
import io.nekohasekai.libbox.Libbox

/** Home screen banner for a newer release. */
@Composable
fun UpdateBanner(version: String, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    OrbitCard(modifier.fillMaxWidth(), highlighted = true, onClick = onOpen) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.SystemUpdate, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_available, version), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.update_banner_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.update_dismiss), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun UpdateDialog(state: MainViewModel.UpdateState, onInstall: () -> Unit, onClose: () -> Unit) {
    val release = state.release ?: return
    val context = LocalContext.current
    val apk = Updates.apkFor(release)
    val downloading = state.progress != null
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Default.SystemUpdate, null) },
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column {
                if (release.notes.isNotEmpty()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                }
                apk?.let {
                    Text(
                        stringResource(R.string.update_size, Libbox.formatBytes(it.size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.update_keeps_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.progress?.let { progress ->
                    Spacer(Modifier.padding(top = 12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.update_downloading, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = apk != null && !downloading) {
                Text(stringResource(if (state.file != null) R.string.update_install else R.string.update_download))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
                }) {
                    Text(stringResource(R.string.update_release_page))
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}
