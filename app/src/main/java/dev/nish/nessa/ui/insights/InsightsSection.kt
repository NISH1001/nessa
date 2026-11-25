package dev.nish.nessa.ui.insights

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.insights.Check
import dev.nish.nessa.insights.InsightsModel
import dev.nish.nessa.insights.Range
import dev.nish.nessa.ui.appContainer
import dev.nish.nessa.ui.litter.Tone
import dev.nish.nessa.ui.litter.color
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

private fun duration(sec: Int): String = if (sec < 60) "$sec sec" else "${(sec + 30) / 60} min"

/**
 * The insight cards for the Home tab. Emits its cards straight into the caller's scrolling column so a
 * Health watch flag can scroll to its card.
 */
@Composable
fun InsightsSection(scroll: ScrollState) {
    val container = appContainer()
    val vm: InsightsViewModel = viewModel(factory = viewModelFactory { initializer { InsightsViewModel(container.history, container.litter) } })
    val model by vm.model.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val litter by container.litter.state.collectAsStateWithLifecycle()
    val zone = (litter as? LitterState.Ready)?.robot?.let(LitterRepository::zoneOf) ?: ZoneId.systemDefault()
    val positions = remember { mutableMapOf<Check, Float>() }
    val scope = rememberCoroutineScope()
    val amber = Tone.Attention.color()
    val colors = chartColors(amber)
    fun Modifier.anchor(check: Check) = onGloballyPositioned { positions[check] = it.positionInParent().y }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Range.Week to "7 days", Range.Month to "30 days", Range.Quarter to "90 days", Range.All to "All").forEach { (r, label) ->
            FilterChip(selected = range == r, onClick = { vm.setRange(r) }, label = { Text(label) })
        }
    }

    val m = model ?: return Text("Loading…", style = MaterialTheme.typography.bodyLarge)

    HealthCard(m, amber) { check -> positions[check]?.let { y -> scope.launch { scroll.animateScrollTo(y.toInt()) } } }
    WeightCard(m, range, zone, colors, Modifier.anchor(Check.Weight))
    VisitsCard(m, colors, Modifier.anchor(Check.Visits).anchor(Check.NoVisits))
    TimeInBoxCard(m, range, zone, colors)
    HoursCard(m, colors)
    RobotCard(m, amber, Modifier.anchor(Check.Robot))
}

