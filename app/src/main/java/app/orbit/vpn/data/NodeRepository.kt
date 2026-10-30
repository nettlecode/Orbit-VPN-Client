package app.orbit.vpn.data

import android.content.Context
import app.orbit.vpn.config.ConfigBuilder
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.RoutingMode
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig
import app.orbit.vpn.model.RuleStore
import app.orbit.vpn.model.Subscription
import app.orbit.vpn.model.upsert
import app.orbit.vpn.parser.LinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppState(
    val nodes: List<ProxyNode> = emptyList(),
    val selectedId: String? = null,
    val mode: RoutingMode = RoutingMode.RULE,
    val remoteDns: String = ConfigBuilder.DEFAULT_REMOTE_DNS,
    val ipv6: Boolean = false,
    val subscriptions: List<Subscription> = emptyList(),
    val autoUpdateSubscriptions: Boolean = true,
    /** User-defined rules, checked before the imported config. */
    val userRules: List<RoutingRule> = emptyList(),
    val geoipUrl: String = ConfigBuilder.DEFAULT_GEOIP_URL,
    val geoipUpdateHours: Int = ConfigBuilder.DEFAULT_GEOIP_UPDATE_HOURS,
    val asnUrl: String = ConfigBuilder.DEFAULT_ASN_URL,
    /** Ids of favourite servers, in the order they were starred. */
    val favoriteIds: List<String> = emptyList(),
    /** Connect through the fastest of [autoNodes] instead of [selected]. */
    val autoSelect: Boolean = false,
    /** Reconnect after a reboot or an app update if the VPN was on. */
    val restoreConnection: Boolean = true,
    /** Look for a new release on GitHub once a day. */
    val checkUpdates: Boolean = true,
    /** Order of servers within each group on the Profiles screen. */
    val serverSort: ServerSort = ServerSort.DEFAULT,
    /** Hide servers whose last latency test failed (the active one stays visible). */
    val hideUnavailable: Boolean = false,
) {
    val selected: ProxyNode? get() = nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()

    /** Servers auto-select picks from: the favourites, or every server when there are none. */
    val autoNodes: List<ProxyNode>
        get() = if (autoFromFavorites) favoriteIds.mapNotNull { id -> nodes.firstOrNull { it.id == id } } else nodes

    val autoFromFavorites: Boolean get() = favoriteIds.isNotEmpty()

    /** The server traffic goes through: [selected], or auto-select's current pick. */
    fun current(autoPickId: String?): ProxyNode? = if (autoSelect) nodes.firstOrNull { it.id == autoPickId } else selected
}

@Serializable
enum class ServerSort {
    /** The order servers were added in, or the subscription's order. */
    DEFAULT,
    LATENCY,
    NAME,
}

/**
 * Persists servers and settings in `state.json`, and rule configs (which may hold thousands
 * of entries) separately in `rule-configs.json`.
 */
class NodeRepository(context: Context) {
    private val stateFile = File(context.filesDir, "state.json")
    private val configsFile = File(context.filesDir, "rule-configs.json")
    /** Single imported config, used before multiple configs were supported. */
    private val legacyRulesFile = File(context.filesDir, "rules.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<RuleStore> = _rules.asStateFlow()

    private fun load(): AppState {
        val state = read(stateFile, AppState.serializer()) ?: return AppState()
        return state.copy(nodes = state.nodes.map(::reparse))
    }

    private fun loadRules(): RuleStore {
        read(configsFile, RuleStore.serializer())?.let { return it }
        val legacy = read(legacyRulesFile, RuleConfig.serializer())?.takeIf { it.importedAt > 0 }
        val store = if (legacy != null) RuleStore(legacy.id, listOf(legacy)) else RuleStore()
        if (legacyRulesFile.exists()) {
            write(configsFile, RuleStore.serializer(), store)
            legacyRulesFile.delete()
        }
        return store
    }

    /** Re-reads nodes from their share links so parser improvements apply to saved servers. */
    private fun reparse(node: ProxyNode): ProxyNode {
        val link = node.link ?: return node
        return runCatching {
            LinkParser.parse(link).copy(id = node.id, name = node.name, subscriptionId = node.subscriptionId)
        }.getOrDefault(node)
    }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()

    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) = synchronized(file) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    private fun mutate(block: (AppState) -> AppState) {
        _state.update { old ->
            val new = block(old)
            // Servers can disappear in many ways (delete, subscription update or removal).
            val ids = new.nodes.mapTo(HashSet()) { it.id }
            if (new.favoriteIds.all(ids::contains)) new else new.copy(favoriteIds = new.favoriteIds.filter(ids::contains))
        }
        write(stateFile, AppState.serializer(), _state.value)
    }

    fun toggleFavorite(id: String) = mutate { state ->
        state.copy(favoriteIds = if (id in state.favoriteIds) state.favoriteIds - id else state.favoriteIds + id)
    }

    fun add(nodes: List<ProxyNode>) = mutate { state ->
        val selected = state.selectedId ?: nodes.firstOrNull()?.id
        state.copy(nodes = state.nodes + nodes, selectedId = selected)
    }

    fun updateNode(node: ProxyNode) = mutate { state ->
        state.copy(nodes = state.nodes.map { if (it.id == node.id) node else it })
    }

    fun remove(id: String) = mutate { state ->
        val nodes = state.nodes.filterNot { it.id == id }
        val selected = if (state.selectedId == id) nodes.firstOrNull()?.id else state.selectedId
        state.copy(nodes = nodes, selectedId = selected)
    }

