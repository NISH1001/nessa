package dev.nish.nessa.schedule

import dev.nish.nessa.whisker.SleepEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

const val DAY_MINUTES = 1440

/**
 * One rest window on a day. Times are minutes from midnight. If [endMin] is not after [startMin]
 * the window runs past midnight into the next day (equal times mean a full 24 hours).
 */
@Serializable
data class Window(
    val id: String,
    val name: String,
    val startMin: Int,
    val endMin: Int,
    /** True for quiet time like Bedtime: the robot rests even when someone is home. */
    val evenWhenHome: Boolean = false,
) {
    val crossesMidnight: Boolean get() = endMin <= startMin

    /** A short reason this window can't be saved, or null if it's fine. */
    fun problem(): String? = when {
        name.isBlank() -> "Give it a name"
        startMin !in 0 until DAY_MINUTES || endMin !in 0 until DAY_MINUTES -> "Times must be within the day"
        else -> null
    }
}

/** A stretch of rest inside one day: from [start] up to but not including [end]. */
data class Seg(val start: Int, val end: Int) {
    operator fun contains(minute: Int) = minute in start until end
}

/** Windows for each day of the week; index 0 is Sunday, matching the robot. */
@Serializable
data class WeekSchedule(val days: List<List<Window>>) {

    fun withDay(dow: Int, windows: List<Window>) = copy(days = days.mapIndexed { i, old -> if (i == dow) windows else old })

    fun copyDay(from: Int, to: Set<Int>) = copy(
        days = days.mapIndexed { i, old ->
            if (i in to && i != from) days[from].map { it.copy(id = newId()) } else old
        },
    )

    /** Windows in force at this moment, including one that started yesterday and runs past midnight. */
    fun activeWindows(dow: Int, minute: Int): List<Window> {
        val today = days[dow].filter { if (it.crossesMidnight) minute >= it.startMin else minute in it.startMin until it.endMin }
        val spill = days[(dow + 6) % 7].filter { it.crossesMidnight && minute < it.endMin }
        return spill + today
    }

    /** The day's rest stretches, merged, including the spill-over from yesterday's overnight windows. */
    fun segments(dow: Int, onlyEvenWhenHome: Boolean = false): List<Seg> {
        fun List<Window>.kept() = if (onlyEvenWhenHome) filter { it.evenWhenHome } else this
        val raw = buildList {
            days[(dow + 6) % 7].kept().filter { it.crossesMidnight && it.endMin > 0 }.forEach { add(Seg(0, it.endMin)) }
            days[dow].kept().forEach { add(Seg(it.startMin, if (it.crossesMidnight) DAY_MINUTES else it.endMin)) }
        }.sortedBy { it.start }
        val merged = mutableListOf<Seg>()
        for (seg in raw) {
            val last = merged.lastOrNull()
            if (last != null && seg.start <= last.end) merged[merged.lastIndex] = Seg(last.start, maxOf(last.end, seg.end))
            else merged += seg
        }
        return merged
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun newId(): String = UUID.randomUUID().toString()

        fun empty() = WeekSchedule(List(7) { emptyList() })

        fun fromJson(text: String): WeekSchedule? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.days.size == 7 }

        /** A starting point for a fresh install: nights every day, working hours on weekdays. Index 0 is Sunday. */
        fun default(): WeekSchedule {
            fun bedtime() = Window(newId(), "Bedtime", 22 * 60, 7 * 60, evenWhenHome = true)
            fun work() = Window(newId(), "Work", 9 * 60, 17 * 60)
            return WeekSchedule((0..6).map { dow -> if (dow in 1..5) listOf(bedtime(), work()) else listOf(bedtime()) })
        }
    }
}

/** Where the owner is, as far as Nessa knows. Unknown means presence detection is off or unavailable. */
enum class Presence { Home, Away, Unknown }

enum class Reason { Away, QuietWindow, Window, HomeOverridesWindow, HomeFree, NoWindow }

data class Decision(val rest: Boolean, val reason: Reason, val windowName: String?)

/**
 * The rule. Presence has priority: away rests, and being home overrides ordinary windows.
 * Quiet windows (Bedtime) apply even at home. With no presence, the schedule alone decides.
 */
