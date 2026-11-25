package dev.nish.nessa.ui.litter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.schedule.detail
import dev.nish.nessa.ui.ConnectWhiskerCard
import dev.nish.nessa.ui.ScreenBody
import dev.nish.nessa.ui.appContainer
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun litterViewModel(): LitterViewModel {
    val container = appContainer()
    return viewModel(factory = viewModelFactory { initializer { LitterViewModel(container.litter, container.session) } })
}

/** Refreshes now and every 30 seconds while the screen is visible. */
@Composable
fun KeepLitterFresh(vm: LitterViewModel) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(vm, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh().join()
                delay(30_000)
            }
        }
    }
}

/** Each state has its own fixed color so it reads at a glance, whatever the wallpaper theme. */
@Composable
fun Tone.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        Tone.Good -> if (dark) Color(0xFF6DD58C) else Color(0xFF1B7F3B)
        Tone.Resting -> if (dark) Color(0xFFA9B4FF) else Color(0xFF4250C4)
        Tone.Attention -> if (dark) Color(0xFFFFB95C) else Color(0xFF9A5B00)
        Tone.Off, Tone.Offline -> if (dark) Color(0xFFB0B0B8) else Color(0xFF6B6B74)
    }
}

fun Tone.icon(): ImageVector = when (this) {
    Tone.Good -> Icons.Rounded.CheckCircle
    Tone.Resting -> Icons.Rounded.Bedtime
    Tone.Off -> Icons.Rounded.PowerSettingsNew
    Tone.Attention -> Icons.Rounded.Warning
    Tone.Offline -> Icons.Rounded.CloudOff
}

