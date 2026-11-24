package dev.nish.nessa.whisker

import dev.nish.nessa.data.SecretStore
import dev.nish.nessa.data.Secrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConnectionStatus { Disconnected, Connected, NeedsReconnect }

/**
 * Owns the Whisker sign-in. Hands out a valid id token, renewing it quietly:
 * refresh token first, then the saved password, and only then asks the owner.
 */
class WhiskerSession(
    private val auth: CognitoAuth,
    private val store: SecretStore,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private var loaded = false
    private var secrets: Secrets? = null
    private var tokens: Tokens? = null

    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    /** Call at app start. Makes no network call. */
    suspend fun restore() = mutex.withLock { ensureLoaded() }

    /** Saves the sign-in only if Whisker accepts it. */
    suspend fun signIn(email: String, password: String) = mutex.withLock {
        val fresh = auth.signIn(email, password)
        val refresh = fresh.refreshToken ?: throw WhiskerException.Server(200, "NoRefreshToken")
        remember(Secrets(email, password, refresh), fresh)
    }

    suspend fun signOut() = mutex.withLock {
        store.clear()
        secrets = null
        tokens = null
        loaded = true
        _status.value = ConnectionStatus.Disconnected
    }

    /** Forget the cached token because Whisker refused it; the next [idToken] renews it. */
    suspend fun invalidate() = mutex.withLock { tokens = null }

    suspend fun idToken(): String = mutex.withLock {
        ensureLoaded()
        val saved = secrets
        if (saved == null || _status.value == ConnectionStatus.NeedsReconnect) throw WhiskerException.NeedsReconnect()
        tokens?.takeIf { it.expiresAtEpochSec - nowEpochSec() > MIN_LIFETIME_SEC }?.let { return it.idToken }

        try {
            tokens = auth.refresh(saved.refreshToken)
        } catch (e: WhiskerException.BadCredentials) {
            val fresh = try {
                auth.signIn(saved.email, saved.password)
            } catch (e2: WhiskerException.BadCredentials) {
                tokens = null
                _status.value = ConnectionStatus.NeedsReconnect
                throw WhiskerException.NeedsReconnect()
            }
            remember(saved.copy(refreshToken = fresh.refreshToken ?: saved.refreshToken), fresh)
        }
        tokens!!.idToken
    }

    private suspend fun remember(s: Secrets, t: Tokens) {
        store.save(s)
        secrets = s
        tokens = t
        loaded = true
        _status.value = ConnectionStatus.Connected
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        secrets = store.load()
        loaded = true
        if (secrets != null) _status.value = ConnectionStatus.Connected
    }

    private companion object {
        const val MIN_LIFETIME_SEC = 60
    }
}
