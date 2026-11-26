package dev.nish.nessa.ui.litter

import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.whisker.Robot
import java.time.Duration
import java.time.Instant

enum class Tone { Good, Resting, Off, Attention, Offline }

/** The litter box's state in words a cat owner would use. */
data class LitterStatusUi(
    val headline: String,
    val detail: String?,
    val tone: Tone,
    val drawerPercent: Int?,
    val litterLabel: String?,
    val weightLb: Double?,
)

/**
 * [reason] is why Nessa wants the robot sleeping or running right now (for example "Bedtime").
 * It is shown only when the robot is actually doing what Nessa wants.
 */
fun litterStatusUi(robot: Robot, now: Instant, reason: String? = null, shouldRest: Boolean? = null): LitterStatusUi {
    val s = robot.state
    val (headline, detail, tone) = when {
        // Only a robot that says it's online counts as online; silence is not good news.
        s == null || s.isOnline != true ->
            Triple("Offline", LitterRepository.parseInstant(s?.lastSeen)?.let { "Last seen ${relativeTime(it, now)}" }, Tone.Offline)
        s.powerStatus != null && s.powerStatus != "On" -> Triple("Off", reason?.takeIf { shouldRest == true } ?: "Powered off", Tone.Off)
        s.isSleeping == true -> Triple("Sleeping", reason?.takeIf { shouldRest == true } ?: "Sleep mode", Tone.Resting)
        s.isDrawerFull == true -> Triple("Drawer is full", "Time to empty it", Tone.Attention)
        s.isDrawerRemoved == true -> Triple("Drawer is out", "Slide it back in", Tone.Attention)
        s.isBonnetRemoved == true -> Triple("Top cover is off", "Put it back on", Tone.Attention)
        // "Drawer almost full" and the like are shown on the drawer card, not as the robot's state.
        s.statusIndicator?.type == "READY" || s.statusIndicator?.type?.startsWith("DRAWER") == true ->
            Triple("Active", reason?.takeIf { shouldRest == false } ?: "Auto-cleaning", Tone.Good)
        // A cat inside, the wait and the cleaning itself are shown on the line below, not as the state.
        s.statusIndicator?.type in setOf("CAT_DETECTED", "CYCLING") || s.state?.startsWith("StRobotCatDetect") == true || s.state == "StRobotClean" ->
            Triple("Active", null, Tone.Good)
        else -> Triple(s.statusIndicator?.title?.takeIf { it.isNotBlank() } ?: "Working", null, Tone.Good)
    }
    return LitterStatusUi(
        headline = headline,
        detail = detail,
        tone = tone,
        drawerPercent = s?.dfiLevelPercent,
        litterLabel = s?.globeLitterLevelIndicator,
        weightLb = s?.weightSensor?.div(100),
    )
}

fun relativeTime(then: Instant, now: Instant): String {
    val minutes = Duration.between(then, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} hr ago"
        minutes < 60 * 48 -> "1 day ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}
