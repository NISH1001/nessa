package dev.nish.nessa.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nish.nessa.data.RobotChoiceStore
import dev.nish.nessa.data.pickRobot
import dev.nish.nessa.whisker.ConnectionStatus
import dev.nish.nessa.whisker.Robot
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerException
import dev.nish.nessa.whisker.WhiskerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RobotOption(val serial: String, val name: String, val selected: Boolean)

data class ConnectionsUi(
    val status: ConnectionStatus,
    val robotName: String?,
    val busy: Boolean,
    val error: String?,
    /** Every robot on the account; more than one means the owner can choose. */
    val robots: List<RobotOption> = emptyList(),
    /** A short success message, e.g. after "Test connection". */
    val note: String? = null,
)

class ConnectionsViewModel(
    private val session: WhiskerSession,
    private val api: WhiskerApi,
    private val choice: RobotChoiceStore,
) : ViewModel() {
    private var lastRobots: List<Robot> = emptyList()

    private val _ui = MutableStateFlow(ConnectionsUi(session.status.value, null, false, null))
    val ui: StateFlow<ConnectionsUi> = _ui

    init {
        viewModelScope.launch {
            session.status.collect { applyStatus(it) }
        }
        viewModelScope.launch { session.restore() }
    }

    fun signIn(email: String, password: String): Job = work {
        if (email.isBlank() || password.isEmpty()) {
            _ui.update { it.copy(error = "Enter your Whisker email and password.") }
            return@work
        }
        session.signIn(email.trim(), password)
        findRobot()
    }

    /** Checks the saved sign-in still works and finds the robot. */
    fun test(): Job = work {
        findRobot()
        if (_ui.value.error == null) _ui.update { it.copy(note = "Connection works") }
    }

    fun selectRobot(serial: String): Job = work {
        choice.save(serial)
        showRobots(lastRobots)
    }

    fun signOut(): Job = work {
        session.signOut()
        choice.save(null)
        lastRobots = emptyList()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun clearNote() = _ui.update { it.copy(note = null) }

    private suspend fun findRobot() {
        lastRobots = api.robots()
        showRobots(lastRobots)
    }

    private suspend fun showRobots(robots: List<Robot>) {
        val picked = pickRobot(robots, choice.load())
        _ui.update {
            it.copy(
                robotName = picked?.displayName(),
                robots = robots.map { r -> RobotOption(r.serial, r.displayName(), r.serial == picked?.serial) },
                error = if (picked == null) "No Litter-Robot found on this account." else null,
            )
        }
    }

    private fun Robot.displayName() = name?.takeIf { it.isNotBlank() } ?: "Litter-Robot"

    private fun applyStatus(status: ConnectionStatus) {
        val connected = status == ConnectionStatus.Connected
        _ui.update {
            it.copy(
                status = status,
                robotName = it.robotName.takeIf { connected },
                robots = if (connected) it.robots else emptyList(),
            )
        }
    }

    private fun work(block: suspend () -> Unit): Job = viewModelScope.launch {
        _ui.update { it.copy(busy = true, error = null, note = null) }
        try {
            block()
        } catch (e: WhiskerException) {
            _ui.update { it.copy(error = message(e)) }
        } finally {
            // Don't wait for the status collector: the screen must be right the moment work ends.
            applyStatus(session.status.value)
            _ui.update { it.copy(busy = false) }
        }
    }

    private fun message(e: WhiskerException): String? = when (e) {
        is WhiskerException.BadCredentials -> "That email or password didn't work."
        is WhiskerException.Network -> "Couldn't reach Whisker. Check your internet and try again."
        is WhiskerException.Server -> "Whisker had a problem (code ${e.code}). Try again in a bit."
        is WhiskerException.NeedsReconnect -> null // the status line already says so
    }
}
