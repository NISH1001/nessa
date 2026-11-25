package dev.nish.nessa.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

class FakeHistoryStore : HistoryStore {
    val activities = MutableStateFlow<List<StoredActivity>>(emptyList())
    val readings = MutableStateFlow<List<RobotReading>>(emptyList())

    override suspend fun addActivities(rows: List<StoredActivity>): Int {
        val known = activities.value.map { it.id }.toMutableSet()
        val fresh = rows.filter { known.add(it.id) }
        activities.value = (activities.value + fresh).sortedBy { it.at }
        return fresh.size
    }
    override suspend fun hasActivity(id: String) = activities.value.any { it.id == id }
    override suspend fun activitiesSince(from: Instant) = activities.value.filter { it.at >= from }
    override fun activitiesFlow(): Flow<List<StoredActivity>> = activities
    override suspend fun addReading(r: RobotReading) { readings.value = (readings.value + r).sortedBy { it.at } }
    override suspend fun readingsSince(from: Instant) = readings.value.filter { it.at >= from }
    override fun readingsFlow(): Flow<List<RobotReading>> = readings
}
