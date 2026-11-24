package dev.nish.nessa.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nish.nessa.whisker.Robot
import kotlinx.coroutines.flow.first

/** Remembers which robot the owner picked when the account has more than one. */
interface RobotChoiceStore {
    suspend fun load(): String?
    suspend fun save(serial: String?)
}

/** The robot Nessa looks after: the saved pick if it still exists, else the EVO, else the first one. */
fun pickRobot(robots: List<Robot>, savedSerial: String?): Robot? =
    robots.firstOrNull { it.serial == savedSerial }
        ?: robots.firstOrNull { it.type == "LRE" }
        ?: robots.firstOrNull()

private val Context.prefsDataStore by preferencesDataStore(name = "nessa_prefs")

class DataStoreRobotChoiceStore(context: Context) : RobotChoiceStore {
    private val store = context.applicationContext.prefsDataStore

    override suspend fun load(): String? = store.data.first()[SERIAL]

    override suspend fun save(serial: String?) {
        store.edit { if (serial == null) it.remove(SERIAL) else it[SERIAL] = serial }
    }

    private companion object {
        val SERIAL = stringPreferencesKey("robot_serial")
    }
}
