package dev.nish.nessa.history

import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.whisker.Activity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** One Whisker activity as kept on the phone. */
data class StoredActivity(
    val id: String,
    val type: String,
    val at: Instant,
    val durationSec: Int?,
    val weightLb: Double?,
    /** Whisker matched this visit to a pet, which it does only when the weight fits her. */
    val assignedToPet: Boolean = false,
)

/** The robot's status at one moment. Whisker keeps no history of these, so Nessa records them. */
data class RobotReading(
    val at: Instant,
    val drawerPercent: Int?,
    val litterPercent: Double?,
    val litterLabel: String?,
    val sleeping: Boolean?,
    val powerOn: Boolean?,
    val cycles: Int?,
)

fun Activity.toStored(): StoredActivity? {
    val at = LitterRepository.parseInstant(timestamp) ?: return null
    return StoredActivity(
        id = eventId ?: messageId ?: "$type@$timestamp",
        type = type,
        at = at,
        durationSec = duration,
        weightLb = petWeight?.div(100),
        assignedToPet = petIds.isNotEmpty(),
    )
}

/** History kept on the phone. Adding a row whose id is already stored does nothing. */
interface HistoryStore {
    /** Returns how many rows were new. */
    suspend fun addActivities(rows: List<StoredActivity>): Int
    suspend fun hasActivity(id: String): Boolean
    suspend fun activitiesSince(from: Instant): List<StoredActivity>
    fun activitiesFlow(): Flow<List<StoredActivity>>
    suspend fun addReading(r: RobotReading)
    suspend fun readingsSince(from: Instant): List<RobotReading>
    fun readingsFlow(): Flow<List<RobotReading>>
}
