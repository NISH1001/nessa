package dev.nish.nessa

import android.content.Context
import dev.nish.nessa.data.ActionLog
import dev.nish.nessa.data.DataStoreRobotChoiceStore
import dev.nish.nessa.data.applyActionText
import dev.nish.nessa.data.presenceActionText
import dev.nish.nessa.data.KeystoreSecretStore
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.data.RobotChoiceStore
import dev.nish.nessa.data.SecretStore
import dev.nish.nessa.presence.DataStorePresenceStore
import dev.nish.nessa.presence.PresenceEngine
import dev.nish.nessa.schedule.ApplyResult
import dev.nish.nessa.schedule.DataStoreManagerStore
import dev.nish.nessa.schedule.Scheduling
import dev.nish.nessa.schedule.LitterManager
import dev.nish.nessa.whisker.CognitoAuth
import dev.nish.nessa.whisker.WhiskerApi
import dev.nish.nessa.whisker.WhiskerSession
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies shared across the app. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val secretStore: SecretStore = KeystoreSecretStore(appContext)
    val robotChoice: RobotChoiceStore = DataStoreRobotChoiceStore(appContext)
    val auth = CognitoAuth(http)
    val session = WhiskerSession(auth, secretStore)
    val api = WhiskerApi(http, userAgent = "nessa/${BuildConfig.VERSION_NAME}", invalidateToken = session::invalidate, idToken = session::idToken)
    val litter = LitterRepository(api, session, robotChoice)
    val actions = ActionLog(appContext)
    val presence: PresenceEngine = PresenceEngine(DataStorePresenceStore(appContext), onNote = { actions.add(it) }) {
        // Home or away just changed. Update the robot in a background job: that keeps the phone awake
        // until it is done and retries by itself if there is no signal.
        android.util.Log.i("NessaPresence", "presence changed to $it")
        actions.add(presenceActionText(it))
        Scheduling.applySoon(appContext)
    }
    val manager: LitterManager = LitterManager(api, robotChoice, DataStoreManagerStore(appContext), presence = { presence.current }, onNote = { actions.add(it) }, onEvent = { result, decision, problem ->
        applyActionText(result, decision, problem)?.let { actions.add(it, ok = result != ApplyResult.Failed) }
    })
}
