package app.orbit.vpn.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.orbit.vpn.OrbitApp
import app.orbit.vpn.config.ConfigBuilder
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig
import app.orbit.vpn.model.Subscription
import app.orbit.vpn.model.SubscriptionUsage
import app.orbit.vpn.parser.LinkParser
import app.orbit.vpn.parser.ShadowrocketConfigParser
import app.orbit.vpn.service.OrbitVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Downloads subscriptions and Shadowrocket rule configs. */
class SubscriptionUpdater(private val context: Context, private val repository: NodeRepository) {

    private class Fetched(val subscription: Subscription, val nodes: List<ProxyNode>)

    /** Adds and immediately fetches a subscription. Returns the number of servers. */
    suspend fun add(url: String, name: String?): Int {
        val draft = Subscription(name = name?.takeIf { it.isNotBlank() } ?: hostOf(url), url = url.trim())
        val fetched = fetch(draft, keepName = !name.isNullOrBlank())
        repository.addSubscription(fetched.subscription)
        repository.applySubscriptionUpdate(fetched.subscription, fetched.nodes)
        return fetched.nodes.size
    }

    /** Refreshes one subscription; failures are stored on the subscription and rethrown. */
    suspend fun update(subscription: Subscription): Int {
        val fetched = try {
            fetch(subscription, keepName = true)
        } catch (e: Exception) {
            repository.setSubscriptionError(subscription.id, e.message ?: e.toString())
            throw e
        }
        repository.applySubscriptionUpdate(fetched.subscription, fetched.nodes)
        OrbitVpnService.reload(context)
        return fetched.nodes.size
    }

    /** Refreshes all subscriptions, returning how many failed. */
    suspend fun updateAll(): Int = repository.state.value.subscriptions.count { subscription ->
        runCatching { update(subscription) }.isFailure
    }

    private suspend fun fetch(subscription: Subscription, keepName: Boolean): Fetched = withContext(Dispatchers.IO) {
        val response = Http.get(subscription.url)
        val nodes = LinkParser.parseAll(response.body)
        check(nodes.isNotEmpty()) { "no supported servers in subscription" }
        val title = response.headers["profile-title"]?.let(::decodeTitle)
        Fetched(
            subscription.copy(
                name = if (keepName || title.isNullOrBlank()) subscription.name else title,
                updatedAt = System.currentTimeMillis(),
                usage = SubscriptionUsage.parse(response.headers["subscription-userinfo"]),
                error = null,
            ),
            nodes,
        )
    }

    /**
     * Imports routing rules from a Shadowrocket config URL as a new active config, or into
     * [replace] keeping its id and name. Returns the converted config's rule count.
     */
    suspend fun importRules(url: String, replace: RuleConfig? = null): Int = withContext(Dispatchers.IO) {
        val text = Http.get(url).body
        importRulesText(text, nameOf(url), url, replace)
    }

    /** Imports routing rules from config text (e.g. a picked file). */
    suspend fun importRulesText(text: String, name: String, sourceUrl: String? = null, replace: RuleConfig? = null): Int =
        withContext(Dispatchers.IO) {
            val parsed = ShadowrocketConfigParser { Http.get(it).body }.parse(text, name, sourceUrl)
            // AS numbers the source has no list for would stop the tunnel from starting.
            val missing = downloadRuleSets(parsed.rules)
            val cleaned = parsed.copy(rules = parsed.rules.withoutAsns(missing), skipped = parsed.skipped + missing.size)
            val config = replace?.let { cleaned.copy(id = it.id, name = it.name) } ?: cleaned
            repository.saveRuleConfig(config, activate = replace == null)
            if (repository.rules.value.activeId == config.id) OrbitVpnService.reload(context)
            config.ruleCount
        }

    /**
     * Stores the GeoIP and ASN rule-sets that [rules] use locally, so the tunnel can start even when
     * the source is unreachable. Best effort and blocking: other failures are left for sing-box to
     * retry. Returns the AS numbers the source has no list for (HTTP 404).
     */
    fun downloadRuleSets(rules: List<RoutingRule>): Set<Long> {
        val dir = File(context.filesDir, "box").apply { mkdirs() }
        val state = repository.state.value
        for (country in rules.mapNotNull { it.geoip }.distinct()) {
            runCatching { fetchOnce(File(dir, ConfigBuilder.geoipFileName(country)), ConfigBuilder.geoipUrl(country, state.geoipUrl)) }
        }
        val missing = mutableSetOf<Long>()
        for (asn in rules.flatMap { it.asns }.distinct()) {
            try {
                fetchOnce(File(dir, ConfigBuilder.asnFileName(asn)), ConfigBuilder.asnUrl(asn, state.asnUrl))
            } catch (e: Http.StatusException) {
                if (e.code == 404) missing += asn
            } catch (e: Exception) {
                // Offline or blocked: sing-box downloads it through the proxy later.
            }
        }
        return missing
    }

    private fun fetchOnce(file: File, url: String) {
        if (file.exists()) return
        val bytes = Http.getBytes(url)
        File(file.parentFile, "${file.name}.tmp").apply { writeBytes(bytes) }.renameTo(file)
    }

    private fun hostOf(url: String) = runCatching { URI(url.trim()).host }.getOrNull() ?: url

    private fun nameOf(url: String) =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { hostOf(url) }

    /** `profile-title` may be plain text or `base64:...`. */
    private fun decodeTitle(value: String): String =
        if (value.startsWith("base64:")) LinkParser.decodeBase64(value.removePrefix("base64:")) ?: value else value

    companion object {
        /** Drops [missing] AS numbers, and rules left with nothing to match. */
        fun List<RoutingRule>.withoutAsns(missing: Set<Long>): List<RoutingRule> =
            if (missing.isEmpty()) this else mapNotNull { rule -> rule.copy(asns = rule.asns - missing).takeIf { it.size > 0 } }

        private const val WORK_NAME = "subscription-update"

        /** Schedules or cancels the periodic refresh according to the user setting. */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as OrbitApp
            val failed = SubscriptionUpdater(app, app.repository).updateAll()
            return if (failed == 0) Result.success() else Result.retry()
        }
    }
}
