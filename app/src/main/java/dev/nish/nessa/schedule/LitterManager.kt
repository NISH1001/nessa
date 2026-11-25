package dev.nish.nessa.schedule

import dev.nish.nessa.data.RobotChoiceStore
import dev.nish.nessa.data.pickRobot
import dev.nish.nessa.data.plainText
import dev.nish.nessa.whisker.SleepEntry
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Serializable enum class RestMode { Sleep, PowerOff }

/** The owner's say on presence. Auto follows presence detection. */
@Serializable enum class Override { Auto, Home, Away }

/** A copy of the schedule the robot had just before Nessa changed it. */
@Serializable data class Checkpoint(val atEpochSec: Long, val entries: List<SleepEntry>)

/**
 * "Sleep now": the robot sleeps until [untilEpochSec] (null = until woken), ignoring presence and the
 * schedule. [restoreTo] is the robot's schedule from before, put back afterwards when Nessa isn't
 * otherwise managing sleep.
 */
@Serializable
data class SleepNow(val untilEpochSec: Long?, val restoreTo: List<SleepEntry>? = null)

@Serializable
data class ManagerState(
    /** The master switch. While false Nessa never changes anything on the robot. */
    val enabled: Boolean = false,
    val restMode: RestMode = RestMode.Sleep,
    val override: Override = Override.Auto,
    val schedule: WeekSchedule = WeekSchedule.default(),
    /** The robot's schedule from before Nessa ever touched it. Written once. */
    val original: List<SleepEntry>? = null,
    val checkpoints: List<Checkpoint> = emptyList(),
    val sleepNow: SleepNow? = null,
) {
    /** Whether background checks are needed: sleep management is on, or a Sleep now is running. */
    val needsChecks: Boolean get() = enabled || sleepNow != null
}

interface ManagerStore {
    suspend fun load(): ManagerState
    suspend fun save(s: ManagerState)
}

enum class ApplyResult { Off, AlreadyRight, Applied, Failed }

data class ManagerStatus(
    val decision: Decision? = null,
    val problem: String? = null,
    /** When Nessa needs to look again: the next rule change, or midnight, whichever comes first. */
    val nextCheckAt: Instant? = null,
    val lastAppliedAt: Instant? = null,
)

/**
 * Keeps the robot's sleep schedule in line with the rule. Every write is preceded by a saved
 * checkpoint and followed by reading the robot back until it agrees.
 */
