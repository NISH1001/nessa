package dev.nish.nessa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.ui.litter.color
import dev.nish.nessa.ui.litter.litterStatusUi
import java.time.Instant
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.nish.nessa.ui.home.HomeScreen
import dev.nish.nessa.ui.litter.LitterScreen
import dev.nish.nessa.ui.log.LogScreen
import dev.nish.nessa.ui.settings.ConnectionsScreen
import dev.nish.nessa.ui.settings.PresenceScreen
import dev.nish.nessa.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", "Nessa", Icons.Rounded.Home),
    Tab("litter", "Litter", "Litter", Icons.Rounded.Pets),
    Tab("log", "Log", "Log", Icons.AutoMirrored.Rounded.ListAlt),
)

private val subScreenTitles = mapOf(
    "settings" to "Settings",
    "settings/connections" to "Connections",
    "settings/presence" to "Presence",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NessaRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val tab = tabs.firstOrNull { it.route == route }
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val litterState by container.litter.state.collectAsStateWithLifecycle()
    val litterTone = (litterState as? LitterState.Ready)?.let { litterStatusUi(it.robot, Instant.now()).tone }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab?.title ?: subScreenTitles[route].orEmpty()) },
                navigationIcon = {
                    if (tab == null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (tab != null) {
                        IconButton(
                            enabled = !refreshing,
                            onClick = {
                                scope.launch {
                                    refreshing = true
                                    try {
                                        container.litter.refresh()
                                        container.litter.refreshHistory()
                                        container.historySync.sync()
                                    } finally {
                                        refreshing = false
                                    }
                                }
                            },
                        ) {
                            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (tab != null) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = t.route == route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val dot = litterTone.takeIf { t.route == "litter" }
                                BadgedBox(badge = { if (dot != null) Badge(containerColor = dot.color()) }) { Icon(t.icon, contentDescription = null) }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(onOpenLitter = {
                        nav.navigate("litter") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    })
                }
                composable("litter") { LitterScreen() }
                composable("log") { LogScreen() }
                composable("settings") { SettingsScreen(onOpenConnections = { nav.navigate("settings/connections") }, onOpenPresence = { nav.navigate("settings/presence") }) }
                composable("settings/connections") { ConnectionsScreen() }
                composable("settings/presence") { PresenceScreen() }
            }
        }
    }
}
