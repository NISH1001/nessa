package dev.nish.nessa.schedule

import dev.nish.nessa.data.FakeRobotChoiceStore
import dev.nish.nessa.data.Secrets
import dev.nish.nessa.whisker.CognitoAuth
import dev.nish.nessa.whisker.FakeSecretStore
import dev.nish.nessa.whisker.SleepEntry
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerJson
import dev.nish.nessa.whisker.WhiskerSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class FakeManagerStore(var saved: ManagerState = ManagerState()) : ManagerStore {
    val saves = mutableListOf<ManagerState>()
    override suspend fun load() = saved
    override suspend fun save(s: ManagerState) { saved = s; saves += s }
}

class LitterManagerTest {
    private lateinit var server: MockWebServer
    private val store = FakeManagerStore()
    // Monday 2026-11-23, 10:00 in the test robot's timezone (America/New_York): inside Work.
    private var now: Instant = Instant.parse("2026-11-23T15:00:00Z")
    private lateinit var manager: LitterManager
    private val notes = mutableListOf<String>()
    private val whiskerDefault = (0..6).map { SleepEntry(it, false, 480, 480) }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient()
        val session = WhiskerSession(CognitoAuth(http, server.url("/").toString(), "c"), FakeSecretStore(Secrets("cat@example.com", "pw", "r1")))
        val api = WhiskerApi(http, server.url("/").toString(), "nessa/test", session::invalidate, session::idToken)
        manager = LitterManager(api, FakeRobotChoiceStore(), store, now = { now }, pause = { }, onNote = { notes += it })
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun tokens() = MockResponse().setBody("""{"AuthenticationResult":{"IdToken":"id1","RefreshToken":"r1","ExpiresIn":3600}}""")

    private fun robot(entries: List<SleepEntry>, sleeping: Boolean = false, array: Boolean = false, power: String = "On"): MockResponse {
        val body = """{"serial":"LRE-TEST-0001","type":"LRE","timezone":"America/New_York","state":{"isOnline":true,"powerStatus":"$power","isSleeping":$sleeping},"sleepSchedules":${WhiskerJson.encodeToString(ListSerializer(SleepEntry.serializer()), entries)}}"""
        return MockResponse().setBody(if (array) "[$body]" else body)
    }

    private fun desiredNow() = nativeWeek(WeekSchedule.default(), Presence.Unknown, 1, 600)

    private fun requests(): List<RecordedRequest> = (0 until server.requestCount).map { server.takeRequest() }

    @Test fun `does nothing while Nessa is not managing the schedule`() = runBlocking {
        assertEquals(ApplyResult.Off, manager.apply())
        assertEquals(0, server.requestCount)
    }

