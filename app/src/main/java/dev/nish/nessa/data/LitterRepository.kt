package dev.nish.nessa.data

import dev.nish.nessa.whisker.Activity
import dev.nish.nessa.whisker.ConnectionStatus
import dev.nish.nessa.whisker.Robot
import dev.nish.nessa.whisker.RobotCommand
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerException
import dev.nish.nessa.whisker.WhiskerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

sealed interface LitterState {
    data object Loading : LitterState
    data object NotConnected : LitterState
    data object NoRobot : LitterState
    data class Unavailable(val problem: String) : LitterState
    data class Ready(
        val robot: Robot,
        val todayVisits: Int,
        val lastVisit: Activity?,
        val fetchedAt: Instant,
        /** True when the last refresh failed and this is older data. */
        val stale: Boolean = false,
        val problem: String? = null,
        /** Where the robot is in a visit right now. */
        val phase: VisitPhase = VisitPhase.None,
        /** When cleaning is due, if the start of the wait was seen. */
        val cleanAt: Instant? = null,
        /** A visit has happened since the last cleaning. */
        val cleanPending: Boolean = false,
    ) : LitterState
}

/** The litter box as the screens see it: the chosen robot plus today's visits. */
class LitterRepository(
    private val api: WhiskerApi,
    private val session: WhiskerSession,
    private val choice: RobotChoiceStore,
    private val now: () -> Instant = Instant::now,
    /** Told when the visit phase changes, with the robot's raw state name, for diagnostics. */
    private val onPhase: (String?, VisitPhase) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private var countdown = CleanCountdown()
    private val _state = MutableStateFlow<LitterState>(LitterState.Loading)
    val state: StateFlow<LitterState> = _state

    suspend fun refresh() = mutex.withLock {
        session.restore()
        if (session.status.value != ConnectionStatus.Connected) {
            _state.value = LitterState.NotConnected
            return@withLock
        }
        try {
            val robot = pickRobot(api.robots(), choice.load())
            if (robot == null) {
                _state.value = LitterState.NoRobot
                return@withLock
            }
            // The robot is what matters; a hiccup fetching history shouldn't hide it.
            val activities = try {
                api.activities(robot.serial)
            } catch (e: WhiskerException.NeedsReconnect) {
                throw e
            } catch (e: WhiskerException) {
                emptyList()
            }
            val phase = visitPhase(robot)
            if (phase != countdown.phase) onPhase(robot.state?.state, phase)
            countdown = countdown.next(phase, now(), robot.litterRobotSettings?.cycleDelay)
            val visits = activities.filter { it.type == "PET_VISIT" }
            val lastVisitEnd = visits.mapNotNull { parseInstant(it.timestamp) }.maxOrNull()
            val lastCleaned = activities.filter { it.type == "CYCLE_COMPLETED" }.mapNotNull { parseInstant(it.timestamp) }.maxOrNull()
            val zone = zoneOf(robot)
            val today = now().atZone(zone).toLocalDate()
            _state.value = LitterState.Ready(
                robot = robot,
                todayVisits = visits.count { parseInstant(it.timestamp)?.atZone(zone)?.toLocalDate() == today },
                lastVisit = visits.maxByOrNull { parseInstant(it.timestamp) ?: Instant.MIN },
                fetchedAt = now(),
                phase = countdown.phase,
                cleanAt = cleanDueAt(phase, lastVisitEnd, lastCleaned, robot.litterRobotSettings?.cycleDelay, countdown.cleanAt),
                cleanPending = lastVisitEnd != null && (lastCleaned == null || lastVisitEnd > lastCleaned),
            )
        } catch (e: WhiskerException.NeedsReconnect) {
            _state.value = LitterState.NotConnected
        } catch (e: WhiskerException) {
            val problem = e.plainText()
            val previous = _state.value
            _state.value = if (previous is LitterState.Ready) previous.copy(stale = true, problem = problem) else LitterState.Unavailable(problem)
        }
    }

    private val _history = MutableStateFlow<List<Activity>>(emptyList())
    /** Everything Whisker remembers about the litter box, newest first. */
    val history: StateFlow<List<Activity>> = _history

    /** Loads the full history. Returns null on success, or a short reason it didn't work. */
    suspend fun refreshHistory(): String? {
        if (_state.value !is LitterState.Ready) refresh()
        val robot = when (val s = _state.value) {
            is LitterState.Ready -> s.robot
            LitterState.NotConnected -> return "Connect your Whisker account first"
            LitterState.NoRobot -> return "No Litter-Robot found on this account"
            is LitterState.Unavailable -> return s.problem
            LitterState.Loading -> return "Still loading"
        }
        return try {
            val all = mutableListOf<Activity>()
            while (all.size < HISTORY_LIMIT) {
                val page = api.activities(robot.serial, limit = PAGE, offset = all.size)
                all += page
                if (page.size < PAGE) break
            }
            _history.value = all.take(HISTORY_LIMIT)
            null
        } catch (e: WhiskerException) {
            e.plainText()
        }
    }

    /** Starts a cleaning cycle. Returns null on success, or a short reason it didn't work. */
    suspend fun cleanNow(): String? {
        val robot = (_state.value as? LitterState.Ready)?.robot ?: return "No litter box to clean yet"
        try {
            api.command(robot.serial, RobotCommand.CLEAN_CYCLE)
        } catch (e: WhiskerException) {
            return e.plainText()
        }
        refresh()
        return null
    }

    companion object {
        private const val PAGE = 100
        private const val HISTORY_LIMIT = 2000
        fun zoneOf(robot: Robot): ZoneId = runCatching { ZoneId.of(robot.timezone!!) }.getOrDefault(ZoneId.systemDefault())
        fun parseInstant(text: String?): Instant? = runCatching { Instant.parse(text) }.getOrNull()
    }
}

/** A short, plain reason for the owner. Never contains credentials. */
fun WhiskerException.plainText(): String = when (this) {
    is WhiskerException.Network -> "Couldn't reach Whisker"
    is WhiskerException.Server -> "Whisker had a problem (code $code)"
    is WhiskerException.BadCredentials, is WhiskerException.NeedsReconnect -> "Sign in to Whisker again"
}
