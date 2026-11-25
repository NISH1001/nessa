package dev.nish.nessa.history

import dev.nish.nessa.data.FakeRobotChoiceStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import dev.nish.nessa.whisker.WhiskerApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class HistorySyncTest {
    private lateinit var server: MockWebServer
    private val store = FakeHistoryStore()
    private val progress = FakeSyncProgress()
    private val now = Instant.parse("2026-01-10T12:00:00Z")
    private lateinit var sync: HistorySync

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = WhiskerApi(OkHttpClient(), server.url("/").toString(), "t") { "tok" }
        sync = HistorySync(api, FakeRobotChoiceStore(), store, progress) { now }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private val robots = MockResponse().setBody(
        """[{"serial":"R-1","type":"LRE","state":{"dfiLevelPercent":40,"litterLevelPercent":90.0,"globeLitterLevelIndicator":"Optimal","isSleeping":false,"powerStatus":"On","odometerCleanCycles":12}}]""",
    )

    /** A page of [n] visits with ids starting at [from], newest first like Whisker. */
    private fun page(from: Int, n: Int) = MockResponse().setBody(
        (from until from + n).joinToString(",", "[", "]") { i ->
            """{"type":"PET_VISIT","timestamp":"${Instant.parse("2026-01-09T00:00:00Z").minusSeconds(i * 60L)}","eventId":"e$i","duration":60,"petWeight":900.0}"""
        },
    )

    @Test fun `first sync stores every page`() = runBlocking {
        server.enqueue(robots); server.enqueue(page(0, 100)); server.enqueue(page(100, 100)); server.enqueue(page(200, 30))
        assertNull(sync.sync())
        assertEquals(230, store.activities.value.size)
    }

    @Test fun `resync stores nothing twice`() = runBlocking {
        server.enqueue(robots); server.enqueue(page(0, 50))
        sync.sync()
        server.enqueue(robots); server.enqueue(page(0, 50))
        sync.sync()
        assertEquals(50, store.activities.value.size)
        assertEquals(store.activities.value.map { it.id }.toSet().size, 50)
    }

    @Test fun `stores a robot reading each sync`() = runBlocking {
        server.enqueue(robots); server.enqueue(page(0, 1))
        sync.sync()
        assertEquals(listOf(RobotReading(now, 40, 90.0, "Optimal", false, true, 12)), store.readings.value)
    }

    @Test fun `failed sync keeps stored rows and returns the problem`() = runBlocking {
        server.enqueue(robots); server.enqueue(page(0, 5))
        sync.sync()
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("Whisker had a problem (code 500)", sync.sync())
        assertEquals(5, store.activities.value.size)
    }

    @Test fun `stops at 2000 rows`() = runBlocking {
        server.enqueue(robots); repeat(25) { server.enqueue(page(it * 100, 100)) }
        sync.sync()
        assertEquals(2000, store.activities.value.size)
    }

    @Test fun `no robot on the account is a problem, not a crash`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals("No Litter-Robot found on this account", sync.sync())
    }

    @Test fun `a first sync cut off midway carries on with older pages next time`() = runBlocking {
        server.enqueue(robots); server.enqueue(page(0, 100)); server.enqueue(page(100, 100)); server.enqueue(MockResponse().setResponseCode(500))
        sync.sync()
        assertEquals(200, store.activities.value.size)
        // Next time: page 1 and 2 are already stored, but older history is still missing.
        server.enqueue(robots); server.enqueue(page(0, 100)); server.enqueue(page(100, 100)); server.enqueue(page(200, 40))
        assertNull(sync.sync())
        assertEquals(240, store.activities.value.size)
        assertEquals(true, progress.done)
    }

    @Test fun `once caught up, a sync stops at the first page with nothing new`() = runBlocking {
        progress.done = true
        server.enqueue(robots); server.enqueue(page(0, 100)); server.enqueue(page(100, 100))
        sync.sync()
        server.enqueue(robots); server.enqueue(page(0, 100))
        sync.sync()
        assertEquals(5, server.requestCount)
    }
}

class FakeSyncProgress(var done: Boolean = false) : SyncProgress {
    override suspend fun backfillDone() = done
    override suspend fun markBackfillDone() { done = true }
}
