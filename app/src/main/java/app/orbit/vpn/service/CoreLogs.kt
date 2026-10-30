package app.orbit.vpn.service

import io.nekohasekai.libbox.LogIterator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory tail of sing-box logs; bounded independently of the core's own buffer. */
object CoreLogs {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()

    @Synchronized
    fun append(messages: LogIterator?) {
        if (messages == null) return
        val tail = ArrayDeque(_lines.value)
        var characters = tail.sumOf { it.length }
        while (messages.hasNext()) {
            val line = messages.next().message.take(8192)
            tail.addLast(line)
            characters += line.length
            while (tail.size > 1000 || characters > 200_000) characters -= tail.removeFirst().length
        }
        _lines.value = tail.toList()
    }

    @Synchronized
    fun clear() { _lines.value = emptyList() }
}
