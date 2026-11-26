package dev.nish.nessa.data

import dev.nish.nessa.whisker.Robot
import dev.nish.nessa.whisker.RobotState
import dev.nish.nessa.whisker.StatusIndicator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class VisitPhaseTest {
    private fun phase(state: String? = null, display: String? = null, indicator: String? = null, cat: String? = null) =
        visitPhase(Robot(serial = "X", state = RobotState(isOnline = true, state = state, displayCode = display, catDetect = cat, statusIndicator = indicator?.let { StatusIndicator(null, it) })))

    @Test fun `robot states map to the phases of a visit`() {
        assertEquals(VisitPhase.None, phase("StRobotIdle"))
        assertEquals(VisitPhase.CatInside, phase("StRobotCatDetect"))
        assertEquals(VisitPhase.Waiting, phase("StRobotCatDetectDelay"))
        assertEquals(VisitPhase.Cleaning, phase("StRobotClean"))
        assertEquals(VisitPhase.Cleaning, phase("StRobotFindDump"))
        assertEquals(VisitPhase.Cleaning, phase("StRobotEmpty"))
    }

    @Test fun `display code and indicator are used when the state says nothing`() {
        assertEquals(VisitPhase.CatInside, phase(display = "DcCatDetect"))
        assertEquals(VisitPhase.CatInside, phase(indicator = "CAT_DETECTED"))
        assertEquals(VisitPhase.Cleaning, phase(display = "DcModeCycle"))
        assertEquals(VisitPhase.Cleaning, phase(indicator = "CYCLING"))
        assertEquals(VisitPhase.None, phase())
        assertEquals(VisitPhase.None, visitPhase(Robot(serial = "X")))
    }

    @Test fun `the waiting state wins over a lingering cat-detect display`() {
        assertEquals(VisitPhase.Waiting, phase("StRobotCatDetectDelay", display = "DcCatDetect"))
    }

    private val t0: Instant = Instant.parse("2026-01-10T12:00:00Z")

    @Test fun `the countdown starts when she is seen leaving`() {
        var c = CleanCountdown()
        c = c.next(VisitPhase.CatInside, t0, 5)
        assertEquals(null, c.cleanAt)
        c = c.next(VisitPhase.Waiting, t0.plusSeconds(60), 5)
        assertEquals(t0.plusSeconds(60 + 300), c.cleanAt)
    }

    @Test fun `the countdown holds while waiting and clears when cleaning starts`() {
        var c = CleanCountdown().next(VisitPhase.CatInside, t0, 5).next(VisitPhase.Waiting, t0.plusSeconds(60), 5)
        c = c.next(VisitPhase.Waiting, t0.plusSeconds(90), 5)
        assertEquals(t0.plusSeconds(360), c.cleanAt)
        c = c.next(VisitPhase.Cleaning, t0.plusSeconds(360), 5)
        assertEquals(null, c.cleanAt)
    }

    @Test fun `stepping back in restarts the wait`() {
        var c = CleanCountdown().next(VisitPhase.CatInside, t0, 5).next(VisitPhase.Waiting, t0.plusSeconds(60), 5)
        c = c.next(VisitPhase.CatInside, t0.plusSeconds(100), 5)
        assertEquals(null, c.cleanAt)
        c = c.next(VisitPhase.Waiting, t0.plusSeconds(130), 5)
        assertEquals(t0.plusSeconds(130 + 300), c.cleanAt)
    }

    @Test fun `a wait first seen partway through has no known end`() {
        assertEquals(null, CleanCountdown().next(VisitPhase.Waiting, t0, 5).cleanAt)
    }

    @Test fun `a missing wait setting falls back to 7 minutes`() {
        val c = CleanCountdown().next(VisitPhase.CatInside, t0, null).next(VisitPhase.Waiting, t0, null)
        assertEquals(t0.plusSeconds(420), c.cleanAt)
    }

    @Test fun `the button and status text for each phase`() {
        assertEquals("Clean now", cleanText(VisitPhase.None, null, t0))
        assertEquals("Nessa is inside", cleanText(VisitPhase.CatInside, null, t0))
        assertEquals("Cleaning in 4:32", cleanText(VisitPhase.Waiting, t0.plusSeconds(272), t0))
        assertEquals("Cleaning in 0:00", cleanText(VisitPhase.Waiting, t0.minusSeconds(5), t0))
        assertEquals("Cleaning soon", cleanText(VisitPhase.Waiting, null, t0))
        assertEquals("Cleaning", cleanText(VisitPhase.Cleaning, null, t0))
    }

    @Test fun `phases as a real EVO reported them during a visit`() {
        assertEquals(VisitPhase.CatInside, phase("StRobotIdle", display = "DcCatDetect", cat = "WeightSet"))
        assertEquals(VisitPhase.CatInside, phase("StRobotCatDetectDelay", display = "DcCatDetect", indicator = "IN_USE", cat = "WeightSet"))
        assertEquals(VisitPhase.Waiting, phase("StRobotCatDetectDelay", display = "DcCatDetect", indicator = "IN_USE", cat = "WeightClear"))
        assertEquals(VisitPhase.None, phase("StRobotIdle", display = "DcModeIdle", cat = "Clear"))
    }

    @Test fun `cleaning is due the wait setting after the logged end of the visit`() {
        val left = t0
        assertEquals(left.plusSeconds(420), cleanDueAt(VisitPhase.Waiting, left, left.minusSeconds(3600), 7, fallback = null))
        assertEquals(left.plusSeconds(420), cleanDueAt(VisitPhase.Waiting, left, null, 7, fallback = left.plusSeconds(999)))
    }

    @Test fun `an old visit already cleaned up after falls back to the phone's own clock`() {
        val fallback = t0.plusSeconds(300)
        assertEquals(fallback, cleanDueAt(VisitPhase.Waiting, t0.minusSeconds(7200), t0.minusSeconds(3600), 7, fallback))
        assertEquals(null, cleanDueAt(VisitPhase.Waiting, null, null, 7, null))
        assertEquals(null, cleanDueAt(VisitPhase.CatInside, t0, null, 7, t0))
    }

    @Test fun `the button shows just the time left while waiting`() {
        assertEquals("6:12", cleanButtonText(VisitPhase.Waiting, t0.plusSeconds(372), t0))
        assertEquals("Clean now", cleanButtonText(VisitPhase.Waiting, null, t0))
        assertEquals("Clean now", cleanButtonText(VisitPhase.CatInside, null, t0))
        assertEquals("Clean now", cleanButtonText(VisitPhase.None, null, t0))
    }
}