    @Test fun `turning management on writes the schedule the rule wants and confirms it`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(robot(desiredNow(), sleeping = true))
        val result = manager.setEnabled(true)
        assertEquals(ApplyResult.Applied, result)
        val reqs = requests()
        val patch = reqs.single { it.method == "PATCH" }
        assertEquals("/robots/LRE-TEST-0001", patch.path)
        val sent = WhiskerJson.decodeFromJsonElement(
            ListSerializer(SleepEntry.serializer()),
            WhiskerJson.parseToJsonElement(patch.body.readUtf8()).jsonObject["sleepSchedules"]!!,
        )
        assertEquals(desiredNow(), sent)
        assertTrue(manager.status.value.decision!!.rest)
        assertNull(manager.status.value.problem)
    }

    @Test fun `the robot's own schedule is saved as original before the first write and never replaced`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(desiredNow(), sleeping = true))
        manager.setEnabled(true)
        assertEquals(whiskerDefault, store.saved.original)
        // the save holding the original happened before the PATCH reached the server
        val firstWithOriginal = store.saves.indexOfFirst { it.original != null }
        assertTrue(firstWithOriginal >= 0)

        now = now.plusSeconds(8 * 3600) // 18:00, outside every window
        val later = nativeWeek(WeekSchedule.default(), Presence.Unknown, 1, 18 * 60)
        server.enqueue(robot(desiredNow(), array = true)); server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(later))
        assertEquals(ApplyResult.Applied, manager.apply())
        assertEquals(whiskerDefault, store.saved.original)
        assertEquals(desiredNow(), store.saved.checkpoints.last().entries)
    }

    @Test fun `no write when the robot already has the right schedule`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        assertEquals(ApplyResult.AlreadyRight, manager.apply())
        assertTrue(requests().none { it.method == "PATCH" })
        assertTrue(store.saved.checkpoints.isEmpty())
    }

    @Test fun `waits for Whisker to show the new schedule before calling it applied`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true)); server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(robot(whiskerDefault)); server.enqueue(robot(whiskerDefault)); server.enqueue(robot(desiredNow(), sleeping = true))
        assertEquals(ApplyResult.Applied, manager.apply())
    }

    @Test fun `reports a problem when the robot never takes the schedule`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true)); server.enqueue(MockResponse().setBody("{}"))
        repeat(12) { server.enqueue(robot(whiskerDefault)) }
        assertEquals(ApplyResult.Failed, manager.apply())
        assertEquals("The robot didn't take the new schedule", manager.status.value.problem)
    }

    @Test fun `reports a problem when the schedule is right but the robot is not resting`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true)); server.enqueue(MockResponse().setBody("{}"))
        repeat(12) { server.enqueue(robot(desiredNow(), sleeping = false)) }
        assertEquals(ApplyResult.Failed, manager.apply())
        assertEquals("The robot should be resting but says it isn't", manager.status.value.problem)
    }

    @Test fun `a Whisker outage is reported and nothing is half done`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.shutdown()
        assertEquals(ApplyResult.Failed, manager.apply())
        assertEquals("Couldn't reach Whisker", manager.status.value.problem)
        assertTrue(store.saved.checkpoints.isEmpty())
    }

    @Test fun `saying I'm away rests every day completely`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        val away = (0..6).map { SleepEntry(it, true, 0, 0) }
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(away, sleeping = true))
        assertEquals(ApplyResult.Applied, manager.setOverride(Override.Away))
        assertEquals(Reason.Away, manager.status.value.decision!!.reason)
        assertEquals(Override.Away, store.saved.override)
    }

    @Test fun `saying I'm home during work lets the robot run`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        val home = nativeWeek(WeekSchedule.default(), Presence.Home, 1, 600)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(home, sleeping = false))
        assertEquals(ApplyResult.Applied, manager.setOverride(Override.Home))
        assertFalse(manager.status.value.decision!!.rest)
    }

    @Test fun `editing the schedule saves it and applies it`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        val edited = WeekSchedule.default().withDay(1, emptyList())
        val want = nativeWeek(edited, Presence.Unknown, 1, 600)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(want, sleeping = false))
        assertEquals(ApplyResult.Applied, manager.setSchedule(edited))
        assertEquals(edited, store.saved.schedule)
    }

    @Test fun `editing the schedule while management is off only saves it`() = runBlocking {
        val edited = WeekSchedule.default().withDay(1, emptyList())
        assertEquals(ApplyResult.Off, manager.setSchedule(edited))
        assertEquals(edited, store.saved.schedule)
        assertEquals(0, server.requestCount)
    }

    @Test fun `turning management off puts the original schedule back`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(whiskerDefault))
        assertEquals(ApplyResult.Applied, manager.setEnabled(false))
        assertFalse(store.saved.enabled)
        val patch = requests().single { it.method == "PATCH" }
        assertTrue(patch.body.readUtf8().contains("\"isEnabled\":false"))
    }

    @Test fun `at most 30 checkpoints are kept`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, checkpoints = List(30) { Checkpoint(it.toLong(), whiskerDefault) })
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(desiredNow(), sleeping = true))
        manager.apply()
        assertEquals(30, store.saved.checkpoints.size)
        assertEquals(now.epochSecond, store.saved.checkpoints.last().atEpochSec)
    }

    @Test fun `next check is the next rule change or midnight whichever is sooner`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        manager.apply()
        // 10:00 Monday: Work ends 17:00 -> 420 minutes
        assertEquals(now.plusSeconds(420 * 60), manager.status.value.nextCheckAt)
        now = Instant.parse("2026-11-24T03:00:00Z") // Monday 22:00, in Bedtime until 7:00 Tue; midnight comes first
        server.enqueue(robot(nativeWeek(WeekSchedule.default(), Presence.Unknown, 1, 22 * 60), sleeping = true, array = true))
        manager.apply()
        assertEquals(now.plusSeconds(120 * 60), manager.status.value.nextCheckAt)
    }

    @Test fun `manual choices are noted so the log can tell them from automatic detection`() = runBlocking {
        manager.setOverride(Override.Away)
        manager.setOverride(Override.Home)
        manager.setOverride(Override.Auto)
        assertEquals(listOf("You set Away", "You set Home", "You set Auto"), notes)
    }

    @Test fun `choosing what is already chosen is not noted again`() = runBlocking {
        manager.setOverride(Override.Away)
        manager.setOverride(Override.Away)
        assertEquals(listOf("You set Away"), notes)
    }

    @Test fun `turning management on or off and editing the schedule are noted`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), sleeping = true, array = true))
        manager.setEnabled(true)
        manager.setSchedule(WeekSchedule.default().withDay(6, emptyList()))
        manager.setEnabled(false)
        assertEquals(listOf("Sleep management on", "Schedule changed", "Sleep management off"), notes)
    }

    // --- Sleep now -----------------------------------------------------------------------

    private val allDay = (0..6).map { SleepEntry(it, true, 0, 0) }

    @Test fun `sleep now writes a whole-day sleep and overrides home`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, override = Override.Home)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(allDay, sleeping = true))
        assertEquals(ApplyResult.Applied, manager.sleepNow(hours = 12))
        val d = manager.status.value.decision!!
        assertTrue(d.rest)
        assertEquals(Reason.SleepNow, d.reason)
        assertEquals("until 10:00 PM", d.windowName) // 10:00 + 12 h, robot's time
        assertEquals(now.plusSeconds(12 * 3600), manager.status.value.nextCheckAt)
        assertEquals(listOf("Sleep now for 12 h"), notes)
    }

    @Test fun `sleep now until woken has no end`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(allDay, sleeping = true, array = true))
        assertEquals(ApplyResult.AlreadyRight, manager.sleepNow(hours = null))
        assertEquals("until you wake it", manager.status.value.decision!!.windowName)
        assertNull(manager.state.value.sleepNow!!.untilEpochSec)
        assertEquals(listOf("Sleep now until you wake it"), notes)
    }

    @Test fun `when sleep now runs out the normal rule comes back`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, sleepNow = SleepNow(untilEpochSec = now.epochSecond - 60))
        server.enqueue(tokens()); server.enqueue(robot(allDay, sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(desiredNow(), sleeping = true))
        assertEquals(ApplyResult.Applied, manager.apply())
        assertNull(manager.state.value.sleepNow)
        assertEquals(Reason.Window, manager.status.value.decision!!.reason)
        assertEquals(listOf("Sleep now ended"), notes)
    }

    @Test fun `waking early ends sleep now and applies the rule`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, override = Override.Home, sleepNow = SleepNow(untilEpochSec = null))
        val home = nativeWeek(WeekSchedule.default(), Presence.Home, 1, 600)
        server.enqueue(tokens()); server.enqueue(robot(allDay, sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(home, sleeping = false))
        assertEquals(ApplyResult.Applied, manager.endSleepNow())
        assertNull(manager.state.value.sleepNow)
        assertFalse(manager.status.value.decision!!.rest)
        assertEquals(listOf("You ended Sleep now"), notes)
    }

    @Test fun `sleep now works with sleep management off and puts the old schedule back after`() = runBlocking {
        store.saved = ManagerState(enabled = false)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(allDay, sleeping = true))
        assertEquals(ApplyResult.Applied, manager.sleepNow(hours = 2))
        assertEquals(whiskerDefault, manager.state.value.sleepNow!!.restoreTo)

        server.enqueue(robot(allDay, sleeping = true, array = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(whiskerDefault))
        assertEquals(ApplyResult.Applied, manager.endSleepNow())
        val patch = requests().last { it.method == "PATCH" }
        assertTrue(patch.body.readUtf8().contains("\"sleepTime\":480"))
        assertNull(manager.state.value.sleepNow)
        assertFalse(store.saved.enabled)
    }

    @Test fun `checks keep running while sleep now is on even with management off`() {
        assertTrue(ManagerState(enabled = false, sleepNow = SleepNow(null)).needsChecks)
        assertFalse(ManagerState(enabled = false).needsChecks)
        assertTrue(ManagerState(enabled = true).needsChecks)
    }

    // --- Power off ------------------------------------------------------------------------

    private fun commandsSent() = requests().filter { it.path!!.endsWith("/commands") }.map { it.body.readUtf8() }

    @Test fun `power off sends the command, confirms, and holds until turned on`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true))
        server.enqueue(MockResponse().setBody("")); server.enqueue(robot(desiredNow(), power = "Off"))
        assertEquals(ApplyResult.Applied, manager.powerOff())
        assertEquals(listOf("""{"type":"POWER_OFF"}"""), commandsSent())
        val d = manager.status.value.decision!!
        assertEquals(Reason.PowerOff, d.reason)
        assertNull(manager.status.value.nextCheckAt)
        assertEquals(listOf("You turned power off"), notes)
    }

    @Test fun `while powered off nothing else is written, and no repeat command is sent`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, powerOff = PowerOff)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true, power = "Off"))
        assertEquals(ApplyResult.AlreadyRight, manager.apply())
        assertTrue(requests().none { it.method == "PATCH" || it.path!!.endsWith("/commands") })
    }

    @Test fun `power on early`() = runBlocking {
        store.saved = ManagerState(enabled = false, powerOff = PowerOff)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true, power = "Off"))
        server.enqueue(MockResponse().setBody("")); server.enqueue(robot(whiskerDefault, power = "On"))
        assertEquals(ApplyResult.Applied, manager.powerOn())
        assertEquals(listOf("""{"type":"POWER_ON"}"""), commandsSent())
        assertNull(manager.state.value.powerOff)
        assertEquals(listOf("You turned power on"), notes)
    }

    @Test fun `a robot that never reports off is a problem, and the hold is kept`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true))
        server.enqueue(MockResponse().setBody("")); repeat(12) { server.enqueue(robot(desiredNow(), power = "On")) }
        assertEquals(ApplyResult.Failed, manager.powerOff())
        assertEquals("The robot didn't turn off", manager.status.value.problem)
        assertTrue(manager.state.value.powerOff != null)
    }

    @Test fun `power off needs checks and outranks sleep now`() {
        assertTrue(ManagerState(enabled = false, powerOff = PowerOff).needsChecks)
    }

    // --- power: the hold must never fight the owner -------------------------------------------

    @Test fun `power on clears the hold even when the robot is slow to say it is on`() = runBlocking {
        store.saved = ManagerState(enabled = false, powerOff = PowerOff)
        server.enqueue(tokens()); server.enqueue(robot(whiskerDefault, array = true, power = "Off"))
        server.enqueue(MockResponse().setBody("")); repeat(40) { server.enqueue(robot(whiskerDefault, power = "Off")) }
        manager.powerOn()
        assertNull(manager.state.value.powerOff)
        assertNull(store.saved.powerOff)
        assertEquals(listOf("""{"type":"POWER_ON"}"""), commandsSent())
    }

    @Test fun `a robot found on while Nessa thinks it is off clears the hold and is never turned off again`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, powerOff = PowerOff)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true, power = "On", sleeping = true))
        server.enqueue(robot(desiredNow(), array = true, power = "On", sleeping = true))
        assertEquals(ApplyResult.AlreadyRight, manager.apply())
        assertNull(manager.state.value.powerOff)
        assertTrue(commandsSent().isEmpty())
        assertEquals(listOf("Power was turned on outside Nessa"), notes)
    }

    @Test fun `a problem from an earlier attempt is cleared once things are right`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        server.enqueue(MockResponse().setResponseCode(500))
        manager.apply()
        assertEquals("Whisker had a problem (code 500)", manager.status.value.problem)
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true, sleeping = true))
        manager.apply()
        assertNull(manager.status.value.problem)
    }

    // --- Wake up ------------------------------------------------------------------------------

    private fun awakeWeek() = desiredNow().map { if (it.dayOfWeek == 1) SleepEntry(1, false, 480, 480) else it }

    @Test fun `wake up keeps the robot active until the sleep it interrupted would have ended`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault) // Monday 10:00, asleep for Work until 5 PM
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true, sleeping = true))
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(robot(awakeWeek(), sleeping = false))
        assertEquals(ApplyResult.Applied, manager.wakeNow())
        val d = manager.status.value.decision!!
        assertFalse(d.rest)
        assertEquals(Reason.WakeNow, d.reason)
        assertEquals("until 5:00 PM", d.windowName)
        assertEquals(now.plusSeconds(7 * 3600), manager.status.value.nextCheckAt)
        assertEquals(listOf("You woke it until 5:00 PM"), notes)
    }

    @Test fun `wake up ends by itself and the schedule takes over`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, wake = WakeHold(now.epochSecond - 60, "Unknown"))
        server.enqueue(tokens()); server.enqueue(robot(desiredNow(), array = true, sleeping = true))
        assertEquals(ApplyResult.AlreadyRight, manager.apply())
        assertNull(manager.state.value.wake)
        assertTrue(manager.status.value.decision!!.rest)
    }

    @Test fun `leaving or coming home ends a wake up`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, override = Override.Away, wake = WakeHold(now.epochSecond + 3600, "Unknown"))
        val away = (0..6).map { SleepEntry(it, true, 0, 0) }
        server.enqueue(tokens()); server.enqueue(robot(away, array = true, sleeping = true))
        manager.apply()
        assertNull(manager.state.value.wake)
        assertEquals(Reason.Away, manager.status.value.decision!!.reason)
    }

    @Test fun `wake up does nothing when the robot is not meant to be asleep`() = runBlocking {
        now = now.plusSeconds(8 * 3600) // Monday 18:00
        store.saved = ManagerState(enabled = true, original = whiskerDefault)
        val evening = nativeWeek(WeekSchedule.default(), Presence.Unknown, 1, 18 * 60)
        server.enqueue(tokens()); server.enqueue(robot(evening, array = true))
        manager.wakeNow()
        assertNull(manager.state.value.wake)
        assertTrue(notes.isEmpty())
    }

    @Test fun `sleep now replaces a wake up`() = runBlocking {
        store.saved = ManagerState(enabled = true, original = whiskerDefault, wake = WakeHold(now.epochSecond + 3600, "Unknown"))
        val allDay = (0..6).map { SleepEntry(it, true, 0, 0) }
        server.enqueue(tokens()); server.enqueue(robot(allDay, array = true, sleeping = true))
        manager.sleepNow(2)
        assertNull(manager.state.value.wake)
    }
}
