package com.metalsensor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsensor.app.ui.ConnState
import com.metalsensor.app.ui.MainViewModel
import com.metalsensor.app.ui.ParamKey
import com.metalsensor.app.ui.theme.WarnOrange

private data class ParamSpec(
    val key: ParamKey,
    val title: String,
    val techKey: String,
    val hint: String,
    val isDecimal: Boolean,
)

private val PARAM_SPECS = listOf(
    ParamSpec(ParamKey.ON, "آستانه روشن شدن", "THRESHOLD_ON", "مقدار دلتا برای ورود به تشخیص", false),
    ParamSpec(ParamKey.OFF, "آستانه خاموش شدن", "THRESHOLD_OFF", "مقدار دلتا برای شروع رهاسازی", false),
    ParamSpec(ParamKey.ONT, "زمان پایداری تشخیص", "ON_TIME_MS", "مدت پایداری بالای آستانه (میلی‌ثانیه)", false),
    ParamSpec(ParamKey.OFFT, "زمان پایداری رهاسازی", "OFF_TIME_MS", "مدت پایداری زیر آستانه (میلی‌ثانیه)", false),
    ParamSpec(ParamKey.ALPHA, "ضریب به‌روزرسانی Baseline", "BASELINE_ALPHA", "سرعت تطبیق خط پایه، بین 0 و 1", true),
)

/**
 * Tuning screen: five live-tunable parameters, each with its own apply button,
 * plus CAL / RS actions. THRESHOLD_OFF > THRESHOLD_ON raises an orange warning
 * but never blocks sending (by design).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningScreen(vm: MainViewModel, onBack: () -> Unit) {
    val connState by vm.connState.collectAsState()
    val params by vm.params.collectAsState()
    val connected = connState == ConnState.CONNECTED

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("تنظیم پارامترها") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!connected) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "برای اعمال تغییرات باید به دستگاه متصل باشید.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            PARAM_SPECS.forEach { spec ->
                val value = when (spec.key) {
                    ParamKey.ON -> params.thresholdOn
                    ParamKey.OFF -> params.thresholdOff
                    ParamKey.ONT -> params.onTimeMs
                    ParamKey.OFFT -> params.offTimeMs
                    ParamKey.ALPHA -> params.alpha
                }
                ParamRow(
                    title = spec.title,
                    techKey = spec.techKey,
                    hint = spec.hint,
                    value = value,
                    isDecimal = spec.isDecimal,
                    onValueChange = { vm.updateParamField(spec.key, it) },
                    onApply = { vm.applyParam(spec.key) },
                    applyEnabled = connected && vm.isParamValid(spec.key),
                )
            }

            // Spec: OFF must not be greater than ON — orange warning, never blocks
            val onVal = params.thresholdOn.trim().toIntOrNull()
            val offVal = params.thresholdOff.trim().toIntOrNull()
            if (onVal != null && offVal != null && offVal > onVal) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = WarnOrange,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "هشدار: THRESHOLD_OFF بزرگ‌تر از THRESHOLD_ON است.",
                            color = WarnOrange,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { vm.startCalibration() },
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("شروع کالیبراسیون (CAL)") }

            OutlinedButton(
                onClick = { vm.resetBaseline() },
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ریست Baseline (RS)") }

            Text(
                "پس از زدن «اعمال»، دستگاه باید OK بفرستد؛ اگر تا ۲ ثانیه پاسخ نرسد پیام خطا نمایش داده می‌شود. مقادیر اعمال‌شده به‌صورت خودکار در حافظه اپ ذخیره می‌شوند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // ---------- Motion band on chart ----------
            val showMotionOnChart by vm.showMotionOnChart.collectAsState()
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "نمایش سیگنال حرکت در نمودار",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "نوار نارنجی روی بازه‌های فعال بودن تشخیص حرکت رسم می‌شود",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showMotionOnChart,
                        onCheckedChange = { vm.setShowMotionOnChart(it) },
                    )
                }
            }

            // ---------- Manual command console (SCAN / ADDR= / any raw command) ----------
            val consoleLog by vm.consoleLog.collectAsState()
            CommandConsole(
                log = consoleLog,
                enabled = connected,
                onSend = { vm.sendRawCommand(it) },
                onClear = { vm.clearConsole() },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ParamRow(
    title: String,
    techKey: String,
    hint: String,
    value: String,
    isDecimal: Boolean,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    applyEnabled: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                techKey,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(hint) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onApply, enabled = applyEnabled) { Text("اعمال") }
        }
    }
}

/** Quick one-tap raw commands shown as chips above the console input. */
private val QUICK_COMMANDS = listOf("SCAN", "SHOW", "HIDE", "RAW", "NRAW")

/**
 * Manual protocol console: send any raw command (SCAN, ADDR=0x2C, ...) and see
 * the device replies (OK= / ERR= / I=CHIP / I=SCAN) in a small log. The log is
 * rendered LTR + monospace because the protocol itself is plain ASCII.
 */
@Composable
private fun CommandConsole(
    log: List<String>,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to the newest entry. Uses the instant scrollToItem (not the
    // animated variant): with the 200-entry cap trimming old lines, the animated
    // scroll could race with the list mutation and crash on a stale index.
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) {
            runCatching { listState.scrollToItem(log.lastIndex) }
        }
    }

    fun submit() {
        if (input.isNotBlank()) {
            onSend(input)
            input = ""
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "کنسول فرمان",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "SCAN | ADDR=0x2C",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            // quick commands
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_COMMANDS.forEach { cmd ->
                    OutlinedButton(
                        onClick = { onSend(cmd) },
                        enabled = enabled,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 4.dp
                        ),
                    ) {
                        Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // command input (LTR: the protocol is ASCII)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled,
                    label = { Text("فرمان دستی، مثلاً ADDR=0x2C") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { submit() }, enabled = enabled && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال")
                }
            }
            Spacer(Modifier.height(8.dp))

            // log
            if (log.isEmpty()) {
                Text(
                    "پاسخ‌های دستگاه اینجا نمایش داده می‌شود…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                ) {
                    items(log.size) { i ->
                        Text(
                            log[i],
                            style = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("پاک کردن گزارش") }
            }
        }
    }
}