/** A card tinted with the state's color, with the state's icon beside the headline. */
@Composable
fun StatusCard(
    label: String,
    headline: String,
    detail: String?,
    tone: Tone,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val color = tone.color()
    // Off is the one state where the whole card goes dark, so it reads as "switched off" at a glance.
    val container = if (tone == Tone.Off) MaterialTheme.colorScheme.surfaceContainerLowest
    else color.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
    val animated by animateColorAsState(container, label = "status card")
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = animated)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(tone.icon(), contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
                Text(headline, style = MaterialTheme.typography.displaySmall, color = color)
            }
            detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LitterScreen() {
    val vm = litterViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    KeepLitterFresh(vm)

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh(showSpinner = true) }, modifier = Modifier.fillMaxSize()) {
        ScreenBody {
            when (val s = state) {
                LitterState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading…", style = MaterialTheme.typography.bodyLarge)
                }
                LitterState.NotConnected -> ConnectWhiskerCard()
                LitterState.NoRobot -> InfoCard("No Litter-Robot found on this account", "Set one up in the Whisker app.")
                is LitterState.Unavailable -> InfoCard(s.problem, "Pull down to retry.")
                is LitterState.Ready -> ReadyContent(s, vm)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(s: LitterState.Ready, vm: LitterViewModel) {
    val manager = appContainer().manager
    val managing by manager.state.collectAsStateWithLifecycle()
    val managerStatus by manager.status.collectAsStateWithLifecycle()
    val decision = managerStatus.decision.takeIf { managing.needsChecks }
    val status = litterStatusUi(s.robot, Instant.now(), reason = decision?.detail(), shouldRest = decision?.rest)
    val cleaning by vm.cleaning.collectAsStateWithLifecycle()
    val schedule = scheduleViewModel()
    val scheduleBusy by schedule.busy.collectAsStateWithLifecycle()
    var pickSleep by rememberSaveable { mutableStateOf(false) }
    var confirmPowerOff by rememberSaveable { mutableStateOf(false) }
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmClean by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(message) { if (message != null) { delay(4000); vm.clearMessage() } }

    if (s.stale) {
        Text(
            "${s.problem ?: "Couldn't reach Whisker"} · showing last known",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val poweredOff = managing.powerOff != null
    val sleepingNow = managing.sleepNow != null
    val off = poweredOff || status.headline == "Off"
    StatusCard(
        s.robot.name ?: "Litter-Robot", status.headline, status.detail, status.tone,
        trailing = {
            PowerKey(
                on = !off,
                busy = scheduleBusy,
                enabled = status.tone != Tone.Offline,
                onToggle = { if (off) schedule.powerOn() else confirmPowerOff = true },
            )
        },
    ) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { confirmClean = true },
                enabled = !cleaning && !off && status.tone != Tone.Offline,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (cleaning) "Starting…" else "Clean now", maxLines = 1)
            }
            FilledTonalButton(
                onClick = { if (sleepingNow) schedule.endSleepNow() else pickSleep = true },
                enabled = !scheduleBusy && !off && status.tone != Tone.Offline,
                modifier = Modifier.weight(1f),
            ) {
                Icon(if (sleepingNow) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (sleepingNow) "Wake up" else "Sleep now", maxLines = 1)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    if (pickSleep) DurationDialog("Sleep for…", "Until I wake it", "Sleep", "Ignores presence and the schedule until then.", onDismiss = { pickSleep = false }) { hours -> pickSleep = false; schedule.sleepNow(hours) }
    if (confirmPowerOff) {
        AlertDialog(
            onDismissRequest = { confirmPowerOff = false },
            title = { Text("Turn the robot off?") },
            text = { Text("Nothing is cleaned or recorded until you turn it back on.") },
            confirmButton = { TextButton(onClick = { confirmPowerOff = false; schedule.powerOff() }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { confirmPowerOff = false }) { Text("Cancel") } },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            status.drawerPercent?.let { pct ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Waste drawer", style = MaterialTheme.typography.bodyLarge)
                        Text("$pct% full", style = MaterialTheme.typography.bodyLarge)
                    }
                    LinearProgressIndicator(progress = { pct.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            status.litterLabel?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Litter level", style = MaterialTheme.typography.bodyLarge)
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    ScheduleSection(dev.nish.nessa.data.LitterRepository.zoneOf(s.robot))

    Column {
        Row(
            Modifier.fillMaxWidth().clickable { showDetails = !showDetails }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Details", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (showDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(showDetails) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailRow("Updated", relativeTime(s.fetchedAt, Instant.now()))
                DetailRow("Serial ends in", s.robot.serial.takeLast(4))
                DetailRow("Robot says", s.robot.state?.state ?: "Unknown")
                DetailRow("Cleaning cycles so far", s.robot.state?.odometerCleanCycles?.toString() ?: "Unknown")
                DetailRow("Wi-Fi signal", s.robot.state?.wifiRssi?.let { "$it dBm" } ?: "Unknown")
            }
        }
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("Start a cleaning cycle now?") },
            text = { Text("Make sure Nessa isn't inside.") },
            confirmButton = { TextButton(onClick = { confirmClean = false; vm.cleanNow() }) { Text("Clean now") } },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Pick how long Sleep now or Power off lasts: a number of hours, or until undone (null). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationDialog(title: String, foreverLabel: String, confirm: String, note: String, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(12) }
    var forever by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4, 8, 12).forEach { h ->
                        FilterChip(selected = !forever && choice == h, onClick = { forever = false; choice = h }, label = { Text("$h h") })
                    }
                }
                FilterChip(selected = forever, onClick = { forever = true }, label = { Text(foreverLabel) })
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(if (forever) null else choice) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A round power key, like the robot's own button: it glows green when on, goes dark when off, and sinks
 * with a short click when pressed.
 */
@Composable
private fun PowerKey(on: Boolean, busy: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "press")
    val glow = Tone.Good.color()
    val tint by animateColorAsState(if (on) glow else MaterialTheme.colorScheme.outline, label = "power tint")
    val glowAlpha by animateFloatAsState(if (on) 0.35f else 0f, label = "glow")
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = glowAlpha), Color.Transparent)), radius = size.minDimension * 0.75f)
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.5.dp, tint.copy(alpha = 0.7f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !busy, role = Role.Switch) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
            .semantics { stateDescription = if (on) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = tint)
        else Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (on) "Turn off" else "Turn on", tint = tint, modifier = Modifier.size(26.dp))
    }
}
