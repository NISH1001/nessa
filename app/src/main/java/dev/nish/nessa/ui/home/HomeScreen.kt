package dev.nish.nessa.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.ui.ConnectWhiskerCard
import dev.nish.nessa.schedule.Reason
import dev.nish.nessa.schedule.detail
import dev.nish.nessa.ui.ScreenBody
import dev.nish.nessa.ui.insights.InsightsSection
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.nish.nessa.ui.appContainer
import dev.nish.nessa.ui.presenceWhy
import dev.nish.nessa.ui.settings.label
import dev.nish.nessa.ui.litter.KeepLitterFresh
import dev.nish.nessa.ui.litter.StatusCard
import dev.nish.nessa.ui.litter.color
import dev.nish.nessa.ui.litter.litterStatusUi
import dev.nish.nessa.ui.litter.litterViewModel
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenLitter: () -> Unit) {
    val vm = litterViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    KeepLitterFresh(vm)

    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val historySync = appContainer().historySync
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh(showSpinner = true); scope.launch { historySync.sync() } },
        modifier = Modifier.fillMaxSize(),
    ) {
    ScreenBody(scroll) {
        when (val s = state) {
            LitterState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyLarge)
            LitterState.NotConnected -> ConnectWhiskerCard()
            LitterState.NoRobot -> SimpleCard("Litter", "No Litter-Robot found on this account", onOpenLitter)
            is LitterState.Unavailable -> SimpleCard("Litter", s.problem, onOpenLitter)
            is LitterState.Ready -> {
                val manager = appContainer().manager
                val managing by manager.state.collectAsStateWithLifecycle()
                val managerStatus by manager.status.collectAsStateWithLifecycle()
                val decision = managerStatus.decision.takeIf { managing.enabled }
                // The "You" card above already says home or away, so only show reasons that add something (Bedtime, Work).
                val reason = decision?.takeIf { it.reason != Reason.HomeFree && it.reason != Reason.Away }?.detail()
                val status = litterStatusUi(s.robot, Instant.now(), reason = reason, shouldRest = decision?.rest)
                val presence by appContainer().presence.state.collectAsStateWithLifecycle()
                if (presence.config.enabled) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("You", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(presence.presence.label(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                            Text(presenceWhy(presence), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                StatusCard("Litter box", status.headline, status.detail, status.tone, Modifier.clickable(onClick = onOpenLitter)) {
                    if (s.stale) Text("Couldn't refresh · showing last known", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Nessa today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatRow("Visits", s.todayVisits.toString())
                        StatRow("Last visit", lastVisitText(s))
                        StatRow("Weight", (s.lastVisit?.petWeight?.div(100) ?: status.weightLb)?.let { String.format(Locale.US, "%.1f lb", it) } ?: "Not yet")
                    }
                }
            }
        }
        if (state !is LitterState.NotConnected) InsightsSection(scroll)
    }
    }
}

private fun lastVisitText(s: LitterState.Ready): String {
    val at = LitterRepository.parseInstant(s.lastVisit?.timestamp) ?: return "None yet"
    val zone = LitterRepository.zoneOf(s.robot)
    val day = at.atZone(zone).toLocalDate()
    val today = Instant.now().atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(at.atZone(zone))
    return when (day) {
        today -> time
        today.minusDays(1) -> "Yesterday, $time"
        else -> DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).format(at.atZone(zone))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SimpleCard(title: String, body: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.titleMedium)
        }
    }
}
