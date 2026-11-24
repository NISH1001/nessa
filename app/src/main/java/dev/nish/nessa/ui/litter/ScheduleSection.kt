package dev.nish.nessa.ui.litter

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nish.nessa.schedule.DAY_MINUTES
import dev.nish.nessa.schedule.Override
import dev.nish.nessa.schedule.Seg
import dev.nish.nessa.schedule.WeekSchedule
import dev.nish.nessa.schedule.Window
import dev.nish.nessa.schedule.formatMinute
import dev.nish.nessa.schedule.rangeText
import dev.nish.nessa.ui.appContainer
import dev.nish.nessa.ui.settings.label
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val DAY_FULL = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

@Composable
fun scheduleViewModel(): ScheduleViewModel {
    val container = appContainer()
    val app = LocalContext.current.applicationContext as Application
    return viewModel(factory = viewModelFactory { initializer { ScheduleViewModel(app, container.manager) { container.litter.refresh() } } })
}

/** Everything about when the robot rests: the master switch, where you are, and the weekly windows. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleSection(zone: ZoneId) {
    val vm = scheduleViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val presence by appContainer().presence.state.collectAsStateWithLifecycle()

    val today = Instant.now().atZone(zone).dayOfWeek.value % 7
    var day by rememberSaveable { mutableIntStateOf(today) }
    var editing by remember { mutableStateOf<Window?>(null) }
    var copying by rememberSaveable { mutableStateOf(false) }
    var confirmOn by rememberSaveable { mutableStateOf(false) }

    // --- master switch and what's happening now ---
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Manage sleep", style = MaterialTheme.typography.titleMedium)
                }
                if (busy) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                Switch(checked = state.enabled, enabled = !busy, onCheckedChange = { if (it) confirmOn = true else vm.setEnabled(false) })
            }
            if (state.enabled) {
                status.nextCheckAt?.let {
                    Text(
                        "Next check ${DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(it.atZone(zone))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status.problem?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.check() }, enabled = !busy) { Text("Try again") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("I am", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(Override.Auto to "Auto", Override.Home to "Home", Override.Away to "Away")
                    options.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = state.override == value,
                            enabled = !busy,
                            onClick = { vm.setOverride(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            icon = {}, // no check mark: it squeezes the label
                        ) { Text(label, maxLines = 1) }
                    }
                }
                if (presence.config.enabled && state.override == Override.Auto) {
                    Text("Auto: ${presence.presence.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // --- the week ---
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sleep schedule", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DAY_SHORT.forEachIndexed { i, label ->
                    val selected = i == day
                    Column(
                        Modifier.clickable { day = i }.padding(vertical = 4.dp).width(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(
                            Modifier.width(24.dp),
                            thickness = 3.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                        )
                    }
                }
            }
            Text(
                DAY_FULL[day] + if (day == today) " · today" else "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DayBar(state.schedule.segments(day), state.schedule.segments(day, onlyEvenWhenHome = true))

            val windows = state.schedule.days[day]
            if (windows.isEmpty()) {
                Text("No sleep windows", style = MaterialTheme.typography.bodyMedium)
            }
            windows.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = w }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (w.evenWhenHome) Icons.Rounded.Bedtime else Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.padding(horizontal = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(w.name, style = MaterialTheme.typography.bodyLarge)
                        Text(w.rangeText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (w.evenWhenHome) {
                            Text("Even when home", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit ${w.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = { editing = Window(WeekSchedule.newId(), "", 9 * 60, 17 * 60) }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add window")
                }
                TextButton(enabled = !busy && windows.isNotEmpty(), onClick = { copying = true }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy to other days")
                }
            }
        }
    }

    editing?.let { w ->
        val isNew = state.schedule.days[day].none { it.id == w.id }
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            WindowEditor(
                window = w,
                dayName = DAY_FULL[day],
                isNew = isNew,
                onSave = { vm.saveWindow(day, it); editing = null },
                onDelete = { vm.deleteWindow(day, w.id); editing = null },
            )
        }
    }

    if (copying) CopyDialog(day, onDismiss = { copying = false }, onCopy = { vm.copyDay(day, it); copying = false })

    if (confirmOn) {
        AlertDialog(
            onDismissRequest = { confirmOn = false },
            title = { Text("Manage sleep?") },
            text = { Text("Replaces the robot's sleep schedule. Turning this off restores the old one.") },
            confirmButton = { TextButton(onClick = { confirmOn = false; vm.setEnabled(true) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { confirmOn = false }) { Text("Not now") } },
        )
    }
}

/** The day at a glance: resting time shaded, quiet time (applies even at home) darker. */
@Composable
private fun DayBar(all: List<Seg>, quiet: List<Seg>) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rest = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val quietColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            fun draw(segs: List<Seg>, color: androidx.compose.ui.graphics.Color) = segs.forEach {
                val x = size.width * it.start / DAY_MINUTES
                val w = size.width * (it.end - it.start) / DAY_MINUTES
                drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = radius)
            }
            draw(all, rest)
            draw(quiet, quietColor)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12 AM", "6 AM", "Noon", "6 PM", "12 AM").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WindowEditor(window: Window, dayName: String, isNew: Boolean, onSave: (Window) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(window.name) }
    var start by remember { mutableIntStateOf(window.startMin) }
    var end by remember { mutableIntStateOf(window.endMin) }
    var evenWhenHome by remember { mutableStateOf(window.evenWhenHome) }
    var picking by remember { mutableStateOf<String?>(null) }
    val draft = window.copy(name = name.trim(), startMin = start, endMin = end, evenWhenHome = evenWhenHome)

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (isNew) "New sleep window · $dayName" else "Edit · $dayName", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Work", "Bedtime", "Gym", "Errands", "Trip").forEach { suggestion ->
                FilterChip(selected = name == suggestion, onClick = { name = suggestion; if (suggestion == "Bedtime") evenWhenHome = true }, label = { Text(suggestion) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) { Text("From ${formatMinute(start)}") }
            OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) { Text("To ${formatMinute(end)}") }
        }
        Text(draft.rangeText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Even when home", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = evenWhenHome, onCheckedChange = { evenWhenHome = it })
        }
        draft.problem()?.takeIf { name.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(draft) }, enabled = draft.problem() == null, modifier = Modifier.weight(1f)) { Text("Save") }
            if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }

    picking?.let { which ->
        val initial = if (which == "start") start else end
        val picker = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == "start") "Sleep from" else "Sleep until") },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    val value = picker.hour * 60 + picker.minute
                    if (which == "start") start = value else end = value
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopyDialog(from: Int, onDismiss: () -> Unit, onCopy: (Set<Int>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy ${DAY_FULL[from]} to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DAY_SHORT.forEachIndexed { i, label ->
                        if (i != from) FilterChip(selected = i in picked, onClick = { picked = if (i in picked) picked - i else picked + i }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onCopy(picked) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
