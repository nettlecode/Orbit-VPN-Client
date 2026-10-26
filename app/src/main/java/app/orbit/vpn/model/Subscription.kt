package app.orbit.vpn.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val updatedAt: Long = 0,
    val usage: SubscriptionUsage? = null,
    /** Last update error, cleared on success. */
    val error: String? = null,
)

/** Parsed `subscription-userinfo` response header. Byte counts; expire is epoch seconds. */
@Serializable
data class SubscriptionUsage(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
) {
    val used: Long get() = upload + download

    companion object {
        /** Parses `upload=1; download=2; total=3; expire=4`. */
        fun parse(header: String?): SubscriptionUsage? {
            if (header.isNullOrBlank()) return null
            val values = header.split(';')
                .mapNotNull { part ->
                    val key = part.substringBefore('=').trim().lowercase()
                    val value = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
                    value?.let { key to it }
                }
                .toMap()
            if (values.isEmpty()) return null
            return SubscriptionUsage(
                upload = values["upload"] ?: 0,
                download = values["download"] ?: 0,
                total = values["total"] ?: 0,
                expire = values["expire"] ?: 0,
            )
        }
    }
}
