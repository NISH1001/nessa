package dev.nish.nessa

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NessaApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Load the saved Whisker sign-in right away so every screen knows whether we're connected.
        appScope.launch { container.session.restore() }
        // Re-arm the schedule checks in case the app was updated or force-stopped.
        appScope.launch {
            container.presence.load()
            dev.nish.nessa.presence.PresenceService.sync(this@NessaApp, container.presence.state.value.config.enabled)
        }
        appScope.launch {
            container.manager.load()
            if (container.manager.state.value.enabled) dev.nish.nessa.schedule.Scheduling.applySoon(this@NessaApp)
        }
    }
}
