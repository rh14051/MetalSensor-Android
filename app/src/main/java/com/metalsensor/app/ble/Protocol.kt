package com.metalsensor.app.ble

/**
 * State reported by the ESP32 sensor.
 * CAL: calibrating | CLEAR: no vehicle | DETECTING: above ON threshold, waiting for confirmation
 * OCCUPIED: vehicle confirmed present
 */
enum class SensorState {
    CAL, CLEAR, DETECTING, OCCUPIED;

    companion object {
        fun from(raw: String): SensorState? =
            entries.firstOrNull { it.name == raw.trim().uppercase() }
    }
}

/** A single parsed message received from the ESP32 TX characteristic (FFE2). */
sealed class InboundMessage {
    /** D=1234 — current delta value. */
    data class Delta(val value: Int) : InboundMessage()

    /** S=CLEAR — sensor state machine state. */
    data class State(val state: SensorState) : InboundMessage()

    /** B=1234,-567,890 — current baseline vector. */
    data class Baseline(val x: Int, val y: Int, val z: Int) : InboundMessage()

    /** R=100,-200,50 — raw X/Y/Z (only while RAW streaming is enabled). */
    data class Raw(val x: Int, val y: Int, val z: Int) : InboundMessage()

    /** A=400,250,2500,4000,0.004 — all current parameters (answer to SHOW). */
    data class Params(
        val thresholdOn: Int,
        val thresholdOff: Int,
        val onTimeMs: Int,
        val offTimeMs: Int,
        val alpha: Double,
    ) : InboundMessage()

    /** OK=ON — device acknowledged a command. */
    data class Ok(val key: String) : InboundMessage()

    /** ERR=... — device reported an error. */
    data class Err(val message: String) : InboundMessage()

    /** I=CHIP:QMC5883P/QMC6310@0x2C — informational line (chip identity, I2C scan results). */
    data class Info(val text: String) : InboundMessage()

    /** Unrecognized line (ignored by the app). */
    data object Unknown : InboundMessage()
}

/**
 * Protocol constants + text line parser.
 * Every message (both directions) is a plain-text line terminated by '\n'.
 */
object Protocol {

    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_RX_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val CHAR_TX_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"

    /** Only devices whose advertised name starts with this prefix are shown in the scan list. */
    const val DEVICE_NAME_PREFIX = "CAR-"

    // Commands sent to the device
    const val CMD_CAL = "CAL"
    const val CMD_RS = "RS"
    const val CMD_SHOW = "SHOW"
    const val CMD_HIDE = "HIDE"
    const val CMD_RAW = "RAW"
    const val CMD_NRAW = "NRAW"

    /** Ack keys that require an OK=... answer within the 2-second window. */
    val ACK_KEYS = setOf("ON", "OFF", "ONT", "OFFT", "ALPHA", "CAL", "RS")

    fun parse(line: String): InboundMessage {
        val idx = line.indexOf('=')
        if (idx <= 0) return InboundMessage.Unknown
        val key = line.substring(0, idx).trim().uppercase()
        val value = line.substring(idx + 1).trim()
        return when (key) {
            "D" -> value.toIntOrNull()
                ?.let { InboundMessage.Delta(it) } ?: InboundMessage.Unknown
            "S" -> SensorState.from(value)
                ?.let { InboundMessage.State(it) } ?: InboundMessage.Unknown
            "B" -> parseTriple(value)
                ?.let { InboundMessage.Baseline(it[0], it[1], it[2]) } ?: InboundMessage.Unknown
            "R" -> parseTriple(value)
                ?.let { InboundMessage.Raw(it[0], it[1], it[2]) } ?: InboundMessage.Unknown
            "A" -> parseParams(value) ?: InboundMessage.Unknown
            "OK" -> InboundMessage.Ok(value.uppercase())
            "ERR" -> InboundMessage.Err(value.ifEmpty { "unknown" })
            "I" -> InboundMessage.Info(value.ifEmpty { "" })
            else -> InboundMessage.Unknown
        }
    }

    private fun parseTriple(value: String): IntArray? {
        val parts = value.split(',')
        if (parts.size < 3) return null
        val out = IntArray(3)
        for (i in 0..2) {
            out[i] = parts[i].trim().toIntOrNull() ?: return null
        }
        return out
    }

    private fun parseParams(value: String): InboundMessage.Params? {
        val parts = value.split(',')
        if (parts.size < 5) return null
        val on = parts[0].trim().toIntOrNull() ?: return null
        val off = parts[1].trim().toIntOrNull() ?: return null
        val onTime = parts[2].trim().toIntOrNull() ?: return null
        val offTime = parts[3].trim().toIntOrNull() ?: return null
        val alpha = parts[4].trim().toDoubleOrNull() ?: return null
        return InboundMessage.Params(on, off, onTime, offTime, alpha)
    }
}