    fun select(id: String) = mutate { it.copy(selectedId = id, autoSelect = false) }

    fun selectAuto() = mutate { it.copy(autoSelect = true) }

    fun setMode(mode: RoutingMode) = mutate { it.copy(mode = mode) }

    fun setRemoteDns(dns: String) = mutate { it.copy(remoteDns = dns) }

    fun setIpv6(enabled: Boolean) = mutate { it.copy(ipv6 = enabled) }

    fun setAutoUpdate(enabled: Boolean) = mutate { it.copy(autoUpdateSubscriptions = enabled) }

    fun setRestoreConnection(enabled: Boolean) = mutate { it.copy(restoreConnection = enabled) }

    fun setCheckUpdates(enabled: Boolean) = mutate { it.copy(checkUpdates = enabled) }

    fun setServerSort(sort: ServerSort) = mutate { it.copy(serverSort = sort) }

    fun setHideUnavailable(enabled: Boolean) = mutate { it.copy(hideUnavailable = enabled) }

    fun setGeoipUrl(url: String) = mutate { it.copy(geoipUrl = url) }

    fun setAsnUrl(url: String) = mutate { it.copy(asnUrl = url) }

    fun setGeoipUpdateHours(hours: Int) = mutate { it.copy(geoipUpdateHours = hours) }

    fun addSubscription(subscription: Subscription) = mutate {
        it.copy(subscriptions = it.subscriptions + subscription)
    }

    fun removeSubscription(id: String) = mutate { state ->
        val nodes = state.nodes.filterNot { it.subscriptionId == id }
        val selected = state.selectedId?.takeIf { selectedId -> nodes.any { it.id == selectedId } }
            ?: nodes.firstOrNull()?.id
        state.copy(
            subscriptions = state.subscriptions.filterNot { it.id == id },
            nodes = nodes,
            selectedId = selected,
        )
    }

    /**
     * Replaces a subscription's servers. Servers whose link is unchanged keep their id, so the
     * selection survives an update; if the selected server disappeared, the first new one is selected.
     */
    fun applySubscriptionUpdate(updated: Subscription, fetched: List<ProxyNode>) = mutate { state ->
        val previous = state.nodes.filter { it.subscriptionId == updated.id }
        val idsByLink = previous.mapNotNull { node -> node.link?.let { it to node.id } }.toMap()
        val fresh = fetched.map { node ->
            node.copy(id = node.link?.let(idsByLink::get) ?: node.id, subscriptionId = updated.id)
        }
        // Keep the subscription's position in the list.
        val nodes = buildList {
            var inserted = false
            for (node in state.nodes) {
                if (node.subscriptionId == updated.id) {
                    if (!inserted) addAll(fresh).also { inserted = true }
                } else {
                    add(node)
                }
            }
            if (!inserted) addAll(fresh)
        }
        val selected = state.selectedId?.takeIf { id -> nodes.any { it.id == id } } ?: nodes.firstOrNull()?.id
        state.copy(
            nodes = nodes,
            selectedId = selected,
            subscriptions = state.subscriptions.map { if (it.id == updated.id) updated else it },
        )
    }

    fun setSubscriptionError(id: String, error: String) = mutate { state ->
        state.copy(subscriptions = state.subscriptions.map { if (it.id == id) it.copy(error = error) else it })
    }

    /** Adds a user rule, or replaces the one with the same id. */
    fun saveUserRule(rule: RoutingRule) = mutate { state -> state.copy(userRules = state.userRules.upsert(rule)) }

    fun removeUserRule(id: String) = mutate { state -> state.copy(userRules = state.userRules.filterNot { it.id == id }) }

    fun setUserRuleEnabled(id: String, enabled: Boolean) = mutate { state ->
        state.copy(userRules = state.userRules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun mutateRules(block: (RuleStore) -> RuleStore) {
        _rules.update(block)
        write(configsFile, RuleStore.serializer(), _rules.value)
    }

    /** Adds a config, or replaces the one with the same id. The built-in config can't be saved. */
    fun saveRuleConfig(config: RuleConfig, activate: Boolean = false) {
        require(!config.isDefault)
        mutateRules { store ->
            val exists = store.configs.any { it.id == config.id }
            store.copy(
                configs = if (exists) store.configs.map { if (it.id == config.id) config else it } else store.configs + config,
                activeId = if (activate) config.id else store.activeId,
            )
        }
    }

    /** Edits a stored config; does nothing for the built-in one. */
    fun updateRuleConfig(id: String, block: (RuleConfig) -> RuleConfig) = mutateRules { store ->
        store.copy(configs = store.configs.map { if (it.id == id) block(it) else it })
    }

    /** Deletes a config; if it was active, the built-in config takes over. */
    fun removeRuleConfig(id: String) = mutateRules { store ->
        store.copy(
            configs = store.configs.filterNot { it.id == id },
            activeId = if (store.activeId == id) RuleConfig.DEFAULT_ID else store.activeId,
        )
    }

    fun setActiveRuleConfig(id: String) = mutateRules { it.copy(activeId = id) }

    fun backup(now: Long = System.currentTimeMillis()) = Backup(createdAt = now, state = state.value, rules = rules.value)

    /** Replaces all servers, subscriptions, rules and settings with the backup's. */
    fun restore(backup: Backup) {
        mutate { backup.state.copy(nodes = backup.state.nodes.map(::reparse)) }
        mutateRules { backup.rules }
    }
}
