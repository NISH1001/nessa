package dev.nish.nessa.whisker

import dev.nish.nessa.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WhiskerApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WhiskerApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = WhiskerApi(OkHttpClient(), server.url("/").toString(), "nessa/test") { "tok-1" }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `robots sends GET robots with bearer token and user agent`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("robots.json")))
        val robots = api.robots()
        assertEquals(1, robots.size)
        assertEquals("LRE-TEST-0001", robots[0].serial)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/robots", req.path)
        assertEquals("Bearer tok-1", req.getHeader("authorization"))
        assertEquals("nessa/test", req.getHeader("User-Agent"))
    }

    @Test fun `robot sends GET robots slash serial`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("robot.json")))
        assertEquals("Litter-Robot EVO", api.robot("LRE-TEST-0001").name)
        assertEquals("/robots/LRE-TEST-0001", server.takeRequest().path)
    }

    @Test fun `patchRobot sends PATCH with the exact JSON body`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        api.patchRobot("LRE-TEST-0001", buildJsonObject { put("name", "Box") })
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/robots/LRE-TEST-0001", req.path)
        assertEquals("""{"name":"Box"}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun `command posts the command type`() = runTest {
        server.enqueue(MockResponse().setBody(""))
        api.command("LRE-TEST-0001", RobotCommand.CLEAN_CYCLE)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/robots/LRE-TEST-0001/commands", req.path)
        assertEquals("""{"type":"CLEAN_CYCLE"}""", req.body.readUtf8())
    }

    @Test fun `activities sends limit and offset`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("activities.json")))
        val acts = api.activities("LRE-TEST-0001", limit = 20, offset = 40)
        assertEquals(3, acts.size)
        assertEquals("/robots/LRE-TEST-0001/activities?limit=20&offset=40", server.takeRequest().path)
    }

    @Test fun `401 drops the token and retries once with a fresh one`() = runTest {
        var current = "stale"
        var invalidated = 0
        val retrying = WhiskerApi(OkHttpClient(), server.url("/").toString(), "nessa/test", { invalidated++; current = "fresh" }) { current }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(Fixtures.text("robots.json")))
        assertEquals(1, retrying.robots().size)
        assertEquals(1, invalidated)
        assertEquals("Bearer stale", server.takeRequest().getHeader("authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("authorization"))
    }

    @Test fun `403 also retries once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(0, api.robots().size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `still refused after a fresh token is a Whisker problem not a sign-in problem`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        val e = expect<WhiskerException.Server> { api.robots() }
        assertEquals(403, e.code)
        assertEquals(2, server.requestCount)
    }

    @Test fun `500 throws Server with the code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(500, expect<WhiskerException.Server> { api.robots() }.code)
    }

    @Test fun `body that is not the expected JSON throws Server`() = runTest {
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        expect<WhiskerException.Server> { api.robots() }
    }

    @Test fun `dropped connection throws Network`() = runTest {
        server.shutdown()
        expect<WhiskerException.Network> { api.robots() }
    }

    @Test fun `token provider failure passes through unchanged`() = runTest {
        val failing = WhiskerApi(OkHttpClient(), server.url("/").toString(), "nessa/test", idToken = { throw WhiskerException.NeedsReconnect() })
        expect<WhiskerException.NeedsReconnect> { failing.robots() }
        assertEquals(0, server.requestCount)
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw t
        }
        fail("Expected ${T::class.simpleName}")
        throw IllegalStateException()
    }
}
