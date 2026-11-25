package dev.nish.nessa.data

import dev.nish.nessa.schedule.ApplyResult
import dev.nish.nessa.schedule.Decision
import dev.nish.nessa.schedule.Presence
import dev.nish.nessa.schedule.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionLogTest {
    @Test fun `presence changes read plainly`() {
        assertEquals("You came home", presenceActionText(Presence.Away, Presence.Home))
        assertEquals("You left", presenceActionText(Presence.Home, Presence.Away))
        assertEquals("Presence unknown", presenceActionText(Presence.Home, Presence.Unknown))
    }

    @Test fun `first detection after a restart or setup does not claim you came or went`() {
        assertEquals("Detected home", presenceActionText(Presence.Unknown, Presence.Home))
        assertEquals("Detected away", presenceActionText(Presence.Unknown, Presence.Away))
    }

    @Test fun `robot updates say what happened and why`() {
        assertEquals("Litter sleeping · Away", applyActionText(ApplyResult.Applied, Decision(true, Reason.Away, null), null))
        assertEquals("Litter active · Home", applyActionText(ApplyResult.Applied, Decision(false, Reason.HomeFree, null), null))
        assertEquals("Litter sleeping · Bedtime", applyActionText(ApplyResult.Applied, Decision(true, Reason.QuietWindow, "Bedtime"), null))
        assertEquals("Schedule restored", applyActionText(ApplyResult.Applied, null, null))
        assertEquals("Couldn't update litter · Couldn't reach Whisker", applyActionText(ApplyResult.Failed, null, "Couldn't reach Whisker"))
    }

    @Test fun `nothing is logged when nothing changed`() {
        assertNull(applyActionText(ApplyResult.AlreadyRight, Decision(true, Reason.Away, null), null))
        assertNull(applyActionText(ApplyResult.Off, null, null))
    }

    @Test fun `entries are kept newest last and capped`() {
        var list = emptyList<ActionEntry>()
        repeat(305) { list = appendAction(list, ActionEntry(it.toLong() * 3600, "e$it", true)) }
        assertEquals(300, list.size)
        assertEquals("e304", list.last().text)
        assertEquals("e5", list.first().text)
    }

    @Test fun `the same failure repeated within half an hour is logged once`() {
        var list = listOf(ActionEntry(1000, "Couldn't update litter · Couldn't reach Whisker", false))
        list = appendAction(list, ActionEntry(1000 + 600, "Couldn't update litter · Couldn't reach Whisker", false))
        assertEquals(1, list.size)
        list = appendAction(list, ActionEntry(1000 + 1801, "Couldn't update litter · Couldn't reach Whisker", false))
        assertEquals(2, list.size)
        list = appendAction(list, ActionEntry(1000 + 1900, "You left", true))
        assertEquals(3, list.size)
    }
}
