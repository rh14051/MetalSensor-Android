package com.metalsensor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metalsensor.app.ui.ConnState
import com.metalsensor.app.ui.theme.StateCalColor
import com.metalsensor.app.ui.theme.StateClearColor
import com.metalsensor.app.ui.theme.StateDetectingColor
import com.metalsensor.app.ui.theme.StateOccupiedColor
import com.metalsensor.app.ble.SensorState

/** Colored status indicator shown at the top of every screen. */
@Composable
fun ConnectionStatusBar(
    state: ConnState,
    deviceName: String,
    reconnectAttempt: Int,
    onDisconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
) {
    val color = when (state) {
        ConnState.CONNECTED -> StateClearColor
        ConnState.CONNECTING, ConnState.RECONNECTING -> StateDetectingColor
        ConnState.DISCONNECTED -> StateCalColor
    }
    val label = when (state) {
        ConnState.CONNECTED -> "متصل"
        ConnState.CONNECTING -> "در حال اتصال…"
        ConnState.RECONNECTING -> "اتصال مجدد (تلاش $reconnectAttempt)…"
        ConnState.DISCONNECTED -> "قطع"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(
                deviceName.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            when (state) {
                ConnState.CONNECTED -> {
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Filled.LinkOff, contentDescription = "قطع اتصال")
                    }
                }
                ConnState.RECONNECTING -> {
                    TextButton(onClick = onCancelReconnect) { Text("لغو") }
                }
                else -> Unit
            }
        }
    }
}

/** Colored badge showing the sensor state machine state. */
@Composable
fun StateBadge(state: SensorState) {
    val color = when (state) {
        SensorState.CAL -> StateCalColor
        SensorState.CLEAR -> StateClearColor
        SensorState.DETECTING -> StateDetectingColor
        SensorState.OCCUPIED -> StateOccupiedColor
    }
    val label = when (state) {
        SensorState.CAL -> "در حال کالیبراسیون"
        SensorState.CLEAR -> "پاک (CLEAR)"
        SensorState.DETECTING -> "در حال تشخیص"
        SensorState.OCCUPIED -> "اشغال (OCCUPIED)"
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
