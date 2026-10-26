package app.orbit.vpn.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Policy { PROXY, DIRECT, REJECT }

/**
 * One routing rule. All matchers inside a rule are OR'ed (a destination matching any domain,
 * suffix, keyword, CIDR or ASN hits the rule). [geoip] and [packages] rules carry no other matchers.
 */
@Serializable
data class RoutingRule(
    val policy: Policy,
    val domains: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    /** Lower-case ISO country code, e.g. "ru". */
    val geoip: String? = null,
    /** Android package names of apps whose traffic this rule matches. */
    val packages: List<String> = emptyList(),
    /** Autonomous system numbers; each is a downloaded rule-set of the AS's IP ranges. */
    val asns: List<Long> = emptyList(),
    val enabled: Boolean = true,
    /** Optional display name for user rules. */
    val name: String? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    val hasDomains: Boolean get() = domains.isNotEmpty() || suffixes.isNotEmpty() || keywords.isNotEmpty()
    val size: Int
        get() = domains.size + suffixes.size + keywords.size + ipCidrs.size + asns.size + packages.size +
            (if (geoip != null) 1 else 0)

    val kind: RuleKind
        get() = when {
            packages.isNotEmpty() -> RuleKind.APP
            hasDomains -> RuleKind.DOMAIN
            else -> RuleKind.IP
        }

    /** All matcher values in display order. */
    val values: List<String>
        get() = domains + suffixes + keywords + ipCidrs + asns.map { "AS$it" } + packages + listOfNotNull(geoip?.uppercase())
}

enum class RuleKind { DOMAIN, IP, APP }

/**
 * Routing rules used in [RoutingMode.RULE]: the built-in set, one imported from a Shadowrocket
 * config, or one the user assembled.
 */
@Serializable
data class RuleConfig(
    val name: String,
    val rules: List<RoutingRule>,
    val finalPolicy: Policy = Policy.PROXY,
    /** URL the config was imported from, for re-import. */
    val sourceUrl: String? = null,
    val importedAt: Long = 0,
    /** Rule lines that could not be converted (scripts, AND/OR, user agents, ...). */
    val skipped: Int = 0,
    val id: String = UUID.randomUUID().toString(),
) {
    val ruleCount: Int get() = rules.sumOf { it.size }
    val isDefault: Boolean get() = id == DEFAULT_ID

    companion object {
        const val DEFAULT_ID = "default"

        val DEFAULT = RuleConfig(
            name = "default",
            rules = listOf(
                RoutingRule(Policy.DIRECT, suffixes = listOf("ru", "su", "xn--p1ai", "local", "lan"), id = "default-ru-domains"),
                RoutingRule(Policy.DIRECT, geoip = "ru", id = "default-ru-geoip"),
            ),
            id = DEFAULT_ID,
        )
    }
}

/** All rule configs; the built-in one is implicit and never stored. */
@Serializable
data class RuleStore(
    val activeId: String = RuleConfig.DEFAULT_ID,
    val configs: List<RuleConfig> = emptyList(),
) {
    val all: List<RuleConfig> get() = listOf(RuleConfig.DEFAULT) + configs
    val active: RuleConfig get() = configs.firstOrNull { it.id == activeId } ?: RuleConfig.DEFAULT
}

/** Adds [rule] at the end, or replaces the rule with the same id in place. */
fun List<RoutingRule>.upsert(rule: RoutingRule): List<RoutingRule> =
    if (any { it.id == rule.id }) map { if (it.id == rule.id) rule else it } else this + rule
