package app.orbit.vpn

import app.orbit.vpn.data.AppState
import app.orbit.vpn.data.Backup
import app.orbit.vpn.model.Policy
import app.orbit.vpn.model.RoutingMode
import app.orbit.vpn.model.RoutingRule
import app.orbit.vpn.model.RuleConfig
import app.orbit.vpn.model.RuleStore
import app.orbit.vpn.model.Subscription
import app.orbit.vpn.parser.LinkParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupTest {
    private val node = LinkParser.parse("trojan://secret-pw@t.example.com:443#t")
    private val config = RuleConfig(
        name = "Work",
        rules = listOf(RoutingRule(Policy.REJECT, suffixes = listOf("ads.example"))),
        finalPolicy = Policy.DIRECT,
    )
    private val backup = Backup(
        createdAt = 1_700_000_000_000,
        state = AppState(
            nodes = listOf(node),
            selectedId = node.id,
            mode = RoutingMode.GLOBAL,
            remoteDns = "tls://1.1.1.1",
            ipv6 = true,
            subscriptions = listOf(Subscription(name = "sub", url = "https://sub.example/x")),
            autoUpdateSubscriptions = false,
            userRules = listOf(RoutingRule(Policy.DIRECT, packages = listOf("com.example"))),
        ),
        rules = RuleStore(activeId = config.id, configs = listOf(config)),
    )

    @Test
    fun roundTrips() {
        assertEquals(Backup.Opened.Plain(backup), Backup.open(Backup.encode(backup)))
    }

    @Test
    fun roundTripsEncrypted() {
        val text = Backup.encode(backup, "correct horse", iterations = 1000)
        assertFalse("credentials must not leak", "secret-pw" in text || "t.example.com" in text)
        val locked = Backup.open(text) as Backup.Opened.Locked
        assertEquals(backup, Backup.decrypt(locked.encrypted, "correct horse"))
    }

    @Test(expected = Backup.WrongPasswordException::class)
    fun rejectsWrongPassword() {
        val locked = Backup.open(Backup.encode(backup, "correct horse", iterations = 1000)) as Backup.Opened.Locked
        Backup.decrypt(locked.encrypted, "wrong")
    }

    @Test
    fun emptyPasswordMeansPlain() {
        assertTrue(Backup.open(Backup.encode(backup, "")) is Backup.Opened.Plain)
    }

    @Test
    fun keyDerivationMatchesJvmPbkdf2() {
        val salt = ByteArray(16) { it.toByte() }
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("пароль 123".toCharArray(), salt, 5000, 256))
            .encoded
        assertArrayEquals(expected, Backup.deriveKey("пароль 123", salt, 5000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherJson() {
        Backup.open("""{"nodes": []}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNewerVersion() {
        Backup.open(Backup.encode(backup).replace("\"version\": 1", "\"version\": 99"))
    }
}
