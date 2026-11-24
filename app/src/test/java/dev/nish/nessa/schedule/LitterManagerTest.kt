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

    private fun robot(entries: List<SleepEntry>, sleeping: Boolean = false, array: Boolean = false): MockResponse {
        val body = """{"serial":"LRE-TEST-0001","type":"LRE","timezone":"America/New_York","state":{"isOnline":true,"powerStatus":"On","isSleeping":$sleeping},"sleepSchedules":${WhiskerJson.encodeToString(ListSerializer(SleepEntry.serializer()), entries)}}"""
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
}
