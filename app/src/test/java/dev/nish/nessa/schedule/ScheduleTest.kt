package dev.nish.nessa.schedule

import dev.nish.nessa.whisker.SleepEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SUN = 0; private const val MON = 1; private const val TUE = 2; private const val WED = 3; private const val SAT = 6
private fun t(h: Int, m: Int = 0) = h * 60 + m

class ScheduleTest {
    /** A made-up busy week for exercising the logic: nights, weekday work, and a few gym sessions. */
    private val week = run {
        fun bedtime() = Window(WeekSchedule.newId(), "Bedtime", t(21), t(7), evenWhenHome = true)
        fun work() = Window(WeekSchedule.newId(), "Work", t(9), t(17))
        fun gym(from: Int, to: Int) = Window(WeekSchedule.newId(), "Gym", from, to)
        WeekSchedule(
            listOf(
                listOf(bedtime(), gym(t(13), t(16))),            // Sun
                listOf(bedtime(), work()),                       // Mon
                listOf(bedtime(), gym(t(6), t(8)), work()),      // Tue
                listOf(bedtime(), work(), gym(t(17), t(19, 30))),// Wed
                listOf(bedtime(), work()),                       // Thu
                listOf(bedtime(), work()),                       // Fri
                listOf(bedtime()),                               // Sat
            ),
        )
    }

    // --- defaults -------------------------------------------------------------------------

    @Test fun `a fresh install starts with nights every day and work on weekdays`() {
        val d = WeekSchedule.default()
        assertEquals(7, d.days.size)
        assertEquals(listOf("Bedtime", "Work"), d.days[MON].map { it.name })
        assertEquals(listOf("Bedtime"), d.days[SAT].map { it.name })
        assertEquals(listOf("Bedtime"), d.days[SUN].map { it.name })
        assertEquals(t(22) to t(7), d.days[MON][0].let { it.startMin to it.endMin })
        assertTrue(d.days[MON][0].evenWhenHome)
        assertEquals(t(9) to t(17), d.days[MON][1].let { it.startMin to it.endMin })
        assertFalse(d.days[MON][1].evenWhenHome)
    }

    // --- segments -------------------------------------------------------------------------

    @Test fun `a day includes the spill-over of yesterday's overnight window`() {
        assertEquals(listOf(Seg(0, t(7)), Seg(t(9), t(17)), Seg(t(21), 1440)), week.segments(MON))
    }

    @Test fun `touching and overlapping windows merge`() {
        // Tuesday: bedtime spill until 7:00, gym 6:00-8:00, work 9:00-17:00, bedtime from 21:00
        assertEquals(listOf(Seg(0, t(8)), Seg(t(9), t(17)), Seg(t(21), 1440)), week.segments(TUE))
        // Wednesday: work 9:00-17:00 touches gym 17:00-19:30
        assertEquals(listOf(Seg(0, t(7)), Seg(t(9), t(19, 30)), Seg(t(21), 1440)), week.segments(WED))
    }

    @Test fun `quiet-only segments keep just the windows that apply at home`() {
        assertEquals(listOf(Seg(0, t(7)), Seg(t(21), 1440)), week.segments(TUE, onlyEvenWhenHome = true))
    }

    @Test fun `an empty week has no segments and a full-day window is one segment`() {
        assertTrue(WeekSchedule.empty().segments(MON).isEmpty())
        val all = WeekSchedule.empty().withDay(MON, listOf(Window("a", "All day", 0, 0)))
        assertEquals(listOf(Seg(0, 1440)), all.segments(MON))
        assertTrue(all.segments(TUE).isEmpty())
    }

    // --- the rule -------------------------------------------------------------------------

    @Test fun `away always rests`() {
        val d = decide(week, Presence.Away, SAT, t(14))
        assertTrue(d.rest); assertEquals(Reason.Away, d.reason)
    }

