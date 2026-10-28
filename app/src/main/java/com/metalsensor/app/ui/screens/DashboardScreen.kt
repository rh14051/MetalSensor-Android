package com.metalsensor.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    var rawExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                Text(
                    "$delta",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
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
