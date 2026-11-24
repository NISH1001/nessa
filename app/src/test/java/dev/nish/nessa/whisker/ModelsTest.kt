package dev.nish.nessa.whisker

import dev.nish.nessa.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun `robot fixture parses with unknown keys present`() {
        val robot = WhiskerJson.decodeFromString<Robot>(Fixtures.text("robot.json"))
        assertEquals("LRE-TEST-0001", robot.serial)
        assertEquals("LRE", robot.type)
        assertEquals("America/New_York", robot.timezone)
        assertEquals("READY", robot.state?.statusIndicator?.type)
        assertEquals(30, robot.state?.dfiLevelPercent)
        assertEquals(100.0, robot.state?.litterLevelPercent)
        assertEquals(930.0, robot.state?.weightSensor)
        assertEquals(false, robot.state?.isSleeping)
        assertEquals(7, robot.sleepSchedules.size)
        assertEquals(SleepEntry(0, true, 1260, 450), robot.sleepSchedules[0])
        assertEquals("Off", robot.nightLightSettings?.mode)
        assertEquals("Low", robot.panelSettings?.displayIntensity)
        assertEquals(5, robot.litterRobotSettings?.cycleDelay)
    }

    @Test fun `minimal offline robot parses with nulls`() {
        val robot = WhiskerJson.decodeFromString<Robot>(Fixtures.text("robot_offline_minimal.json"))
        assertEquals(false, robot.state?.isOnline)
        assertNull(robot.state?.statusIndicator)
        assertNull(robot.name)
        assertTrue(robot.sleepSchedules.isEmpty())
    }

    @Test fun `robot with explicit null state and null sleepSchedules parses`() {
        val robot = WhiskerJson.decodeFromString<Robot>("""{"serial":"X","state":null,"sleepSchedules":null}""")
        assertNull(robot.state)
        assertTrue(robot.sleepSchedules.isEmpty())
    }

    @Test fun `a broken sleep entry does not stop the robot from parsing`() {
        val robot = WhiskerJson.decodeFromString<Robot>(
            """{"serial":"X","sleepSchedules":[{"dayOfWeek":0,"isEnabled":true,"sleepTime":null},{"dayOfWeek":1,"isEnabled":false,"sleepTime":480,"wakeTime":480},{}]}""",
        )
        assertEquals("X", robot.serial)
        assertEquals(3, robot.sleepSchedules.size)
        assertEquals(SleepEntry(1, false, 480, 480), robot.sleepSchedules[1])
    }

    @Test fun `activities fixture parses visits and cycles`() {
        val acts = WhiskerJson.decodeFromString<List<Activity>>(Fixtures.text("activities.json"))
        val visit = acts.first { it.type == "PET_VISIT" }
        assertEquals(930.0, visit.petWeight)
        assertEquals(431, visit.duration)
        assertEquals(listOf("PET-TEST"), visit.petIds)
        val cycle = acts.first { it.type == "CYCLE_COMPLETED" }
        assertNull(cycle.petWeight)
        assertFalse(cycle.timestamp.isEmpty())
    }
}
