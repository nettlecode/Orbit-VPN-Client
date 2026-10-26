package app.orbit.vpn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.orbit.vpn.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) viewModel.connect()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::import)
    }

    private val rulesFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importRulesFile)
    }

    private val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportBackup)
    }

    private val backupImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::readBackup)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestNotificationPermission()
        setContent {
            OrbitTheme {
                val snackbar = remember { SnackbarHostState() }
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var routingMode by rememberSaveable { mutableStateOf(false) }
                var connections by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    viewModel.messages.collect { snackbar.showSnackbar(it) }
                }
                BackHandler(enabled = routingMode || connections || tab != Tab.HOME) {
                    when {
                        connections -> connections = false
                        routingMode -> routingMode = false
                        else -> tab = Tab.HOME
                    }
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item && !routingMode && !connections,
                                    onClick = {
                                        routingMode = false
                                        connections = false
                                        tab = item
                                    },
                                    icon = { Icon(item.icon, null) },
                                    label = { Text(stringResource(item.label), maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        when {
                            connections -> ConnectionsScreen(onBack = { connections = false })
                            routingMode -> RoutingModeScreen(
                                viewModel = viewModel,
                                onBack = { routingMode = false },
                                onConfigureRules = {
                                    routingMode = false
                                    tab = Tab.RULES
                                },
                            )

                            tab == Tab.HOME -> HomeScreen(
                                viewModel = viewModel,
                                onConnect = ::connect,
                                onOpenProfiles = { tab = Tab.PROFILES },
                                onOpenRoutingMode = { routingMode = true },
                            )

                            tab == Tab.PROFILES -> ProfilesScreen(viewModel = viewModel, onScanQr = ::scanQr)
                            tab == Tab.RULES -> RulesScreen(
                                viewModel = viewModel,
                                onPickRulesFile = { rulesFilePicker.launch(arrayOf("*/*")) },
                            )

                            tab == Tab.TRAFFIC -> TrafficScreen(
                                viewModel = viewModel,
                                onOpenConnections = { connections = true },
                            )

                            tab == Tab.SETTINGS -> SettingsScreen(
                                viewModel = viewModel,
                                onExportBackup = {
                                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    backupExport.launch("orbit-backup-$date.json")
                                },
                                // Some file managers don't tag .json files with a JSON MIME type.
                                onImportBackup = { backupImport.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                }
            }
        }
    }

    private enum class Tab(val label: Int, val icon: ImageVector) {
        HOME(R.string.tab_home, Icons.Default.Home),
        PROFILES(R.string.tab_profiles, Icons.Default.Dns),
        RULES(R.string.tab_rules, Icons.Default.Rule),
        TRAFFIC(R.string.tab_traffic, Icons.Default.Insights),
        SETTINGS(R.string.settings, Icons.Default.Settings),
    }

    private fun connect() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else viewModel.connect()
    }

    private fun scanQr() {
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun importRulesFile(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        viewModel.importRulesText(text, name)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
