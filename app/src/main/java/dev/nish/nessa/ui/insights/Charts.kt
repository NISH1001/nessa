package dev.nish.nessa.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** One mark on a chart. [x] runs 0..1 across the plot. [label] is what a tap shows. */
data class ChartDot(val x: Float, val y: Float, val hollow: Boolean, val label: String)
data class ChartBar(val y: Float, val highlight: Boolean, val label: String)

/** Colors for a chart, taken from the app theme by the caller. */
data class ChartColors(val mark: Color, val highlight: Color, val muted: Color, val grid: Color, val ink: Color, val surface: Color)

@Composable
fun chartColors(highlight: Color): ChartColors = ChartColors(
    mark = MaterialTheme.colorScheme.primary,
    highlight = highlight,
    muted = MaterialTheme.colorScheme.outline,
    grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    ink = MaterialTheme.colorScheme.onSurfaceVariant,
    surface = MaterialTheme.colorScheme.surfaceContainerHighest,
)

private val LEFT = 34.dp
private val BOTTOM = 18.dp
private val TOP = 6.dp

/** The line above a chart that shows the tapped mark, or a hint. */
@Composable
private fun Readout(text: String?) {
    Text(
        text ?: "Tap or drag for details",
        style = MaterialTheme.typography.bodySmall,
        color = if (text == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 18.dp),
    )
}

/**
 * Dots over time with an optional dashed guide (the median). Hollow dots are left out of the guide.
 * Values outside [yMin]..[yMax] are pinned to the edge so they still show.
 */
@Composable
fun DotChart(
    dots: List<ChartDot>,
    guide: Float?,
    guideLabel: String?,
    yMin: Float,
    yMax: Float,
    yTicks: List<Float>,
    yLabel: (Float) -> String,
    xLabels: List<Pair<Float, String>>,
    colors: ChartColors,
    height: Dp = 150.dp,
) {
    var selected by remember(dots) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 10.sp, color = colors.ink)
    Column {
        Readout(selected?.let { dots.getOrNull(it)?.label })
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(dots) {
                    fun pick(o: Offset) {
                        val left = LEFT.toPx()
                        val w = size.width - left - 6.dp.toPx()
                        val h = size.height - BOTTOM.toPx() - TOP.toPx()
                        selected = dots.indices.minByOrNull { i ->
                            val d = dots[i]
                            val dx = left + d.x * w - o.x
                            val dy = TOP.toPx() + (1 - (d.y.coerceIn(yMin, yMax) - yMin) / (yMax - yMin)) * h - o.y
                            dx * dx + dy * dy * 0.25f
                        }
                    }
                    detectTapGestures { pick(it) }
                }
                .pointerInput(dots) {
                    detectHorizontalDragGestures { change, _ ->
                        val left = LEFT.toPx()
                        val x = ((change.position.x - left) / (size.width - left - 6.dp.toPx())).coerceIn(0f, 1f)
                        selected = dots.indices.minByOrNull { abs(dots[it].x - x) }
                    }
                },
        ) {
            val left = LEFT.toPx()
            val plotH = size.height - BOTTOM.toPx() - TOP.toPx()
            fun y(v: Float) = TOP.toPx() + (1 - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin)) * plotH
            fun x(f: Float) = left + f * (size.width - left - 6.dp.toPx())
            grid(yTicks, ::y, yLabel, left, colors, measurer, small)
            xAxis(xLabels, ::x, colors, measurer, small)
            dots.forEachIndexed { i, d ->
                val c = Offset(x(d.x), y(d.y))
                val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
                if (d.hollow) drawCircle(colors.muted, r, c, style = Stroke(1.5.dp.toPx()))
                else drawCircle(colors.mark, r, c)
            }
            guide?.let { guideLine(y(it), guideLabel, left, colors, measurer, small) }
            selected?.let { i -> dots.getOrNull(i)?.let { drawLine(colors.ink.copy(alpha = 0.4f), Offset(x(it.x), TOP.toPx()), Offset(x(it.x), TOP.toPx() + plotH), 1.dp.toPx()) } }
        }
    }
}

