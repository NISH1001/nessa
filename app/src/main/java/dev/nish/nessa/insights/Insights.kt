package dev.nish.nessa.insights

import dev.nish.nessa.history.RobotReading
import dev.nish.nessa.history.StoredActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Turns the stored history into what the Home tab shows. Plain Kotlin, no Android.
 *
 * "Usual" is always a fixed baseline: the 30 days before today, counting only days the robot recorded
 * anything. It never follows the selected range, so switching the range can't turn flags on or off.
 */

enum class Range(val days: Int?) { Week(7), Month(30), Quarter(90), All(null) }

data class WeightPoint(val at: Instant, val lb: Double, val outlier: Boolean)
data class WeightCard(val points: List<WeightPoint>, val usualLb: Double?)

data class DayCount(val day: LocalDate, val count: Int, val busy: Boolean)
data class VisitsCard(val days: List<DayCount>, val usual: Double?, val today: Int, val returnTrips: Int, val returnAfterCleaning: Int)

data class VisitDot(val at: Instant, val seconds: Int)
data class TimeCard(val dots: List<VisitDot>, val typicalSec: Int?, val longestTodaySec: Int?)

class HoursCard(val counts: IntArray, val busiest: String?)

data class RobotCard(
    val cleaningsToday: Int,
    val usualCleanings: Double?,
    val drawerPercent: Int?,
    val drawerFullInDays: Int?,
    val litterLabel: String?,
    val interruptedWeek: Int,
    val safetyStopsWeek: Int,
    val filterDue: LocalDate?,
)

enum class Check { Weight, Visits, NoVisits, Robot }
data class HealthItem(val check: Check, val ok: Boolean, val text: String)
data class HealthWatch(val learning: Boolean, val items: List<HealthItem>)

class InsightsModel(
    val health: HealthWatch,
    val weight: WeightCard,
    val visits: VisitsCard,
    val time: TimeCard,
    val hours: HoursCard,
    val robot: RobotCard,
)

private const val VISIT = "PET_VISIT"
private const val CLEANED = "CYCLE_COMPLETED"
private const val INTERRUPTED = "CYCLE_INTERRUPTED"
private const val SAFETY_STOP = "PINCH_FAULT"
private const val DRAWER_OUT = "WASTE_DRAWER_REMOVED"

private const val BASELINE_DAYS = 30L
private const val LEARNING_DAYS = 14
private const val OUTLIER = 0.15
private const val RELIABLE_VISIT_SEC = 60
private const val BUSY_FACTOR = 2.0
private const val RETURN_TRIP_MIN = 15L
private const val WEIGHT_DROP = 0.05
private const val NO_VISIT_HOURS = 24L
private const val MAX_INTERRUPTIONS = 2

fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

