package app.orbit.vpn.data

import app.orbit.vpn.model.RuleStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Everything the user set up: servers, subscriptions, rules and settings. Traffic history and
 * caches are left out. Without a password the file holds server credentials in plain text.
 */
@Serializable
data class Backup(
    val app: String = APP,
    val version: Int = VERSION,
    val createdAt: Long,
    val state: AppState,
    val rules: RuleStore,
) {
    /** A password-protected backup file: the encoded [Backup], encrypted with AES-256-GCM. */
    @Serializable
    data class Encrypted(
        val app: String = APP,
        val version: Int = VERSION,
        val encryption: Params,
        val data: String,
    )

    /** Stored in the file so the KDF can be strengthened later without breaking old backups. */
    @Serializable
    data class Params(
        val kdf: String = KDF,
        val iterations: Int,
        val salt: String,
        val cipher: String = CIPHER,
        val iv: String,
    )

    /** Result of opening a file: either readable right away or waiting for a password. */
    sealed interface Opened {
        data class Plain(val backup: Backup) : Opened
        data class Locked(val encrypted: Encrypted) : Opened
    }

    class WrongPasswordException : Exception("wrong password")

    companion object {
        private const val APP = "orbit"
        private const val VERSION = 1
        private const val KDF = "PBKDF2-HMAC-SHA256"
        private const val CIPHER = "AES-256-GCM"
        const val ITERATIONS = 600_000

        /** Binds the ciphertext to this format, so it can't be passed off as something else. */
        private val AAD = "orbit-backup".toByteArray()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        /** Encodes [backup], encrypting it when [password] is not empty. Slow with a password. */
        fun encode(backup: Backup, password: String? = null, iterations: Int = ITERATIONS): String {
            val plain = json.encodeToString(serializer(), backup)
            if (password.isNullOrEmpty()) return plain
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = cipher(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), iv)
            val encrypted = Encrypted(
                encryption = Params(iterations = iterations, salt = Base64.encode(salt), iv = Base64.encode(iv)),
                data = Base64.encode(cipher.doFinal(plain.toByteArray())),
            )
            return json.encodeToString(Encrypted.serializer(), encrypted)
        }

        /** Parses a backup file; throws [IllegalArgumentException] for anything else. */
        fun open(text: String): Opened {
            val obj = runCatching { json.parseToJsonElement(text) as JsonObject }
                .getOrElse { throw IllegalArgumentException("not an Orbit backup", it) }
            if ("encryption" !in obj) return Opened.Plain(decode(text))
            val encrypted = runCatching { json.decodeFromJsonElement(Encrypted.serializer(), obj) }
                .getOrElse { throw IllegalArgumentException("not an Orbit backup", it) }
            check(encrypted.app, encrypted.version)
            require(encrypted.encryption.kdf == KDF && encrypted.encryption.cipher == CIPHER) {
                "unsupported encryption"
            }
            return Opened.Locked(encrypted)
        }

        /** Decrypts a locked backup; throws [WrongPasswordException] if [password] doesn't fit. Slow. */
        fun decrypt(encrypted: Encrypted, password: String): Backup {
            val params = encrypted.encryption
            val key = deriveKey(password, Base64.decode(params.salt), params.iterations)
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, Base64.decode(params.iv)).doFinal(Base64.decode(encrypted.data))
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException()
            }
            return decode(plain.toString(Charsets.UTF_8))
        }

        fun decode(text: String): Backup {
            val backup = runCatching { json.decodeFromString(serializer(), text) }
                .getOrElse { throw IllegalArgumentException("not an Orbit backup", it) }
            check(backup.app, backup.version)
            return backup
        }

        private fun check(app: String, version: Int) {
            require(app == APP) { "not an Orbit backup" }
            require(version <= VERSION) { "backup is from a newer version of Orbit" }
        }

        private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(AAD)
            }

        /**
         * PBKDF2-HMAC-SHA256 with a 32-byte output (a single block). Written out because the
         * platform's `PBKDF2WithHmacSHA256` only exists from API 26.
         */
        internal fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password.toByteArray(), "HmacSHA256"))
            mac.update(salt)
            var block = mac.doFinal(byteArrayOf(0, 0, 0, 1))
            val result = block.copyOf()
            repeat(iterations - 1) {
                block = mac.doFinal(block)
                for (i in result.indices) result[i] = (result[i].toInt() xor block[i].toInt()).toByte()
            }
            return result
        }
    }
}
