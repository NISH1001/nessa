package dev.nish.nessa.ui.log

import dev.nish.nessa.whisker.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LogEntriesTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-11-24T03:00:00Z") // Nov 23, 10:00 PM local

    private fun days(vararg a: Activity) = logDays(a.toList(), zone, now)

    @Test fun `a visit shows minutes and weight`() {
        val item = days(Activity("PET_VISIT", "2026-11-24T00:13:38Z", petWeight = 930.0, duration = 431)).single().items.single()
        assertEquals("Nessa visited", item.title)
        assertEquals("7 min · 9.3 lb", item.detail)
        assertEquals("7:13 PM", item.time)
        assertEquals(LogKind.Visit, item.kind)
    }

    @Test fun `a short visit shows seconds`() {
        val item = days(Activity("PET_VISIT", "2026-11-24T00:13:38Z", petWeight = 395.0, duration = 26)).single().items.single()
        assertEquals("26 sec · 4.0 lb", item.detail)
    }

    @Test fun `a visit without weight or duration has no detail`() {
        assertNull(days(Activity("PET_VISIT", "2026-11-24T00:13:38Z")).single().items.single().detail)
    }

    @Test fun `known events get plain names`() {
        fun title(type: String) = days(Activity(type, "2026-11-24T00:13:38Z")).single().items.single().title
        assertEquals("Cleaned", title("CYCLE_COMPLETED"))
        assertEquals("Cleaning was interrupted", title("CYCLE_INTERRUPTED"))
        assertEquals("Drawer taken out", title("WASTE_DRAWER_REMOVED"))
        assertEquals("Safety stop", title("PINCH_FAULT"))
        assertEquals("Long activity on the scale", title("EXTENDED_SCALE_ACTIVITY"))
    }

    @Test fun `unknown events are made readable instead of hidden`() {
        assertEquals("Some new thing", days(Activity("SOME_NEW_THING", "2026-11-24T00:13:38Z")).single().items.single().title)
    }

    @Test fun `days are labelled Today Yesterday then by date and sorted newest first`() {
        val result = days(
            Activity("CYCLE_COMPLETED", "2026-11-18T17:00:00Z"),
            Activity("CYCLE_COMPLETED", "2026-11-24T00:20:00Z"),
            Activity("PET_VISIT", "2026-11-24T00:13:38Z"),
            Activity("CYCLE_COMPLETED", "2026-11-22T15:00:00Z"),
        )
        assertEquals(listOf("Today", "Yesterday", "Wed, Nov 18"), result.map { it.label })
        assertEquals(listOf("Cleaned", "Nessa visited"), result[0].items.map { it.title })
    }

    @Test fun `an event just after midnight UTC still belongs to the local evening`() {
        assertEquals("Today", days(Activity("CYCLE_COMPLETED", "2026-11-24T02:59:00Z")).single().label)
    }

    @Test fun `entries with unreadable times are skipped and empty input gives no days`() {
        assertTrue(days(Activity("CYCLE_COMPLETED", "not-a-time")).isEmpty())
        assertTrue(days().isEmpty())
    }

    @Test fun `day summary counts visits and cleanings`() {
        val day = days(
            Activity("PET_VISIT", "2026-11-24T00:13:38Z"),
            Activity("PET_VISIT", "2026-11-24T00:30:00Z"),
            Activity("CYCLE_COMPLETED", "2026-11-24T00:40:00Z"),
            Activity("WASTE_DRAWER_REMOVED", "2026-11-24T00:50:00Z"),
        ).single()
        assertEquals("2 visits · 1 cleaning", day.summary)
        assertEquals("1 visit · 2 cleanings", days(
            Activity("PET_VISIT", "2026-11-24T00:13:38Z"),
            Activity("CYCLE_COMPLETED", "2026-11-24T00:40:00Z"),
            Activity("CYCLE_COMPLETED", "2026-11-24T00:45:00Z"),
        ).single().summary)
    }

    @Test fun `Nessa's own actions are mixed into the timeline by time`() {
        val result = logDays(
            listOf(Activity("CYCLE_COMPLETED", "2026-11-24T00:20:00Z"), Activity("PET_VISIT", "2026-11-24T00:10:00Z")),
            zone, now,
            actions = listOf(
                dev.nish.nessa.data.ActionEntry(Instant.parse("2026-11-24T00:15:00Z").epochSecond, "You left", true),
                dev.nish.nessa.data.ActionEntry(Instant.parse("2026-11-24T00:16:00Z").epochSecond, "Couldn't update litter · Couldn't reach Whisker", false),
            ),
        ).single()
        assertEquals(listOf("Cleaned", "Couldn't update litter · Couldn't reach Whisker", "You left", "Nessa visited"), result.items.map { it.title })
        assertEquals(LogKind.NessaProblem, result.items[1].kind)
        assertEquals(LogKind.Nessa, result.items[2].kind)
        assertEquals("1 visit · 1 cleaning", result.summary)
    }

    @Test fun `a day with only Nessa's actions still shows`() {
        val result = logDays(emptyList(), zone, now, actions = listOf(dev.nish.nessa.data.ActionEntry(now.epochSecond - 60, "You came home", true)))
        assertEquals("Today", result.single().label)
        assertEquals("", result.single().summary)
    }

    @Test fun `every entry is tagged with where it came from and can be filtered`() {
        val days = logDays(
            listOf(Activity("CYCLE_COMPLETED", "2026-11-24T00:20:00Z"), Activity("PET_VISIT", "2026-11-22T15:00:00Z")),
            zone, now,
            actions = listOf(dev.nish.nessa.data.ActionEntry(Instant.parse("2026-11-24T00:15:00Z").epochSecond, "You left", true)),
        )
        assertEquals(listOf(LogSource.Whisker, LogSource.Nessa), days[0].items.map { it.source })
        assertEquals(2, days.size)

        val nessaOnly = days.only(LogSource.Nessa)
        assertEquals(listOf("Today"), nessaOnly.map { it.label })
        assertEquals(listOf("You left"), nessaOnly.single().items.map { it.title })

        val whiskerOnly = days.only(LogSource.Whisker)
        assertEquals(listOf("Today", "Yesterday"), whiskerOnly.map { it.label })
        assertEquals(days, days.only(null))
    }
}