    @Test fun `home during bedtime rests because bedtime applies at home`() {
        val d = decide(week, Presence.Home, MON, t(23))
        assertTrue(d.rest); assertEquals(Reason.QuietWindow, d.reason); assertEquals("Bedtime", d.windowName)
    }

    @Test fun `home after midnight is still inside last night's bedtime`() {
        val d = decide(week, Presence.Home, TUE, t(3))
        assertTrue(d.rest); assertEquals("Bedtime", d.windowName)
    }

    @Test fun `home during work runs because presence wins`() {
        val d = decide(week, Presence.Home, MON, t(10))
        assertFalse(d.rest); assertEquals(Reason.HomeOverridesWindow, d.reason); assertEquals("Work", d.windowName)
    }

    @Test fun `home outside every window runs`() {
        val d = decide(week, Presence.Home, MON, t(19))
        assertFalse(d.rest); assertEquals(Reason.HomeFree, d.reason); assertNull(d.windowName)
    }

    @Test fun `without presence the schedule alone decides`() {
        assertTrue(decide(week, Presence.Unknown, MON, t(10)).rest)
        assertEquals(Reason.Window, decide(week, Presence.Unknown, MON, t(10)).reason)
        assertFalse(decide(week, Presence.Unknown, MON, t(19)).rest)
        assertEquals(Reason.NoWindow, decide(week, Presence.Unknown, MON, t(19)).reason)
        assertTrue(decide(week, Presence.Unknown, TUE, t(7, 30)).rest) // gym
    }

    @Test fun `window edges are start-inclusive and end-exclusive`() {
        assertTrue(decide(week, Presence.Unknown, MON, t(9)).rest)
        assertFalse(decide(week, Presence.Unknown, MON, t(17)).rest)
        assertFalse(decide(week, Presence.Unknown, MON, t(7)).rest)
    }

    // --- next change ----------------------------------------------------------------------

    @Test fun `next change is the next edge of the effective schedule`() {
        assertEquals(90, minutesUntilChange(week, Presence.Unknown, MON, t(7, 30)))   // work starts 9:00
        assertEquals(30, minutesUntilChange(week, Presence.Unknown, MON, t(16, 30)))  // work ends 17:00
        assertEquals(t(11), minutesUntilChange(week, Presence.Home, MON, t(10)))      // nothing until bedtime 21:00
        assertEquals(t(8), minutesUntilChange(week, Presence.Home, MON, t(23)))       // bedtime ends 7:00 Tue
    }

    @Test fun `next change is null when nothing ever changes`() {
        assertNull(minutesUntilChange(week, Presence.Away, MON, t(10)))
        assertNull(minutesUntilChange(WeekSchedule.empty(), Presence.Unknown, MON, t(10)))
    }

    // --- what the robot is told ---------------------------------------------------------------

    @Test fun `robot reading matches the verified behavior`() {
        val wrap = SleepEntry(MON, true, t(21), t(7))
        assertTrue(wrap.restsAt(t(3))); assertTrue(wrap.restsAt(t(22))); assertFalse(wrap.restsAt(t(12)))
        assertFalse(wrap.restsAt(t(7))); assertTrue(wrap.restsAt(t(21)))
        val single = SleepEntry(MON, true, t(9), t(17))
        assertTrue(single.restsAt(t(9))); assertFalse(single.restsAt(t(18))); assertFalse(single.restsAt(t(3)))
        val whole = SleepEntry(MON, true, 0, 0)
        assertTrue(whole.restsAt(0)); assertTrue(whole.restsAt(1439))
        assertFalse(SleepEntry(MON, false, t(21), t(7)).restsAt(t(22)))
    }

    @Test fun `baseline for a day is bedtime as morning plus evening`() {
        assertEquals(SleepEntry(TUE, true, t(21), t(7)), nativeEntry(TUE, week.segments(TUE, onlyEvenWhenHome = true), nowMin = null))
    }

    @Test fun `a day with nothing gets a disabled entry`() {
        assertEquals(SleepEntry(MON, false, 480, 480), nativeEntry(MON, emptyList(), nowMin = null))
    }

