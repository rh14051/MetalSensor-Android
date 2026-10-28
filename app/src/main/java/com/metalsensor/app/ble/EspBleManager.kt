package com.metalsensor.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Nordic Android BLE Library manager for the ESP32-C3 metal sensor.
 *
 * Responsibilities:
 *  - Service/characteristic discovery (FFE0 / FFE1 / FFE2)
 *  - MTU negotiation (247) so that longer lines (e.g. A=...) fit in one packet
 *  - Subscribing to notifications on FFE2 and reassembling \n-terminated lines
 *    (a notification may contain a partial line, several lines, or both)
 *  - Queueing all writes to FFE1 (the library serializes them automatically)
 */
class EspBleManager(context: Context) : BleManager(context) {

    interface Listener {
        /** Called for every complete line received from the device. */
        fun onLineReceived(line: String)
    }

    var listener: Listener? = null

    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val rxBuffer = StringBuilder(256)

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(Protocol.SERVICE_UUID))
        writeChar = service?.getCharacteristic(UUID.fromString(Protocol.CHAR_RX_UUID))
        notifyChar = service?.getCharacteristic(UUID.fromString(Protocol.CHAR_TX_UUID))
        val valid = writeChar != null && notifyChar != null
        if (!valid) {
            writeChar = null
            notifyChar = null
        }
        return valid
    }

    override fun initialize() {
        // Ask for a bigger MTU; if the peripheral refuses, line buffering still works.
        requestMtu(247).enqueue()

        setNotificationCallback(notifyChar).with { _, data ->
            data.value?.let { handleBytes(it) }
        }
        enableNotifications(notifyChar).enqueue()
    }

    override fun onServicesInvalidated() {
        writeChar = null
        notifyChar = null
        synchronized(rxBuffer) { rxBuffer.setLength(0) }
    }

    /** Queue a text command; a trailing '\n' is appended when missing. */
    fun send(line: String) {
        val characteristic = writeChar ?: return
        val payload = (if (line.endsWith("\n")) line else "$line\n")
            .toByteArray(StandardCharsets.US_ASCII)
        writeCharacteristic(
            characteristic,
            payload,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ).enqueue()
    }

    fun disconnectNow() {
        disconnect().enqueue()
    }

    private fun handleBytes(bytes: ByteArray) {
        synchronized(rxBuffer) {
            for (b in bytes) {
                if (b == '\n'.code.toByte()) {
                    val text = rxBuffer.toString().trim('\r', ' ')
                    rxBuffer.setLength(0)
                    if (text.isNotEmpty()) listener?.onLineReceived(text)
                } else {
                    // The protocol is plain ASCII; unknown bytes are harmless noise.
                    rxBuffer.append(b.toInt().toChar())
                    if (rxBuffer.length > 512) rxBuffer.setLength(0)
                }
            }
        }
    }
}