fun buildInsights(
    activities: List<StoredActivity>,
    readings: List<RobotReading>,
    range: Range,
    zone: ZoneId,
    now: Instant,
    filterDue: LocalDate?,
): InsightsModel {
    val acts = activities.sortedBy { it.at }
    val today = now.atZone(zone).toLocalDate()
    fun dayOf(a: StoredActivity): LocalDate = a.at.atZone(zone).toLocalDate()
    val visits = acts.filter { it.type == VISIT }

    val rangeStart: LocalDate? = range.days?.let { today.minusDays(it - 1L) }
    fun inRange(d: LocalDate) = rangeStart == null || d >= rangeStart
    val baselineStart = today.minusDays(BASELINE_DAYS)
    fun inBaseline(d: LocalDate) = d >= baselineStart && d < today

    val daysWithData = acts.map(::dayOf).toSortedSet()
    val baselineDays = daysWithData.filter(::inBaseline)

    // --- weight ---
    val weightWindow = visits.filter { it.weightLb != null && dayOf(it) >= baselineStart }
    val anchor = median(weightWindow.filter { (it.durationSec ?: 0) > RELIABLE_VISIT_SEC }.map { it.weightLb!! })
    val usualLb = anchor?.let { a -> median(weightWindow.map { it.weightLb!! }.filter { abs(it - a) / a <= OUTLIER }) }
    fun isOutlier(lb: Double) = usualLb != null && abs(lb - usualLb) / usualLb > OUTLIER
    val weight = WeightCard(
        points = visits.filter { it.weightLb != null && inRange(dayOf(it)) }.map { WeightPoint(it.at, it.weightLb!!, isOutlier(it.weightLb)) },
        usualLb = usualLb,
    )

    // --- visits ---
    val visitsByDay = visits.groupingBy(::dayOf).eachCount()
    val usualVisits = median(baselineDays.map { (visitsByDay[it] ?: 0).toDouble() })
    fun busy(d: LocalDate) = usualVisits != null && usualVisits > 0 && (visitsByDay[d] ?: 0) >= BUSY_FACTOR * usualVisits
    val cleanings = acts.filter { it.type == CLEANED }

    /** Visits within 15 minutes of the one before, and whether the box cleaned in between. */
    fun returnTrips(days: Set<LocalDate>): Pair<Int, Int> {
        var trips = 0
        var afterCleaning = 0
        visits.zipWithNext().forEach { (prev, cur) ->
            if (dayOf(cur) in days && Duration.between(prev.at, cur.at) <= Duration.ofMinutes(RETURN_TRIP_MIN)) {
                trips++
                if (cleanings.any { it.at > prev.at && it.at < cur.at }) afterCleaning++
            }
        }
        return trips to afterCleaning
    }
    val (tripsToday, afterToday) = returnTrips(setOf(today))
    val visitsCard = VisitsCard(
        days = daysWithData.filter(::inRange).map { DayCount(it, visitsByDay[it] ?: 0, busy(it)) },
        usual = usualVisits,
        today = visitsByDay[today] ?: 0,
        returnTrips = tripsToday,
        returnAfterCleaning = afterToday,
    )

    // --- time in the box ---
    val timed = visits.filter { it.durationSec != null }
    val time = TimeCard(
        dots = timed.filter { inRange(dayOf(it)) }.map { VisitDot(it.at, it.durationSec!!) },
        typicalSec = median(timed.filter { inBaseline(dayOf(it)) }.map { it.durationSec!!.toDouble() })?.roundToInt(),
        longestTodaySec = timed.filter { dayOf(it) == today }.maxOfOrNull { it.durationSec!! },
    )

    // --- usual times ---
    val counts = IntArray(24)
    visits.filter { inRange(dayOf(it)) }.forEach { counts[it.at.atZone(zone).hour]++ }
    val hours = HoursCard(counts, busiestText(counts))

    // --- robot ---
    val weekAgo = now.minus(Duration.ofDays(7))
    val lastWeek = acts.filter { it.at >= weekAgo }
    val cleaningsByDay = cleanings.groupingBy(::dayOf).eachCount()
    val latest = readings.maxByOrNull { it.at }
    val robot = RobotCard(
        cleaningsToday = cleaningsByDay[today] ?: 0,
        usualCleanings = median(baselineDays.map { (cleaningsByDay[it] ?: 0).toDouble() }),
        drawerPercent = readings.sortedBy { it.at }.lastOrNull { it.drawerPercent != null }?.drawerPercent,
        drawerFullInDays = drawerForecast(readings, acts.lastOrNull { it.type == DRAWER_OUT }?.at),
        litterLabel = readings.sortedBy { it.at }.lastOrNull { it.litterLabel != null }?.litterLabel ?: latest?.litterLabel,
        interruptedWeek = lastWeek.count { it.type == INTERRUPTED },
        safetyStopsWeek = lastWeek.count { it.type == SAFETY_STOP },
        filterDue = filterDue,
    )

    // --- health watch ---
    val health = if (baselineDays.size < LEARNING_DAYS) HealthWatch(learning = true, items = emptyList()) else {
        val items = mutableListOf<HealthItem>()

        val recent = visits.filter { it.weightLb != null && !isOutlier(it.weightLb) && dayOf(it) > today.minusDays(14) }.map { it.weightLb!! }
        val before = visits.filter { it.weightLb != null && !isOutlier(it.weightLb) && dayOf(it) <= today.minusDays(14) && dayOf(it) > today.minusDays(44) }.map { it.weightLb!! }
        val recentMedian = median(recent)
        val beforeMedian = median(before)
        val drop = if (recentMedian != null && beforeMedian != null && beforeMedian > 0) (beforeMedian - recentMedian) / beforeMedian else 0.0
        items += if (drop >= WEIGHT_DROP) HealthItem(Check.Weight, false, "Weight down ${(drop * 100).roundToInt()}% in 2 weeks")
        else HealthItem(Check.Weight, true, usualLb?.let { String.format(Locale.US, "Weight steady · %.1f lb", it) } ?: "Weight steady")

        val lastThree = (0L..2L).map { today.minusDays(it) }
        val busyNow = busy(today) || lastThree.count(::busy) >= 2
        items += if (!busyNow) HealthItem(Check.Visits, true, "Visits normal") else {
            val (trips, after) = returnTrips(lastThree.toSet())
            val text = when {
                trips == 0 -> "Visits above usual"
                after * 2 >= trips -> "Visits above usual, box was clean"
                else -> "Visits above usual, box hadn't cleaned yet"
            }
            HealthItem(Check.Visits, false, text)
        }

        val lastVisit = visits.lastOrNull()?.at
        val sinceVisit = lastVisit?.let { Duration.between(it, now) }
        val dayAgo = now.minus(Duration.ofHours(NO_VISIT_HOURS))
        val recentReadings = readings.filter { it.at >= dayAgo }
        val mostlyAwake = recentReadings.isNotEmpty() &&
            recentReadings.count { it.sleeping == false && it.powerOn != false } * 2 >= recentReadings.size
        items += when {
            sinceVisit != null && sinceVisit.toHours() >= NO_VISIT_HOURS && mostlyAwake ->
                HealthItem(Check.NoVisits, false, "No visits for ${sinceVisit.toHours()} h")
            sinceVisit != null && sinceVisit.toHours() < NO_VISIT_HOURS -> HealthItem(Check.NoVisits, true, "Visited in the last 24 h")
            else -> HealthItem(Check.NoVisits, true, "No recent visits · box was resting")
        }

        items += when {
            robot.safetyStopsWeek > 0 -> HealthItem(Check.Robot, false, "${robot.safetyStopsWeek} safety stops this week")
            robot.interruptedWeek > MAX_INTERRUPTIONS -> HealthItem(Check.Robot, false, "${robot.interruptedWeek} cleanings interrupted this week")
            else -> HealthItem(Check.Robot, true, "Robot running smoothly")
        }
        HealthWatch(learning = false, items = items)
    }

    return InsightsModel(health, weight, visitsCard, time, hours, robot)
}

