package app.orbit.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainDiagnosticsTest {
    @Test fun normalizesHostnames() {
        assertEquals("example.com", diagnosticDomain(" EXAMPLE.com. "))
        assertEquals("xn--e1afmkfd.xn--p1ai", diagnosticDomain("пример.рф"))
    }

    @Test fun rejectsUrlsAndRequestInjection() {
        listOf(
            "", "localhost", "127.0.0.1", "::1", "https://example.com",
            "user@example.com", "example.com:443", "example.com/path",
            "example.com?x=1", "example.com\r\nX-Test: yes",
            "-bad.example", "bad..example", "a".repeat(64) + ".com",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { diagnosticDomain(value) }
        }
    }
}