fun decide(schedule: WeekSchedule, presence: Presence, dow: Int, minute: Int): Decision {
    if (presence == Presence.Away) return Decision(true, Reason.Away, null)
    val active = schedule.activeWindows(dow, minute)
    active.firstOrNull { it.evenWhenHome }?.let { return Decision(true, Reason.QuietWindow, it.name) }
    val window = active.firstOrNull()
    return when {
        window != null && presence == Presence.Home -> Decision(false, Reason.HomeOverridesWindow, window.name)
        window != null -> Decision(true, Reason.Window, window.name)
        presence == Presence.Home -> Decision(false, Reason.HomeFree, null)
        else -> Decision(false, Reason.NoWindow, null)
    }
}

/** The rest stretches that actually apply on a day, given where the owner is. */
fun effectiveSegments(schedule: WeekSchedule, presence: Presence, dow: Int): List<Seg> = when (presence) {
    Presence.Away -> listOf(Seg(0, DAY_MINUTES))
    Presence.Home -> schedule.segments(dow, onlyEvenWhenHome = true)
    Presence.Unknown -> schedule.segments(dow)
}

/** Minutes from now until the robot should switch between resting and running, or null if it never will. */
fun minutesUntilChange(schedule: WeekSchedule, presence: Presence, dow: Int, minute: Int): Int? {
    fun restAt(offset: Int): Boolean {
        val absolute = minute + offset
        val day = (dow + absolute / DAY_MINUTES) % 7
        return effectiveSegments(schedule, presence, day).any { absolute % DAY_MINUTES in it }
    }
    val now = restAt(0)
    val edges = (0..7).flatMap { d ->
        effectiveSegments(schedule, presence, (dow + d) % 7).flatMap { listOf(it.start, it.end) }.map { d * DAY_MINUTES + it - minute }
    }.filter { it > 0 }.sorted()
    return edges.firstOrNull { restAt(it) != now }
}

/** How the robot reads one day's entry (verified on the EVO): a later sleep than wake covers early morning and evening of that same day. */
fun SleepEntry.restsAt(minute: Int): Boolean = when {
    !isEnabled -> false
    sleepTime < wakeTime -> minute in sleepTime until wakeTime
    else -> minute >= sleepTime || minute < wakeTime
}

/**
 * The single entry to give the robot for one day so it rests during [segs].
 * One entry can hold one stretch, or "from midnight until X" plus "from Y until midnight".
 * With [nowMin] (today) the entry is exact from now until the phone's next change, and keeps
 * tonight's stretch when it can. Without it (other days) it is the day's morning and evening.
 */
fun nativeEntry(dow: Int, segs: List<Seg>, nowMin: Int?): SleepEntry {
    fun single(seg: Seg) = SleepEntry(dow, true, seg.start, seg.end % DAY_MINUTES)
    if (segs.isEmpty()) return SleepEntry(dow, false, 480, 480)
    if (segs.first() == Seg(0, DAY_MINUTES)) return SleepEntry(dow, true, 0, 0)
    val evening = segs.last().takeIf { it.end == DAY_MINUTES }

    if (nowMin == null) {
        val morning = segs.first().takeIf { it.start == 0 }
        return if (morning != null && evening != null && morning != evening) SleepEntry(dow, true, evening.start, morning.end)
        else single(morning ?: evening ?: segs.first())
    }

    val current = segs.firstOrNull { nowMin in it }
    if (current != null) {
        return if (evening != null && evening != current) SleepEntry(dow, true, evening.start, current.end) else single(current)
    }
    val next = segs.firstOrNull { it.start > nowMin } ?: return SleepEntry(dow, false, 480, 480)
    return single(next)
}

/** All seven entries to send to the robot right now. */
fun nativeWeek(schedule: WeekSchedule, presence: Presence, todayDow: Int, nowMin: Int): List<SleepEntry> =
    (0..6).map { dow ->
        when {
            presence == Presence.Away -> SleepEntry(dow, true, 0, 0)
            dow == todayDow -> nativeEntry(dow, effectiveSegments(schedule, presence, dow), nowMin)
            // Other days hold only quiet time, which is right whether or not the phone is around.
            else -> nativeEntry(dow, schedule.segments(dow, onlyEvenWhenHome = true), null)
        }
    }