/** Days until the drawer is full, from its fill rate since the last emptying. Needs 2 days of readings. */
private fun drawerForecast(readings: List<RobotReading>, lastEmptied: Instant?): Int? {
    val since = readings.filter { it.drawerPercent != null && (lastEmptied == null || it.at > lastEmptied) }.sortedBy { it.at }
    if (since.size < 2) return null
    val first = since.first()
    val last = since.last()
    val days = Duration.between(first.at, last.at).toMinutes() / 1440.0
    if (days < 2.0) return null
    val perDay = (last.drawerPercent!! - first.drawerPercent!!) / days
    if (perDay <= 0) return null
    return ceil((100 - last.drawerPercent) / perDay - 1e-9).toInt().coerceAtLeast(0)
}

/** Up to two stretches of busy hours, e.g. "7–9 AM and 5–7 PM". */
private fun busiestText(counts: IntArray): String? {
    val max = counts.maxOrNull() ?: 0
    if (max == 0) return null
    val runs = mutableListOf<IntRange>()
    var start = -1
    for (h in 0..24) {
        val hot = h < 24 && counts[h] * 2 >= max
        if (hot && start < 0) start = h
        if (!hot && start >= 0) { runs += start until h; start = -1 }
    }
    return runs.sortedByDescending { r -> r.sumOf { counts[it] } }.take(2).sortedBy { it.first }
        .joinToString(" and ") { hourSpan(it.first, it.last + 1) }
}

private fun hourSpan(from: Int, to: Int): String {
    fun h12(h: Int) = (h % 12).let { if (it == 0) 12 else it }
    fun period(h: Int) = if (h % 24 < 12) "AM" else "PM"
    return if (period(from) == period(to)) "${h12(from)}–${h12(to)} ${period(to)}"
    else "${h12(from)} ${period(from)}–${h12(to)} ${period(to)}"
}
