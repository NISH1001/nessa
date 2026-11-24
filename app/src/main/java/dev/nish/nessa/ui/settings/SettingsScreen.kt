package dev.nish.nessa.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pets
import dev.nish.nessa.BuildConfig
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nish.nessa.ui.ScreenBody
import dev.nish.nessa.ui.appContainer
import dev.nish.nessa.whisker.ConnectionStatus

fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Disconnected -> "Not connected"
    ConnectionStatus.NeedsReconnect -> "Needs you to sign in again"
}

@Composable
fun SettingsScreen(onOpenConnections: () -> Unit, onOpenPresence: () -> Unit) {
    val status by appContainer().session.status.collectAsStateWithLifecycle()
    val presence by appContainer().presence.state.collectAsStateWithLifecycle()
    ScreenBody {
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenPresence)) {
            ListItem(
                headlineContent = { Text("Presence") },
                supportingContent = { Text(if (presence.config.enabled) presence.presence.label() else "Off") },
                leadingContent = { Icon(Icons.Rounded.Home, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenConnections)) {
            ListItem(
                headlineContent = { Text("Connections") },
                supportingContent = { Text("Whisker · ${status.label()}") },
                leadingContent = { Icon(Icons.Rounded.Link, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Card(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("Nessa") },
                supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Rounded.Pets, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}
