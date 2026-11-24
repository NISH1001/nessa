package dev.nish.nessa.presence

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AwayTimerTest {
    private val start: Instant = Instant.parse("2026-01-10T12:00:00Z")

    @Test fun `fires once when the time comes`() = runTest {
        var fired = 0
        val timer = AwayTimer(backgroundScope, now = { start.plusMillis(testScheduler.currentTime) }) { fired++ }
        timer.arm(start.plusSeconds(60))
        advanceTimeBy(59_000); assertEquals(0, fired)
        advanceTimeBy(2_000); assertEquals(1, fired)
    }

    @Test fun `disarming before the time cancels it`() = runTest {
        var fired = false
        val timer = AwayTimer(backgroundScope, now = { start.plusMillis(testScheduler.currentTime) }) { fired = true }
        timer.arm(start.plusSeconds(60))
        advanceTimeBy(30_000)
        timer.arm(null)
        advanceTimeBy(120_000)
        assertFalse(fired)
    }

    /** The bug: the work started by the timer cleared the timer, which cancelled that very work halfway. */
    @Test fun `work started by the timer keeps going when the timer is cleared during it`() = runTest {
        var finished = false
        lateinit var timer: AwayTimer
        timer = AwayTimer(backgroundScope, now = { start.plusMillis(testScheduler.currentTime) }) {
            timer.arm(null)      // what happens when presence flips to Away
            delay(5_000)         // the robot update over the network
            finished = true
        }
        timer.arm(start.plusSeconds(60))
        advanceTimeBy(70_000)
        assertTrue(finished)
    }

    @Test fun `re-arming replaces the earlier time`() = runTest {
        var fired = 0
        val timer = AwayTimer(backgroundScope, now = { start.plusMillis(testScheduler.currentTime) }) { fired++ }
        timer.arm(start.plusSeconds(60))
        timer.arm(start.plusSeconds(300))
        advanceTimeBy(100_000); assertEquals(0, fired)
        advanceTimeBy(250_000); assertEquals(1, fired)
    }
}
