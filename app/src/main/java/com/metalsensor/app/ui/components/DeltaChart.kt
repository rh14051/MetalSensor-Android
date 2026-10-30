package com.metalsensor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.metalsensor.app.ui.ChartPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Live 60-second line chart of Delta, drawn on a plain Compose Canvas.
 * Two horizontal reference lines (THRESHOLD_ON / THRESHOLD_OFF) are drawn
 * so the position of Delta relative to the thresholds is visible at a glance.
 * Where motion detection was active, a faint orange band covers that time
 * range so the correlation between the sensor signal and motion is visible.
 *
 * Time axis: oldest on the left, newest on the right (left-to-right, like any
 * technical chart — independent of the RTL layout).
 *
 * IMPORTANT (crash fix): nothing is ever *measured or drawn as text* inside
 * this Canvas. The previous version drew the threshold labels with drawText
 * during the draw phase; on some devices (notably MIUI) re-measuring text at
 * stream rate while the hardware layer is recording crashed the whole app
 * (stack: TextPainterKt.drawText -> DeltaChart$lambda). The labels now live
 * in a plain legend Row rendered by the parent screen, and the whole draw
 * body is additionally wrapped in a guard so a draw-phase failure can never
 * kill the process — it just skips that frame.
 */
@Composable
fun DeltaChart(
    points: List<ChartPoint>,
    thresholdOn: Float,
    thresholdOff: Float,
    modifier: Modifier = Modifier,
    windowMs: Long = 60_000L,
    showMotion: Boolean = true,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val onColor = Color(0xFFD32F2F)
    val offColor = Color(0xFF43A047)
    // Orange band behind the line where motion was active (~20% opacity).
    val motionBandColor = Color(0xFFF57C00).copy(alpha = 0.20f)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            try {
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

                // ---- motion bands ----
                // Contiguous runs of samples flagged with motion=true get a
                // translucent orange background so the motion signal can be
                // correlated with the delta curve at a glance.
                if (showMotion && visible.isNotEmpty()) {
                    var runStart: Long? = null
                    var runEnd: Long = 0L
                    fun flushRun() {
                        val s = runStart
                        if (s != null) {
                            val x0 = xOf(s)
                            val x1 = xOf(runEnd)
                            if (x1 > x0) {
                                drawRect(
                                    color = motionBandColor,
                                    topLeft = Offset(x0, 0f),
                                    size = androidx.compose.ui.geometry.Size(x1 - x0, h),
                                )
                            }
                        }
                        runStart = null
                    }
                    for (p in visible) {
                        if (p.motion) {
                            if (runStart == null) runStart = p.t
                            runEnd = p.t
                        } else {
                            flushRun()
                        }
                    }
                    flushRun()
                }

                // threshold reference lines (only when the value is parseable)
                if (thresholdOn.isFinite()) {
                    val y = yOf(thresholdOn)
                    drawLine(onColor, Offset(0f, y), Offset(w, y), strokeWidth = 2.5f, cap = StrokeCap.Round)
                }
                if (thresholdOff.isFinite()) {
                    val y = yOf(thresholdOff)
                    drawLine(offColor, Offset(0f, y), Offset(w, y), strokeWidth = 2.5f, cap = StrokeCap.Round)
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
            } catch (t: Throwable) {
                // Draw-phase guard: never let a bad frame crash the app.
            }
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
