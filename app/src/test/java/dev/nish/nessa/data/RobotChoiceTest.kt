package dev.nish.nessa.data

import dev.nish.nessa.whisker.Robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeRobotChoiceStore(var saved: String? = null) : RobotChoiceStore {
    override suspend fun load() = saved
    override suspend fun save(serial: String?) { saved = serial }
}

class RobotChoiceTest {
    private val lr5 = Robot(serial = "A-1", type = "LR5")
    private val evo = Robot(serial = "B-2", type = "LRE")

    @Test fun `no robots picks nothing`() = assertNull(pickRobot(emptyList(), null))
    @Test fun `single robot is picked`() = assertEquals(lr5, pickRobot(listOf(lr5), null))
    @Test fun `EVO is preferred when nothing is saved`() = assertEquals(evo, pickRobot(listOf(lr5, evo), null))
    @Test fun `saved serial wins`() = assertEquals(lr5, pickRobot(listOf(lr5, evo), "A-1"))
    @Test fun `unknown saved serial falls back to the default`() = assertEquals(evo, pickRobot(listOf(lr5, evo), "GONE"))
    @Test fun `first robot is picked when there is no EVO`() = assertEquals(lr5, pickRobot(listOf(lr5, Robot(serial = "C-3")), null))
}
