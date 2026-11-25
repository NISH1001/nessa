package dev.nish.nessa.insights

import dev.nish.nessa.history.RobotReading
import dev.nish.nessa.history.StoredActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** All data here is made up. */
class InsightsTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private val now = at(today, 20, 0)
    private var n = 0

    private fun at(day: LocalDate, h: Int, m: Int = 0): Instant = LocalDateTime.of(day, java.time.LocalTime.of(h, m)).atZone(zone).toInstant()
    private fun visit(day: LocalDate, h: Int, m: Int = 0, sec: Int = 120, lb: Double? = 8.0) = StoredActivity("v${n++}", "PET_VISIT", at(day, h, m), sec, lb)
    private fun event(type: String, day: LocalDate, h: Int, m: Int = 0) = StoredActivity("e${n++}", type, at(day, h, m), null, null)
    private fun clean(day: LocalDate, h: Int, m: Int = 0) = event("CYCLE_COMPLETED", day, h, m)

    /** [days] days before today, each with [perDay] visits at 8:00, 12:00, 18:00… and one cleaning. */
    private fun history(days: Int, perDay: Int = 2, lb: Double = 8.0): List<StoredActivity> = (1..days).flatMap { d ->
        val day = today.minusDays(d.toLong())
        (0 until perDay).map { visit(day, 8 + it * 4, lb = lb) } + clean(day, 9)
    }

    private fun build(acts: List<StoredActivity>, readings: List<RobotReading> = emptyList(), range: Range = Range.Month, filterDue: LocalDate? = null) =
        buildInsights(acts, readings, range, zone, now, filterDue)

    // --- weight -------------------------------------------------------------------------

    @Test fun `weight outliers are more than 15 percent from usual`() {
        val w = build(history(20) + visit(today, 7, lb = 3.1) + visit(today, 9, lb = 8.9) + visit(today, 10, lb = 9.4)).weight
        assertEquals(8.0, w.usualLb!!, 0.001)
        assertEquals(listOf(true, false, true), w.points.takeLast(3).map { it.outlier })
    }

    @Test fun `usual weight anchors on visits longer than 60 seconds`() {
        // Many short half-in readings at 3 lb would win a plain median; long visits say 8 lb.
        val acts = (1..10).map { visit(today.minusDays(it.toLong()), 8, sec = 20, lb = 3.0) } +
            (1..10).map { visit(today.minusDays(it.toLong()), 18, sec = 200, lb = 8.0) } +
            (1..2).map { visit(today.minusDays(it.toLong()), 19, sec = 200, lb = 3.1) }
        assertEquals(8.0, build(acts).weight.usualLb!!, 0.001)
    }

    @Test fun `no usual weight when every reading is an outlier`() {
        val w = build((1..5).map { visit(today.minusDays(it.toLong()), 8, sec = 30, lb = 3.0 + it * 3) }).weight
        assertNull(w.usualLb)
        assertTrue(w.points.none { it.outlier })
    }

    // --- visits ---------------------------------------------------------------------------

    @Test fun `usual visits is the median of the 30 days before today`() {
        val acts = history(20, perDay = 2) + history(40, perDay = 0).filter { it.at < at(today.minusDays(30), 0) } +
            (0 until 6).map { visit(today, 6 + it * 2) }
        val v = build(acts).visits
        assertEquals(2.0, v.usual!!, 0.001)
        assertEquals(6, v.today)
    }

    @Test fun `days with cleanings but no recorded visit don't pull usual down`() {
        // Whisker misses visits, so a day with only cleanings is a gap in the record, not a zero.
        val acts = history(10, perDay = 3) + history(25, perDay = 0).filter { it.at < at(today.minusDays(10), 0) }
        assertEquals(3.0, build(acts).visits.usual!!, 0.001)
    }

    @Test fun `range changes the bars but not usual or busy`() {
        val acts = history(60, perDay = 2) + (0 until 5).map { visit(today, 6 + it * 2) }
        val week = build(acts, range = Range.Week).visits
        val all = build(acts, range = Range.All).visits
        assertEquals(7, week.days.size)
        assertEquals(61, all.days.size)
        assertEquals(week.usual, all.usual)
        assertEquals(week.days.last().busy, all.days.last().busy)
    }

    @Test fun `busy day is twice usual`() {
        val acts = history(20, perDay = 2) + (0 until 4).map { visit(today, 6 + it * 2) }
        assertTrue(build(acts).visits.days.last().busy)
        val three = history(20, perDay = 2) + (0 until 3).map { visit(today, 6 + it * 2) }
        assertFalse(build(three).visits.days.last().busy)
    }

    @Test fun `days without any activity are left out`() {
        val acts = listOf(visit(today.minusDays(5), 8), visit(today.minusDays(2), 8), visit(today, 8))
        assertEquals(listOf(today.minusDays(5), today.minusDays(2), today), build(acts).visits.days.map { it.day })
    }

    @Test fun `return trips split by a cleaning in between`() {
        val acts = listOf(
            visit(today, 7, 0), visit(today, 7, 10),                 // return, no cleaning between
            visit(today, 9, 0), clean(today, 9, 5), visit(today, 9, 12), // return, after a cleaning
            visit(today, 13, 0),                                       // not a return trip
        )
        val v = build(acts).visits
        assertEquals(2, v.returnTrips)
        assertEquals(1, v.returnAfterCleaning)
    }

    // --- time in the box, hours ----------------------------------------------------------------

    @Test fun `typical time is the median and longest today is today's max`() {
        val acts = listOf(visit(today.minusDays(1), 8, sec = 60), visit(today.minusDays(1), 9, sec = 120), visit(today.minusDays(2), 8, sec = 600),
            visit(today, 8, sec = 90), visit(today, 9, sec = 400))
        val t = build(acts).time
        assertEquals(120, t.typicalSec)
        assertEquals(400, t.longestTodaySec)
        assertEquals(5, t.dots.size)
    }

    @Test fun `hours strip and busiest text`() {
        val acts = (1..5).flatMap { d -> val day = today.minusDays(d.toLong()); listOf(visit(day, 7), visit(day, 8), visit(day, 17), visit(day, 18), visit(day, 13)) } +
            listOf(visit(today.minusDays(1), 2))
        val h = build(acts).hours
        assertEquals(24, h.counts.size)
        assertEquals(5, h.counts[7])
        assertEquals(1, h.counts[2])
        assertEquals("7–9 AM and 5–7 PM", h.busiest)
    }

    @Test fun `days follow the robot's timezone`() {
        // 11:30 PM local on the 19th is already the 20th in UTC.
        val late = StoredActivity("late", "PET_VISIT", at(today.minusDays(1), 23, 30), 60, 8.0)
        val v = build(listOf(late)).visits
        assertEquals(listOf(today.minusDays(1)), v.days.map { it.day })
        assertEquals(0, v.today)
    }

    // --- robot ------------------------------------------------------------------------------

    @Test fun `robot card counts today, usual and the last 7 days`() {
        val acts = history(20) + clean(today, 8) + clean(today, 12) + clean(today, 15) +
            event("CYCLE_INTERRUPTED", today.minusDays(2), 10) + event("CYCLE_INTERRUPTED", today.minusDays(9), 10) +
            event("PINCH_FAULT", today.minusDays(3), 10)
        val r = build(acts, filterDue = today.plusDays(8)).robot
        assertEquals(3, r.cleaningsToday)
        assertEquals(1.0, r.usualCleanings!!, 0.001)
        assertEquals(1, r.interruptedWeek)
        assertEquals(1, r.safetyStopsWeek)
        assertEquals(today.plusDays(8), r.filterDue)
    }

    @Test fun `drawer forecast from readings since the last emptying`() {
        val acts = listOf(event("WASTE_DRAWER_REMOVED", today.minusDays(4), 9))
        val readings = listOf(
            RobotReading(at(today.minusDays(6), 9), 90, null, null, false, true, 1), // before emptying: ignored
            RobotReading(at(today.minusDays(4), 10), 0, null, null, false, true, 1),
            RobotReading(at(today, 10), 40, 95.0, "Optimal", false, true, 1),
        )
        val r = build(acts, readings).robot
        assertEquals(40, r.drawerPercent)
        assertEquals("Optimal", r.litterLabel)
        assertEquals(6, r.drawerFullInDays) // 10% a day, 60% to go
    }

    @Test fun `drawer forecast null with under two days of readings`() {
        val readings = listOf(RobotReading(at(today, 8), 30, null, null, false, true, 1), RobotReading(at(today, 18), 35, null, null, false, true, 1))
        assertNull(build(emptyList(), readings).robot.drawerFullInDays)
    }

    // --- health watch ---------------------------------------------------------------------------

    @Test fun `health watch learns until 14 days with data`() {
        assertTrue(build(history(13)).health.learning)
        val h = build(history(14)).health
        assertFalse(h.learning)
        assertEquals(listOf(Check.Weight, Check.Visits, Check.NoVisits, Check.Robot), h.items.map { it.check })
        assertTrue(h.items.all { it.ok })
    }

    @Test fun `all normal texts`() {
        val texts = build(history(20) + visit(today, 8)).health.items.map { it.text }
        assertEquals(listOf("Weight steady · 8.0 lb", "Visits normal", "Visited in the last 24 h", "Robot running smoothly"), texts)
    }

    @Test fun `weight flag at 5 percent drop`() {
        val acts = (15..44).map { visit(today.minusDays(it.toLong()), 8, lb = 9.0) } + (0..13).map { visit(today.minusDays(it.toLong()), 8, lb = 8.5) }
        val item = build(acts).health.items.first { it.check == Check.Weight }
        assertFalse(item.ok)
        assertEquals("Weight down 6% in 2 weeks", item.text)
        val small = (15..44).map { visit(today.minusDays(it.toLong()), 8, lb = 9.0) } + (0..13).map { visit(today.minusDays(it.toLong()), 8, lb = 8.7) }
        assertTrue(build(small).health.items.first { it.check == Check.Weight }.ok)
    }

    @Test fun `visits flag says whether the box was clean`() {
        val clean = history(20) + listOf(visit(today, 6), visit(today, 7), clean(today, 7, 5), visit(today, 7, 10), clean(today, 7, 15), visit(today, 7, 20))
        assertEquals("Visits above usual, box was clean", build(clean).health.items.first { it.check == Check.Visits }.text)
        val dirty = history(20) + listOf(visit(today, 6), visit(today, 7), visit(today, 7, 10), visit(today, 7, 20))
        assertEquals("Visits above usual, box hadn't cleaned yet", build(dirty).health.items.first { it.check == Check.Visits }.text)
    }

    @Test fun `no visits flag ignores time the robot was asleep or off`() {
        val acts = history(20).filter { it.at < at(today.minusDays(1), 12) }
        val awake = (0..23).map { RobotReading(now.minusSeconds(it * 3600L), 10, null, null, false, true, 1) }
        val item = build(acts, awake).health.items.first { it.check == Check.NoVisits }
        assertFalse(item.ok)
        assertTrue(item.text.startsWith("No visits for "))
        val asleep = (0..23).map { RobotReading(now.minusSeconds(it * 3600L), 10, null, null, true, true, 1) }
        assertTrue(build(acts, asleep).health.items.first { it.check == Check.NoVisits }.ok)
    }

    @Test fun `robot flag for a safety stop or many interruptions`() {
        val stop = history(20) + visit(today, 8) + event("PINCH_FAULT", today.minusDays(1), 10)
        assertEquals("1 safety stop this week", build(stop).health.items.first { it.check == Check.Robot }.text)
        val interrupts = history(20) + visit(today, 8) + (1..3).map { event("CYCLE_INTERRUPTED", today.minusDays(it.toLong()), 10) }
        assertEquals("3 cleanings interrupted this week", build(interrupts).health.items.first { it.check == Check.Robot }.text)
    }

    @Test fun `all cards survive empty history`() {
        val m = build(emptyList())
        assertTrue(m.health.learning)
        assertTrue(m.weight.points.isEmpty()); assertNull(m.weight.usualLb)
        assertTrue(m.visits.days.isEmpty()); assertNull(m.visits.usual); assertEquals(0, m.visits.today)
        assertNull(m.time.typicalSec); assertNull(m.time.longestTodaySec)
        assertNull(m.hours.busiest)
        assertEquals(0, m.robot.cleaningsToday); assertNull(m.robot.drawerPercent); assertNull(m.robot.drawerFullInDays)
    }
}
