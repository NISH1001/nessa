package dev.nish.nessa.schedule

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.managerDataStore by preferencesDataStore(name = "nessa_manager")

/** Keeps the schedule, settings and checkpoints on the phone. */
class DataStoreManagerStore(context: Context) : ManagerStore {
    private val store = context.applicationContext.managerDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun load(): ManagerState =
        store.data.first()[STATE]?.let { runCatching { json.decodeFromString(ManagerState.serializer(), it) }.getOrNull() } ?: ManagerState()

    override suspend fun save(s: ManagerState) {
        store.edit { it[STATE] = json.encodeToString(ManagerState.serializer(), s) }
    }

    private companion object {
        val STATE = stringPreferencesKey("state")
    }
}
