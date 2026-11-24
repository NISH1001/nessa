package dev.nish.nessa.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nish.nessa.schedule.ApplyResult
import dev.nish.nessa.schedule.Decision
import dev.nish.nessa.schedule.Presence
import dev.nish.nessa.schedule.detail
import dev.nish.nessa.schedule.title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Something Nessa itself noticed or did, for the Log tab. */
@Serializable
data class ActionEntry(val atEpochSec: Long, val text: String, val ok: Boolean)

private const val MAX_ENTRIES = 300
private const val REPEAT_WINDOW_SEC = 30 * 60

fun presenceActionText(presence: Presence): String = when (presence) {
    Presence.Home -> "You came home"
    Presence.Away -> "You left"
    Presence.Unknown -> "Presence unknown"
}

/** What to log for a robot update, or null when nothing changed. A null [decision] means the old schedule was put back. */
fun applyActionText(result: ApplyResult, decision: Decision?, problem: String?): String? = when (result) {
    ApplyResult.Applied -> decision?.let { "Litter ${it.title().lowercase()} · ${it.detail()}" } ?: "Schedule restored"
    ApplyResult.Failed -> "Couldn't update litter · ${problem ?: "Unknown problem"}"
    ApplyResult.AlreadyRight, ApplyResult.Off -> null
}

fun appendAction(list: List<ActionEntry>, entry: ActionEntry): List<ActionEntry> {
    val last = list.lastOrNull()
    val repeat = last != null && !entry.ok && last.text == entry.text && entry.atEpochSec - last.atEpochSec <= REPEAT_WINDOW_SEC
    return if (repeat) list else (list + entry).takeLast(MAX_ENTRIES)
}

private val Context.actionDataStore by preferencesDataStore(name = "nessa_actions")

class ActionLog(context: Context) {
    private val store = context.applicationContext.actionDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ActionEntry.serializer())
    private val mutex = Mutex()
    private var loaded = false

    private val _entries = MutableStateFlow<List<ActionEntry>>(emptyList())
    val entries: StateFlow<List<ActionEntry>> = _entries

    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun add(text: String, ok: Boolean = true, atEpochSec: Long = System.currentTimeMillis() / 1000) = mutex.withLock {
        ensureLoaded()
        val next = appendAction(_entries.value, ActionEntry(atEpochSec, text, ok))
        if (next !== _entries.value) {
            _entries.value = next
            store.edit { it[KEY] = json.encodeToString(serializer, next) }
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _entries.value = store.data.first()[KEY]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
        loaded = true
    }

    private companion object {
        val KEY = stringPreferencesKey("entries")
    }
}
