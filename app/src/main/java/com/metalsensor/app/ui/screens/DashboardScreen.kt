package com.metalsensor.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsensor.app.ui.ConnState
import com.metalsensor.app.ui.MainViewModel
import com.metalsensor.app.ui.components.ConnectionStatusBar
import com.metalsensor.app.ui.components.DeltaChart
import com.metalsensor.app.ui.components.StateBadge
import com.metalsensor.app.ui.theme.WarnOrange
import kotlin.math.abs

/**
 * Main dashboard: live delta, state badge, baseline, 60s chart with the two
 * threshold reference lines, raw X/Y/Z section, RAW switch, SHOW button and
 * CSV recording controls.
 */
@Composable
fun DashboardScreen(vm: MainViewModel, onOpenTuning: () -> Unit) {
    val connState by vm.connState.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val reconnectAttempt by vm.reconnectAttempt.collectAsState()
    val delta by vm.delta.collectAsState()
    val sensorState by vm.sensorState.collectAsState()
    val baseline by vm.baseline.collectAsState()
    val raw by vm.raw.collectAsState()
    val rawEnabled by vm.rawEnabled.collectAsState()
    val params by vm.params.collectAsState()
    val chartPoints by vm.chartPoints.collectAsState()
    val recording by vm.recording.collectAsState()
    val recordingFile by vm.recordingFile.collectAsState()
    val motion by vm.motion.collectAsState()
    val motionCount by vm.motionCount.collectAsState()
    val showMotionOnChart by vm.showMotionOnChart.collectAsState()

    var rawExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Keep all content away from the status bar / punch-hole camera on
            // top and the home & back gesture area on the bottom.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionStatusBar(
            state = connState,
            deviceName = deviceName,
            reconnectAttempt = reconnectAttempt,
            onDisconnect = { vm.disconnect() },
            onCancelReconnect = { vm.cancelReconnect() },
        )

        if (connState != ConnState.CONNECTED) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (connState == ConnState.RECONNECTING)
                            "اتصال برقرار نیست؛ تلاش خودکار برای اتصال مجدد در جریان است…"
                        else
                            "اتصال با دستگاه برقرار نیست.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (connState == ConnState.DISCONNECTED) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.connectSaved() }) { Text("اتصال مجدد") }
                    }
                }
            }
        }

        // ---- Delta card ----
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "دلتای فعلی",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The font shrinks for long numbers (e.g. INT32 artifacts sent by a
                // misbehaving sensor) so the value never overflows the card width.
                val deltaText = delta.toString()
                val deltaFontSize = when (deltaText.length) {
                    in 0..6 -> 56
                    in 7..8 -> 40
                    else -> 28
                }
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        deltaText,
                        fontSize = deltaFontSize.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // Motion indicator, always visible in this card (no scroll):
                    // blinking orange badge while motion is active, faint neutral
                    // icon otherwise. Left side of the number in the RTL layout.
                    MotionIndicator(
                        active = motion,
                        count = motionCount,
                        onReset = { vm.resetMotionCount() },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                if (abs(delta) > 70000) {
                    // Real delta from an int16 magnetometer can never exceed ±65534;
                    // anything bigger is a firmware/sensor artifact (e.g. a failed
                    // float->int conversion). Show a hint instead of a raw huge number.
                    Text(
                        "مقدار دلتا غیرعادی است — احتمال خطای سنسور یا ارتباط آن",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarnOrange,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                StateBadge(sensorState)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Baseline: ${baseline.x}, ${baseline.y}, ${baseline.z}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Chart card ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "نمودار دلتا (60 ثانیه اخیر)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenTuning) { Text("تنظیم پارامترها") }
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DeltaChart(
                        points = chartPoints,
                        thresholdOn = params.thresholdOn.toFloatOrNull() ?: Float.NaN,
                        thresholdOff = params.thresholdOff.toFloatOrNull() ?: Float.NaN,
                        showMotion = showMotionOnChart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                }

                // Chart legend (plain composables — never drawn inside the Canvas,
                // see DeltaChart notes about the draw-phase text crash).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChartLegendDot(Color(0xFFD32F2F), "آستانه روشن: ${params.thresholdOn}")
                    ChartLegendDot(Color(0xFF43A047), "آستانه خاموش: ${params.thresholdOff}")
                    if (showMotionOnChart) {
                        ChartLegendDot(WarnOrange.copy(alpha = 0.55f), "حرکت")
                    }
                }
            }
        }

        // ---- Actions row ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.requestShow() },
                enabled = connState == ConnState.CONNECTED,
                modifier = Modifier.weight(1f),
            ) { Text("SHOW") }

            if (recording) {
                Button(
                    onClick = { vm.toggleRecording() },
                    modifier = Modifier.weight(1f),
                ) { Text("توقف ثبت CSV") }
            } else {
                OutlinedButton(
                    onClick = { vm.toggleRecording() },
                    enabled = connState == ConnState.CONNECTED,
                    modifier = Modifier.weight(1f),
                ) { Text("شروع ثبت CSV") }
            }

            IconButton(onClick = { vm.shareCsv() }) {
                Icon(Icons.Filled.Share, contentDescription = "اشتراک‌گذاری فایل CSV")
            }
        }

        recordingFile?.let { name ->
            Text(
                "آخرین فایل: $name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- Raw data (collapsible) ----
        Card(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rawExpanded = !rawExpanded }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "داده خام (X/Y/Z)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (rawExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(visible = rawExpanded) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        Text(
                            "حرکت: " + if (motion) "فعال" else "غیرفعال",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (motion) WarnOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "X: ${raw.x}    Y: ${raw.y}    Z: ${raw.z}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "ارسال داده خام (RAW)",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = rawEnabled,
                                onCheckedChange = { vm.setRawEnabled(it) },
                                enabled = connState == ConnState.CONNECTED,
                            )
                        }
                        Text(
                            "سوییچ RAW فقط هنگام اتصال کار می‌کند؛ برای دیدن نمودار و دلتا هم باید ارسال مداوم (SHOW) فعال باشد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Tuning entry ----
        Button(
            onClick = onOpenTuning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("تنظیم پارامترها (Tuning)")
        }
    }
}

/**
 * Motion indicator shown inside the Delta card, always visible without
 * scrolling. While motion is active it blinks orange with the label
 * "حرکت"; when idle it leaves a faint neutral icon so the layout does not
 * jump. Below it: the session motion-event counter and a reset button
 * (useful for judging the real false-positive rate).
 */
@Composable
private fun MotionIndicator(
    active: Boolean,
    count: Int,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (active) {
            val blink = rememberInfiniteTransition(label = "motionBlink")
            val alpha by blink.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 450),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "motionAlpha",
            )
            Surface(
                color = WarnOrange.copy(alpha = 0.18f),
                contentColor = WarnOrange,
                shape = RoundedCornerShape(50),
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Waves,
                        contentDescription = "تشخیص حرکت فعال",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "حرکت",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Icon(
                Icons.Filled.Waves,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier
                    .padding(vertical = 5.dp)
                    .size(18.dp),
            )
        }
        Text(
            "رویدادها: $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onReset,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("ریست شمارنده", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Small colored dot + label used in the chart legend row. */
@Composable
private fun ChartLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
