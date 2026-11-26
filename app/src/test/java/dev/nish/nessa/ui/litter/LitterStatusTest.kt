package dev.nish.nessa.ui.litter

import dev.nish.nessa.Fixtures
import dev.nish.nessa.whisker.Robot
import dev.nish.nessa.whisker.RobotState
import dev.nish.nessa.whisker.StatusIndicator
import dev.nish.nessa.whisker.WhiskerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LitterStatusTest {
    private val now = Instant.parse("2026-11-23T20:00:00Z")
    private val ready = RobotState(powerStatus = "On", isOnline = true, isSleeping = false, statusIndicator = StatusIndicator("Ready", "READY"))
    private fun ui(state: RobotState?) = litterStatusUi(Robot(serial = "X", state = state), now)

    @Test fun `ready robot shows Active and Good`() {
        assertEquals("Active", ui(ready).headline)
        assertEquals("Auto-cleaning", ui(ready).detail)
        assertEquals(Tone.Good, ui(ready).tone)
    }

    @Test fun `offline shows Offline with last seen`() {
        val s = ui(ready.copy(isOnline = false, lastSeen = "2026-11-23T17:00:00Z"))
        assertEquals("Offline", s.headline)
        assertEquals("Last seen 3 hr ago", s.detail)
        assertEquals(Tone.Offline, s.tone)
    }

    @Test fun `offline without a last seen time has no detail`() {
        assertNull(ui(ready.copy(isOnline = false)).detail)
    }

    @Test fun `powered off shows Off in its own tone`() {
        val s = ui(ready.copy(powerStatus = "Off"))
        assertEquals("Off", s.headline)
        assertEquals("Powered off", s.detail)
        assertEquals(Tone.Off, s.tone)
    }

    @Test fun `sleeping shows Sleeping`() {
        val s = ui(ready.copy(isSleeping = true, statusIndicator = StatusIndicator("Sleep", "SLEEP_MODE")))
        assertEquals("Sleeping", s.headline)
        assertEquals("Sleep mode", s.detail)
        assertEquals(Tone.Resting, s.tone)
    }

    @Test fun `sleeping and drawer full shows Sleeping first`() {
        assertEquals("Sleeping", ui(ready.copy(isSleeping = true, isDrawerFull = true)).headline)
    }

    @Test fun `drawer full needs attention`() {
        val s = ui(ready.copy(isDrawerFull = true))
        assertEquals("Drawer is full", s.headline)
        assertEquals(Tone.Attention, s.tone)
    }

    @Test fun `drawer removed needs attention`() = assertEquals("Drawer is out", ui(ready.copy(isDrawerRemoved = true)).headline)

    @Test fun `bonnet removed needs attention`() = assertEquals("Top cover is off", ui(ready.copy(isBonnetRemoved = true)).headline)

    @Test fun `other status uses Whisker's own title`() {
        val s = ui(ready.copy(statusIndicator = StatusIndicator("Cleaning", "CYCLING")))
        assertEquals("Cleaning", s.headline)
        assertEquals(Tone.Good, s.tone)
    }

    @Test fun `drawer almost full is not the headline`() {
        val s = ui(ready.copy(statusIndicator = StatusIndicator("Drawer almost full", "DRAWER_ALMOST_FULL")))
        assertEquals("Active", s.headline)
        assertEquals(Tone.Good, s.tone)
    }

    @Test fun `no status at all shows Working`() = assertEquals("Working", ui(RobotState(isOnline = true, powerStatus = "On")).headline)

    @Test fun `robot that does not say it is online is treated as offline`() {
        val s = ui(RobotState(powerStatus = "On", statusIndicator = StatusIndicator("Ready", "READY")))
        assertEquals("Offline", s.headline)
        assertEquals(Tone.Offline, s.tone)
    }

    @Test fun `missing state is treated as offline without crashing`() {
        assertEquals("Offline", ui(null).headline)
    }

    @Test fun `minimal offline fixture maps to Offline`() {
        val robot = WhiskerJson.decodeFromString<Robot>(Fixtures.text("robot_offline_minimal.json"))
        assertEquals(Tone.Offline, litterStatusUi(robot, now).tone)
    }

    @Test fun `levels and weight come from the fixture`() {
        val s = litterStatusUi(WhiskerJson.decodeFromString<Robot>(Fixtures.text("robot.json")), now)
        assertEquals(30, s.drawerPercent)
        assertEquals("Optimal", s.litterLabel)
        assertEquals(9.3, s.weightLb!!, 0.001)
    }

    @Test fun `Nessa's reason replaces the generic detail when the robot agrees with it`() {
        val sleeping = Robot(serial = "X", state = ready.copy(isSleeping = true))
        assertEquals("Bedtime", litterStatusUi(sleeping, now, reason = "Bedtime", shouldRest = true).detail)
        val running = Robot(serial = "X", state = ready)
        assertEquals("You're home", litterStatusUi(running, now, reason = "You're home", shouldRest = false).detail)
    }

    @Test fun `Nessa's reason is not shown when the robot is doing something else`() {
        val running = Robot(serial = "X", state = ready)
        assertEquals("Auto-cleaning", litterStatusUi(running, now, reason = "Bedtime", shouldRest = true).detail)
        val full = Robot(serial = "X", state = ready.copy(isDrawerFull = true))
        assertEquals("Time to empty it", litterStatusUi(full, now, reason = "You're home", shouldRest = false).detail)
    }

    @Test fun `relative time wording`() {
        assertEquals("just now", relativeTime(now.minusSeconds(20), now))
        assertEquals("1 min ago", relativeTime(now.minusSeconds(60), now))
        assertEquals("59 min ago", relativeTime(now.minusSeconds(59 * 60), now))
        assertEquals("1 hr ago", relativeTime(now.minusSeconds(3600), now))
        assertEquals("1 day ago", relativeTime(now.minusSeconds(86400), now))
        assertEquals("3 days ago", relativeTime(now.minusSeconds(3 * 86400 + 5), now))
        assertEquals("just now", relativeTime(now.plusSeconds(120), now))
    }
}
