package dev.nish.nessa.history

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.historyMetaStore by preferencesDataStore(name = "nessa_history_meta")

class DataStoreSyncProgress(context: Context) : SyncProgress {
    private val store = context.applicationContext.historyMetaStore
    override suspend fun backfillDone() = store.data.first()[DONE] == true
    override suspend fun markBackfillDone() { store.edit { it[DONE] = true } }
    private companion object { val DONE = booleanPreferencesKey("backfill_done") }
}
