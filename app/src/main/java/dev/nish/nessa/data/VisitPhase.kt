package dev.nish.nessa.data

import dev.nish.nessa.whisker.Robot
import java.time.Duration
import java.time.Instant

/** Where the robot is in a visit: cat inside, waiting before it cleans, cleaning, or nothing going on. */
enum class VisitPhase { None, CatInside, Waiting, Cleaning }

/**
 * Reads the phase from the robot's live status. Checked against a real visit on an EVO (2026-11-25):
 * the scale reports `catDetect = WeightSet` while she is inside; the robot enters `StRobotCatDetectDelay`
 * while she is still in there and stays in it through the wait, so the scale clearing is what marks her
 * leaving. Cleaning state names follow pylitterbot's LR5 map.
 */
fun visitPhase(robot: Robot): VisitPhase {
    val s = robot.state ?: return VisitPhase.None
    return when {
        s.state in CLEANING_STATES -> VisitPhase.Cleaning
        s.catDetect == "WeightSet" || s.state == "StRobotCatDetect" -> VisitPhase.CatInside
        s.state == "StRobotCatDetectDelay" -> VisitPhase.Waiting
        s.displayCode == "DcCatDetect" || s.statusIndicator?.type == "CAT_DETECTED" -> VisitPhase.CatInside
        s.displayCode == "DcModeCycle" || s.statusIndicator?.type == "CYCLING" -> VisitPhase.Cleaning
        else -> VisitPhase.None
    }
}

private val CLEANING_STATES = setOf("StRobotClean", "StRobotFindDump", "StRobotEmpty")
private const val DEFAULT_WAIT_MIN = 7

/**
 * Works out when cleaning is due. The robot doesn't say; it only says it is waiting. So the clock starts
 * when the wait is first seen right after the cat was seen inside. A wait first seen partway through
 * (the app was just opened) has no known end.
 */
data class CleanCountdown(val phase: VisitPhase = VisitPhase.None, val cleanAt: Instant? = null) {
    fun next(newPhase: VisitPhase, now: Instant, waitMinutes: Int?): CleanCountdown = when {
        newPhase != VisitPhase.Waiting -> CleanCountdown(newPhase, null)
        phase == VisitPhase.Waiting -> this
        phase == VisitPhase.CatInside -> CleanCountdown(newPhase, now.plus(Duration.ofMinutes((waitMinutes ?: DEFAULT_WAIT_MIN).toLong())))
        else -> CleanCountdown(newPhase, null)
    }
}

/** The words for the Clean button and the status line during a visit. */
fun cleanText(phase: VisitPhase, cleanAt: Instant?, now: Instant): String = when (phase) {
    VisitPhase.None -> "Clean now"
    VisitPhase.CatInside -> "Nessa is inside"
    VisitPhase.Cleaning -> "Cleaning"
    VisitPhase.Waiting -> cleanAt?.let {
        val sec = Duration.between(now, it).seconds.coerceAtLeast(0)
        "Cleaning in ${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
    } ?: "Cleaning soon"
}

/**
 * When cleaning is due. Whisker logs the visit within seconds of her leaving, stamped with the moment she
 * left, so that time plus the robot's wait setting is exact, even if the app was opened mid-wait.
 * [fallback] is the phone's own clock from seeing her leave, used until the visit entry shows up.
 */
fun cleanDueAt(phase: VisitPhase, lastVisitEnd: Instant?, lastCleaned: Instant?, waitMinutes: Int?, fallback: Instant?): Instant? {
    if (phase != VisitPhase.Waiting) return null
    val fresh = lastVisitEnd?.takeIf { lastCleaned == null || it > lastCleaned }
    return fresh?.plus(Duration.ofMinutes((waitMinutes ?: DEFAULT_WAIT_MIN).toLong())) ?: fallback
}

/** The Clean button: the time left while waiting (tap to clean at once), otherwise "Clean now". */
fun cleanButtonText(phase: VisitPhase, cleanAt: Instant?, now: Instant): String =
    if (phase == VisitPhase.Waiting && cleanAt != null) cleanText(phase, cleanAt, now).removePrefix("Cleaning in ") else "Clean now"