@Composable
private fun InsightCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Headline(big: String, small: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(big, style = MaterialTheme.typography.headlineMedium)
        small?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Empty(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)

@Composable
private fun HealthCard(m: InsightsModel, amber: Color, onOpen: (Check) -> Unit) {
    InsightCard("Health watch") {
        if (m.health.learning) {
            Text("Learning her usual", style = MaterialTheme.typography.titleMedium)
            Empty("Needs 2 weeks of history")
            return@InsightCard
        }
        val flags = m.health.items.count { !it.ok }
        Text(
            if (flags == 0) "All normal" else "$flags to look at",
            style = MaterialTheme.typography.titleMedium,
            color = if (flags == 0) Tone.Good.color() else amber,
        )
        m.health.items.forEach { item ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(item.check) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (item.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = if (item.ok) Tone.Good.color() else amber,
                    modifier = Modifier.size(18.dp),
                )
                Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Where a time falls across the range, 0..1. */
private fun spanOf(range: Range, zone: ZoneId, first: Instant?): Pair<Instant, Instant> {
    val end = Instant.now()
    val start = range.days?.let { LocalDate.now(zone).minusDays(it - 1L).atStartOfDay(zone).toInstant() } ?: (first ?: end.minusSeconds(86_400))
    return start to end
}

private fun dateLabels(start: Instant, end: Instant, zone: ZoneId): List<Pair<Float, String>> =
    listOf(0f, 0.5f, 1f).map { f -> f to DAY.format(start.plusMillis(((end.toEpochMilli() - start.toEpochMilli()) * f).toLong()).atZone(zone)) }

@Composable
private fun WeightCard(m: InsightsModel, range: Range, zone: ZoneId, colors: ChartColors, modifier: Modifier) {
    val w = m.weight
    InsightCard("Weight", modifier) {
        Headline(w.usualLb?.let { String.format(Locale.US, "%.1f lb", it) } ?: "–", "median")
        if (w.points.isEmpty()) return@InsightCard Empty("No readings yet")
        val (start, end) = spanOf(range, zone, w.points.first().at)
        val span = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1).toFloat()
        val center = w.usualLb ?: w.points.map { it.lb }.average()
        val lo = (center * 0.6).toFloat()
        val hi = (center * 1.4).toFloat()
        DotChart(
            dots = w.points.map {
                ChartDot(
                    x = ((it.at.toEpochMilli() - start.toEpochMilli()) / span).coerceIn(0f, 1f),
                    y = it.lb.toFloat(),
                    hollow = it.outlier,
                    label = String.format(Locale.US, "%s · %.1f lb%s", DAY_TIME.format(it.at.atZone(zone)), it.lb, if (it.outlier) " · ignored" else ""),
                )
            },
            guide = w.usualLb?.toFloat(),
            guideLabel = w.usualLb?.let { String.format(Locale.US, "median %.1f", it) },
            yMin = lo, yMax = hi,
            yTicks = listOf(lo, (lo + hi) / 2, hi),
            yLabel = { String.format(Locale.US, "%.0f", it) },
            xLabels = dateLabels(start, end, zone),
            colors = colors,
        )
        Text("Hollow dots are readings far from her usual and don't count.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun VisitsCard(m: InsightsModel, colors: ChartColors, modifier: Modifier) {
    val v = m.visits
    InsightCard("Visits per day", modifier) {
        Headline("${v.today}", v.usual?.let { "today · usual ${trim(it)}" } ?: "today")
        if (v.returnTrips > 0) {
            Text(
                "Return trips today: ${v.returnTrips} · ${v.returnAfterCleaning} after a cleaning",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (v.days.isEmpty()) return@InsightCard Empty("No visits yet")
        val top = maxOf(4.0, v.days.maxOf { it.count }.toDouble(), (v.usual ?: 0.0) * 2).let { ceil(it).toFloat() }
        BarChart(
            bars = v.days.map { ChartBar(it.count.toFloat(), it.busy, "${DAY.format(it.day)} · ${it.count} ${if (it.count == 1) "visit" else "visits"}${if (it.busy) " · well above usual" else ""}") },
            guide = v.usual?.toFloat(),
            guideLabel = v.usual?.let { "usual ${trim(it)}" },
            yMax = top,
            yTicks = listOf(0f, (top / 2).let { ceil(it) }, top),
            xLabels = listOf(0, v.days.size / 2, v.days.size - 1).distinct().map { it to DAY.format(v.days[it].day) },
            colors = colors,
        )
    }
}

@Composable
private fun TimeInBoxCard(m: InsightsModel, range: Range, zone: ZoneId, colors: ChartColors) {
    val t = m.time
    InsightCard("Time in the box") {
        Headline(t.typicalSec?.let(::duration) ?: "–", t.longestTodaySec?.let { "typical · longest today ${duration(it)}" } ?: "typical")
        if (t.dots.isEmpty()) return@InsightCard Empty("No visits yet")
        val (start, end) = spanOf(range, zone, t.dots.first().at)
        val span = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1).toFloat()
        val top = maxOf(8f, ((t.typicalSec ?: 60) * 3 / 60f)).let { ceil(it) }
        DotChart(
            dots = t.dots.map {
                ChartDot(((it.at.toEpochMilli() - start.toEpochMilli()) / span).coerceIn(0f, 1f), it.seconds / 60f, false, "${DAY_TIME.format(it.at.atZone(zone))} · ${duration(it.seconds)}")
            },
            guide = t.typicalSec?.let { it / 60f },
            guideLabel = "typical",
            yMin = 0f, yMax = top,
            yTicks = listOf(0f, top / 2, top),
            yLabel = { "${it.toInt()}m" },
            xLabels = dateLabels(start, end, zone),
            colors = colors,
            height = 130.dp,
        )
    }
}

@Composable
private fun HoursCard(m: InsightsModel, colors: ChartColors) {
    InsightCard("Usual times") {
        Text(m.hours.busiest?.let { "Most visits $it" } ?: "No visits yet", style = MaterialTheme.typography.bodyLarge)
        if (m.hours.busiest != null) HourStrip(m.hours.counts, colors)
    }
}

@Composable
private fun RobotCard(m: InsightsModel, amber: Color, modifier: Modifier) {
    val r = m.robot
    InsightCard("Robot", modifier) {
        StatRow("Cleanings today", "${r.cleaningsToday}${r.usualCleanings?.let { " · usual ${trim(it)}" } ?: ""}")
        StatRow("Waste drawer", r.drawerPercent?.let { "$it%" } ?: "–")
        r.drawerPercent?.let { LinearProgressIndicator(progress = { it.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth()) }
        StatRow("Drawer full in", r.drawerFullInDays?.let { if (it == 1) "1 day" else "$it days" } ?: "Collecting data", muted = r.drawerFullInDays == null)
        StatRow("Litter", r.litterLabel ?: "–")
        StatRow("Interrupted this week", "${r.interruptedWeek}", warn = r.interruptedWeek > 0, amber = amber)
        StatRow("Safety stops this week", "${r.safetyStopsWeek}", warn = r.safetyStopsWeek > 0, amber = amber)
        r.filterDue?.let { due ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), due)
            StatRow("Filter due", when { days < 0 -> "overdue"; days == 0L -> "today"; days == 1L -> "tomorrow"; else -> "in $days days" }, warn = days <= 0, amber = amber)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, warn: Boolean = false, muted: Boolean = false, amber: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                warn -> amber
                muted -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(Locale.US, "%.1f", d)
