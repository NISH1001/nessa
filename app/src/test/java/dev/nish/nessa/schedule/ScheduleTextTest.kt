package dev.nish.nessa.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTextTest {
    @Test fun `times read like a clock`() {
        assertEquals("12:00 AM", formatMinute(0))
        assertEquals("7:00 AM", formatMinute(420))
        assertEquals("12:05 PM", formatMinute(725))
        assertEquals("9:05 PM", formatMinute(21 * 60 + 5))
    }

    @Test fun `a window that runs past midnight says so`() {
        assertEquals("9:00 AM → 5:00 PM", Window("a", "Work", 540, 1020).rangeText())
        assertEquals("9:00 PM → 7:00 AM next day", Window("a", "Bedtime", 1260, 420).rangeText())
        assertEquals("All day", Window("a", "Trip", 0, 0).rangeText())
    }

    @Test fun `each decision has a one-word title and a short reason`() {
        fun both(d: Decision) = d.title() to d.detail()
        assertEquals("Sleeping" to "Away", both(Decision(true, Reason.Away, null)))
        assertEquals("Sleeping" to "Bedtime", both(Decision(true, Reason.QuietWindow, "Bedtime")))
        assertEquals("Sleeping" to "Work", both(Decision(true, Reason.Window, "Work")))
        assertEquals("Active" to "Home · Work skipped", both(Decision(false, Reason.HomeOverridesWindow, "Work")))
        assertEquals("Active" to "Home", both(Decision(false, Reason.HomeFree, null)))
        assertEquals("Active" to "No sleep window", both(Decision(false, Reason.NoWindow, null)))
    }

    @Test fun `sleep now reads with its end`() {
        assertEquals("Sleeping" to "Sleep now · until 9:30 AM", Decision(true, Reason.SleepNow, "until 9:30 AM").let { it.title() to it.detail() })
    }

    @Test fun `power off reads as Off`() {
        assertEquals("Off" to "Powered off", Decision(true, Reason.PowerOff, null).let { it.title() to it.detail() })
    }
}
