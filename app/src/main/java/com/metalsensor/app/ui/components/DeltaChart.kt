package com.metalsensor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsensor.app.ui.ChartPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Live 60-second line chart of Delta, drawn on a plain Compose Canvas.
 * Two horizontal reference lines (THRESHOLD_ON / THRESHOLD_OFF) are drawn
 * so the position of Delta relative to the thresholds is visible at a glance.
 *
 * Time axis: oldest on the left, newest on the right (left-to-right, like any
 * technical chart — independent of the RTL layout).
 */
@Composable
fun DeltaChart(
    points: List<ChartPoint>,
    thresholdOn: Float,
    thresholdOff: Float,
    modifier: Modifier = Modifier,
    windowMs: Long = 60_000L,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val onColor = Color(0xFFD32F2F)
    val offColor = Color(0xFF43A047)

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(labelColor) { TextStyle(fontSize = 10.sp, color = labelColor) }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val windowEnd = points.lastOrNull()?.t ?: 0L
            val windowStart = windowEnd - windowMs

            // Only finite, in-window samples are plotted; a single NaN/Infinity
            // sample must never poison the Y scale (defensive hardening).
            val visible = points.filter { it.t >= windowStart && it.v.isFinite() }
            val dataMax = visible.maxOfOrNull { it.v } ?: 0f
            val dataMin = visible.minOfOrNull { it.v } ?: 0f

            // NaN/Infinity thresholds (unparseable fields) must not poison the Y scale
            val refCandidates = buildList {
                if (thresholdOn.isFinite()) add(thresholdOn)
                if (thresholdOff.isFinite()) add(thresholdOff)
                if (dataMax.isFinite()) add(dataMax)
            }
            val refMax = refCandidates.maxOrNull() ?: 0f
            val top = if (refMax.isFinite()) max(refMax * 1.15f, 1f) else 1f
            val bottom = if (dataMin.isFinite()) min(0f, dataMin) else 0f
            val ySpan = ((top - bottom).takeIf { it.isFinite() && it > 0.001f }) ?: 1f

            fun xOf(t: Long): Float = (t - windowStart).toFloat() / windowMs.toFloat() * w
            fun yOf(v: Float): Float = h - ((v - bottom) / ySpan) * h

            // horizontal grid
            val gridLines = 4
            for (i in 0..gridLines) {
                val y = h * i / gridLines.toFloat()
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            // vertical grid every 10 s
            val stepMs = 10_000L
            var tMark = windowStart + stepMs - (windowStart.mod(stepMs))
            while (tMark <= windowEnd) {
                val x = xOf(tMark)
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                tMark += stepMs
            }

            // threshold reference lines (only when the value is parseable)
            if (!thresholdOn.isNaN()) {
                val y = yOf(thresholdOn)
                drawLine(onColor, Offset(0f, y), Offset(w, y), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawText(
                    textMeasurer,
                    "ON $thresholdOn",
                    style = labelStyle,
                    topLeft = Offset(6.dp.toPx(), (y - 18.dp.toPx()).coerceAtLeast(0f)),
                )
            }
            if (!thresholdOff.isNaN()) {
                val y = yOf(thresholdOff)
                drawLine(offColor, Offset(0f, y), Offset(w, y), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawText(
                    textMeasurer,
                    "OFF $thresholdOff",
                    style = labelStyle,
                    topLeft = Offset(6.dp.toPx(), y + 4.dp.toPx()),
                )
            }

            // data series
            if (visible.isNotEmpty()) {
                val path = Path()
                visible.forEachIndexed { i, p ->
                    val x = xOf(p.t)
                    val y = yOf(p.v)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    color = lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // time labels
            drawText(textMeasurer, "0s", style = labelStyle, topLeft = Offset(w - 26.dp.toPx(), 2.dp.toPx()))
            drawText(textMeasurer, "-60s", style = labelStyle, topLeft = Offset(2.dp.toPx(), 2.dp.toPx()))
        }

        if (points.isEmpty()) {
            Text(
                "در انتظار داده…",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
