package app.orbit.vpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Uplink/downlink bytes for one hour, keyed by the hour's start in epoch millis. */
@Serializable
data class TrafficBucket(val hour: Long, val up: Long, val down: Long)

/** Hourly traffic totals for the last [KEEP_DAYS] days, persisted in `traffic.json`. */
class TrafficHistory(context: Context) {
    private val file = File(context.filesDir, "traffic.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val buckets = sortedMapOf<Long, LongArray>()
    private val _version = MutableStateFlow(0)

    /** Bumps whenever data changes, so the UI can recompute aggregates. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private var lastSave = 0L

    init {
        runCatching {
            json.decodeFromString<List<TrafficBucket>>(file.readText())
                .forEach { buckets[it.hour] = longArrayOf(it.up, it.down) }
        }
    }

    @Synchronized
    fun record(up: Long, down: Long, now: Long = System.currentTimeMillis()) {
        if (up <= 0 && down <= 0) return
        val hour = now - now % HOUR
        val bucket = buckets.getOrPut(hour) { LongArray(2) }
        bucket[0] += up
        bucket[1] += down
        _version.value++
        if (now - lastSave > SAVE_INTERVAL) save(now)
    }

    @Synchronized
    fun save(now: Long = System.currentTimeMillis()) {
        lastSave = now
        buckets.headMap(now - TimeUnit.DAYS.toMillis(KEEP_DAYS)).clear()
        val data = buckets.map { (hour, v) -> TrafficBucket(hour, v[0], v[1]) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(data))
        tmp.renameTo(file)
    }

    /** Totals of buckets whose hour falls in [from, to). */
    @Synchronized
    fun total(from: Long, to: Long): Pair<Long, Long> {
        var up = 0L
        var down = 0L
        buckets.subMap(from, to).values.forEach {
            up += it[0]
            down += it[1]
        }
        return up to down
    }

    /** Hourly series for today (24 points). */
    fun today(now: Long = System.currentTimeMillis()): List<Pair<Long, Long>> {
        val start = startOfDay(now, 0)
        return (0 until 24).map { total(start + it * HOUR, start + (it + 1) * HOUR) }
    }

    /** Daily series for the last [days] days, oldest first. */
    fun days(days: Int, now: Long = System.currentTimeMillis()): List<Pair<Long, Long>> =
        (days - 1 downTo 0).map { ago -> total(startOfDay(now, ago), startOfDay(now, ago - 1)) }

    fun todayTotal(now: Long = System.currentTimeMillis()) = total(startOfDay(now, 0), startOfDay(now, -1))

    private fun startOfDay(now: Long, daysAgo: Int): Long = Calendar.getInstance().run {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        timeInMillis
    }

    companion object {
        private val HOUR = TimeUnit.HOURS.toMillis(1)
        private val SAVE_INTERVAL = TimeUnit.MINUTES.toMillis(1)
        private const val KEEP_DAYS = 35L
    }
}
