package dev.nish.nessa.presence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nish.nessa.schedule.Presence
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.time.Instant

private val Context.presenceDataStore by preferencesDataStore(name = "nessa_presence")

/** Home Wi-Fi names and the home location stay in the app's private storage on this phone. */
class DataStorePresenceStore(context: Context) : PresenceStore {
    private val store = context.applicationContext.presenceDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun loadConfig(): PresenceConfig =
        store.data.first()[CONFIG]?.let { runCatching { json.decodeFromString(PresenceConfig.serializer(), it) }.getOrNull() } ?: PresenceConfig()

    override suspend fun saveConfig(c: PresenceConfig) {
        store.edit { it[CONFIG] = json.encodeToString(PresenceConfig.serializer(), c) }
    }

    override suspend fun loadMemory(): PresenceMemory {
        val prefs = store.data.first()
        val presence = prefs[PRESENCE]?.let { runCatching { Presence.valueOf(it) }.getOrNull() } ?: Presence.Unknown
        return PresenceMemory(presence, prefs[AWAY_AT]?.let(Instant::ofEpochSecond))
    }

    override suspend fun saveMemory(m: PresenceMemory) {
        store.edit {
            it[PRESENCE] = m.presence.name
            if (m.awayAt == null) it.remove(AWAY_AT) else it[AWAY_AT] = m.awayAt.epochSecond
        }
    }

    private companion object {
        val CONFIG = stringPreferencesKey("config")
        val PRESENCE = stringPreferencesKey("presence")
        val AWAY_AT = longPreferencesKey("away_at")
    }
}
