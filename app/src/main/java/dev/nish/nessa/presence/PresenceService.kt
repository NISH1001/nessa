package dev.nish.nessa.presence

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.nish.nessa.MainActivity
import dev.nish.nessa.NessaApp
import dev.nish.nessa.R
import dev.nish.nessa.schedule.Presence
import dev.nish.nessa.schedule.PresenceAlarm
import dev.nish.nessa.schedule.title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Runs while presence detection is on. Watches the Wi-Fi the phone is on and, if a home area is set,
 * the phone's location, and feeds both to the [PresenceEngine]. Shows a quiet permanent notification
 * so Android keeps it alive.
 */
class PresenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine get() = (application as NessaApp).container.presence
    private val manager get() = (application as NessaApp).container.manager
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var locationListener: LocationListener? = null
    private val awayTimer = AwayTimer(scope) {
        Log.i(TAG, "away timer fired")
        engine.tick()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Home and away", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Home or away"
                setShowBadge(false)
            },
        )
        startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        watchWifi()
        scope.launch {
            engine.load()
            // Re-arm the location watch and the away timer whenever settings or the pending departure change.
            engine.state.collectLatest { state ->
                Log.i(TAG, "state: ${state.presence} wifi=${state.wifi} area=${state.area} pending=${state.awayAt != null}")
                watchLocation(state.config.enabled && state.config.areaEnabled && state.config.homeLat != null)
                awayTimer.arm(state.awayAt)
                PresenceAlarm.arm(this@PresenceService, state.awayAt)
            }
        }
        scope.launch {
            combine(engine.state, manager.status, manager.state) { p, status, managing -> Triple(p, status, managing) }.collectLatest { (p, status, managing) ->
                val who = when (p.presence) {
                    Presence.Home -> "Home"
                    Presence.Away -> "Away"
                    Presence.Unknown -> "Unknown"
                }
                val robot = status.decision?.takeIf { managing.enabled }?.let { " · Litter ${it.title().lowercase()}" }.orEmpty()
                val text = if (p.awayAt != null) "Home · away in" else who + robot
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, countdownTo = p.awayAt))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wifiCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        watchLocation(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun watchWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        // FLAG_INCLUDE_LOCATION_INFO is what lets Android hand over the Wi-Fi name (needs location permission).
        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val name = (caps.transportInfo as? WifiInfo)?.ssid
                val reading = if (name == null || name == WifiManager.UNKNOWN_SSID) WifiReading.NameHidden else WifiReading.Connected(name)
                Log.i(TAG, "wifi: capabilities changed, name ${if (reading is WifiReading.Connected) "known" else "hidden"}")
                scope.launch { engine.onWifi(reading) }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "wifi: lost")
                scope.launch { engine.onWifi(WifiReading.NotOnWifi) }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        wifiCallback = callback
        // If the phone isn't on Wi-Fi at all, no callback fires: say so now.
        val onWifi = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (!onWifi) scope.launch { engine.onWifi(WifiReading.NotOnWifi) }
    }

    private fun watchLocation(on: Boolean) {
        val lm = getSystemService(LocationManager::class.java)
        locationListener?.let { lm.removeUpdates(it) }
        locationListener = null
        if (!on || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val listener = LocationListener { location: Location ->
            scope.launch { engine.onLocation(LocationFix(location.latitude, location.longitude, location.accuracy, Instant.ofEpochMilli(location.time))) }
        }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.allProviders.contains(it) } ?: return
        runCatching {
            lm.requestLocationUpdates(provider, 2 * 60_000L, 50f, listener, Looper.getMainLooper())
            lm.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
        }
        locationListener = listener
    }

    private fun notification(text: String, countdownTo: Instant? = null): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Nessa")
            .setContentText(text)
            .setOngoing(true)
            // Android draws the ticking countdown itself, so this costs no battery.
            .setShowWhen(countdownTo != null)
            .setUsesChronometer(countdownTo != null)
            .setChronometerCountDown(countdownTo != null)
            .setWhen(countdownTo?.toEpochMilli() ?: System.currentTimeMillis())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        private const val TAG = "NessaPresence"
        private const val CHANNEL = "presence"
        private const val NOTIFICATION_ID = 1

        fun hasPermissions(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts or stops the service to match the setting. Safe to call any time. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, PresenceService::class.java)
            if (enabled && hasPermissions(context)) runCatching { context.startForegroundService(intent) } else context.stopService(intent)
        }
    }
}
