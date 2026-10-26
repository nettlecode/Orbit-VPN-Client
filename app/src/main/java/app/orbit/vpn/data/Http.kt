package app.orbit.vpn.data

import app.orbit.vpn.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** Minimal blocking HTTP GET for subscriptions and rule lists. Call off the main thread. */
object Http {
    private const val TIMEOUT_MS = 20_000
    private const val MAX_BYTES = 32 * 1024 * 1024

    class Response(val body: String, val headers: Map<String, String>)

    /** The server answered with a non-2xx [code]. */
    class StatusException(val code: Int) : IllegalStateException("HTTP $code")

    fun get(url: String): Response {
        val (bytes, headers) = request(url)
        return Response(bytes.toString(Charsets.UTF_8), headers)
    }

    fun getBytes(url: String): ByteArray = request(url).first

    private fun request(url: String): Pair<ByteArray, Map<String, String>> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Orbit/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) throw StatusException(code)
            val bytes = connection.inputStream.use { input ->
                val data = input.readNBytesCompat(MAX_BYTES + 1)
                check(data.size <= MAX_BYTES) { "response too large" }
                data
            }
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.joinToString(",") }
            return bytes to headers
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
