package dev.nish.nessa.history

import dev.nish.nessa.data.RobotChoiceStore
import dev.nish.nessa.data.pickRobot
import dev.nish.nessa.data.plainText
import dev.nish.nessa.whisker.Robot
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

fun Robot.toReading(at: Instant) = RobotReading(
    at = at,
    drawerPercent = state?.dfiLevelPercent,
    litterPercent = state?.litterLevelPercent,
    litterLabel = state?.globeLitterLevelIndicator,
    sleeping = state?.isSleeping,
    powerOn = state?.powerStatus?.let { it == "On" },
    cycles = state?.odometerCleanCycles,
)

/** Copies Whisker's history into the phone's database, newest first, stopping where it caught up. */
class HistorySync(
    private val api: WhiskerApi,
    private val choice: RobotChoiceStore,
    private val store: HistoryStore,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    /** Returns null when it worked, or a short reason when it didn't. Stored rows are never lost. */
    suspend fun sync(): String? = mutex.withLock {
        try {
            val robot = pickRobot(api.robots(), choice.load()) ?: return@withLock "No Litter-Robot found on this account"
            store.addReading(robot.toReading(now()))
            var offset = 0
            while (offset < LIMIT) {
                val page = api.activities(robot.serial, limit = PAGE, offset = offset)
                val rows = page.mapNotNull { it.toStored() }
                val added = store.addActivities(rows)
                offset += page.size
                // A short page is the end; a page with nothing new means the rest is already stored.
                if (page.size < PAGE || added == 0) break
            }
            null
        } catch (e: WhiskerException) {
            e.plainText()
        }
    }

    private companion object {
        const val PAGE = 100
        const val LIMIT = 2000
    }
}