/** One bar per day, a dashed guide (the usual), highlighted bars in [ChartColors.highlight]. */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    guide: Float?,
    guideLabel: String?,
    yMax: Float,
    yTicks: List<Float>,
    xLabels: List<Pair<Int, String>>,
    colors: ChartColors,
    height: Dp = 130.dp,
) {
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 10.sp, color = colors.ink)
    Column {
        Readout(selected?.let { bars.getOrNull(it)?.label })
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(bars) {
                    fun pick(px: Float) {
                        val left = LEFT.toPx()
                        if (bars.isEmpty()) return
                        selected = (((px - left) / (size.width - left)) * bars.size).toInt().coerceIn(0, bars.size - 1)
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(bars) {
                    detectHorizontalDragGestures { change, _ ->
                        val left = LEFT.toPx()
                        if (bars.isNotEmpty()) selected = (((change.position.x - left) / (size.width - left)) * bars.size).toInt().coerceIn(0, bars.size - 1)
                    }
                },
        ) {
            val left = LEFT.toPx()
            val plotH = size.height - BOTTOM.toPx() - TOP.toPx()
            val slot = (size.width - left) / bars.size.coerceAtLeast(1)
            fun y(v: Float) = TOP.toPx() + (1 - v.coerceIn(0f, yMax) / yMax) * plotH
            fun cx(i: Int) = left + (i + 0.5f) * slot
            grid(yTicks, ::y, { it.toInt().toString() }, left, colors, measurer, small)
            xLabels.forEach { (i, text) -> label(measurer, text, small, cx(i), size.height - BOTTOM.toPx() + 3.dp.toPx(), center = true) }
            val bw = (slot * 0.62f).coerceAtMost(18.dp.toPx())
            bars.forEachIndexed { i, b ->
                val top = y(b.y)
                val base = y(0f)
                if (base - top > 0.5f) {
                    val color = if (b.highlight) colors.highlight else colors.mark
                    drawRoundRect(
                        color.copy(alpha = if (selected == null || selected == i) 1f else 0.55f),
                        Offset(cx(i) - bw / 2, top), Size(bw, base - top), CornerRadius(3.dp.toPx()),
                    )
                }
            }
            guide?.let { guideLine(y(it), guideLabel, left, colors, measurer, small) }
        }
    }
}

/** 24 cells, one per hour; darker means more visits. */
@Composable
fun HourStrip(counts: IntArray, colors: ChartColors) {
    var selected by remember(counts) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 10.sp, color = colors.ink)
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Readout(selected?.let { h -> "${hourName(h)} · ${counts[h]} ${if (counts[h] == 1) "visit" else "visits"}" })
        Canvas(
            Modifier.fillMaxWidth().height(50.dp)
                .pointerInput(counts) { detectTapGestures { selected = (it.x / size.width * 24).toInt().coerceIn(0, 23) } }
                .pointerInput(counts) { detectHorizontalDragGestures { c, _ -> selected = (c.position.x / size.width * 24).toInt().coerceIn(0, 23) } },
        ) {
            val cell = size.width / 24
            val h = 28.dp.toPx()
            counts.forEachIndexed { i, c ->
                val color = if (c == 0) colors.grid else colors.mark.copy(alpha = 0.25f + 0.75f * c / max)
                drawRoundRect(color, Offset(i * cell + 1.5f, 0f), Size(cell - 3f, h), CornerRadius(4.dp.toPx()))
                if (i == selected) drawRoundRect(colors.ink, Offset(i * cell + 1.5f, 0f), Size(cell - 3f, h), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            listOf(0 to "12 AM", 6 to "6 AM", 12 to "Noon", 18 to "6 PM").forEach { (i, t) -> label(measurer, t, small, i * cell, h + 4.dp.toPx()) }
        }
    }
}

fun hourName(h: Int): String = "${(h % 12).let { if (it == 0) 12 else it }} ${if (h < 12) "AM" else "PM"}"

private fun DrawScope.grid(ticks: List<Float>, y: (Float) -> Float, text: (Float) -> String, left: Float, colors: ChartColors, m: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    ticks.forEach { t ->
        drawLine(colors.grid, Offset(left, y(t)), Offset(size.width, y(t)), 1.dp.toPx())
        val layout = m.measure(text(t), style)
        drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y(t) - layout.size.height / 2))
    }
}

private fun DrawScope.xAxis(labels: List<Pair<Float, String>>, x: (Float) -> Float, colors: ChartColors, m: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    labels.forEach { (f, t) -> label(m, t, style, x(f), size.height - BOTTOM.toPx() + 3.dp.toPx(), center = true) }
}

/** The dashed guide, drawn over the marks, with its label on a solid pill at the left so dots never hide it. */
private fun DrawScope.guideLine(yPx: Float, text: String?, left: Float, colors: ChartColors, m: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    drawLine(colors.ink, Offset(left, yPx), Offset(size.width, yPx), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    if (text != null) {
        val layout = m.measure(text, style.copy(color = colors.ink, fontSize = 11.sp))
        val padX = 6.dp.toPx()
        val padY = 2.dp.toPx()
        val w = layout.size.width + padX * 2
        val h = layout.size.height + padY * 2
        val top = (yPx - h / 2).coerceIn(0f, size.height - h)
        val x = left + 4.dp.toPx()
        drawRoundRect(colors.surface, Offset(x, top), Size(w, h), CornerRadius(h / 2))
        drawRoundRect(colors.ink.copy(alpha = 0.5f), Offset(x, top), Size(w, h), CornerRadius(h / 2), style = Stroke(1.dp.toPx()))
        drawText(layout, topLeft = Offset(x + padX, top + padY))
    }
}

private fun DrawScope.label(m: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, x: Float, y: Float, center: Boolean = false) {
    val layout = m.measure(text, style)
    val left = if (center) (x - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width) else x.coerceAtMost(size.width - layout.size.width)
    drawText(layout, topLeft = Offset(left, y))
}