    @Test fun `a whole day of rest uses equal times`() {
        assertEquals(SleepEntry(MON, true, 0, 0), nativeEntry(MON, listOf(Seg(0, 1440)), nowMin = t(10)))
    }

    @Test fun `single stretches map directly`() {
        assertEquals(SleepEntry(MON, true, t(9), t(17)), nativeEntry(MON, listOf(Seg(t(9), t(17))), nowMin = null))
        assertEquals(SleepEntry(MON, true, t(21), 0), nativeEntry(MON, listOf(Seg(t(21), 1440)), nowMin = null))
        assertEquals(SleepEntry(MON, true, 0, t(7)), nativeEntry(MON, listOf(Seg(0, t(7))), nowMin = null))
    }

    /** Whatever entry is chosen for today must tell the robot the right thing from now until the phone's next change. */
    @Test fun `today's entry is right from now until the next change for every minute of every day`() {
        for (presence in listOf(Presence.Unknown, Presence.Home)) for (dow in 0..6) {
            val segs = week.segments(dow, onlyEvenWhenHome = presence == Presence.Home)
            for (now in 0 until 1440 step 7) {
                val entry = nativeEntry(dow, segs, now)
                val until = minOf(1440, now + (minutesUntilChange(week, presence, dow, now) ?: 1440))
                for (m in now until until) {
                    assertEquals("dow=$dow now=$now m=$m $entry", decide(week, presence, dow, m).rest, entry.restsAt(m))
                }
            }
        }
    }

    @Test fun `today's entry also keeps tonight's bedtime when it can so a dead phone still gets a quiet night`() {
        // Monday 6:00, resting until 7:00: the entry should already include 21:00 onward.
        val entry = nativeEntry(MON, week.segments(MON), nowMin = t(6))
        assertTrue(entry.restsAt(t(22)))
        assertFalse(entry.restsAt(t(12)))
    }

    @Test fun `the week sent to the robot when away rests every day completely`() {
        val entries = nativeWeek(week, Presence.Away, MON, t(10))
        assertEquals(7, entries.size)
        assertEquals((0..6).toList(), entries.map { it.dayOfWeek })
        assertTrue(entries.all { it.isEnabled && it.sleepTime == it.wakeTime })
    }

    @Test fun `the week sent to the robot otherwise is today's live entry plus bedtime on other days`() {
        val entries = nativeWeek(week, Presence.Unknown, MON, t(10))
        // 10:00, inside Work: rest until 17:00 and keep tonight's bedtime from 21:00 in the same entry.
        assertEquals(SleepEntry(MON, true, t(21), t(17)), entries[MON])
        assertEquals(SleepEntry(TUE, true, t(21), t(7)), entries[TUE])
        assertEquals(SleepEntry(SUN, true, t(21), t(7)), entries[SUN])
    }

    // --- editing --------------------------------------------------------------------------

    @Test fun `windows are validated`() {
        assertNull(Window("a", "Work", t(9), t(17)).problem())
        assertEquals("Give it a name", Window("a", "  ", t(9), t(17)).problem())
        assertEquals("Times must be within the day", Window("a", "X", -1, t(17)).problem())
        assertEquals("Times must be within the day", Window("a", "X", 0, 1440).problem())
    }

    @Test fun `copying a day replaces the target days' windows with fresh ids`() {
        val copied = week.copyDay(from = TUE, to = setOf(SAT))
        assertEquals(listOf("Bedtime", "Gym", "Work"), copied.days[SAT].map { it.name })
        assertTrue(copied.days[SAT].map { it.id }.intersect(copied.days[TUE].map { it.id }.toSet()).isEmpty())
        assertEquals(week.days[MON], copied.days[MON])
    }

    @Test fun `schedule survives being saved and loaded`() {
        assertEquals(week, WeekSchedule.fromJson(week.toJson()))
        assertNull(WeekSchedule.fromJson("not json"))
        assertNull(WeekSchedule.fromJson("""{"days":[[]]}"""))
    }
}
