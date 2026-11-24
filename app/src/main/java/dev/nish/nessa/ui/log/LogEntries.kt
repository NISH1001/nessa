package dev.nish.nessa.ui.log

import dev.nish.nessa.data.ActionEntry
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.whisker.Activity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LogKind { Visit, Cleaning, Attention, Other, Nessa, NessaProblem }

/** Who recorded an entry: the robot's own history from Whisker, or something Nessa did. */
enum class LogSource { Whisker, Nessa }

data class LogItem(val time: String, val title: String, val detail: String?, val kind: LogKind, val source: LogSource = LogSource.Whisker)

/** Keeps only entries from [source] (null keeps everything) and drops days left empty. */
fun List<LogDay>.only(source: LogSource?): List<LogDay> =
    if (source == null) this else mapNotNull { day -> day.items.filter { it.source == source }.takeIf { it.isNotEmpty() }?.let { day.copy(items = it) } }

data class LogDay(val label: String, val summary: String, val items: List<LogItem>)

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/** Turns Whisker's raw history into a day-by-day timeline in plain words, newest first. */
fun logDays(activities: List<Activity>, zone: ZoneId, now: Instant, actions: List<ActionEntry> = emptyList()): List<LogDay> {
    val today = now.atZone(zone).toLocalDate()
    val robot = activities.mapNotNull { a -> LitterRepository.parseInstant(a.timestamp)?.let { Triple(it, a, null as ActionEntry?) } }
    val nessa = actions.map { Triple(Instant.ofEpochSecond(it.atEpochSec), null as Activity?, it) }
    return (robot + nessa)
        .sortedByDescending { it.first }
        .groupBy { it.first.atZone(zone).toLocalDate() }
        .map { (date, entries) ->
            val visits = entries.count { it.second?.type == "PET_VISIT" }
            val cleanings = entries.count { it.second?.type == "CYCLE_COMPLETED" }
            LogDay(
                label = when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> DAY.format(date)
                },
                summary = listOfNotNull(
                    count(visits, "visit", "visits"),
                    count(cleanings, "cleaning", "cleanings"),
                ).joinToString(" · "),
                items = entries.map { (at, activity, action) ->
                    val time = TIME.format(at.atZone(zone))
                    if (activity != null) item(time, activity)
                    else LogItem(time, action!!.text, null, if (action.ok) LogKind.Nessa else LogKind.NessaProblem, LogSource.Nessa)
                },
            )
        }
}

private fun count(n: Int, one: String, many: String): String? = when (n) {
    0 -> null
    1 -> "1 $one"
    else -> "$n $many"
}

private fun item(time: String, a: Activity): LogItem = when (a.type) {
    "PET_VISIT" -> LogItem(
        time,
        "Nessa visited",
        listOfNotNull(
            a.duration?.let { if (it < 60) "$it sec" else "${it / 60} min" },
            a.petWeight?.let { String.format(Locale.US, "%.1f lb", it / 100) },
        ).joinToString(" · ").ifEmpty { null },
        LogKind.Visit,
    )
    "CYCLE_COMPLETED" -> LogItem(time, "Cleaned", null, LogKind.Cleaning)
    "CYCLE_INTERRUPTED" -> LogItem(time, "Cleaning was interrupted", null, LogKind.Attention)
    "WASTE_DRAWER_REMOVED" -> LogItem(time, "Drawer taken out", null, LogKind.Other)
    "PINCH_FAULT" -> LogItem(time, "Safety stop", "Something was in the way", LogKind.Attention)
    "EXTENDED_SCALE_ACTIVITY" -> LogItem(time, "Long activity on the scale", null, LogKind.Other)
    else -> LogItem(time, a.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, null, LogKind.Other)
}
