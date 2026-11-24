package dev.nish.nessa.presence

import dev.nish.nessa.schedule.Presence
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

val AWAY_AFTER_OPTIONS = listOf(1, 2, 5, 10, 15)
val RADIUS_OPTIONS = listOf(100, 200, 500)

/** How Nessa works out whether the owner is home. Lives only on the phone. */
@Serializable
data class PresenceConfig(
    val enabled: Boolean = false,
    val wifiEnabled: Boolean = true,
    val homeWifi: Set<String> = emptySet(),
    val areaEnabled: Boolean = false,
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val radiusM: Int = 200,
    /** Minutes every signal must say "away" before Nessa believes it. */
    val awayAfterMin: Int = 5,
) {
    override fun toString() = "PresenceConfig(enabled=$enabled, wifi=${homeWifi.size} networks, area=${homeLat != null})"
}

sealed interface WifiReading {
    data class Connected(val name: String) : WifiReading { override fun toString() = "Connected" }
    data object NotOnWifi : WifiReading
    /** On Wi-Fi, but Android won't say which (location is off or permission is missing). */
    data object NameHidden : WifiReading
}

data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Float, val at: Instant) {
    override fun toString() = "LocationFix(at=$at)"
}

/** What one source says. Off means the owner hasn't set it up or has turned it off. */
enum class Signal { Home, Away, Unknown, Off }

fun cleanWifiName(name: String) = name.removeSurrounding("\"")

fun wifiSignal(config: PresenceConfig, reading: WifiReading): Signal = when {
    !config.wifiEnabled || config.homeWifi.isEmpty() -> Signal.Off
    reading is WifiReading.NameHidden -> Signal.Unknown
    reading is WifiReading.Connected && cleanWifiName(reading.name) in config.homeWifi -> Signal.Home
    else -> Signal.Away
}

private val FIX_MAX_AGE: Duration = Duration.ofMinutes(15)

fun areaSignal(config: PresenceConfig, fix: LocationFix?, now: Instant): Signal {
    val lat = config.homeLat
    val lon = config.homeLon
    if (!config.areaEnabled || lat == null || lon == null) return Signal.Off
    if (fix == null || Duration.between(fix.at, now) > FIX_MAX_AGE) return Signal.Unknown
    val distance = distanceMeters(lat, lon, fix.lat, fix.lon)
    return when {
        distance + fix.accuracyM <= config.radiusM -> Signal.Home
        distance - fix.accuracyM > config.radiusM -> Signal.Away
        distance <= config.radiusM && fix.accuracyM <= config.radiusM -> Signal.Home
        else -> Signal.Unknown
    }
}

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** What Nessa currently believes, and when a pending "you left" becomes final. */
data class PresenceMemory(val presence: Presence = Presence.Unknown, val awayAt: Instant? = null)

/**
 * Combines the signals. Any signal saying home means home at once. Leaving needs every usable signal
 * to agree (or be unsure) for the whole away delay, so a Wi-Fi blip at home doesn't count.
 */
fun track(memory: PresenceMemory, config: PresenceConfig, wifi: Signal, area: Signal, now: Instant): PresenceMemory {
    if (!config.enabled) return PresenceMemory()
    val signals = listOf(wifi, area).filter { it != Signal.Off }
    return when {
        signals.isEmpty() -> PresenceMemory()
        Signal.Home in signals -> PresenceMemory(Presence.Home)
        Signal.Away in signals -> when (memory.presence) {
            Presence.Home -> {
                val awayAt = memory.awayAt ?: now.plus(Duration.ofMinutes(config.awayAfterMin.toLong()))
                if (now >= awayAt) PresenceMemory(Presence.Away) else PresenceMemory(Presence.Home, awayAt)
            }
            else -> PresenceMemory(Presence.Away)
        }
        // Every signal is unsure: keep what we believed, including a pending departure, rather than guess.
        else -> memory
    }
}

fun whyText(presence: Presence, wifi: Signal, area: Signal, awayAt: Instant?, now: Instant): String = when {
    awayAt != null -> {
        val minutes = maxOf(1, ceil(Duration.between(now, awayAt).seconds / 60.0).toInt())
        "Away in $minutes min"
    }
    presence == Presence.Home && wifi == Signal.Home -> "Home Wi-Fi"
    presence == Presence.Home && area == Signal.Home -> "Home area"
    presence == Presence.Home -> "Home"
    presence == Presence.Away && wifi == Signal.Away -> "Off home Wi-Fi"
    presence == Presence.Away && area == Signal.Away -> "Outside home area"
    presence == Presence.Away -> "Away"
    else -> "Unknown"
}

/** Live countdown until a pending departure becomes final. */
fun countdownText(awayAt: Instant, now: Instant): String {
    val seconds = Duration.between(now, awayAt).seconds.coerceAtLeast(0)
    return "Away in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
