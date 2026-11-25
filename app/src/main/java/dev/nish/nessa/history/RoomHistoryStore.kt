package dev.nish.nessa.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

@Entity(tableName = "activity", indices = [Index("atMs")])
data class ActivityRow(
    @PrimaryKey val id: String,
    val type: String,
    val atMs: Long,
    val durationSec: Int?,
    val weightLb: Double?,
)

@Entity(tableName = "robot_reading", indices = [Index("atMs")])
data class ReadingRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val atMs: Long,
    val drawerPercent: Int?,
    val litterPercent: Double?,
    val litterLabel: String?,
    val sleeping: Boolean?,
    val powerOn: Boolean?,
    val cycles: Int?,
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivities(rows: List<ActivityRow>): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM activity WHERE id = :id)")
    suspend fun hasActivity(id: String): Boolean

    @Query("SELECT * FROM activity WHERE atMs >= :fromMs ORDER BY atMs")
    suspend fun activitiesSince(fromMs: Long): List<ActivityRow>

    @Query("SELECT * FROM activity ORDER BY atMs")
    fun allActivities(): Flow<List<ActivityRow>>

    @Insert
    suspend fun insertReading(row: ReadingRow)

    @Query("SELECT * FROM robot_reading WHERE atMs >= :fromMs ORDER BY atMs")
    suspend fun readingsSince(fromMs: Long): List<ReadingRow>

    @Query("SELECT * FROM robot_reading ORDER BY atMs")
    fun allReadings(): Flow<List<ReadingRow>>
}

@Database(entities = [ActivityRow::class, ReadingRow::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao
}

/** The history database, in the app's private storage. Not backed up; removed on uninstall. */
class RoomHistoryStore(context: Context, inMemory: Boolean = false) : HistoryStore {
    private val db = (if (inMemory) Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
    else Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "nessa_history.db")).build()
    private val dao = db.dao()

    override suspend fun addActivities(rows: List<StoredActivity>): Int =
        dao.insertActivities(rows.map { ActivityRow(it.id, it.type, it.at.toEpochMilli(), it.durationSec, it.weightLb) }).count { it != -1L }

    override suspend fun hasActivity(id: String) = dao.hasActivity(id)

    override suspend fun activitiesSince(from: Instant) = dao.activitiesSince(from.toEpochMilli()).map { it.toStored() }

    override fun activitiesFlow(): Flow<List<StoredActivity>> = dao.allActivities().map { rows -> rows.map { it.toStored() } }

    override suspend fun addReading(r: RobotReading) =
        dao.insertReading(ReadingRow(atMs = r.at.toEpochMilli(), drawerPercent = r.drawerPercent, litterPercent = r.litterPercent, litterLabel = r.litterLabel, sleeping = r.sleeping, powerOn = r.powerOn, cycles = r.cycles))

    override suspend fun readingsSince(from: Instant) = dao.readingsSince(from.toEpochMilli()).map { it.toReading() }

    override fun readingsFlow(): Flow<List<RobotReading>> = dao.allReadings().map { rows -> rows.map { it.toReading() } }

    fun close() = db.close()

    private fun ActivityRow.toStored() = StoredActivity(id, type, Instant.ofEpochMilli(atMs), durationSec, weightLb)
    private fun ReadingRow.toReading() = RobotReading(Instant.ofEpochMilli(atMs), drawerPercent, litterPercent, litterLabel, sleeping, powerOn, cycles)
}
