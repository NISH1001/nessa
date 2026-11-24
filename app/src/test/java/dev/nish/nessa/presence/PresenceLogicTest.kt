package dev.nish.nessa.presence

import dev.nish.nessa.schedule.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PresenceLogicTest {
    private val t0 = Instant.parse("2026-01-10T12:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)
    private val wifiOnly = PresenceConfig(enabled = true, wifiEnabled = true, homeWifi = setOf("HomeNet"), awayAfterMin = 5)
    private val both = wifiOnly.copy(areaEnabled = true, homeLat = 40.0, homeLon = -75.0, radiusM = 200)

    // --- individual signals ---------------------------------------------------------------

    @Test fun `wifi signal`() {
        assertEquals(Signal.Home, wifiSignal(wifiOnly, WifiReading.Connected("HomeNet")))
        assertEquals(Signal.Away, wifiSignal(wifiOnly, WifiReading.Connected("CoffeeShop")))
        assertEquals(Signal.Away, wifiSignal(wifiOnly, WifiReading.NotOnWifi))
        assertEquals(Signal.Unknown, wifiSignal(wifiOnly, WifiReading.NameHidden))
        assertEquals(Signal.Off, wifiSignal(wifiOnly.copy(wifiEnabled = false), WifiReading.Connected("HomeNet")))
        assertEquals(Signal.Off, wifiSignal(wifiOnly.copy(homeWifi = emptySet()), WifiReading.Connected("HomeNet")))
    }

    @Test fun `wifi names match with or without the quotes Android adds`() {
        assertEquals(Signal.Home, wifiSignal(wifiOnly, WifiReading.Connected("\"HomeNet\"")))
    }

    @Test fun `area signal uses distance and how sure the fix is`() {
        fun fix(lat: Double, acc: Float = 20f, ageMin: Long = 0) = LocationFix(lat, -75.0, acc, at(-ageMin))
        assertEquals(Signal.Home, areaSignal(both, fix(40.0), t0))
        assertEquals(Signal.Home, areaSignal(both, fix(40.0015), t0))          // ~167 m
        assertEquals(Signal.Away, areaSignal(both, fix(40.01), t0))            // ~1.1 km
        assertEquals(Signal.Unknown, areaSignal(both, fix(40.0025, acc = 150f), t0)) // ~278 m, could be either
        assertEquals(Signal.Unknown, areaSignal(both, fix(40.01, ageMin = 30), t0))  // too old
        assertEquals(Signal.Unknown, areaSignal(both, null, t0))
        assertEquals(Signal.Off, areaSignal(both.copy(areaEnabled = false), fix(40.0), t0))
        assertEquals(Signal.Off, areaSignal(both.copy(homeLat = null), fix(40.0), t0))
    }

    @Test fun `distance is roughly right`() {
        assertEquals(111_195.0, distanceMeters(40.0, -75.0, 41.0, -75.0), 300.0)
        assertEquals(0.0, distanceMeters(40.0, -75.0, 40.0, -75.0), 0.01)
    }

    // --- combining, with the away delay ---------------------------------------------------------

    @Test fun `off when presence is turned off or no signal is usable`() {
        val off = track(PresenceMemory(), wifiOnly.copy(enabled = false), Signal.Home, Signal.Off, t0)
        assertEquals(Presence.Unknown, off.presence)
        assertEquals(Presence.Unknown, track(PresenceMemory(), wifiOnly, Signal.Off, Signal.Off, t0).presence)
        assertEquals(Presence.Unknown, track(PresenceMemory(), both, Signal.Unknown, Signal.Unknown, t0).presence)
    }

    @Test fun `any home signal means home right away`() {
        assertEquals(Presence.Home, track(PresenceMemory(), both, Signal.Home, Signal.Away, t0).presence)
        assertEquals(Presence.Home, track(PresenceMemory(), both, Signal.Away, Signal.Home, t0).presence)
        assertEquals(Presence.Home, track(PresenceMemory(Presence.Away), both, Signal.Home, Signal.Unknown, t0).presence)
    }

    @Test fun `leaving only counts after the away delay`() {
        var m = track(PresenceMemory(Presence.Home), wifiOnly, Signal.Away, Signal.Off, t0)
        assertEquals(Presence.Home, m.presence)
        assertEquals(at(5), m.awayAt)
        m = track(m, wifiOnly, Signal.Away, Signal.Off, at(4))
        assertEquals(Presence.Home, m.presence)
        m = track(m, wifiOnly, Signal.Away, Signal.Off, at(5))
        assertEquals(Presence.Away, m.presence)
        assertNull(m.awayAt)
    }

    @Test fun `coming back within the delay cancels leaving`() {
        var m = track(PresenceMemory(Presence.Home), wifiOnly, Signal.Away, Signal.Off, t0)
        m = track(m, wifiOnly, Signal.Home, Signal.Off, at(3))
        assertEquals(Presence.Home, m.presence)
        assertNull(m.awayAt)
        m = track(m, wifiOnly, Signal.Away, Signal.Off, at(4))
        assertEquals(at(9), m.awayAt)
    }

    @Test fun `wifi dropping while location still says home is not leaving`() {
        val m = track(PresenceMemory(Presence.Home), both, Signal.Away, Signal.Home, t0)
        assertEquals(Presence.Home, m.presence)
        assertNull(m.awayAt)
    }

    @Test fun `one signal away and the other unsure still counts as leaving`() {
        var m = track(PresenceMemory(Presence.Home), both, Signal.Away, Signal.Unknown, t0)
        assertEquals(at(5), m.awayAt)
        m = track(m, both, Signal.Away, Signal.Unknown, at(5))
        assertEquals(Presence.Away, m.presence)
    }

    @Test fun `first reading with no history starts from that reading`() {
        assertEquals(Presence.Away, track(PresenceMemory(), wifiOnly, Signal.Away, Signal.Off, t0).presence)
        assertEquals(Presence.Home, track(PresenceMemory(), wifiOnly, Signal.Home, Signal.Off, t0).presence)
    }

    @Test fun `losing every signal while away stays away rather than guessing`() {
        val m = track(PresenceMemory(Presence.Away), both, Signal.Unknown, Signal.Unknown, t0)
        assertEquals(Presence.Away, m.presence)
    }

    @Test fun `losing every signal while home keeps home and does not start the delay`() {
        val m = track(PresenceMemory(Presence.Home), both, Signal.Unknown, Signal.Unknown, t0)
        assertEquals(Presence.Home, m.presence)
        assertNull(m.awayAt)
    }

    @Test fun `away delay options snap to the allowed values`() {
        assertEquals(listOf(1, 2, 5, 10, 15), AWAY_AFTER_OPTIONS)
        assertEquals(5, PresenceConfig().awayAfterMin)
        assertTrue(PresenceConfig().radiusM in listOf(100, 200, 500))
    }

    @Test fun `why text explains the decision`() {
        assertEquals("Home Wi-Fi", whyText(Presence.Home, Signal.Home, Signal.Off, null, t0))
        assertEquals("Home area", whyText(Presence.Home, Signal.Away, Signal.Home, null, t0))
        assertEquals("Off home Wi-Fi", whyText(Presence.Away, Signal.Away, Signal.Off, null, t0))
        assertEquals("Outside home area", whyText(Presence.Away, Signal.Off, Signal.Away, null, t0))
        assertEquals("Away in 3 min", whyText(Presence.Home, Signal.Away, Signal.Off, at(3), t0))
        assertEquals("Unknown", whyText(Presence.Unknown, Signal.Unknown, Signal.Unknown, null, t0))
    }

    @Test fun `countdown reads as minutes and seconds`() {
        assertEquals("Away in 1:00", countdownText(at(1), t0))
        assertEquals("Away in 0:42", countdownText(t0.plusSeconds(42), t0))
        assertEquals("Away in 14:05", countdownText(t0.plusSeconds(14 * 60 + 5), t0))
        assertEquals("Away in 0:00", countdownText(t0.minusSeconds(3), t0))
    }
}
