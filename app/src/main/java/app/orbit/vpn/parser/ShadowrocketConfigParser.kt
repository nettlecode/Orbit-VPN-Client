package app.orbit.vpn.parser

import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig

/**
 * Converts the routing part of a Shadowrocket / Surge `.conf` file into a [RuleConfig].
 *
 * Supported: `[General] skip-proxy`, and `[Rule]` lines DOMAIN, DOMAIN-SUFFIX, DOMAIN-KEYWORD,
 * IP-CIDR(6), IP-ASN, GEOIP, RULE-SET, DOMAIN-SET and FINAL. External RULE-SET / DOMAIN-SET lists are
 * downloaded with [fetch] and inlined. Proxy groups and unknown policies map to PROXY.
 * Everything else (scripts, AND/OR/NOT, USER-AGENT, URL-REGEX, ...) is counted as skipped.
 */
class ShadowrocketConfigParser(private val fetch: (String) -> String) {

    fun parse(text: String, name: String, sourceUrl: String? = null, now: Long = System.currentTimeMillis()): RuleConfig {
        val rules = RuleAccumulator()
        var finalPolicy = Policy.PROXY
        var skipped = 0
        var section = ""
        var sawRules = false

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            when (section) {
                "general" -> {
                    val key = line.substringBefore('=').trim().lowercase()
                    if (key == "skip-proxy") {
                        line.substringAfter('=').split(',').forEach { rules.addSkipProxy(it.trim()) }
                    }
                }

                "rule" -> {
                    sawRules = true
                    val parts = line.split(',').map { it.trim() }
                    val type = parts[0].uppercase()
                    if (type == "FINAL" || type == "MATCH") {
                        finalPolicy = policyOf(parts.getOrNull(1))
                        continue
                    }
                    val value = parts.getOrNull(1)
                    if (value.isNullOrEmpty()) {
                        skipped++
                        continue
                    }
                    val policy = policyOf(parts.getOrNull(2))
                    val ok = when (type) {
                        "RULE-SET" -> rules.addList(policy, fetchOrNull(value)?.let(::parseRuleList))
                        "DOMAIN-SET" -> rules.addList(policy, fetchOrNull(value)?.let(::parseDomainList))
                        else -> rules.add(policy, type, value)
                    }
                    if (!ok) skipped++
                }
            }
        }
        require(sawRules || rules.isNotEmpty()) { "no [Rule] section found" }
        return RuleConfig(
            name = name,
            rules = rules.build(),
            finalPolicy = finalPolicy,
            sourceUrl = sourceUrl,
            importedAt = now,
            skipped = skipped,
        )
    }

    private fun fetchOrNull(url: String): String? =
        if (url.startsWith("http://") || url.startsWith("https://")) runCatching { fetch(url) }.getOrNull() else null

    /** Surge rule list (`DOMAIN-SUFFIX,example.com`) or Clash classical YAML (`- DOMAIN,x`). */
    private fun parseRuleList(text: String): List<Pair<String, String>> = text.lineSequence()
        .map { it.trim().removePrefix("-").trim().trim('\'', '"') }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("payload") }
        .mapNotNull { line ->
            val parts = line.split(',').map { it.trim() }
            parts.getOrNull(1)?.let { parts[0].uppercase() to it }
        }
        .toList()

    /** Domain list: `.example.com` / `+.example.com` mean the domain and all subdomains. */
    private fun parseDomainList(text: String): List<Pair<String, String>> = text.lineSequence()
        .map { it.trim().removePrefix("-").trim().trim('\'', '"') }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("payload") }
        .map { domain ->
            when {
                domain.startsWith("+.") -> "DOMAIN-SUFFIX" to domain.removePrefix("+.")
                domain.startsWith(".") -> "DOMAIN-SUFFIX" to domain.removePrefix(".")
                else -> "DOMAIN" to domain
            }
        }
        .toList()

    private fun policyOf(name: String?): Policy = when {
        name == null -> Policy.PROXY
        name.equals("DIRECT", ignoreCase = true) -> Policy.DIRECT
        name.startsWith("REJECT", ignoreCase = true) -> Policy.REJECT
        else -> Policy.PROXY
    }

    private companion object {
        val CIDR = Regex("""^[0-9a-f:.]+(/\d{1,3})?$""")
        val DOMAIN = Regex("""^[a-z0-9_-]+(\.[a-z0-9_-]+)*$""")
        val COUNTRY = Regex("""^[A-Za-z]{2}$""")
        val ASN = Regex("""^(?:AS)?(\d{1,10})$""", RegexOption.IGNORE_CASE)
        val PRIVATE_CIDRS = listOf(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
            "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
        )
    }

    /** Merges consecutive matchers with the same policy into one [RoutingRule], keeping order. */
    private class RuleAccumulator {
        private val rules = mutableListOf<RoutingRule>()
        private var policy: Policy? = null
        private val domains = mutableListOf<String>()
        private val suffixes = mutableListOf<String>()
        private val keywords = mutableListOf<String>()
        private val cidrs = mutableListOf<String>()
        private val asns = mutableListOf<Long>()

        fun isNotEmpty() = rules.isNotEmpty() || policy != null

        fun add(policy: Policy, type: String, value: String): Boolean {
            val target = when (type) {
                "DOMAIN" -> domains
                "DOMAIN-SUFFIX" -> suffixes
                "DOMAIN-KEYWORD" -> keywords
                "IP-CIDR", "IP-CIDR6" -> cidrs
                "GEOIP" -> {
                    if (value.equals("LAN", ignoreCase = true)) {
                        PRIVATE_CIDRS.forEach { add(policy, "IP-CIDR", it) }
                        return true
                    }
                    if (!COUNTRY.matches(value)) return false
                    flush()
                    rules += RoutingRule(policy, geoip = value.lowercase())
                    return true
                }

                "IP-ASN" -> {
                    val asn = ASN.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull() ?: return false
                    if (this.policy != policy) flush()
                    this.policy = policy
                    asns += asn
                    return true
                }

                else -> return false
            }
            val normalized = value.lowercase().trimEnd('.')
            val valid = if (target === cidrs) CIDR.matches(normalized) else DOMAIN.matches(normalized)
            if (!valid) return false
            if (this.policy != policy) flush()
            this.policy = policy
            target += normalized
            return true
        }

        fun addList(policy: Policy, entries: List<Pair<String, String>>?): Boolean {
            if (entries == null) return false
            entries.forEach { (type, value) -> add(policy, type, value) }
            return true
        }

        /** skip-proxy entries: CIDRs, `*.suffix`, `suffix` or `localhost`. */
        fun addSkipProxy(item: String) {
            if (item.isEmpty()) return
            when {
                item.first().isDigit() || item.contains(':') -> add(Policy.DIRECT, "IP-CIDR", item)
                item.startsWith("*.") -> add(Policy.DIRECT, "DOMAIN-SUFFIX", item.removePrefix("*."))
                else -> add(Policy.DIRECT, "DOMAIN", item)
            }
        }

        private fun flush() {
            val p = policy ?: return
            if (domains.isNotEmpty() || suffixes.isNotEmpty() || keywords.isNotEmpty() || cidrs.isNotEmpty() || asns.isNotEmpty()) {
                rules += RoutingRule(p, domains.toList(), suffixes.toList(), keywords.toList(), cidrs.toList(), asns = asns.distinct())
            }
            domains.clear()
            suffixes.clear()
            keywords.clear()
            cidrs.clear()
            asns.clear()
            policy = null
        }

        fun build(): List<RoutingRule> {
            flush()
            return rules.toList()
        }
    }
}
