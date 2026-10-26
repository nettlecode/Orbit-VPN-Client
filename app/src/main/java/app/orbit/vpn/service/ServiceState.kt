package app.orbit.vpn.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus { STOPPED, STARTING, STARTED, STOPPING }

data class Traffic(
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
)

/** What auto-select is doing in the running tunnel. */
data class AutoStatus(
    /** Id of the server in use, or null before the first measurement. */
    val selectedId: String? = null,
    /** Last measured delays in ms by server id; servers that failed are absent. */
    val delays: Map<String, Int> = emptyMap(),
)

/** Process-wide VPN state shared between the service and the UI. */
object ServiceState {
    private val _status = MutableStateFlow(VpnStatus.STOPPED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    /** Per-second (uplink, downlink) speed samples for the realtime chart, oldest first. */
    private val _samples = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val samples: StateFlow<List<Pair<Long, Long>>> = _samples.asStateFlow()

    const val MAX_SAMPLES = 120

    private val _startedAt = MutableStateFlow(0L)
    val startedAt: StateFlow<Long> = _startedAt.asStateFlow()

    /** Set while a tunnel with auto-select runs. */
    private val _auto = MutableStateFlow<AutoStatus?>(null)
    val auto: StateFlow<AutoStatus?> = _auto.asStateFlow()

    internal fun auto(value: AutoStatus?) {
        _auto.value = value
    }

    internal fun set(status: VpnStatus) {
        _status.value = status
        when (status) {
            VpnStatus.STARTED -> _startedAt.value = System.currentTimeMillis()
            VpnStatus.STOPPED -> {
                _startedAt.value = 0
                _traffic.value = Traffic()
                _samples.value = emptyList()
                _auto.value = null
            }
            else -> Unit
        }
    }

    internal fun fail(message: String?) {
        _error.value = message ?: "unknown error"
    }

    fun clearError() {
        _error.value = null
    }

    internal fun traffic(value: Traffic) {
        _traffic.value = value
        _samples.value = (_samples.value + (value.uplink to value.downlink)).takeLast(MAX_SAMPLES)
    }
}
