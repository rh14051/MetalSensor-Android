package com.metalsensor.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metalsensor.app.ui.ConnState
import com.metalsensor.app.ui.MainViewModel
import com.metalsensor.app.ui.ScanDeviceUi
import com.metalsensor.app.ui.components.ConnectionStatusBar

/**
 * Connection screen: scan nearby BLE devices (filtered to names starting with
 * "CAR-"), connect by tapping, and quick-reconnect to the last saved device.
 */
@Composable
fun ConnectionScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val connState by vm.connState.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val reconnectAttempt by vm.reconnectAttempt.collectAsState()
    val scanning by vm.isScanning.collectAsState()
    val results by vm.scanResults.collectAsState()
    val savedMac = vm.savedMac
    val savedName = vm.savedName

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            vm.maybeAutoConnect()
        } else {
            Toast.makeText(context, "برای عملکرد اپ، مجوزهای بلوتوث لازم است", Toast.LENGTH_LONG).show()
        }
    }

    // Auto-connect to the saved device once, when permissions are already granted
    LaunchedEffect(Unit) {
        if (hasAllPermissions()) vm.maybeAutoConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Keep all content away from the status bar / punch-hole camera on
            // top and the home & back gesture area on the bottom.
            .safeDrawingPadding()
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

        Text(
            "اتصال به سنسور",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // Saved device quick connect
        if (connState == ConnState.DISCONNECTED && savedMac != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "دستگاه ذخیره‌شده",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            savedName ?: savedMac,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(onClick = { vm.connectSaved() }) { Text("اتصال") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) {
                OutlinedButton(
                    onClick = { vm.stopScan() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("توقف اسکن")
                }
            } else {
                Button(
                    onClick = {
                        if (hasAllPermissions()) {
                            vm.startScan()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("اسکن دستگاه‌ها")
                }
            }
        }

        Text(
            "فقط دستگاه‌هایی که نامشان با «CAR-» شروع می‌شود نمایش داده می‌شوند.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("در حال جستجوی دستگاه‌ها…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (results.isEmpty() && !scanning) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "هنوز دستگاهی پیدا نشده است.\nدکمه «اسکن دستگاه‌ها» را بزنید و مطمئن شوید ESP32 روشن و در دسترس است.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.mac }) { device: ScanDeviceUi ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.connect(device.mac, device.name) },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                device.mac,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${device.rssi} dBm",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            "با کلیک روی هر دستگاه، اتصال برقرار می‌شود و وارد داشبورد می‌شوید.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
