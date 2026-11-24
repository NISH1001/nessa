package dev.nish.nessa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nish.nessa.presence.PresenceState
import dev.nish.nessa.presence.countdownText
import kotlinx.coroutines.delay
import java.time.Instant

/** The reason line for presence. While a departure is pending it counts down every second. */
@Composable
fun presenceWhy(state: PresenceState): String {
    val awayAt = state.awayAt ?: return state.why
    var now by remember(awayAt) { mutableStateOf(Instant.now()) }
    LaunchedEffect(awayAt) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }
    return countdownText(awayAt, now)
}
