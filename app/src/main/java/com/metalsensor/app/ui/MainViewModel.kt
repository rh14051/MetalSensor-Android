package com.metalsensor.app.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metalsensor.app.ble.EspBleManager
import com.metalsensor.app.ble.InboundMessage
import com.metalsensor.app.ble.Protocol
import com.metalsensor.app.ble.SensorState
import com.metalsensor.app.data.CsvLogger
import com.metalsensor.app.data.PrefsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// UI models
// ---------------------------------------------------------------------------

enum class ConnState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED }

data class ScanDeviceUi(val name: String, val mac: String, val rssi: Int)

data class Triple3(val x: Int = 0, val y: Int = 0, val z: Int = 0)

data class ChartPoint(val t: Long, val v: Float)

enum class ParamKey { ON, OFF, ONT, OFFT, ALPHA }

data class ParamFields(
    val thresholdOn: String = "400",
    val thresholdOff: String = "250",
    val onTimeMs: String = "2500",
    val offTimeMs: String = "4000",
    val alpha: String = "0.004",
)

/** One CSV row, queued from the BLE callback and written on Dispatchers.IO. */
private data class CsvRow(
    val delta: Int,
    val state: String,
    val b: Triple3,
    val r: Triple3,
)

// ---------------------------------------------------------------------------
// MainViewModel — single VM shared by all three screens (activity scope)
// ---------------------------------------------------------------------------

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PrefsRepository(app)
    private val csv = CsvLogger(app)
    private val manager = EspBleManager(app)

    private val adapter: BluetoothAdapter?
        get() = (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter

    // ----- last device (for the connection screen) -----
    val savedMac: String? get() = prefs.savedMac
    val savedName: String? get() = prefs.savedName

    // ----- connection state -----
    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    // ----- scanning -----
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScanDeviceUi>>(emptyList())
    val scanResults: StateFlow<List<ScanDeviceUi>> = _scanResults.asStateFlow()

    // ----- live data -----
    private val _delta = MutableStateFlow(0)
    val delta: StateFlow<Int> = _delta.asStateFlow()

    private val _sensorState = MutableStateFlow(SensorState.CAL)
    val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()

    private val _baseline = MutableStateFlow(Triple3())
    val baseline: StateFlow<Triple3> = _baseline.asStateFlow()

    private val _raw = MutableStateFlow(Triple3())
    val raw: StateFlow<Triple3> = _raw.asStateFlow()

    private val _rawEnabled = MutableStateFlow(false)
    val rawEnabled: StateFlow<Boolean> = _rawEnabled.asStateFlow()

    private val _chartPoints = MutableStateFlow<List<ChartPoint>>(emptyList())
    val chartPoints: StateFlow<List<ChartPoint>> = _chartPoints.asStateFlow()

    // ----- parameters -----
    private val _params = MutableStateFlow(loadParams())
    val params: StateFlow<ParamFields> = _params.asStateFlow()

    // ----- CSV recording -----
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _recordingFile = MutableStateFlow<String?>(null)
    val recordingFile: StateFlow<String?> = _recordingFile.asStateFlow()

    // ----- manual command console -----
    private val _consoleLog = MutableStateFlow<List<String>>(emptyList())
    val consoleLog: StateFlow<List<String>> = _consoleLog.asStateFlow()

    // CSV rows are queued here (never blocking the BLE callback) and flushed to
    // disk by a single writer coroutine on Dispatchers.IO.
    private val csvQueue = Channel<CsvRow>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // ----- internals -----
    private var currentMac: String? = null
    private var userDisconnect = false
    private var reconnectJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var autoConnectTried = false
    private var reconnectAttemptNo = 0

    private val pendingAcks = ConcurrentHashMap<String, Job>()

    // console flood control
    private var lastConsoleLine: String? = null
    private var lastConsoleAtMs = 0L
    private var consoleSuppressed = 0

    init {
        manager.listener = object : EspBleManager.Listener {
            override fun onLineReceived(line: String) = handleLine(line)
        }

        // Single CSV writer on IO — file I/O never runs on the main/BLE threads.
        viewModelScope.launch(Dispatchers.IO) {
            for (row in csvQueue) {
                csv.log(
                    delta = row.delta,
                    state = row.state,
                    bx = row.b.x, by = row.b.y, bz = row.b.z,
                    raw = intArrayOf(row.r.x, row.r.y, row.r.z),
                )
            }
        }

        manager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _connState.value = ConnState.CONNECTING
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // Link established; service discovery + initialization in progress.
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                if (userDisconnect) {
                    _connState.value = ConnState.DISCONNECTED
                    return
                }
                showToast("اتصال ناموفق بود — تلاش مجدد خودکار…")
                scheduleReconnect()
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                _connState.value = ConnState.CONNECTED
                _reconnectAttempt.value = 0
                reconnectAttemptNo = 0
                _deviceName.value = try {
                    device.name ?: _deviceName.value.ifEmpty { "CAR-?" }
                } catch (e: SecurityException) {
                    _deviceName.value.ifEmpty { "CAR-?" }
                }
                currentMac = device.address
                prefs.savedMac = device.address
                prefs.savedName = _deviceName.value
                _chartPoints.value = emptyList()
                _delta.value = 0
                // Auto SHOW: pull current parameters and start the live stream
                viewModelScope.launch {
                    delay(300)
                    manager.send(Protocol.CMD_SHOW)
                }
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // waiting for the stack to finish
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                if (userDisconnect) {
                    _connState.value = ConnState.DISCONNECTED
                    _deviceName.value = ""
                } else {
                    showToast("اتصال قطع شد — در حال تلاش مجدد…")
                    scheduleReconnect()
                }
            }
        })
    }

    // -----------------------------------------------------------------------
    // Incoming protocol lines
    // -----------------------------------------------------------------------

    private fun handleLine(line: String) {
        // Keep the console log readable: skip the high-rate streaming lines.
        if (!line.startsWith("D=") && !line.startsWith("B=") && !line.startsWith("R=")) {
            appendConsole(line)
        }
        when (val msg = Protocol.parse(line)) {
            is InboundMessage.Delta -> {
                _delta.value = msg.value
                appendChart(msg.value)
                if (_recording.value) {
                    csvQueue.trySend(
                        CsvRow(msg.value, _sensorState.value.name, _baseline.value, _raw.value)
                    )
                }
            }

            is InboundMessage.State -> _sensorState.value = msg.state

            is InboundMessage.Baseline -> _baseline.value = Triple3(msg.x, msg.y, msg.z)

            is InboundMessage.Raw -> _raw.value = Triple3(msg.x, msg.y, msg.z)

            is InboundMessage.Params -> {
                _params.value = ParamFields(
                    thresholdOn = msg.thresholdOn.toString(),
                    thresholdOff = msg.thresholdOff.toString(),
                    onTimeMs = msg.onTimeMs.toString(),
                    offTimeMs = msg.offTimeMs.toString(),
                    alpha = msg.alpha.toString(),
                )
            }

            is InboundMessage.Ok -> onAck(msg.key)

            is InboundMessage.Err -> showToast("خطای دستگاه: ${msg.message}")

            // Info lines (I=CHIP:..., I=SCAN:...) are already captured in the console log.
            is InboundMessage.Info -> Unit

            InboundMessage.Unknown -> Unit
        }
    }

    private fun appendConsole(entry: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // A misbehaving device can spam ERR/INFO lines at stream rate (e.g. when
        // I2C fails). Collapse duplicates and rate-limit appends so neither the
        // memory nor the recomposition can flood; user echoes are never dropped.
        if (!force) {
            if (entry == lastConsoleLine && now - lastConsoleAtMs < DUP_WINDOW_MS) {
                consoleSuppressed++
                return
            }
            if (now - lastConsoleAtMs < MIN_CONSOLE_INTERVAL_MS) {
                consoleSuppressed++
                return
            }
        }
        val stamp = LocalTime.now().format(consoleTimeFormat)
        val current = _consoleLog.value
        val next = ArrayList<String>(current.size + 2)
        if (current.size >= CONSOLE_MAX_LINES) {
            next.addAll(current.subList(current.size - CONSOLE_MAX_LINES + 1, current.size))
        }
        if (consoleSuppressed > 0) {
            next.add("$stamp  … ${consoleSuppressed} پیام تکراری حذف شد")
            consoleSuppressed = 0
        }
        next.add("$stamp  $entry")
        _consoleLog.value = next
        lastConsoleLine = entry
        lastConsoleAtMs = now
    }

    /** Sends a raw protocol command from the manual console (e.g. SCAN, ADDR=0x2C). */
    fun sendRawCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        if (!requireConnected()) return
        appendConsole("> $cmd", force = true)
        manager.send(cmd)
        val key = cmd.substringBefore('=').trim().uppercase()
        if (key in Protocol.ACK_KEYS) registerAck(key)
    }

    fun clearConsole() {
        _consoleLog.value = emptyList()
    }

    private fun appendChart(value: Int) {
        // Firmware artifacts (e.g. an INT32_MIN sentinel produced by a failed
        // float->int conversion on a failing sensor) would flatten the Y scale
        // for minutes. Real delta from an int16 magnetometer can never exceed
        // ±65534, so anything beyond ±1e6 is not plotted — the numeric readout
        // above still shows the raw value the device sent.
        if (kotlin.math.abs(value) > 1_000_000) return
        val now = System.currentTimeMillis()
        val current = _chartPoints.value
        val next = ArrayList<ChartPoint>(current.size + 1)
        for (p in current) {
            if (p.t >= now - CHART_WINDOW_MS) next.add(p)
        }
        next.add(ChartPoint(now, value.toFloat()))
        if (next.size > CHART_MAX_POINTS) {
            next.subList(0, next.size - CHART_MAX_POINTS).clear()
        }
        _chartPoints.value = next
    }

    // -----------------------------------------------------------------------
    // Command sending + 2s ack tracking
    // -----------------------------------------------------------------------

    private fun registerAck(key: String) {
        pendingAcks.remove(key)?.cancel()
        pendingAcks[key] = viewModelScope.launch {
            delay(ACK_TIMEOUT_MS)
            if (pendingAcks.remove(key) != null) {
                showToast("پاسخی از دستگاه دریافت نشد ($key)")
            }
        }
    }

    private fun onAck(key: String) {
        pendingAcks.remove(key)?.cancel()
        if (key in Protocol.ACK_KEYS) showToast("اعمال شد: $key")
    }

    fun updateParamField(key: ParamKey, text: String) {
        _params.value = when (key) {
            ParamKey.ON -> _params.value.copy(thresholdOn = text)
            ParamKey.OFF -> _params.value.copy(thresholdOff = text)
            ParamKey.ONT -> _params.value.copy(onTimeMs = text)
            ParamKey.OFFT -> _params.value.copy(offTimeMs = text)
            ParamKey.ALPHA -> _params.value.copy(alpha = text)
        }
    }

    fun isParamValid(key: ParamKey): Boolean {
        val p = _params.value
        return when (key) {
            ParamKey.ON -> p.thresholdOn.trim().toIntOrNull() != null
            ParamKey.OFF -> p.thresholdOff.trim().toIntOrNull() != null
            ParamKey.ONT -> p.onTimeMs.trim().toIntOrNull() != null
            ParamKey.OFFT -> p.offTimeMs.trim().toIntOrNull() != null
            ParamKey.ALPHA -> p.alpha.trim().toDoubleOrNull() != null
        }
    }

    fun applyParam(key: ParamKey) {
        if (!requireConnected()) return
        val p = _params.value
        if (!isParamValid(key)) {
            showToast("مقدار واردشده نامعتبر است")
            return
        }
        val rawValue: String
        val line: String
        val ackKey: String
        when (key) {
            ParamKey.ON -> {
                rawValue = p.thresholdOn.trim(); line = "ON=$rawValue"; ackKey = "ON"
            }
            ParamKey.OFF -> {
                rawValue = p.thresholdOff.trim(); line = "OFF=$rawValue"; ackKey = "OFF"
            }
            ParamKey.ONT -> {
                rawValue = p.onTimeMs.trim(); line = "ONT=$rawValue"; ackKey = "ONT"
            }
            ParamKey.OFFT -> {
                rawValue = p.offTimeMs.trim(); line = "OFFT=$rawValue"; ackKey = "OFFT"
            }
            ParamKey.ALPHA -> {
                rawValue = p.alpha.trim(); line = "ALPHA=$rawValue"; ackKey = "ALPHA"
            }
        }
        manager.send(line)
        registerAck(ackKey)
        // Persist automatically on apply (spec requirement)
        val prefKey = when (key) {
            ParamKey.ON -> PrefsRepository.PARAM_THRESHOLD_ON
            ParamKey.OFF -> PrefsRepository.PARAM_THRESHOLD_OFF
            ParamKey.ONT -> PrefsRepository.PARAM_ON_TIME
            ParamKey.OFFT -> PrefsRepository.PARAM_OFF_TIME
            ParamKey.ALPHA -> PrefsRepository.PARAM_ALPHA
        }
        prefs.saveParam(prefKey, rawValue)
    }

    fun startCalibration() {
        if (requireConnected()) {
            manager.send(Protocol.CMD_CAL)
            registerAck("CAL")
        }
    }

    fun resetBaseline() {
        if (requireConnected()) {
            manager.send(Protocol.CMD_RS)
            registerAck("RS")
        }
    }

    fun requestShow() {
        if (requireConnected()) manager.send(Protocol.CMD_SHOW)
    }

    fun hideStream() {
        if (requireConnected()) manager.send(Protocol.CMD_HIDE)
    }

    fun setRawEnabled(enabled: Boolean) {
        _rawEnabled.value = enabled
        if (_connState.value == ConnState.CONNECTED) {
            manager.send(if (enabled) Protocol.CMD_RAW else Protocol.CMD_NRAW)
        }
    }

    private fun requireConnected(): Boolean {
        if (_connState.value != ConnState.CONNECTED) {
            showToast("ابتدا به دستگاه متصل شوید")
            return false
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            showToast("بلوتوث روشن نیست")
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            showToast("اسکن BLE در دسترس نیست")
            return
        }
        if (_isScanning.value) return
        _scanResults.value = emptyList()
        _isScanning.value = true

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    result.scanRecord?.deviceName ?: result.device.name ?: return
                } catch (e: SecurityException) {
                    return
                }
                if (!name.startsWith(Protocol.DEVICE_NAME_PREFIX)) return
                val item = ScanDeviceUi(name, result.device.address, result.rssi)
                val list = _scanResults.value.toMutableList()
                val idx = list.indexOfFirst { it.mac == item.mac }
                if (idx >= 0) list[idx] = item else list.add(item)
                _scanResults.value = list.sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                showToast("خطا در اسکن (کد $errorCode)")
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(cb)
            viewModelScope.launch {
                delay(SCAN_DURATION_MS)
                stopScan()
            }
        } catch (e: Exception) {
            _isScanning.value = false
            showToast("شروع اسکن ناموفق بود")
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        val cb = scanCallback
        try {
            if (cb != null) adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            // ignore
        }
        scanCallback = null
        _isScanning.value = false
    }

    // -----------------------------------------------------------------------
    // Connect / disconnect / auto reconnect
    // -----------------------------------------------------------------------

    fun connect(mac: String, name: String) {
        stopReconnect()
        stopScan()
        userDisconnect = false
        currentMac = mac
        _deviceName.value = name
        _connState.value = ConnState.CONNECTING

        val a = adapter
        if (a == null) {
            _connState.value = ConnState.DISCONNECTED
            showToast("بلوتوث در دسترس نیست")
            return
        }
        val device = try {
            a.getRemoteDevice(mac)
        } catch (e: Exception) {
            _connState.value = ConnState.DISCONNECTED
            showToast("آدرس دستگاه نامعتبر است")
            return
        }
        try {
            manager.connect(device).enqueue()
        } catch (e: SecurityException) {
            _connState.value = ConnState.DISCONNECTED
            showToast("مجوز بلوتوث داده نشده است")
        } catch (e: IllegalStateException) {
            _connState.value = ConnState.DISCONNECTED
            showToast("اتصال قبلی هنوز در حال پایان است، دوباره تلاش کنید")
        }
    }

    fun connectSaved() {
        val mac = prefs.savedMac ?: return
        if (_connState.value != ConnState.DISCONNECTED) return
        connect(mac, prefs.savedName ?: mac)
    }

    /** Tries to reconnect to the last saved device, once per process, on app start. */
    fun maybeAutoConnect() {
        if (autoConnectTried) return
        if (prefs.savedMac == null) return
        autoConnectTried = true
        connectSaved()
    }

    fun disconnect() {
        userDisconnect = true
        stopReconnect()
        stopScan()
        try {
            manager.disconnectNow()
        } catch (e: Exception) {
            // ignore
        }
        _connState.value = ConnState.DISCONNECTED
        _deviceName.value = ""
    }

    fun cancelReconnect() {
        userDisconnect = true
        stopReconnect()
        _connState.value = ConnState.DISCONNECTED
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectAttempt.value = 0
        reconnectAttemptNo = 0
    }

    /**
     * Auto-reconnect with increasing backoff: 2s, 4s, 8s, then capped at 10s.
     * Canceled by [disconnect] / [cancelReconnect].
     */
    private fun scheduleReconnect() {
        if (userDisconnect) {
            _connState.value = ConnState.DISCONNECTED
            return
        }
        if (currentMac == null && prefs.savedMac == null) {
            _connState.value = ConnState.DISCONNECTED
            return
        }
        _connState.value = ConnState.RECONNECTING
        // If a reconnect loop is already running (waiting in its backoff delay), keep it.
        if (reconnectJob?.isActive == true) return

        reconnectJob = viewModelScope.launch {
            while (isActive && !userDisconnect) {
                reconnectAttemptNo++
                _reconnectAttempt.value = reconnectAttemptNo
                val backoff = minOf(2000L * (1L shl minOf(reconnectAttemptNo - 1, 3)), 10_000L)
                delay(backoff)
                if (userDisconnect) break
                val mac = currentMac ?: prefs.savedMac ?: break
                val device = try {
                    adapter?.getRemoteDevice(mac)
                } catch (e: Exception) {
                    null
                } ?: break
                try {
                    manager.connect(device).enqueue()
                    return@launch // waiting for the observer; on failure it calls scheduleReconnect again
                } catch (e: Exception) {
                    // Could not even enqueue; loop and retry after the next backoff.
                }
            }
            if (!userDisconnect) _connState.value = ConnState.DISCONNECTED
        }
    }

    // -----------------------------------------------------------------------
    // CSV recording + sharing
    // -----------------------------------------------------------------------

    fun toggleRecording() {
        if (_recording.value) {
            val f = csv.stop()
            _recording.value = false
            _recordingFile.value = f?.name
            showToast(
                if (f != null) "ثبت داده ذخیره شد: ${f.name}" else "ثبت داده متوقف شد"
            )
        } else {
            val f = csv.start()
            if (f != null) {
                _recording.value = true
                _recordingFile.value = f.name
                showToast("ثبت داده شروع شد")
            } else {
                showToast("ایجاد فایل CSV ناموفق بود")
            }
        }
    }

    fun shareCsv() {
        val context = getApplication<Application>()
        val f = csv.lastFile
        if (f == null || !f.exists()) {
            showToast("فایلی برای اشتراک‌گذاری وجود ندارد")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "اشتراک‌گذاری فایل CSV")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            showToast("اشتراک‌گذاری ناموفق بود")
        }
    }

    // -----------------------------------------------------------------------

    private fun loadParams() = ParamFields(
        thresholdOn = prefs.loadParam(PrefsRepository.PARAM_THRESHOLD_ON, "400"),
        thresholdOff = prefs.loadParam(PrefsRepository.PARAM_THRESHOLD_OFF, "250"),
        onTimeMs = prefs.loadParam(PrefsRepository.PARAM_ON_TIME, "2500"),
        offTimeMs = prefs.loadParam(PrefsRepository.PARAM_OFF_TIME, "4000"),
        alpha = prefs.loadParam(PrefsRepository.PARAM_ALPHA, "0.004"),
    )

    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            manager.disconnectNow()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        private const val ACK_TIMEOUT_MS = 2_000L
        private const val SCAN_DURATION_MS = 15_000L
        private const val CHART_WINDOW_MS = 60_000L
        private const val CHART_MAX_POINTS = 300
        private const val CONSOLE_MAX_LINES = 200
        private const val DUP_WINDOW_MS = 1_500L
        private const val MIN_CONSOLE_INTERVAL_MS = 100L

        // java.time formatters are immutable and thread-safe.
        private val consoleTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
