package dev.nish.nessa.presence

import dev.nish.nessa.schedule.Presence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FakePresenceStore(var config: PresenceConfig = PresenceConfig(), var memory: PresenceMemory = PresenceMemory()) : PresenceStore {
    override suspend fun loadConfig() = config
    override suspend fun saveConfig(c: PresenceConfig) { config = c }
    override suspend fun loadMemory() = memory
    override suspend fun saveMemory(m: PresenceMemory) { memory = m }
}

class PresenceEngineTest {
    private var now: Instant = Instant.parse("2026-01-10T12:00:00Z")
    private val store = FakePresenceStore(PresenceConfig(enabled = true, homeWifi = setOf("HomeNet"), awayAfterMin = 5))
    private val changes = mutableListOf<Presence>()
    private val notes = mutableListOf<String>()
    private val engine = PresenceEngine(store, now = { now }, onNote = { notes += it }, onChanged = { changes += it })

    @Test fun `starts unknown and becomes home on home wifi`() = runBlocking {
        engine.load()
        assertEquals(Presence.Unknown, engine.current)
        engine.onWifi(WifiReading.Connected("HomeNet"))
        assertEquals(Presence.Home, engine.current)
        assertEquals(listOf(Presence.Home), changes)
        assertEquals("Home Wi-Fi", engine.state.value.why)
    }

    @Test fun `leaving wifi starts the delay and a later tick makes it away`() = runBlocking {
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.onWifi(WifiReading.NotOnWifi)
        assertEquals(Presence.Home, engine.current)
        assertEquals(now.plusSeconds(300), engine.state.value.awayAt)
        now = now.plusSeconds(299); engine.tick()
        assertEquals(Presence.Home, engine.current)
        now = now.plusSeconds(1); engine.tick()
        assertEquals(Presence.Away, engine.current)
        assertEquals(listOf(Presence.Home, Presence.Away), changes)
        assertNull(engine.state.value.awayAt)
    }

    @Test fun `repeated readings do not announce a change again`() = runBlocking {
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.tick()
        assertEquals(listOf(Presence.Home), changes)
    }

    @Test fun `what Nessa believed survives a restart`() = runBlocking {
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.onWifi(WifiReading.NotOnWifi)
        val restarted = PresenceEngine(store, now = { now }, onChanged = { })
        restarted.load()
        assertEquals(Presence.Home, restarted.current)
        assertEquals(now.plusSeconds(300), restarted.state.value.awayAt)
    }

    @Test fun `turning presence off goes back to unknown and says so`() = runBlocking {
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.setConfig(store.config.copy(enabled = false))
        assertEquals(Presence.Unknown, engine.current)
        assertEquals(listOf(Presence.Home, Presence.Unknown), changes)
        assertTrue(!store.config.enabled)
    }

    @Test fun `adding the current network as home makes you home at once`() = runBlocking {
        store.config = PresenceConfig(enabled = true, homeWifi = emptySet())
        engine.load()
        engine.onWifi(WifiReading.Connected("\"NewNet\""))
        assertEquals(Presence.Unknown, engine.current)
        assertEquals("NewNet", engine.state.value.currentWifi)
        engine.setConfig(store.config.copy(homeWifi = setOf("NewNet")))
        assertEquals(Presence.Home, engine.current)
    }

    @Test fun `home area keeps you home when wifi drops`() = runBlocking {
        store.config = store.config.copy(areaEnabled = true, homeLat = 40.0, homeLon = -75.0)
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.onLocation(LocationFix(40.0, -75.0, 20f, now))
        engine.onWifi(WifiReading.NotOnWifi)
        assertEquals(Presence.Home, engine.current)
        assertNull(engine.state.value.awayAt)
        assertEquals("Home area", engine.state.value.why)
    }

    @Test fun `the countdown starting and being cancelled are noted for the log`() = runBlocking {
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.onWifi(WifiReading.NotOnWifi)
        assertEquals(listOf("Left home Wi-Fi · away in 5 min"), notes)
        engine.onWifi(WifiReading.NotOnWifi)
        assertEquals(1, notes.size)
        engine.onWifi(WifiReading.Connected("HomeNet"))
        assertEquals(listOf("Left home Wi-Fi · away in 5 min", "Back before the wait ended"), notes)
    }

    @Test fun `a countdown that runs out is not noted as cancelled`() = runBlocking {
        engine.load()
        engine.onWifi(WifiReading.Connected("HomeNet"))
        engine.onWifi(WifiReading.NotOnWifi)
        now = now.plusSeconds(300); engine.tick()
        assertEquals(listOf("Left home Wi-Fi · away in 5 min"), notes)
        assertEquals(Presence.Away, engine.current)
    }

    @Test fun `leaving the home area is noted too`() = runBlocking {
        store.config = PresenceConfig(enabled = true, wifiEnabled = false, areaEnabled = true, homeLat = 40.0, homeLon = -75.0, awayAfterMin = 2)
        engine.load()
        engine.onLocation(LocationFix(40.0, -75.0, 20f, now))
        engine.onLocation(LocationFix(40.05, -75.0, 20f, now))
        assertEquals(listOf("Left home area · away in 2 min"), notes)
    }
}
