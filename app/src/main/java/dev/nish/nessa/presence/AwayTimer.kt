package dev.nish.nessa.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Waits until a pending "you left" becomes final, then runs [onFire]. */
class AwayTimer(
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val onFire: suspend () -> Unit,
) {
    private var waiting: Job? = null

    fun arm(awayAt: Instant?) {
        waiting?.cancel()
        waiting = null
        if (awayAt == null) return
        waiting = scope.launch {
            delay(Duration.between(now(), awayAt).toMillis().coerceAtLeast(0) + 500)
            // Run the work on its own: it changes presence, which clears this timer, and that must
            // not cancel the work itself (it once did, so the robot was never told you had left).
            scope.launch { onFire() }
        }
    }
}
