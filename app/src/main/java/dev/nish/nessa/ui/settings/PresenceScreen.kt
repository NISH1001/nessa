package dev.nish.nessa.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nish.nessa.presence.AWAY_AFTER_OPTIONS
import dev.nish.nessa.presence.PresenceConfig
import dev.nish.nessa.presence.PresenceService
import dev.nish.nessa.presence.RADIUS_OPTIONS
import dev.nish.nessa.schedule.Presence
import dev.nish.nessa.ui.ScreenBody
import dev.nish.nessa.ui.appContainer
import dev.nish.nessa.ui.presenceWhy
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun Presence.label(): String = when (this) {
    Presence.Home -> "Home"
    Presence.Away -> "Away"
    Presence.Unknown -> "Unknown"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresenceScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val engine = appContainer().presence
    val state by engine.state.collectAsStateWithLifecycle()
    val config = state.config
    val scope = rememberCoroutineScope()

    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var hasLocation by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var hasBackground by remember { mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    var locating by remember { mutableStateOf(false) }
    var locationProblem by remember { mutableStateOf<String?>(null) }

    fun save(next: PresenceConfig) {
        scope.launch {
            engine.setConfig(next)
            PresenceService.sync(app, next.enabled)
        }
    }

    fun recheck() {
        hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        hasBackground = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PresenceService.sync(app, config.enabled)
    }
    // Permissions can change while the owner is in Android's settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck() }

    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { recheck() }

    ScreenBody {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Detect home and away", style = MaterialTheme.typography.titleMedium)
                    }
                    Switch(checked = config.enabled, onCheckedChange = { save(config.copy(enabled = it)) })
                }
                if (config.enabled) {
                    Text(state.presence.label(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(presenceWhy(state), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (config.enabled && !hasLocation) {
            PermissionCard(
                "Allow location",
                "Needed to read your Wi-Fi name.",
                "Allow",
            ) {
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
            }
        } else if (config.enabled && !hasBackground) {
            PermissionCard(
                "Allow all the time",
                "Choose \"Allow all the time\" on the next screen.",
                "Continue",
            ) { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        }

        if (config.enabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Home Wi-Fi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Switch(checked = config.wifiEnabled, onCheckedChange = { save(config.copy(wifiEnabled = it)) })
                    }
                    if (config.wifiEnabled) {
                        if (config.homeWifi.isEmpty()) {
                            Text("None added", style = MaterialTheme.typography.bodyMedium)
                        }
                        config.homeWifi.sorted().forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { save(config.copy(homeWifi = config.homeWifi - name)) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove $name")
                                }
                            }
                        }
                        val current = state.currentWifi
                        when {
                            current == null -> Text(
                                if (hasLocation) "Not on Wi-Fi" else "Location needed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            current in config.homeWifi -> Text("Connected to $current", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> OutlinedButton(onClick = { save(config.copy(homeWifi = config.homeWifi + current)) }) { Text("Add $current") }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Home area", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Switch(checked = config.areaEnabled, onCheckedChange = { save(config.copy(areaEnabled = it)) })
                    }
                    if (config.areaEnabled) {
                        Text(
                            if (config.homeLat != null) "Home is set" else "Not set",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            enabled = hasLocation && !locating,
                            onClick = {
                                locating = true
                                locationProblem = null
                                currentLocation(app) { lat, lon ->
                                    locating = false
                                    if (lat == null || lon == null) locationProblem = "Couldn't get your location"
                                    else save(config.copy(homeLat = lat, homeLon = lon))
                                }
                            },
                        ) { Text(if (locating) "Finding you…" else if (config.homeLat != null) "Update location" else "Use current location") }
                        locationProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RADIUS_OPTIONS.forEach { r ->
                                FilterChip(selected = config.radiusM == r, onClick = { save(config.copy(radiusM = r)) }, label = { Text("$r m") })
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var index by remember(config.awayAfterMin) { mutableIntStateOf(AWAY_AFTER_OPTIONS.indexOf(config.awayAfterMin).coerceAtLeast(0)) }
                    Row {
                        Text("Away after", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${AWAY_AFTER_OPTIONS[index]} min", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = index.toFloat(),
                        onValueChange = { index = it.roundToInt() },
                        onValueChangeFinished = { save(config.copy(awayAfterMin = AWAY_AFTER_OPTIONS[index])) },
                        valueRange = 0f..(AWAY_AFTER_OPTIONS.size - 1).toFloat(),
                        steps = AWAY_AFTER_OPTIONS.size - 2,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AWAY_AFTER_OPTIONS.forEach { Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Android settings") }
            Text("Set Battery to Unrestricted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

/** One fresh location reading, or nulls if Android can't provide one. */
@SuppressLint("MissingPermission")
private fun currentLocation(app: Application, done: (Double?, Double?) -> Unit) {
    val lm = app.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.allProviders.contains(it) }
    if (provider == null) return done(null, null)
    runCatching {
        lm.getCurrentLocation(provider, null, app.mainExecutor) { location -> done(location?.latitude, location?.longitude) }
    }.onFailure { done(null, null) }
}