class LitterManager(
    private val api: WhiskerApi,
    private val choice: RobotChoiceStore,
    private val store: ManagerStore,
    private val presence: () -> Presence = { Presence.Unknown },
    private val now: () -> Instant = Instant::now,
    private val pause: suspend () -> Unit = { delay(3000) },
    /** Told about every attempt to change the robot, for the Log tab. A null decision means a restore. */
    private val onEvent: suspend (ApplyResult, Decision?, String?) -> Unit = { _, _, _ -> },
    /** Told about things the owner did by hand, for the Log tab. */
    private val onNote: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var loaded = false

    private val _state = MutableStateFlow(ManagerState())
    val state: StateFlow<ManagerState> = _state

    private val _status = MutableStateFlow(ManagerStatus())
    val status: StateFlow<ManagerStatus> = _status

    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun setSchedule(schedule: WeekSchedule): ApplyResult = change("Schedule changed") { it.copy(schedule = schedule) }

    suspend fun setOverride(override: Override): ApplyResult = change("You set ${override.name}") { it.copy(override = override) }

    suspend fun setRestMode(mode: RestMode): ApplyResult = change(null) { it.copy(restMode = mode) }

    /** Turning on applies the rule now. Turning off puts back the schedule the robot had before Nessa. */
    suspend fun setEnabled(enabled: Boolean): ApplyResult = mutex.withLock {
        ensureLoaded()
        if (enabled) {
            if (!_state.value.enabled) onNote("Sleep management on")
            save(_state.value.copy(enabled = true))
            applyLocked()
        } else {
            val result = restoreOriginalLocked()
            if (result != ApplyResult.Failed) {
                if (_state.value.enabled) onNote("Sleep management off")
                save(_state.value.copy(enabled = false))
            }
            result
        }
    }

    /** Sleep right away for [hours], or until woken when null. Overrides presence and the schedule. */
    suspend fun sleepNow(hours: Int?): ApplyResult = mutex.withLock {
        ensureLoaded()
        val until = hours?.let { now().plusSeconds(it * 3600L).epochSecond }
        onNote(if (hours == null) "Sleep now until you wake it" else "Sleep now for $hours h")
        save(_state.value.copy(sleepNow = SleepNow(until, _state.value.sleepNow?.restoreTo)))
        applyLocked()
    }

    /** End a Sleep now early. */
    suspend fun endSleepNow(): ApplyResult = mutex.withLock {
        ensureLoaded()
        val hold = _state.value.sleepNow ?: return@withLock applyLocked()
        onNote("You ended Sleep now")
        finishSleepNow(hold)
    }

    /** Clears the hold, then either applies the rule or, if Nessa isn't managing sleep, puts the old schedule back. */
    private suspend fun finishSleepNow(hold: SleepNow): ApplyResult {
        save(_state.value.copy(sleepNow = null))
        if (_state.value.enabled) return applyLocked()
        val restore = hold.restoreTo ?: return ApplyResult.Off.also { _status.value = ManagerStatus() }
        return try {
            val robot = pickRobot(api.robots(), choice.load()) ?: return fail("No Litter-Robot found on this account")
            val result = write(robot.serial, robot.sleepSchedules, restore, expectResting = null)
            _status.value = ManagerStatus(problem = result.second)
            onEvent(result.first, null, result.second)
            result.first
        } catch (e: WhiskerException) {
            fail(e.plainText())
        }
    }

    /** Brings the robot in line with the rule. Safe to call any time; does nothing if already right. */
    suspend fun apply(): ApplyResult = mutex.withLock {
        ensureLoaded()
        applyLocked()
    }

    private suspend fun change(note: String?, edit: (ManagerState) -> ManagerState): ApplyResult = mutex.withLock {
        ensureLoaded()
        val next = edit(_state.value)
        if (next != _state.value && note != null) onNote(note)
        save(next)
        applyLocked()
    }

    private suspend fun applyLocked(): ApplyResult {
        _state.value.sleepNow?.let { hold ->
            if (hold.untilEpochSec != null && now().epochSecond >= hold.untilEpochSec) {
                onNote("Sleep now ended")
                return finishSleepNow(hold)
            }
        }
        val s = _state.value
        if (s.sleepNow != null) return applySleepNowLocked(s.sleepNow)
        if (!s.enabled) {
            _status.value = ManagerStatus()
            return ApplyResult.Off
        }
        return try {
            val robot = pickRobot(api.robots(), choice.load()) ?: return fail("No Litter-Robot found on this account")
            val zone = runCatching { ZoneId.of(robot.timezone!!) }.getOrDefault(ZoneId.systemDefault())
            val local = now().atZone(zone)
            val dow = local.dayOfWeek.value % 7
            val minute = local.hour * 60 + local.minute
            val where = when (s.override) {
                Override.Auto -> presence()
                Override.Home -> Presence.Home
                Override.Away -> Presence.Away
            }
            val decision = decide(s.schedule, where, dow, minute)
            val untilChange = minutesUntilChange(s.schedule, where, dow, minute) ?: Int.MAX_VALUE
            val nextCheck = now().truncatedTo(ChronoUnit.MINUTES).plus(minOf(untilChange, DAY_MINUTES - minute).toLong(), ChronoUnit.MINUTES)
            val desired = nativeWeek(s.schedule, where, dow, minute)

            val result = write(robot.serial, robot.sleepSchedules, desired, expectResting = decision.rest)
            _status.value = ManagerStatus(
                decision = decision,
                problem = result.second,
                nextCheckAt = nextCheck,
                lastAppliedAt = if (result.first == ApplyResult.Failed) _status.value.lastAppliedAt else now(),
            )
            onEvent(result.first, decision, result.second)
            result.first
        } catch (e: WhiskerException) {
            onEvent(ApplyResult.Failed, null, e.plainText())
            fail(e.plainText())
        }
    }

    private suspend fun applySleepNowLocked(hold: SleepNow): ApplyResult = try {
        val robot = pickRobot(api.robots(), choice.load()) ?: return fail("No Litter-Robot found on this account")
        val zone = runCatching { ZoneId.of(robot.timezone!!) }.getOrDefault(ZoneId.systemDefault())
        if (!_state.value.enabled && hold.restoreTo == null) {
            save(_state.value.copy(sleepNow = hold.copy(restoreTo = robot.sleepSchedules.sortedBy { it.dayOfWeek })))
        }
        val decision = Decision(true, Reason.SleepNow, hold.untilEpochSec?.let { untilText(Instant.ofEpochSecond(it), zone) } ?: "until you wake it")
        val result = write(robot.serial, robot.sleepSchedules, (0..6).map { SleepEntry(it, true, 0, 0) }, expectResting = true)
        _status.value = ManagerStatus(
            decision = decision,
            problem = result.second,
            nextCheckAt = hold.untilEpochSec?.let(Instant::ofEpochSecond),
            lastAppliedAt = if (result.first == ApplyResult.Failed) _status.value.lastAppliedAt else now(),
        )
        onEvent(result.first, decision, result.second)
        result.first
    } catch (e: WhiskerException) {
        onEvent(ApplyResult.Failed, null, e.plainText())
        fail(e.plainText())
    }

    private fun untilText(at: Instant, zone: ZoneId): String {
        val local = at.atZone(zone)
        val sameDay = local.toLocalDate() == now().atZone(zone).toLocalDate()
        val time = java.time.format.DateTimeFormatter.ofPattern(if (sameDay) "h:mm a" else "EEE h:mm a", java.util.Locale.US).format(local)
        return "until $time"
    }

    private suspend fun restoreOriginalLocked(): ApplyResult {
        val original = _state.value.original ?: return ApplyResult.Off.also { _status.value = ManagerStatus() }
        return try {
            val robot = pickRobot(api.robots(), choice.load()) ?: return fail("No Litter-Robot found on this account")
            val result = write(robot.serial, robot.sleepSchedules, original, expectResting = null)
            _status.value = ManagerStatus(problem = result.second)
            onEvent(result.first, null, result.second)
            result.first
        } catch (e: WhiskerException) {
            fail(e.plainText())
        }
    }

    /** Writes [desired] if the robot doesn't already have it. Returns the result and a problem, if any. */
    private suspend fun write(serial: String, current: List<SleepEntry>, desired: List<SleepEntry>, expectResting: Boolean?): Pair<ApplyResult, String?> {
        val before = current.sortedBy { it.dayOfWeek }
        if (before == desired) return ApplyResult.AlreadyRight to null

        // Checkpoint first, durably, so a crash mid-write can always be undone.
        val s = _state.value
        save(
            s.copy(
                original = s.original ?: before,
                checkpoints = (s.checkpoints + Checkpoint(now().epochSecond, before)).takeLast(MAX_CHECKPOINTS),
            ),
        )
        api.patchRobot(serial, buildJsonObject { putJsonArray("sleepSchedules") { desired.forEach { add(it.toJson()) } } })

        // Whisker can keep returning the old schedule for a few seconds after a write.
        var taken = false
        repeat(CONFIRM_TRIES) {
            val robot = api.robot(serial)
            if (robot.sleepSchedules.sortedBy { it.dayOfWeek } == desired) {
                taken = true
                if (expectResting == null || robot.state?.isSleeping == expectResting) return ApplyResult.Applied to null
            }
            pause()
        }
        return ApplyResult.Failed to when {
            !taken -> "The robot didn't take the new schedule"
            expectResting == true -> "The robot should be resting but says it isn't"
            else -> "The robot should be running but says it's resting"
        }
    }

    private fun SleepEntry.toJson() = buildJsonObject {
        // Written out field by field: every field must be sent, including false and zero.
        put("dayOfWeek", dayOfWeek)
        put("isEnabled", isEnabled)
        put("sleepTime", sleepTime)
        put("wakeTime", wakeTime)
    }

    private fun fail(problem: String): ApplyResult {
        _status.value = _status.value.copy(problem = problem)
        return ApplyResult.Failed
    }

    private suspend fun save(s: ManagerState) {
        store.save(s)
        _state.value = s
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _state.value = store.load()
        loaded = true
    }

    private companion object {
        const val MAX_CHECKPOINTS = 30
        const val CONFIRM_TRIES = 10
    }
}
