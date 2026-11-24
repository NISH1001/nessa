package dev.nish.nessa.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.ui.ConnectWhiskerCard
import dev.nish.nessa.ui.appContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen() {
    val repo = appContainer().litter
    val state by repo.state.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        problem = repo.refreshHistory()
        loaded = true
    }
    // Reload when the tab opens and whenever the connection or robot changes.
    LaunchedEffect((state as? LitterState.Ready)?.robot?.serial, state is LitterState.NotConnected) { load() }

    val zone = (state as? LitterState.Ready)?.robot?.let(LitterRepository::zoneOf) ?: ZoneId.systemDefault()
    val actionLog = appContainer().actions
    val actions by actionLog.entries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { actionLog.load() }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val days = remember(history, actions, zone, filter) { logDays(history, zone, Instant.now(), actions).only(filter?.let(LogSource::valueOf)) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; try { load() } finally { refreshing = false } } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "All", LogSource.Nessa.name to "Nessa", LogSource.Whisker.name to "Whisker").forEach { (value, label) ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
                    }
                }
            }
            if (state is LitterState.NotConnected) item { ConnectWhiskerCard() }
            problem?.takeIf { state !is LitterState.NotConnected }?.let {
                item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (!loaded && days.isEmpty()) item { Text("Loading…", style = MaterialTheme.typography.bodyLarge) }
            if (loaded && days.isEmpty() && problem == null) item { Text("Nothing yet", style = MaterialTheme.typography.bodyLarge) }

            days.forEach { day ->
                item(key = "h-${day.label}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(day.label, style = MaterialTheme.typography.titleMedium)
                        Text(day.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(day.items) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun LogRow(item: LogItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.time, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            when (item.kind) {
                LogKind.Visit -> Icons.Rounded.Pets
                LogKind.Cleaning -> Icons.Rounded.CleaningServices
                LogKind.Attention -> Icons.Rounded.Warning
                LogKind.Other -> Icons.Rounded.Info
                LogKind.Nessa -> Icons.Rounded.AutoAwesome
                LogKind.NessaProblem -> Icons.Rounded.Warning
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = when (item.kind) {
                LogKind.Visit -> MaterialTheme.colorScheme.primary
                LogKind.Attention, LogKind.NessaProblem -> MaterialTheme.colorScheme.error
                LogKind.Nessa -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(
            item.source.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (item.source == LogSource.Nessa) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
