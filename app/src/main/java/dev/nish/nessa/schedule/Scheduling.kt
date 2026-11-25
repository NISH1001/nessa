package dev.nish.nessa.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.nish.nessa.NessaApp
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Makes sure the rule is re-applied at the right moments even when the app is closed:
 * an exact alarm at the next change, plus a 15-minute safety check.
 */
object Scheduling {
    private const val APPLY_NOW = "nessa-apply"
    private const val SAFETY = "nessa-safety"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Call after anything that may have changed what should happen next. */
    fun sync(context: Context, enabled: Boolean, nextCheckAt: Instant?) {
        val work = WorkManager.getInstance(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val alarm = PendingIntent.getBroadcast(
            context, 0, Intent(context, ApplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (!enabled) {
            alarms.cancel(alarm)
            work.cancelUniqueWork(SAFETY)
            return
        }
        work.enqueueUniquePeriodicWork(
            SAFETY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ApplyWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        if (nextCheckAt != null) {
            val at = nextCheckAt.toEpochMilli()
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        }
    }

    fun applySoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            APPLY_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ApplyWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}

/** Wakes the phone when a pending "you left" becomes final, even if it is asleep with the screen off. */
object PresenceAlarm {
    fun arm(context: Context, awayAt: Instant?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 1, Intent(context, PresenceTickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (awayAt == null) return alarms.cancel(intent)
        val at = awayAt.toEpochMilli() + 500
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }
}

class PresenceTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("NessaPresence", "away alarm fired")
        WorkManager.getInstance(context).enqueueUniqueWork(
            "nessa-presence-tick", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PresenceTickWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
    }
}

class PresenceTickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as NessaApp).container.presence.tick()
        return Result.success()
    }
}

/** Fires at the next rule change and after a reboot. */
class ApplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Creating the app (which happens before this runs) already restarts presence watching.
        Scheduling.applySoon(context)
    }
}

class ApplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as NessaApp).container.manager
        val result = manager.apply()
        android.util.Log.i("NessaPresence", "apply result $result problem=${manager.status.value.problem}")
        (applicationContext as NessaApp).container.litter.refresh()
        Scheduling.sync(applicationContext, manager.state.value.needsChecks, manager.status.value.nextCheckAt)
        return if (result == ApplyResult.Failed && runAttemptCount < 5) Result.retry() else Result.success()
    }
}

/** Keeps the insights history filled, whether or not Nessa manages sleep. */
object HistoryScheduling {
    fun start(context: Context) {
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            "nessa-history", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HistorySyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        work.enqueueUniqueWork(
            "nessa-history-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HistorySyncWorker>().setConstraints(online).build(),
        )
    }
}

class HistorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val problem = (applicationContext as NessaApp).container.historySync.sync()
        android.util.Log.i("NessaHistory", if (problem == null) "history synced" else "history sync failed: $problem")
        return if (problem != null && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
