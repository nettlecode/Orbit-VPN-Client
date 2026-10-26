package app.orbit.vpn

import app.orbit.vpn.data.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private fun release(vararg names: String) = Updates.Release(
        tag = "v0.3.0",
        pageUrl = "https://github.com/${Updates.REPO}/releases/tag/v0.3.0",
        assets = names.map { Updates.Asset(it, 1, "https://example.com/$it") },
    )

    @Test
    fun comparesVersions() {
        assertTrue(Updates.isNewer("0.3.0", "0.2.2"))
        assertTrue(Updates.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updates.isNewer("1.0", "0.99.99"))
        assertFalse(Updates.isNewer("0.3.0", "0.3.0"))
        assertFalse(Updates.isNewer("0.2.9", "0.3.0"))
        assertFalse(Updates.isNewer("0.3.0-beta", "0.3.0"))
    }

    @Test
    fun picksApkForAbi() {
        val release = release("Orbit-0.3.0-arm64-v8a.apk", "Orbit-0.3.0-universal.apk", "Orbit-0.3.0-x86_64.apk", "SHA256SUMS")
        assertEquals("Orbit-0.3.0-arm64-v8a.apk", Updates.apkFor(release, listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("Orbit-0.3.0-x86_64.apk", Updates.apkFor(release, listOf("x86_64", "arm64-v8a"))?.name)
        assertEquals("Orbit-0.3.0-universal.apk", Updates.apkFor(release, listOf("armeabi-v7a"))?.name)
        assertNull(Updates.apkFor(release("SHA256SUMS"), listOf("arm64-v8a")))
    }

    @Test
    fun parsesChecksums() {
        val sums = Updates.parseSums(
            "ABC123  Orbit-0.3.0-arm64-v8a.apk\ndef456 *Orbit-0.3.0-universal.apk\n\n",
        )
        assertEquals(mapOf("Orbit-0.3.0-arm64-v8a.apk" to "abc123", "Orbit-0.3.0-universal.apk" to "def456"), sums)
    }

    @Test
    fun dropsGeneratedChangelogLink() {
        val notes = release().copy(body = "Auto-select\n\n**Full Changelog**: https://github.com/x/y/commits/v0.3.0").notes
        assertEquals("Auto-select", notes)
    }
}
