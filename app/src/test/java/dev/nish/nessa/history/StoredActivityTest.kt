package dev.nish.nessa.history

import dev.nish.nessa.whisker.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class StoredActivityTest {
    @Test fun `toStored uses eventId then messageId then type and time`() {
        assertEquals("e1", Activity("PET_VISIT", "2026-01-05T10:00:00Z", eventId = "e1", messageId = "m1").toStored()!!.id)
        assertEquals("m1", Activity("PET_VISIT", "2026-01-05T10:00:00Z", messageId = "m1").toStored()!!.id)
        assertEquals("PET_VISIT@2026-01-05T10:00:00Z", Activity("PET_VISIT", "2026-01-05T10:00:00Z").toStored()!!.id)
    }

    @Test fun `toStored converts weight to pounds and keeps duration`() {
        val s = Activity("PET_VISIT", "2026-01-05T10:00:00Z", petWeight = 812.0, duration = 95, eventId = "e").toStored()!!
        assertEquals(8.12, s.weightLb!!, 0.0001)
        assertEquals(95, s.durationSec)
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), s.at)
    }

    @Test fun `toStored skips unreadable times`() {
        assertNull(Activity("PET_VISIT", "not a time").toStored())
    }

    @Test fun `toStored remembers whether Whisker matched the visit to a pet`() {
        assertEquals(true, Activity("PET_VISIT", "2026-01-05T10:00:00Z", petIds = listOf("PET-X"), eventId = "a").toStored()!!.assignedToPet)
        assertEquals(false, Activity("PET_VISIT", "2026-01-05T10:00:00Z", eventId = "b").toStored()!!.assignedToPet)
    }
}
