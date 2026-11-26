package dev.nish.nessa.schedule

import dev.nish.nessa.data.RobotChoiceStore
import dev.nish.nessa.data.pickRobot
import dev.nish.nessa.data.plainText
import dev.nish.nessa.whisker.RobotCommand
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

/**
 * "Wake up": the robot stays active until [untilEpochSec], the moment the sleep it interrupted would have
 * ended. It also ends if home/away changes ([presence] is what it was when the owner woke it).
 */
@Serializable
data class WakeHold(val untilEpochSec: Long, val presence: String)

/** "Power off": the robot stays switched off until the owner turns it on. Outranks everything. */
@Serializable
data object PowerOff

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
    val powerOff: PowerOff? = null,
    val wake: WakeHold? = null,
) {
    /** Whether background checks are needed: sleep management is on, or a Sleep now or Power off is running. */
    val needsChecks: Boolean get() = enabled || sleepNow != null || powerOff != null
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

    /** Switch the robot off until [powerOn]. Nothing else changes the robot meanwhile. */
    suspend fun powerOff(): ApplyResult = mutex.withLock {
        ensureLoaded()
        onNote("You turned power off")
        save(_state.value.copy(powerOff = PowerOff, wake = null))
        val result = try {
            setPower(on = false)
        } catch (e: WhiskerException) {
            fail(e.plainText())
        }
        _status.value = ManagerStatus(
            decision = Decision(true, Reason.PowerOff, null),
            problem = _status.value.problem.takeIf { result == ApplyResult.Failed },
            lastAppliedAt = now(),
        )
        result
    }

    /** Turn the robot back on early. */
    suspend fun powerOn(): ApplyResult = mutex.withLock {
        ensureLoaded()
        if (_state.value.powerOff == null) return@withLock applyLocked()
        onNote("You turned power on")
        endPowerOffLocked()
    }

    /**
     * Sends power on and clears the hold straight away. The robot can take a minute to start; a hold kept
     * while waiting once made Nessa turn the robot off again behind the owner's back.
     */
    private suspend fun endPowerOffLocked(): ApplyResult {
        save(_state.value.copy(powerOff = null))
        _status.value = ManagerStatus()
        val result = try {
            setPower(on = true)
        } catch (e: WhiskerException) {
            return fail(e.plainText())
        }
        if (result == ApplyResult.Failed) return result
        return if (_state.value.needsChecks) applyLocked() else result
    }

    /** Turns the robot on or off and waits until it says so. */
    private suspend fun setPower(on: Boolean): ApplyResult {
        val robot = pickRobot(api.robots(), choice.load()) ?: return fail("No Litter-Robot found on this account")
        if ((robot.state?.powerStatus == "On") == on) return ApplyResult.AlreadyRight
        api.command(robot.serial, if (on) RobotCommand.POWER_ON else RobotCommand.POWER_OFF)
        repeat(if (on) POWER_ON_TRIES else CONFIRM_TRIES) {
            if ((api.robot(robot.serial).state?.powerStatus == "On") == on) return ApplyResult.Applied
            pause()
        }
        return fail(if (on) "The robot hasn't turned on yet" else "The robot didn't turn off")
    }

    /**
     * Wake the robot from whatever sleep the rule has it in (Bedtime, Work, away) and keep it active until
     * that sleep would have ended. Does nothing if the rule doesn't have it asleep.
     */
    suspend fun wakeNow(): ApplyResult = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        wakeRequested = s.enabled && s.sleepNow == null && s.powerOff == null
        try {
            applyLocked()
        } finally {
            wakeRequested = false
        }
    }

    private var wakeRequested = false

    private fun where(s: ManagerState): Presence = when (s.override) {
        Override.Auto -> presence()
        Override.Home -> Presence.Home
        Override.Away -> Presence.Away
    }

    /** Sleep right away for [hours], or until woken when null. Overrides presence and the schedule. */
    suspend fun sleepNow(hours: Int?): ApplyResult = mutex.withLock {
        ensureLoaded()
        val until = hours?.let { now().plusSeconds(it * 3600L).epochSecond }
        onNote(if (hours == null) "Sleep now until you wake it" else "Sleep now for $hours h")
        save(_state.value.copy(sleepNow = SleepNow(until, _state.value.sleepNow?.restoreTo), wake = null))
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
        if (_state.value.powerOff != null) return applyPowerOffLocked()
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
            val where = where(s)
            val ruleDecision = decide(s.schedule, where, dow, minute)
            // A wake-up lasts until its time, or until home/away changes.
            var wake = s.wake?.takeIf { now().epochSecond < it.untilEpochSec && it.presence == where.name }
            if (s.wake != null && wake == null) save(_state.value.copy(wake = null))
            if (wakeRequested && wake == null && ruleDecision.rest) {
                val minutes = minutesUntilChange(s.schedule, where, dow, minute) ?: DEFAULT_WAKE_MIN
                val until = now().truncatedTo(ChronoUnit.MINUTES).plus(minutes.toLong(), ChronoUnit.MINUTES)
                wake = WakeHold(until.epochSecond, where.name)
                onNote("You woke it ${untilText(until, zone)}")
                save(_state.value.copy(wake = wake))
            }
            val decision = if (wake != null) Decision(false, Reason.WakeNow, untilText(Instant.ofEpochSecond(wake.untilEpochSec), zone)) else ruleDecision
            val untilChange = if (wake != null) ((wake.untilEpochSec - now().epochSecond + 59) / 60).toInt()
            else minutesUntilChange(s.schedule, where, dow, minute) ?: Int.MAX_VALUE
            val nextCheck = now().truncatedTo(ChronoUnit.MINUTES).plus(minOf(untilChange, DAY_MINUTES - minute).toLong(), ChronoUnit.MINUTES)
            // While woken, today's entry is switched off so the robot is active; other days keep their nights.
            val desired = nativeWeek(s.schedule, where, dow, minute).map { if (wake != null && it.dayOfWeek == dow) SleepEntry(dow, false, 480, 480) else it }

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

    /**
     * While the robot is meant to be off, checks only look. If the robot is found on, someone turned it on
     * at the robot or in Whisker's app: that wins, the hold is dropped, and the normal rule applies again.
     */
    private suspend fun applyPowerOffLocked(): ApplyResult = try {
        val robot = pickRobot(api.robots(), choice.load()) ?: return fail("No Litter-Robot found on this account")
        if (robot.state?.powerStatus == "On") {
            onNote("Power was turned on outside Nessa")
            save(_state.value.copy(powerOff = null))
            _status.value = ManagerStatus()
            if (_state.value.needsChecks) applyLocked() else ApplyResult.AlreadyRight
        } else {
            _status.value = ManagerStatus(decision = Decision(true, Reason.PowerOff, null), lastAppliedAt = now())
            ApplyResult.AlreadyRight
        }
    } catch (e: WhiskerException) {
        fail(e.plainText())
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
        const val POWER_ON_TRIES = 30
        const val DEFAULT_WAKE_MIN = 120
    }
}
