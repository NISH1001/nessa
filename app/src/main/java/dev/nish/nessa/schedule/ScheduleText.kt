package dev.nish.nessa.schedule

fun formatMinute(minute: Int): String {
    val h = (minute / 60) % 24
    val m = minute % 60
    val hour12 = if (h % 12 == 0) 12 else h % 12
    return "$hour12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
}

fun Window.rangeText(): String = when {
    startMin == endMin -> "All day"
    crossesMidnight -> "${formatMinute(startMin)} → ${formatMinute(endMin)} next day"
    else -> "${formatMinute(startMin)} → ${formatMinute(endMin)}"
}

/** One word for what the robot is doing. */
fun Decision.title(): String = when {
    reason == Reason.PowerOff -> "Off"
    rest -> "Sleeping"
    else -> "Active"
}

/** The short reason, in the owner's words. */
fun Decision.detail(): String = when (reason) {
    Reason.Away -> "Away"
    Reason.QuietWindow, Reason.Window -> windowName ?: "Sleep window"
    Reason.HomeOverridesWindow -> "Home · $windowName skipped"
    Reason.HomeFree -> "Home"
    Reason.NoWindow -> "No sleep window"
    Reason.PowerOff -> "Power off · ${windowName ?: ""}".trimEnd(' ', '·')
    Reason.SleepNow -> "Sleep now · ${windowName ?: ""}".trimEnd(' ', '·')
}
