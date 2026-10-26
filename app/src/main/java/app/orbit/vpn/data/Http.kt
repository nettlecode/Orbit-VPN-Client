package app.orbit.vpn.data

import app.orbit.vpn.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    /**
     * Streams [url] into [file], reporting progress from 0 to 1 when the size is known. Returns the
     * SHA-256 of what was written.
     */
    fun download(url: String, file: File, onProgress: (Float) -> Unit): ByteArray {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw StatusException(code)
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            return digest.digest()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Orbit/${BuildConfig.VERSION_NAME}")
    }

    private fun request(url: String): Pair<ByteArray, Map<String, String>> {
        val connection = open(url)
        try {
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
