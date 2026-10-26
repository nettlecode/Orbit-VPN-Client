package app.orbit.vpn.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.vpn.BuildConfig
import app.orbit.vpn.OrbitApp
import app.orbit.vpn.R
import app.orbit.vpn.data.Backup
import app.orbit.vpn.data.SubscriptionUpdater
import app.orbit.vpn.data.Updates
import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingMode
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig
import app.orbit.vpn.model.Subscription
import app.orbit.vpn.model.upsert
import app.orbit.vpn.parser.LinkParser
import app.orbit.vpn.service.OrbitVpnService
import app.orbit.vpn.service.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApp
    private val repository = app.repository
    private val updater = SubscriptionUpdater(app, repository)

    val state = repository.state
    val rules = repository.rules
    val status = ServiceState.status
    val error = ServiceState.error
    val traffic = ServiceState.traffic
    val startedAt = ServiceState.startedAt
    val samples = ServiceState.samples
    val auto = ServiceState.auto
    val systemVpn = ServiceState.systemVpn
    val trafficHistory = app.trafficHistory

    private val _latency = MutableStateFlow<Map<String, Latency>>(emptyMap())
    val latency: StateFlow<Map<String, Latency>> = _latency.asStateFlow()

    /** True while a subscription or rule download is in progress. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        // Auto-select measures its servers in the running tunnel; show those results too.
        viewModelScope.launch {
            var previous = emptyMap<String, Int>()
            auto.collect { status ->
                val delays = status?.delays.orEmpty()
                // A server drops out of the measurements when a connection through it fails.
                val failed = (previous.keys - delays.keys).takeIf { status != null }.orEmpty()
                previous = delays
                _latency.update { latency ->
                    latency + failed.associateWith { Latency.Failed } + delays.mapValues { (_, ms) -> Latency.Ok(ms.toLong()) }
                }
            }
        }
    }

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private fun message(@StringRes id: Int, vararg args: Any) {
        _messages.trySend(app.getString(id, *args))
    }

    /** Runs a network task, reporting failures as a message. */
    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                message(R.string.error_generic, e.message ?: e.toString())
            } finally {
                _busy.value = false
            }
        }
    }

    /** Imports share links, or adds a subscription if the text is a single http(s) URL. */
    fun import(text: String) {
        val trimmed = text.trim()
        if (trimmed.matches(Regex("""https?://\S+"""))) {
            addSubscription(trimmed, null)
            return
        }
        val nodes = LinkParser.parseAll(trimmed)
        if (nodes.isEmpty()) {
            message(R.string.import_nothing)
        } else {
            repository.add(nodes)
            _messages.trySend(app.resources.getQuantityString(R.plurals.imported_servers, nodes.size, nodes.size))
        }
    }

    fun addSubscription(url: String, name: String?) = launchBusy {
        val count = updater.add(url, name)
        _messages.trySend(app.resources.getQuantityString(R.plurals.imported_servers, count, count))
    }

    fun updateSubscription(subscription: Subscription) = launchBusy {
        val count = updater.update(subscription)
        message(R.string.subscription_updated, subscription.name, count)
    }

    fun updateAllSubscriptions() = launchBusy {
        val failed = updater.updateAll()
        if (failed > 0) message(R.string.subscriptions_failed, failed) else message(R.string.subscriptions_updated)
    }

    fun removeSubscription(id: String) {
        repository.removeSubscription(id)
        OrbitVpnService.reload(app)
    }

    fun importRules(url: String) = launchBusy {
        val count = updater.importRules(url.trim())
        message(R.string.rules_imported, count)
    }

    fun importRulesText(text: String, name: String) = launchBusy {
        val count = updater.importRulesText(text, name)
        message(R.string.rules_imported, count)
    }

    /** Downloads the config again from its source URL, replacing its rules. */
    fun reimportRules(config: RuleConfig) {
        val url = config.sourceUrl ?: return
        launchBusy {
            val count = updater.importRules(url, replace = config)
            message(R.string.rules_imported, count)
        }
    }

    fun saveUserRule(rule: RoutingRule) = withRuleSets(rule) {
        repository.saveUserRule(it)
        OrbitVpnService.reload(app)
    }

    fun removeUserRule(id: String) {
        repository.removeUserRule(id)
        OrbitVpnService.reload(app)
    }

    fun setUserRuleEnabled(id: String, enabled: Boolean) {
        repository.setUserRuleEnabled(id, enabled)
        OrbitVpnService.reload(app)
    }

    /** Creates an empty config; returns its id. */
    fun createRuleConfig(name: String, finalPolicy: Policy): String {
        val config = RuleConfig(name = name.trim(), rules = emptyList(), finalPolicy = finalPolicy)
        repository.saveRuleConfig(config)
        return config.id
    }

    fun editRuleConfig(id: String, name: String, finalPolicy: Policy) =
        updateConfig(id) { it.copy(name = name.trim(), finalPolicy = finalPolicy) }

    fun removeRuleConfig(id: String) {
        val wasActive = rules.value.activeId == id
        repository.removeRuleConfig(id)
        if (wasActive) OrbitVpnService.reload(app)
    }

    fun activateRuleConfig(id: String) {
        if (rules.value.activeId == id) return
        repository.setActiveRuleConfig(id)
        OrbitVpnService.reload(app)
    }

    fun saveConfigRule(configId: String, rule: RoutingRule) = withRuleSets(rule) { checked ->
        updateConfig(configId) { it.copy(rules = it.rules.upsert(checked)) }
    }

    /**
     * Downloads the ASN lists a rule needs before saving it, so the tunnel can start with it.
     * AS numbers the source doesn't know are dropped with a message; a rule left empty isn't saved.
     */
    private fun withRuleSets(rule: RoutingRule, save: (RoutingRule) -> Unit) {
        if (rule.asns.isEmpty()) {
            save(rule)
            return
        }
        launchBusy {
            val missing = withContext(Dispatchers.IO) { updater.downloadRuleSets(listOf(rule)) }
            if (missing.isNotEmpty()) message(R.string.asn_not_found, missing.joinToString { "AS$it" })
            val checked = rule.copy(asns = rule.asns - missing)
            if (checked.size > 0) save(checked)
        }
    }

    fun removeConfigRule(configId: String, ruleId: String) =
        updateConfig(configId) { config -> config.copy(rules = config.rules.filterNot { it.id == ruleId }) }

    fun setConfigRuleEnabled(configId: String, ruleId: String, enabled: Boolean) = updateConfig(configId) { config ->
        config.copy(rules = config.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
    }

    private fun updateConfig(id: String, block: (RuleConfig) -> RuleConfig) {
        repository.updateRuleConfig(id, block)
        if (rules.value.activeId == id) OrbitVpnService.reload(app)
    }

    /** A backup that was read and awaits the user's confirmation before replacing everything. */
    private val _pendingRestore = MutableStateFlow<Backup?>(null)
    val pendingRestore: StateFlow<Backup?> = _pendingRestore.asStateFlow()

    /** Password chosen in the export dialog, used once the user picks where to save. */
    private var exportPassword: String? = null

    fun setExportPassword(password: String?) {
        exportPassword = password
    }

    fun exportBackup(uri: Uri) = launchBusy {
        val password = exportPassword.also { exportPassword = null }
        // Key derivation takes a moment, so keep it off the main thread.
        val text = withContext(Dispatchers.Default) { Backup.encode(repository.backup(), password) }
        withContext(Dispatchers.IO) {
            val stream = app.contentResolver.openOutputStream(uri, "wt") ?: error("can't open $uri")
            stream.use { it.write(text.toByteArray()) }
        }
        message(if (password.isNullOrEmpty()) R.string.backup_exported else R.string.backup_exported_encrypted)
    }

    /** An encrypted backup that was read and waits for its password. */
    private val _lockedBackup = MutableStateFlow<Backup.Encrypted?>(null)
    val lockedBackup: StateFlow<Backup.Encrypted?> = _lockedBackup.asStateFlow()

    fun readBackup(uri: Uri) = launchBusy {
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: return@launchBusy
        try {
            when (val opened = Backup.open(text)) {
                is Backup.Opened.Plain -> _pendingRestore.value = opened.backup
                is Backup.Opened.Locked -> _lockedBackup.value = opened.encrypted
            }
        } catch (e: IllegalArgumentException) {
            message(R.string.backup_invalid)
        }
    }

    /** Shown inside the password dialog, where a snackbar would be hidden behind it. */
    private val _wrongPassword = MutableStateFlow(false)
    val wrongPassword: StateFlow<Boolean> = _wrongPassword.asStateFlow()

    /** Decrypts the locked backup; on a wrong password it stays locked so the user can retry. */
    fun unlockBackup(password: String) {
        val encrypted = _lockedBackup.value ?: return
        _wrongPassword.value = false
        launchBusy {
            try {
                _pendingRestore.value = withContext(Dispatchers.Default) { Backup.decrypt(encrypted, password) }
                _lockedBackup.value = null
            } catch (e: Backup.WrongPasswordException) {
                _wrongPassword.value = true
            }
        }
    }

    fun cancelUnlock() {
        _lockedBackup.value = null
        _wrongPassword.value = false
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore() {
        val backup = _pendingRestore.value ?: return
        _pendingRestore.value = null
        launchBusy {
            repository.restore(backup)
            _latency.value = emptyMap()
            SubscriptionUpdater.schedule(app, backup.state.autoUpdateSubscriptions)
            // Best effort, as on import: lets the tunnel start without reaching the list source.
            val rules = backup.state.userRules + backup.rules.configs.flatMap { it.rules }
            withContext(Dispatchers.IO) { updater.downloadRuleSets(rules) }
            OrbitVpnService.reload(app)
            message(R.string.backup_restored)
        }
    }

    fun select(id: String) {
        if (state.value.selectedId == id && !state.value.autoSelect) return
        repository.select(id)
        OrbitVpnService.reload(app)
    }

    fun selectAuto() {
        if (state.value.autoSelect) return
        repository.selectAuto()
        OrbitVpnService.reload(app)
    }

    fun remove(id: String) {
        repository.remove(id)
        _latency.update { it - id }
        if (state.value.autoSelect) OrbitVpnService.reload(app)
    }

    fun setMode(mode: RoutingMode) {
        if (state.value.mode == mode) return
        repository.setMode(mode)
        OrbitVpnService.reload(app)
    }

    fun setRemoteDns(dns: String) {
        repository.setRemoteDns(dns.trim())
        OrbitVpnService.reload(app)
    }

    fun setIpv6(enabled: Boolean) {
        repository.setIpv6(enabled)
        OrbitVpnService.reload(app)
    }

    fun setGeoipUrl(url: String) {
        repository.setGeoipUrl(url.trim())
        OrbitVpnService.reload(app)
    }

    fun setAsnUrl(url: String) {
        repository.setAsnUrl(url.trim())
        OrbitVpnService.reload(app)
    }

    fun setGeoipUpdateHours(hours: Int) {
        repository.setGeoipUpdateHours(hours)
        OrbitVpnService.reload(app)
    }

    fun setRestoreConnection(enabled: Boolean) = repository.setRestoreConnection(enabled)

    fun setCheckUpdates(enabled: Boolean) = repository.setCheckUpdates(enabled)

    /** A newer release and what is being done with it. */
    data class UpdateState(
        val release: Updates.Release? = null,
        /** Download progress from 0 to 1 while downloading. */
        val progress: Float? = null,
        /** Downloaded and verified, waiting for the system installer. */
        val file: File? = null,
        val dialog: Boolean = false,
        /** The banner on the home screen was closed for this version. */
        val dismissed: String? = null,
    ) {
        val showBanner: Boolean get() = release != null && release.version != dismissed
    }

    private val _update = MutableStateFlow(UpdateState(dismissed = Updates.dismissed(app)))
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** Automatic daily check; debug builds have no real version, so they only check on request. */
    private fun checkForUpdatesIfDue() {
        val due = System.currentTimeMillis() - Updates.lastCheck(app) > Updates.CHECK_INTERVAL_MS
        if (!BuildConfig.DEBUG && state.value.checkUpdates && due) checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            if (manual) _busy.value = true
            try {
                val release = withContext(Dispatchers.IO) { Updates.latest() }
                Updates.setLastCheck(app, System.currentTimeMillis())
                if (Updates.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    _update.update { it.copy(release = release, dialog = it.dialog || manual) }
                } else {
                    _update.update { it.copy(release = null) }
                    if (manual) message(R.string.update_latest, BuildConfig.VERSION_NAME)
                }
            } catch (e: Exception) {
                if (manual) message(R.string.error_generic, e.message ?: e.toString()) else Log.w("Updates", "check failed", e)
            } finally {
                if (manual) _busy.value = false
            }
        }
    }

    fun showUpdate() = _update.update { it.copy(dialog = true) }

    fun closeUpdate() = _update.update { it.copy(dialog = false) }

    fun dismissUpdate() {
        val version = _update.value.release?.version ?: return
        Updates.dismiss(app, version)
        _update.update { it.copy(dismissed = version) }
    }

    /** Downloads the APK for this device, verifies it and opens the system installer. */
    fun installUpdate() {
        val current = _update.value
        val release = current.release ?: return
        if (current.progress != null) return
        current.file?.takeIf { it.exists() }?.let {
            install(it)
            return
        }
        val asset = Updates.apkFor(release) ?: return message(R.string.update_no_apk)
        _update.update { it.copy(progress = 0f) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Updates.download(app, release, asset) { progress -> _update.update { it.copy(progress = progress) } }
                }
                _update.update { it.copy(file = file) }
                install(file)
            } catch (e: Updates.ChecksumException) {
                message(R.string.update_checksum)
            } catch (e: Exception) {
                message(R.string.error_generic, e.message ?: e.toString())
            } finally {
                _update.update { it.copy(progress = null) }
            }
        }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            // Allowed once per app in system settings; the user then taps Install again.
            message(R.string.update_allow_install)
            _activities.trySend(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            return
        }
        _activities.trySend(Updates.installIntent(app, file))
    }

    /** Started by the activity, so Back from the system screen returns to the app. */
    private val _activities = Channel<Intent>(Channel.BUFFERED)
    val activities = _activities.receiveAsFlow()

    fun readSystemVpn() = OrbitVpnService.readSystemVpn(app)

    fun setAutoUpdate(enabled: Boolean) {
        repository.setAutoUpdate(enabled)
        SubscriptionUpdater.schedule(app, enabled)
    }

    fun connect() = OrbitVpnService.start(app)

    fun disconnect() = OrbitVpnService.stop(app)

    fun clearError() = ServiceState.clearError()

    fun testNode(node: app.orbit.vpn.model.ProxyNode) = test(listOf(node))

    fun testNodes(nodes: List<app.orbit.vpn.model.ProxyNode>) = test(nodes)

    fun toggleFavorite(id: String) {
        val pool = state.value.autoNodes
        repository.toggleFavorite(id)
        // Favourites are auto-select's servers.
        if (state.value.autoSelect && state.value.autoNodes != pool) OrbitVpnService.reload(app)
    }

    /** Tests in batches so results appear progressively and one slow server doesn't hold up the rest. */
    private fun test(nodes: List<app.orbit.vpn.model.ProxyNode>) {
        _latency.update { latency -> latency + nodes.associate { it.id to Latency.Testing } }
        val limit = Semaphore(4)
        nodes.chunked(16).forEach { batch ->
            viewModelScope.launch {
                val results = limit.withPermit { withContext(Dispatchers.IO) { LatencyTester.test(app, batch) } }
                _latency.update { it + results }
            }
        }
    }

    // Last, so every property the check uses is initialized.
    init {
        checkForUpdatesIfDue()
    }
}
