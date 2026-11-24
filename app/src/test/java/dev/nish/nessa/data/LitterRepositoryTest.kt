package dev.nish.nessa.data

import dev.nish.nessa.Fixtures
import dev.nish.nessa.whisker.CognitoAuth
import dev.nish.nessa.whisker.FakeSecretStore
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class LitterRepositoryTest {
    private lateinit var server: MockWebServer
    private val secrets = FakeSecretStore(Secrets("cat@example.com", "pw", "r1"))
    private val choice = FakeRobotChoiceStore()
    private var now: Instant = Instant.parse("2026-11-24T03:00:00Z") // still Nov 23 in the fixture's timezone
    private lateinit var repo: LitterRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        repo = newRepo()
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun newRepo(): LitterRepository {
        val http = OkHttpClient()
        val session = WhiskerSession(CognitoAuth(http, server.url("/").toString(), "c"), secrets)
        val api = WhiskerApi(http, server.url("/").toString(), "nessa/test", session::invalidate, session::idToken)
        return LitterRepository(api, session, choice) { now }
    }

    private fun tokens() = MockResponse().setBody("""{"AuthenticationResult":{"IdToken":"id1","RefreshToken":"r1","ExpiresIn":3600}}""")
    private fun robots() = MockResponse().setBody(Fixtures.text("robots.json"))
    private fun activities() = MockResponse().setBody(Fixtures.text("activities.json"))

    @Test fun `starts in Loading`() = assertEquals(LitterState.Loading, repo.state.value)

    @Test fun `refresh publishes Ready with today's visits and the last visit`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        val ready = repo.state.value as LitterState.Ready
        assertEquals("LRE-TEST-0001", ready.robot.serial)
        assertEquals(1, ready.todayVisits)
        assertEquals(930.0, ready.lastVisit?.petWeight)
        assertFalse(ready.stale)
        assertNull(ready.problem)
        assertEquals(now, ready.fetchedAt)
    }

    @Test fun `visits from an earlier day are not counted as today`() = runBlocking {
        now = Instant.parse("2026-11-25T18:00:00Z")
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        val ready = repo.state.value as LitterState.Ready
        assertEquals(0, ready.todayVisits)
        assertEquals("PET_VISIT", ready.lastVisit?.type)
    }

    @Test fun `empty robot list publishes NoRobot`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(MockResponse().setBody("[]"))
        repo.refresh()
        assertEquals(LitterState.NoRobot, repo.state.value)
    }

    @Test fun `two robots picks the EVO`() = runBlocking {
        server.enqueue(tokens())
        server.enqueue(MockResponse().setBody("""[{"serial":"A-1","type":"LR5"},{"serial":"B-2","type":"LRE"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        repo.refresh()
        assertEquals("B-2", (repo.state.value as LitterState.Ready).robot.serial)
        server.takeRequest(); server.takeRequest()
        assertEquals("/robots/B-2/activities?limit=50&offset=0", server.takeRequest().path)
    }

    @Test fun `the owner's saved robot choice is used`() = runBlocking {
        choice.saved = "A-1"
        server.enqueue(tokens())
        server.enqueue(MockResponse().setBody("""[{"serial":"A-1","type":"LR5"},{"serial":"B-2","type":"LRE"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        repo.refresh()
        assertEquals("A-1", (repo.state.value as LitterState.Ready).robot.serial)
    }

    @Test fun `refresh failure after success keeps the data and marks it stale`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        server.enqueue(MockResponse().setResponseCode(500))
        repo.refresh()
        val ready = repo.state.value as LitterState.Ready
        assertTrue(ready.stale)
        assertEquals("Whisker had a problem (code 500)", ready.problem)
        assertEquals("LRE-TEST-0001", ready.robot.serial)
    }

    @Test fun `a later successful refresh clears stale`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        server.enqueue(MockResponse().setResponseCode(500))
        repo.refresh()
        server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        assertFalse((repo.state.value as LitterState.Ready).stale)
    }

    @Test fun `refresh failure with no earlier data publishes Unavailable`() = runBlocking {
        server.shutdown()
        repo.refresh()
        assertEquals(LitterState.Unavailable("Couldn't reach Whisker"), repo.state.value)
    }

    @Test fun `activities failing still shows the robot`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(MockResponse().setResponseCode(500))
        repo.refresh()
        val ready = repo.state.value as LitterState.Ready
        assertEquals(0, ready.todayVisits)
        assertNull(ready.lastVisit)
        assertFalse(ready.stale)
    }

    @Test fun `no saved sign-in publishes NotConnected without calling Whisker`() = runBlocking {
        secrets.saved = null
        repo = newRepo()
        repo.refresh()
        assertEquals(LitterState.NotConnected, repo.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun `rejected sign-in publishes NotConnected`() = runBlocking {
        val rejected = MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException"}""")
        server.enqueue(rejected); server.enqueue(rejected.clone())
        repo.refresh()
        assertEquals(LitterState.NotConnected, repo.state.value)
    }

    @Test fun `Whisker refusing a token it issued is retried with a fresh one and stays connected`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        assertTrue(repo.state.value is LitterState.Ready)
    }

    @Test fun `Whisker refusing even a fresh token shows a Whisker problem not a sign-in card`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(tokens()); server.enqueue(MockResponse().setResponseCode(403))
        repo.refresh()
        assertEquals(LitterState.Unavailable("Whisker had a problem (code 403)"), repo.state.value)
    }

    private fun page(n: Int, type: String = "CYCLE_COMPLETED") =
        MockResponse().setBody((1..n).joinToString(",", "[", "]") { """{"type":"$type","timestamp":"2026-11-20T00:00:00Z"}""" })

    @Test fun `refreshHistory loads every page until a short one`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        repeat(3) { server.takeRequest() }
        server.enqueue(page(100)); server.enqueue(page(100)); server.enqueue(page(7))
        assertNull(repo.refreshHistory())
        assertEquals(207, repo.history.value.size)
        assertEquals("/robots/LRE-TEST-0001/activities?limit=100&offset=0", server.takeRequest().path)
        assertEquals("/robots/LRE-TEST-0001/activities?limit=100&offset=100", server.takeRequest().path)
        assertEquals("/robots/LRE-TEST-0001/activities?limit=100&offset=200", server.takeRequest().path)
    }

    @Test fun `refreshHistory stops at 2000 entries even if Whisker keeps sending full pages`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        repeat(25) { server.enqueue(page(100)) }
        assertNull(repo.refreshHistory())
        assertEquals(2000, repo.history.value.size)
    }

    @Test fun `refreshHistory failure keeps the earlier history and returns the problem`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        server.enqueue(page(5))
        repo.refreshHistory()
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("Whisker had a problem (code 500)", repo.refreshHistory())
        assertEquals(5, repo.history.value.size)
    }

    @Test fun `refreshHistory finds the robot itself when status was never loaded`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities()); server.enqueue(page(3))
        assertNull(repo.refreshHistory())
        assertEquals(3, repo.history.value.size)
    }

    @Test fun `refreshHistory when not signed in returns a reason and calls nothing`() = runBlocking {
        secrets.saved = null
        repo = newRepo()
        assertEquals("Connect your Whisker account first", repo.refreshHistory())
        assertEquals(0, server.requestCount)
    }

    @Test fun `cleanNow posts CLEAN_CYCLE for the chosen robot then refreshes`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        repeat(3) { server.takeRequest() }
        server.enqueue(MockResponse().setBody("")); server.enqueue(robots()); server.enqueue(activities())
        assertNull(repo.cleanNow())
        val post = server.takeRequest()
        assertEquals("/robots/LRE-TEST-0001/commands", post.path)
        assertEquals("""{"type":"CLEAN_CYCLE"}""", post.body.readUtf8())
        assertEquals("/robots", server.takeRequest().path)
    }

    @Test fun `cleanNow failure returns the problem and keeps the data`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots()); server.enqueue(activities())
        repo.refresh()
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("Whisker had a problem (code 500)", repo.cleanNow())
        assertTrue(repo.state.value is LitterState.Ready)
    }

    @Test fun `cleanNow before any robot is loaded does nothing`() = runBlocking {
        assertEquals("No litter box to clean yet", repo.cleanNow())
        assertEquals(0, server.requestCount)
    }
}
