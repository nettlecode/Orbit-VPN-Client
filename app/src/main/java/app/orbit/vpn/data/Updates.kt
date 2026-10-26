package app.orbit.vpn.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * App updates from GitHub Releases. The APK for the device's ABI is downloaded by the app, checked
 * against the release's SHA256SUMS and handed to the system installer, which also enforces that it
 * is signed with the same key.
 */
object Updates {
    const val REPO = "nettlecode/Orbit-VPN-Client"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val SUMS = "SHA256SUMS"

    /** Automatic checks run at most this often. */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DISMISSED = "dismissed"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Asset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String,
    )

    @Serializable
    data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val pageUrl: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    ) {
        val version: String get() = tag.removePrefix("v")

        /** Release notes without GitHub's generated "Full Changelog" link. */
        val notes: String
            get() = body.orEmpty().lines().filterNot { it.startsWith("**Full Changelog**") }.joinToString("\n").trim()
    }

    /** Blocking; call off the main thread. */
    fun latest(): Release = json.decodeFromString(Release.serializer(), Http.get(LATEST_URL).body)

    /** Compares dotted version numbers; a suffix such as `-beta` is ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** The APK built for the first ABI the device supports, or the universal one. */
    fun apkFor(release: Release, abis: List<String> = Build.SUPPORTED_ABIS.toList()): Asset? {
        val apks = release.assets.filter { it.name.endsWith(".apk") }
        return abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.endsWith("-$abi.apk") } }
            ?: apks.firstOrNull { it.name.endsWith("-universal.apk") }
    }

    /** Expected SHA-256 (hex) by file name, from `sha256sum` output. */
    fun parseSums(text: String): Map<String, String> = text.lines()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) parts[1].removePrefix("*") to parts[0].lowercase() else null
        }
        .toMap()

    class ChecksumException(name: String) : IllegalStateException("checksum mismatch for $name")

    /**
     * Downloads [asset] into the cache and verifies it against the release's SHA256SUMS; a release
     * without one is refused. Blocking; call off the main thread.
     */
    fun download(context: Context, release: Release, asset: Asset, onProgress: (Float) -> Unit): File {
        val sumsUrl = release.assets.firstOrNull { it.name == SUMS }?.url ?: error("$SUMS is missing in ${release.tag}")
        val expected = parseSums(Http.get(sumsUrl).body)[asset.name] ?: error("${asset.name} is missing in $SUMS")
        val dir = updatesDir(context).apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, asset.name)
        val actual = Http.download(asset.url, file, onProgress).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw ChecksumException(asset.name)
        }
        return file
    }

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Leftover downloads, removed once the update is installed. */
    fun clearDownloads(context: Context) {
        updatesDir(context).deleteRecursively()
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheck(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0)

    fun setLastCheck(context: Context, time: Long) = prefs(context).edit().putLong(KEY_LAST_CHECK, time).apply()

    /** Version the user closed the banner for; it isn't shown again for that version. */
    fun dismissed(context: Context): String? = prefs(context).getString(KEY_DISMISSED, null)

    fun dismiss(context: Context, version: String) = prefs(context).edit().putString(KEY_DISMISSED, version).apply()
}
