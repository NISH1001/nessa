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
/** Remembers whether the first full copy of Whisker's history has finished. */
interface SyncProgress {
    suspend fun backfillDone(): Boolean
    suspend fun markBackfillDone()
}

class HistorySync(
    private val api: WhiskerApi,
    private val choice: RobotChoiceStore,
    private val store: HistoryStore,
    private val progress: SyncProgress,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    /** Returns null when it worked, or a short reason when it didn't. Stored rows are never lost. */
    suspend fun sync(): String? = mutex.withLock {
        try {
            val robot = pickRobot(api.robots(), choice.load()) ?: return@withLock "No Litter-Robot found on this account"
            store.addReading(robot.toReading(now()))
            val caughtUp = progress.backfillDone()
            var offset = 0
            var reachedEnd = false
            while (offset < LIMIT) {
                val page = api.activities(robot.serial, limit = PAGE, offset = offset)
                val added = store.addActivities(page.mapNotNull { it.toStored() })
                offset += page.size
                if (page.size < PAGE) { reachedEnd = true; break }
                // Once the full history is in, a page with nothing new means the rest is stored too.
                // Before that, keep going: an earlier first copy may have been cut off partway.
                if (caughtUp && added == 0) break
            }
            if (!caughtUp && (reachedEnd || offset >= LIMIT)) progress.markBackfillDone()
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
