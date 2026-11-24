package dev.nish.nessa.ui.settings

import dev.nish.nessa.Fixtures
import dev.nish.nessa.MainDispatcherRule
import dev.nish.nessa.data.FakeRobotChoiceStore
import dev.nish.nessa.data.Secrets
import dev.nish.nessa.whisker.CognitoAuth
import dev.nish.nessa.whisker.ConnectionStatus
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
import org.junit.Rule
import org.junit.Test

class ConnectionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var store: FakeSecretStore
    private lateinit var vm: ConnectionsViewModel
    private val choice = FakeRobotChoiceStore()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeSecretStore()
        vm = newVm()
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun newVm(): ConnectionsViewModel {
        val http = OkHttpClient()
        val session = WhiskerSession(CognitoAuth(http, server.url("/").toString(), "c"), store)
        val api = WhiskerApi(http, server.url("/").toString(), "nessa/test", session::invalidate, session::idToken)
        return ConnectionsViewModel(session, api, choice)
    }

    private fun tokens() = MockResponse().setBody("""{"AuthenticationResult":{"IdToken":"id1","RefreshToken":"r1","ExpiresIn":3600}}""")
    private fun rejected() = MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException"}""")
    private fun robots() = MockResponse().setBody(Fixtures.text("robots.json"))

    @Test fun `starts as not connected with no error`() {
        assertEquals(ConnectionsUi(ConnectionStatus.Disconnected, null, false, null), vm.ui.value)
    }

    @Test fun `signIn success shows Connected and the robot name`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots())
        vm.signIn("cat@example.com", "pw").join()
        assertEquals(ConnectionStatus.Connected, vm.ui.value.status)
        assertEquals("Litter-Robot EVO", vm.ui.value.robotName)
        assertEquals(listOf(RobotOption("LRE-TEST-0001", "Litter-Robot EVO", true)), vm.ui.value.robots)
        assertNull(vm.ui.value.error)
        assertFalse(vm.ui.value.busy)
    }

    @Test fun `wrong password shows the wrong-password message and stays not connected`() = runBlocking {
        server.enqueue(rejected())
        vm.signIn("cat@example.com", "nope").join()
        assertEquals(ConnectionStatus.Disconnected, vm.ui.value.status)
        assertEquals("That email or password didn't work.", vm.ui.value.error)
        assertNull(store.saved)
    }

    @Test fun `no network shows the could-not-reach message`() = runBlocking {
        server.shutdown()
        vm.signIn("cat@example.com", "pw").join()
        assertEquals("Couldn't reach Whisker. Check your internet and try again.", vm.ui.value.error)
        assertEquals(ConnectionStatus.Disconnected, vm.ui.value.status)
    }

    @Test fun `server error shows the code message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        vm.signIn("cat@example.com", "pw").join()
        assertEquals("Whisker had a problem (code 503). Try again in a bit.", vm.ui.value.error)
    }

    @Test fun `blank email or password is refused without calling Whisker`() = runBlocking {
        vm.signIn("  ", "pw").join()
        assertEquals("Enter your Whisker email and password.", vm.ui.value.error)
        vm.signIn("cat@example.com", "").join()
        assertEquals("Enter your Whisker email and password.", vm.ui.value.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun `email is trimmed before signing in`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots())
        vm.signIn("  cat@example.com ", "pw").join()
        assertEquals("cat@example.com", store.saved?.email)
    }

    @Test fun `busy is true while signing in and false after`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots())
        val job = vm.signIn("cat@example.com", "pw")
        assertTrue(vm.ui.value.busy)
        job.join()
        assertFalse(vm.ui.value.busy)
    }

    @Test fun `signed in but account has no robot says so`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(MockResponse().setBody("[]"))
        vm.signIn("cat@example.com", "pw").join()
        assertEquals(ConnectionStatus.Connected, vm.ui.value.status)
        assertNull(vm.ui.value.robotName)
        assertEquals("No Litter-Robot found on this account.", vm.ui.value.error)
    }

    @Test fun `test connection with a saved sign-in finds the robot`() = runBlocking {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        vm = newVm()
        server.enqueue(tokens()); server.enqueue(robots())
        assertNull(vm.ui.value.note)
        vm.test().join()
        assertEquals("Litter-Robot EVO", vm.ui.value.robotName)
        assertEquals("Connection works", vm.ui.value.note)
        assertNull(vm.ui.value.error)
    }

    @Test fun `test connection that fails shows the reason and no success note`() = runBlocking {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        vm = newVm()
        server.enqueue(tokens()); server.enqueue(MockResponse().setResponseCode(502))
        vm.test().join()
        assertNull(vm.ui.value.note)
        assertEquals("Whisker had a problem (code 502). Try again in a bit.", vm.ui.value.error)
    }

    @Test fun `success note is cleared when another action starts`() = runBlocking {
        store.saved = Secrets("cat@example.com", "pw", "r1")
        vm = newVm()
        server.enqueue(tokens()); server.enqueue(robots())
        vm.test().join()
        vm.signOut().join()
        assertNull(vm.ui.value.note)
    }

    private fun twoRobots() = MockResponse().setBody(
        """[{"serial":"A-1","type":"LR5","name":"Upstairs"},{"serial":"B-2","type":"LRE","name":"Downstairs"}]""",
    )

    @Test fun `several robots are listed and the EVO is selected by default`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(twoRobots())
        vm.signIn("cat@example.com", "pw").join()
        assertEquals(
            listOf(RobotOption("A-1", "Upstairs", false), RobotOption("B-2", "Downstairs", true)),
            vm.ui.value.robots,
        )
        assertEquals("Downstairs", vm.ui.value.robotName)
    }

    @Test fun `selecting a robot saves the choice and updates the list`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(twoRobots())
        vm.signIn("cat@example.com", "pw").join()
        vm.selectRobot("A-1").join()
        assertEquals("A-1", choice.saved)
        assertEquals("Upstairs", vm.ui.value.robotName)
        assertEquals(listOf(true, false), vm.ui.value.robots.map { it.selected })
    }

    @Test fun `a saved choice is used instead of the default`() = runBlocking {
        choice.saved = "A-1"
        server.enqueue(tokens()); server.enqueue(twoRobots())
        vm.signIn("cat@example.com", "pw").join()
        assertEquals("Upstairs", vm.ui.value.robotName)
    }

    @Test fun `a saved choice that no longer exists falls back to the default`() = runBlocking {
        choice.saved = "GONE-9"
        server.enqueue(tokens()); server.enqueue(twoRobots())
        vm.signIn("cat@example.com", "pw").join()
        assertEquals("Downstairs", vm.ui.value.robotName)
    }

    @Test fun `robot without a name is listed as Litter-Robot`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(MockResponse().setBody("""[{"serial":"X-1"}]"""))
        vm.signIn("cat@example.com", "pw").join()
        assertEquals("Litter-Robot", vm.ui.value.robotName)
    }

    @Test fun `saved sign-in that Whisker no longer accepts shows needs sign in again`() = runBlocking {
        store.saved = Secrets("cat@example.com", "old", "r-dead")
        vm = newVm()
        server.enqueue(rejected()); server.enqueue(rejected())
        vm.test().join()
        assertEquals(ConnectionStatus.NeedsReconnect, vm.ui.value.status)
    }

    @Test fun `signOut returns to not connected and forgets the robot`() = runBlocking {
        server.enqueue(tokens()); server.enqueue(robots())
        vm.signIn("cat@example.com", "pw").join()
        vm.signOut().join()
        assertEquals(ConnectionsUi(ConnectionStatus.Disconnected, null, false, null), vm.ui.value)
        assertNull(store.saved)
        assertNull(choice.saved)
    }

    @Test fun `error text never contains the password`() = runBlocking {
        server.enqueue(rejected())
        vm.signIn("cat@example.com", "hunter2-secret").join()
        assertFalse(vm.ui.value.toString().contains("hunter2-secret"))
    }
}
