package app.orbit.vpn.ui

import app.orbit.vpn.model.*
import org.junit.Assert.*
import org.junit.Test

class NodeEditorTest {
    private val node = ProxyNode(name = "Test", type = ProxyType.VLESS, server = "example.org", port = 443,
        uuid = "12345678-1234-1234-1234-123456789abc", subscriptionId = "subscription")

    @Test fun everyProtocolCanBeCreatedFromItsDefaults() {
        ProxyType.entries.forEach { type ->
            val draft = newLocalNode(type, "new-id")
            assertNull(draft.subscriptionId)
            assertTrue(runCatching { editedNode(draft, nodeFields(draft), "tcp", if (draft.tls == null) "none" else "tls", false) }.isFailure)
            val fields = nodeFields(draft) + mapOf(
                "name" to "Manual", "server" to "example.org",
                "uuid" to "12345678-1234-1234-1234-123456789abc", "password" to "secret", "username" to "user",
            )
            val saved = editedNode(draft, fields, "tcp", if (draft.tls == null) "none" else "tls", false)
            assertEquals("new-id", saved.id)
            assertEquals(type, saved.type)
            assertEquals("example.org", saved.server)
        }
    }

    @Test fun editsPreserveIdentityAndCredentials() {
        val edited = editedNode(node, nodeFields(node) + mapOf("server" to "new.example", "port" to "8443"), "tcp", "none", false)
        assertEquals(node.copy(server = "new.example", port = 8443), edited)
    }

    @Test fun invalidPortUuidAndExtraAreRejected() {
        for (change in listOf("port" to "0", "port" to "65536", "uuid" to "bad", "server" to "https://example.org")) {
            assertTrue(runCatching { editedNode(node, nodeFields(node) + change, "tcp", "none", false) }.isFailure)
        }
        assertTrue(runCatching { editedNode(node, nodeFields(node) + ("extra" to "[]"), "xhttp", "none", false) }.isFailure)
    }

    @Test fun changingTransportAndSecurityDropsInapplicableFields() {
        val initial = node.copy(transport = TransportSettings("xhttp", path = "/x", host = "host", mode = "auto", extra = "{}"), tls = TlsSettings(realityPublicKey = "a".repeat(43)))
        val edited = editedNode(initial, nodeFields(initial) + ("serviceName" to "svc"), "grpc", "none", false)
        assertEquals(TransportSettings("grpc", serviceName = "svc"), edited.transport)
        assertNull(edited.tls)
    }
}
