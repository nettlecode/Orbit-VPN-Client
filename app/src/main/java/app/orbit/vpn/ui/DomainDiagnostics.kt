package app.orbit.vpn.ui

import app.orbit.vpn.service.ConnectionInfo
import app.orbit.vpn.service.ConnectionsMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A hostname only: paths, credentials and ports must never enter the HTTP request. */
internal fun diagnosticDomain(input: String): String {
    val domain = IDN.toASCII(input.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(java.util.Locale.ROOT)
    require(domain.length in 1..253 && domain.contains('.') &&
        domain.split('.').all { it.length in 1..63 } &&
        domain.any { it in 'a'..'z' }) { "Invalid domain" }
    return domain
}

data class DomainDiagnostic(
    val domain: String,
    val addresses: List<String> = emptyList(),
    val dnsError: String? = null,
    val stage: String? = null,
    val error: String? = null,
    val httpStatus: String? = null,
    val elapsedMs: Long = 0,
    val connection: ConnectionInfo? = null,
)

object DomainDiagnostics {
    // A stuck platform resolver must not occupy the shared IO pool or create unlimited threads.
    private val resolver = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Orbit-domain-dns").apply { isDaemon = true }
    }

    suspend fun run(domain: String): DomainDiagnostic = coroutineScope {
        val addresses = try {
            withContext(Dispatchers.IO) {
                val lookup = resolver.submit<List<InetAddress>> { InetAddress.getAllByName(domain).toList() }
                try { lookup.get(8, java.util.concurrent.TimeUnit.SECONDS) }
                finally { lookup.cancel(true) }
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            return@coroutineScope DomainDiagnostic(domain, dnsError = e.message ?: e.javaClass.simpleName)
        }
        var result = DomainDiagnostic(domain, addresses.mapNotNull { it.hostAddress }.distinct())
        val socket = Socket()
        var tls: SSLSocket? = null
        var stage = "TCP"
        val started = System.nanoTime()
        ConnectionsMonitor.acquire()
        val existing = ConnectionsMonitor.observed.value.map { it.id }.toSet()
        try {
            socket.bind(InetSocketAddress(0))
            val port = socket.localPort
            val route = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(2_000 + 24_000L) {
                    ConnectionsMonitor.observed.first { list ->
                        list.any { it.id !in existing && it.network == "tcp" &&
                            it.source.substringAfterLast(':').toIntOrNull() == port }
                    }.first { it.id !in existing && it.network == "tcp" &&
                        it.source.substringAfterLast(':').toIntOrNull() == port }
                }
            }
            try {
                withTimeout(24_000) {
                    withContext(Dispatchers.IO) {
                        socket.connect(InetSocketAddress(addresses.first(), 443), 8_000)
                        stage = "TLS"
                        tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, domain, 443, true) as SSLSocket
                        tls.apply {
                            soTimeout = 8_000
                            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            startHandshake()
                        }
                        stage = "HTTPS"
                        tls.outputStream.write(
                            "HEAD / HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\nUser-Agent: Orbit-Diagnostics\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                        val line = StringBuilder()
                        val stream = tls.inputStream
                        while (line.length < 512) {
                            val c = stream.read()
                            if (c == -1 || c == 10) break
                            line.append(c.toChar())
                            if ((System.nanoTime() - started) / 1_000_000 > 24_000) error("HTTPS timeout")
                        }
                        val status = line.toString().trim()
                        require(status.matches(Regex("""HTTP/1\.[01] [1-5][0-9]{2}.*"""))) { "Invalid HTTP response" }
                        result = result.copy(httpStatus = status)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                result = result.copy(stage = stage, error = e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { tls?.close() }
                runCatching { socket.close() }
            }
            result.copy(
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                connection = withTimeoutOrNull(1_500) { route.await() },
            ).also { route.cancel() }
        } finally {
            runCatching { tls?.close() }
            runCatching { socket.close() }
            ConnectionsMonitor.release()
        }
    }
}
