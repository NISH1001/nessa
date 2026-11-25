package dev.nish.nessa.presence

import dev.nish.nessa.schedule.Presence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

interface PresenceStore {
    suspend fun loadConfig(): PresenceConfig
    suspend fun saveConfig(c: PresenceConfig)
    suspend fun loadMemory(): PresenceMemory
    suspend fun saveMemory(m: PresenceMemory)
}

data class PresenceState(
    val config: PresenceConfig = PresenceConfig(),
    val presence: Presence = Presence.Unknown,
    val why: String = "Unknown",
    /** When a pending "you left" becomes final, if one is running. */
    val awayAt: Instant? = null,
    val wifi: Signal = Signal.Off,
    val area: Signal = Signal.Off,
    /** The Wi-Fi the phone is on now, for the "use this Wi-Fi" button. Never stored or logged. */
    val currentWifi: String? = null,
) {
    override fun toString() = "PresenceState(presence=$presence)"
}

/** Holds the latest readings and works out home or away. Tells [onChanged] only when the answer changes. */
class PresenceEngine(
    private val store: PresenceStore,
    private val now: () -> Instant = Instant::now,
    /** Smaller happenings worth a line in the log: a countdown starting or being cancelled. */
    private val onNote: suspend (String) -> Unit = {},
    private val onChanged: suspend (from: Presence, to: Presence) -> Unit,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var memory = PresenceMemory()
    private var wifiReading: WifiReading = WifiReading.NameHidden
    private var fix: LocationFix? = null

    private val _state = MutableStateFlow(PresenceState())
    val state: StateFlow<PresenceState> = _state

    /** What the rule should use right now. */
    val current: Presence get() = _state.value.presence

    suspend fun load() = update { }

    suspend fun onWifi(reading: WifiReading) = update { wifiReading = reading }

    suspend fun onLocation(newFix: LocationFix) = update { fix = newFix }

    /** Re-check with the clock, for when the away delay runs out. */
    suspend fun tick() = update { }

    suspend fun setConfig(config: PresenceConfig) = update {
        store.saveConfig(config)
        _state.value = _state.value.copy(config = config)
    }

    private suspend fun update(change: suspend () -> Unit) {
        var note: String? = null
        val announce = mutex.withLock {
            if (!loaded) {
                _state.value = _state.value.copy(config = store.loadConfig())
                memory = store.loadMemory()
                _state.value = _state.value.copy(presence = if (_state.value.config.enabled) memory.presence else Presence.Unknown)
                loaded = true
            }
            val before = _state.value.presence
            change()
            val config = _state.value.config
            val wifi = wifiSignal(config, wifiReading)
            val area = areaSignal(config, fix, now())
            val next = track(memory, config, wifi, area, now())
            note = when {
                memory.awayAt == null && next.awayAt != null ->
                    "Left home ${if (wifi == Signal.Away) "Wi-Fi" else "area"} · away in ${config.awayAfterMin} min"
                memory.awayAt != null && next.awayAt == null && next.presence == Presence.Home -> "Back before the wait ended"
                else -> null
            }
            if (next != memory) {
                memory = next
                store.saveMemory(next)
            }
            _state.value = PresenceState(
                config = config,
                presence = next.presence,
                why = whyText(next.presence, wifi, area, next.awayAt, now()),
                awayAt = next.awayAt,
                wifi = wifi,
                area = area,
                currentWifi = (wifiReading as? WifiReading.Connected)?.let { cleanWifiName(it.name) },
            )
            next.presence.takeIf { it != before }?.let { before to it }
        }
        note?.let { onNote(it) }
        announce?.let { (from, to) -> onChanged(from, to) }
    }
}
