package dev.nish.nessa.whisker

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CognitoAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: CognitoAuth
    private val now = 1_000_000L

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        auth = CognitoAuth(OkHttpClient(), server.url("/").toString(), "client-x") { now }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun ok(refresh: String? = "r1") = MockResponse().setBody(
        """{"AuthenticationResult":{"IdToken":"id1","AccessToken":"a1",${refresh?.let { "\"RefreshToken\":\"$it\"," } ?: ""}"ExpiresIn":3600}}""",
    )

    private fun cognitoError(type: String) =
        MockResponse().setResponseCode(400).setBody("""{"__type":"$type","message":"Incorrect username or password."}""")

    @Test fun `signIn sends USER_PASSWORD_AUTH with target header and returns tokens`() = runTest {
        server.enqueue(ok())
        val tokens = auth.signIn("cat@example.com", "s3cret-pw")
        assertEquals(Tokens("id1", "r1", now + 3600), tokens)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("AWSCognitoIdentityProviderService.InitiateAuth", req.getHeader("X-Amz-Target"))
        assertEquals("application/x-amz-json-1.1", req.getHeader("Content-Type"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("USER_PASSWORD_AUTH", body["AuthFlow"]!!.jsonPrimitive.content)
        assertEquals("client-x", body["ClientId"]!!.jsonPrimitive.content)
        val params = body["AuthParameters"]!!.jsonObject
        assertEquals("cat@example.com", params["USERNAME"]!!.jsonPrimitive.content)
        assertEquals("s3cret-pw", params["PASSWORD"]!!.jsonPrimitive.content)
    }

    @Test fun `signIn with NotAuthorizedException throws BadCredentials`() = runTest {
        server.enqueue(cognitoError("NotAuthorizedException"))
        expect<WhiskerException.BadCredentials> { auth.signIn("cat@example.com", "wrong") }
    }

    @Test fun `signIn with UserNotFoundException throws BadCredentials`() = runTest {
        server.enqueue(cognitoError("UserNotFoundException"))
        expect<WhiskerException.BadCredentials> { auth.signIn("nobody@example.com", "x") }
    }

    @Test fun `refresh sends REFRESH_TOKEN_AUTH and returns tokens with null refreshToken`() = runTest {
        server.enqueue(ok(refresh = null))
        val tokens = auth.refresh("r-old")
        assertEquals("id1", tokens.idToken)
        assertNull(tokens.refreshToken)
        assertEquals(now + 3600, tokens.expiresAtEpochSec)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("REFRESH_TOKEN_AUTH", body["AuthFlow"]!!.jsonPrimitive.content)
        assertEquals("r-old", body["AuthParameters"]!!.jsonObject["REFRESH_TOKEN"]!!.jsonPrimitive.content)
    }

    @Test fun `refresh with NotAuthorizedException throws BadCredentials`() = runTest {
        server.enqueue(cognitoError("NotAuthorizedException"))
        expect<WhiskerException.BadCredentials> { auth.refresh("r-dead") }
    }

    @Test fun `connection failure throws Network`() = runTest {
        server.shutdown()
        expect<WhiskerException.Network> { auth.signIn("cat@example.com", "pw") }
    }

    @Test fun `HTTP 500 throws Server with the code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val e = expect<WhiskerException.Server> { auth.signIn("cat@example.com", "pw") }
        assertEquals(500, e.code)
    }

    @Test fun `unexpected 400 type throws Server carrying the type`() = runTest {
        server.enqueue(cognitoError("TooManyRequestsException"))
        val e = expect<WhiskerException.Server> { auth.signIn("cat@example.com", "pw") }
        assertEquals(400, e.code)
        assertEquals("TooManyRequestsException", e.type)
    }

    @Test fun `success body without tokens throws Server`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"SMS_MFA"}"""))
        expect<WhiskerException.Server> { auth.signIn("cat@example.com", "pw") }
    }

    @Test fun `exceptions and tokens never print the password or token values`() = runTest {
        server.enqueue(cognitoError("NotAuthorizedException"))
        val e = expect<WhiskerException.BadCredentials> { auth.signIn("cat@example.com", "s3cret-pw") }
        assertFalse((e.toString() + e.message).contains("s3cret-pw"))
        val printed = Tokens("id-secret", "refresh-secret", 1).toString()
        assertFalse(printed.contains("id-secret"))
        assertFalse(printed.contains("refresh-secret"))
        assertTrue(printed.contains("Tokens"))
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
