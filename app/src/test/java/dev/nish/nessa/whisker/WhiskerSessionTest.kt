package dev.nish.nessa.whisker

import dev.nish.nessa.data.SecretStore
import dev.nish.nessa.data.Secrets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FakeSecretStore(var saved: Secrets? = null) : SecretStore {
    override suspend fun load() = saved
    override suspend fun save(s: Secrets) { saved = s }
    override suspend fun clear() { saved = null }
}

class WhiskerSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeSecretStore
    private lateinit var session: WhiskerSession
    private var now = 1_000_000L

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeSecretStore()
        session = newSession()
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun newSession() =
        WhiskerSession(CognitoAuth(OkHttpClient(), server.url("/").toString(), "c") { now }, store) { now }

    private fun ok(id: String, refresh: String? = null) = MockResponse().setBody(
        """{"AuthenticationResult":{"IdToken":"$id",${refresh?.let { "\"RefreshToken\":\"$it\"," } ?: ""}"ExpiresIn":3600}}""",
    )

    private fun rejected() = MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException"}""")

    private fun flowOf(index: Int): String {
        var req = server.takeRequest()
        repeat(index) { req = server.takeRequest() }
        return Regex("\"AuthFlow\":\"([A-Z_]+)\"").find(req.body.readUtf8())!!.groupValues[1]
    }

    @Test fun `signIn stores email password and refresh token and sets Connected`() = runTest {
        server.enqueue(ok("id1", "r1"))
        session.signIn("cat@example.com", "pw")
        assertEquals(Secrets("cat@example.com", "pw", "r1"), store.saved)
        assertEquals(ConnectionStatus.Connected, session.status.value)
    }

    @Test fun `signIn with bad password stores nothing and stays Disconnected`() = runTest {
        server.enqueue(rejected())
        expect<WhiskerException.BadCredentials> { session.signIn("cat@example.com", "wrong") }
        assertNull(store.saved)
        assertEquals(ConnectionStatus.Disconnected, session.status.value)
    }

    @Test fun `idToken reuses the cached token within its lifetime`() = runTest {
        server.enqueue(ok("id1", "r1"))
        session.signIn("cat@example.com", "pw")
        now += 3000
        assertEquals("id1", session.idToken())
        assertEquals(1, server.requestCount)
    }

    @Test fun `idToken refreshes when under 60 seconds remain`() = runTest {
        server.enqueue(ok("id1", "r1"))
        server.enqueue(ok("id2"))
        session.signIn("cat@example.com", "pw")
        now += 3600 - 59
        assertEquals("id2", session.idToken())
        assertEquals("REFRESH_TOKEN_AUTH", flowOf(1))
        assertEquals("r1", store.saved?.refreshToken)
    }

    @Test fun `rejected refresh falls back to password sign-in and stores the new refresh token`() = runTest {
        store.saved = Secrets("cat@example.com", "pw", "r-dead")
        session.restore()
        server.enqueue(rejected())
        server.enqueue(ok("id3", "r-new"))
        assertEquals("id3", session.idToken())
        assertEquals(Secrets("cat@example.com", "pw", "r-new"), store.saved)
        assertEquals(ConnectionStatus.Connected, session.status.value)
        assertEquals("USER_PASSWORD_AUTH", flowOf(1))
    }

    @Test fun `rejected refresh and rejected password sets NeedsReconnect and throws`() = runTest {
        store.saved = Secrets("cat@example.com", "old-pw", "r-dead")
        session.restore()
        server.enqueue(rejected())
        server.enqueue(rejected())
        expect<WhiskerException.NeedsReconnect> { session.idToken() }
        assertEquals(ConnectionStatus.NeedsReconnect, session.status.value)
    }

    @Test fun `network error during refresh keeps Connected and rethrows Network`() = runTest {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        session.restore()
        server.shutdown()
        expect<WhiskerException.Network> { session.idToken() }
        assertEquals(ConnectionStatus.Connected, session.status.value)
        assertEquals(Secrets("cat@example.com", "pw", "r1"), store.saved)
    }

    @Test fun `server error during refresh keeps Connected and does not try the password`() = runTest {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        session.restore()
        server.enqueue(MockResponse().setResponseCode(503))
        expect<WhiskerException.Server> { session.idToken() }
        assertEquals(ConnectionStatus.Connected, session.status.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun `two concurrent idToken calls make one refresh`() = runTest {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        session.restore()
        server.enqueue(ok("id9"))
        val tokens = listOf(async { session.idToken() }, async { session.idToken() }).awaitAll()
        assertEquals(listOf("id9", "id9"), tokens)
        assertEquals(1, server.requestCount)
    }

    @Test fun `restore with stored secrets sets Connected without any network call`() = runTest {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        session.restore()
        assertEquals(ConnectionStatus.Connected, session.status.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun `restore with nothing stored stays Disconnected and idToken asks to reconnect`() = runTest {
        session.restore()
        assertEquals(ConnectionStatus.Disconnected, session.status.value)
        expect<WhiskerException.NeedsReconnect> { session.idToken() }
        assertEquals(0, server.requestCount)
    }

    @Test fun `idToken works on a fresh session without calling restore first`() = runTest {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        server.enqueue(ok("id5"))
        assertEquals("id5", session.idToken())
        assertEquals(ConnectionStatus.Connected, session.status.value)
    }

    @Test fun `invalidate makes the next idToken refresh even though the token looked valid`() = runTest {
        server.enqueue(ok("id1", "r1"))
        server.enqueue(ok("id2"))
        session.signIn("cat@example.com", "pw")
        session.invalidate()
        assertEquals("id2", session.idToken())
        assertEquals("REFRESH_TOKEN_AUTH", flowOf(1))
    }

    @Test fun `signOut clears the store and sets Disconnected`() = runTest {
        server.enqueue(ok("id1", "r1"))
        session.signIn("cat@example.com", "pw")
        session.signOut()
        assertNull(store.saved)
        assertEquals(ConnectionStatus.Disconnected, session.status.value)
        expect<WhiskerException.NeedsReconnect> { session.idToken() }
    }

    @Test fun `signing in again after NeedsReconnect returns to Connected`() = runTest {
        store.saved = Secrets("cat@example.com", "old-pw", "r-dead")
        session.restore()
        server.enqueue(rejected()); server.enqueue(rejected())
        runCatching { session.idToken() }
        server.enqueue(ok("id7", "r7"))
        session.signIn("cat@example.com", "new-pw")
        assertEquals(ConnectionStatus.Connected, session.status.value)
        assertEquals("id7", session.idToken())
        assertTrue(store.saved == Secrets("cat@example.com", "new-pw", "r7"))
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
