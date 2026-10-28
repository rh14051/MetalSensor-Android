/*
 * ============================================================================
 *  Metal Sensor (سنسور فلز) — ESP32-C3 Vehicle Detector Firmware  (v1.1)
 * ============================================================================
 *  Companion firmware for the "سنسور فلز" Android tuning app.
 *
 *  Board   : ESP32-C3 (SuperMini / DevKitM-1), Arduino framework
 *  Sensor  : AUTO-DETECTED magnetometer on I2C — supported chips:
 *              - QMC5883P / QMC6310  @ 0x2C (or 0x1C)  ← new GY-271 boards
 *              - QMC5883L            @ 0x0D            ← classic GY-271
 *              - HMC5883L            @ 0x1E            ← original GY-271
 *            ...or the built-in simulator (SENSOR_TYPE_SIM) for app testing.
 *            A board labelled "GY-271 QMC5883L" may actually carry a newer
 *            QST chip at 0x2C — auto-detect handles all of them.
 *  LED     : GPIO4 — HIGH while DETECTING / OCCUPIED (set LED_PIN to change)
 *  BLE     : standard ESP32 Bluedroid stack — NO external Arduino libraries
 *            are required (only the ESP32 board package itself).
 *
 *  ------------------------------ BLE ---------------------------------------
 *  Service UUID : 0000FFE0-0000-1000-8000-00805F9B34FB
 *  RX (app->dev, WRITE)  : 0000FFE1-0000-1000-8000-00805F9B34FB
 *  TX (dev->app, NOTIFY) : 0000FFE2-0000-1000-8000-00805F9B34FB
 *
 *  All messages are plain-text lines terminated with '\n'.
 *
 *  RX (commands the app sends):
 *    ON=<n>        set THRESHOLD_ON        -> OK=ON   / ERR=ON_RANGE
 *    OFF=<n>       set THRESHOLD_OFF       -> OK=OFF  / ERR=OFF_RANGE
 *    ONT=<ms>      set ON_TIME_MS          -> OK=ONT  / ERR=ONT_RANGE
 *    OFFT=<ms>     set OFF_TIME_MS         -> OK=OFFT / ERR=OFFT_RANGE
 *    ALPHA=<f>     set BASELINE_ALPHA      -> OK=ALPHA / ERR=ALPHA_RANGE
 *    CAL           restart calibration     -> OK=CAL
 *    RS            reset baseline to now   -> OK=RS
 *    SHOW          start streaming + send A=...   -> OK=SHOW
 *    HIDE          stop streaming          -> OK=HIDE
 *    RAW           start raw X/Y/Z stream  -> OK=RAW
 *    NRAW          stop raw stream         -> OK=NRAW
 *    SCAN          scan the whole I2C bus  -> I=SCAN:0x2C,... / I=SCAN:none
 *    ADDR=<a>      force I2C address ("0x2C", "44" decimal, or "AUTO")
 *                  -> OK=ADDR (+ I=CHIP:...), ERR=ADDR_RANGE / ERR=ADDR_NO_DEV
 *    (anything else)                       -> ERR=UNKNOWN_CMD
 *
 *  TX (notifications to the app):
 *    D=<delta>            current delta (every 200 ms while streaming)
 *    S=CAL|CLEAR|DETECTING|OCCUPIED   state (sent on every change)
 *    B=<bx,by,bz>         baseline (every 2 s while streaming)
 *    R=<x,y,z>            raw values (every 200 ms while RAW enabled)
 *    A=<on,off,ont,offt,alpha>   current parameters (answer to SHOW / on connect)
 *    I=CHIP:<name>@0x<addr>      detected chip info (on connect / re-detect)
 *    I=SCAN:<list>               I2C bus scan result
 *    OK=<key> / ERR=<msg> acknowledgements
 *
 *  -------------------------- State machine ---------------------------------
 *    CAL        : collecting ~2 s of samples -> baseline = average
 *    CLEAR      : tracking; EMA baseline follows the field (ALPHA)
 *    DETECTING  : delta >= TH_ON and stable for ON_TIME_MS -> OCCUPIED
 *                 (if delta drops below TH_OFF first -> CLEAR again)
 *    OCCUPIED   : vehicle present; baseline frozen
 *                 (if delta <= TH_OFF for OFF_TIME_MS -> CLEAR again)
 *    Delta = |x-bx| + |y-by| + |z-bz|   (L1 distance from baseline)
 *
 *  ------------------- Sensor-loss resilience (v1.1) ------------------------
 *    If reads fail for ~2 s the device enters recovery: it re-probes the bus
 *    every 2 s (ERR=I2C_LOST is sent once), and when the sensor comes back it
 *    re-initializes it and restarts calibration automatically.
 * ============================================================================
 */

#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>

// ----------------------------- Configuration -------------------------------

#define SENSOR_TYPE_QMC 1
#define SENSOR_TYPE_SIM 2
#define SENSOR_TYPE     SENSOR_TYPE_QMC   // change to SENSOR_TYPE_SIM to test the app without hardware

#define I2C_SDA 8        // ESP32-C3 SuperMini: SDA=GPIO8, SCL=GPIO9
#define I2C_SCL 9
#define LED_PIN 4        // status LED on GPIO4: HIGH = DETECTING / OCCUPIED (-1 to disable)

#define DEVICE_NAME "CAR-SENSOR"          // app filters names starting with "CAR-"

static const char* SVC_UUID   = "0000ffe0-0000-1000-8000-00805f9b34fb";
static const char* CHR_RX_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb";
static const char* CHR_TX_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb";

// ----------------------------- Parameters ----------------------------------

static int32_t TH_ON          = 400;    // delta threshold to enter DETECTING
static int32_t TH_OFF         = 250;    // delta threshold to release
static int32_t ON_TIME_MS     = 2500;   // stability time to confirm detection
static int32_t OFF_TIME_MS    = 4000;   // stability time to release
static float   BASELINE_ALPHA = 0.004f; // EMA factor for baseline tracking

// ----------------------------- Globals -------------------------------------

enum SensorState : uint8_t { ST_CAL, ST_CLEAR, ST_DETECTING, ST_OCCUPIED };
static const char* STATE_NAMES[] = { "CAL", "CLEAR", "DETECTING", "OCCUPIED" };
static SensorState state = ST_CAL;

BLEServer*         bleServer = nullptr;
BLECharacteristic* txChar    = nullptr;

static volatile bool deviceConnected = false;
static bool needInitMsg = false;
static uint32_t connectAt = 0;

static int32_t rawX = 0, rawY = 0, rawZ = 0;
static float   baseX = 0.0f, baseY = 0.0f, baseZ = 0.0f;
static int32_t delta = 0;

static bool streamOn    = false;
static bool rawStreamOn = false;

static uint32_t lastSensorTick = 0;
static uint32_t lastStreamTick = 0;
static uint32_t lastBaseTick   = 0;
static uint32_t detectStart    = 0;
static uint32_t releaseStart   = 0;

// calibration
static bool     calibrating = false;
static uint32_t calStart = 0;
static double   calSumX = 0, calSumY = 0, calSumZ = 0;
static uint32_t calCount = 0;

static String rxLine;

Preferences nvs;

// ----------------------------- Persistence ---------------------------------

static void loadParams() {
  TH_ON          = nvs.getInt("thOn", 400);
  TH_OFF         = nvs.getInt("thOff", 250);
  ON_TIME_MS     = nvs.getInt("onT", 2500);
  OFF_TIME_MS    = nvs.getInt("offT", 4000);
  BASELINE_ALPHA = nvs.getFloat("alpha", 0.004f);
}

static void saveParams() {
  nvs.putInt("thOn", TH_ON);
  nvs.putInt("thOff", TH_OFF);
  nvs.putInt("onT", ON_TIME_MS);
  nvs.putInt("offT", OFF_TIME_MS);
  nvs.putFloat("alpha", BASELINE_ALPHA);
}

// ----------------------------- TX helpers ----------------------------------

static void sendLine(const String& s) {
  if (!deviceConnected || txChar == nullptr) return;
  String line = s;
  if (!line.endsWith("\n")) line += "\n";
  txChar->setValue((uint8_t*)line.c_str(), line.length());
  txChar->notify();
}

static void sendState()    { sendLine(String("S=") + STATE_NAMES[state]); }
static void sendBaseline() {
  char buf[48];
  snprintf(buf, sizeof(buf), "B=%d,%d,%d", (int)baseX, (int)baseY, (int)baseZ);
  sendLine(String(buf));
}
static void sendRaw() {
  char buf[40];
  snprintf(buf, sizeof(buf), "R=%d,%d,%d", (int)rawX, (int)rawY, (int)rawZ);
  sendLine(String(buf));
}
static void sendParams() {
  char buf[64];
  snprintf(buf, sizeof(buf), "A=%d,%d,%d,%d,%.4f",
           (int)TH_ON, (int)TH_OFF, (int)ON_TIME_MS, (int)OFF_TIME_MS, BASELINE_ALPHA);
  sendLine(String(buf));
}

// ----------------------------- Sensor driver -------------------------------

static void startCalibration();               // forward decl (used by recovery)

#if SENSOR_TYPE == SENSOR_TYPE_QMC

/*
 * Supported chip families (all handled transparently by auto-detect):
 *
 *  CHIP_QST_NEW   : QMC5883P / QMC6310 — the newer GY-271 boards.
 *                   Data @0x01..0x06 little-endian, STATUS 0x09,
 *                   CTRL1 0x0A (mode/ODR/OSR), CTRL2 0x0B (range).
 *                   I2C address 0x2C (sometimes 0x1C).
 *
 *  CHIP_QMC5883L  : the classic QMC5883L @0x0D.
 *                   Data @0x00..0x05 little-endian, CTRL 0x09, SET/RESET 0x0B.
 *
 *  CHIP_HMC5883L  : the original Honeywell HMC5883L @0x1E.
 *                   Data @0x03..0x08 big-endian in X,Z,Y order,
 *                   CRA 0x00 / CRB 0x01 / MR 0x02.
 */
enum ChipType : uint8_t { CHIP_NONE = 0, CHIP_QST_NEW, CHIP_QMC5883L, CHIP_HMC5883L };

// NOTE: keep uint8_t in function signatures (NOT ChipType) — the Arduino
// auto-prototype generator places prototypes before the enum declaration,
// which would break compilation of the .ino.

static const char* CHIP_NAMES[] = { "NONE", "QMC5883P/QMC6310", "QMC5883L", "HMC5883L" };
static const uint8_t AUTO_ADDRS[] = { 0x2C, 0x1C, 0x0D, 0x1E };   // probe order

static uint8_t  cfgAddr = 0;      // 0 = AUTO, else manual I2C address (persisted)
static uint8_t  curAddr = 0;      // address currently in use
static uint8_t  chip    = CHIP_NONE;
static bool     sensorLost = false;
static uint32_t failCount = 0;
static uint32_t lastProbeTick = 0;
static uint32_t lastLostPrint = 0;

static const char* chipName(uint8_t c) {
  return (c < sizeof(CHIP_NAMES) / sizeof(CHIP_NAMES[0])) ? CHIP_NAMES[c] : "?";
}

static String chipInfoLine() {
  char ab[8];
  snprintf(ab, sizeof(ab), "0x%02X", curAddr);
  return String("I=CHIP:") + chipName(chip) + "@" + ab;
}

static bool i2cWrite(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg); Wire.write(val);
  return Wire.endTransmission() == 0;
}

static bool i2cProbe(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

static uint8_t readChipId(uint8_t addr) {           // reg 0x00 (QST chips)
  Wire.beginTransmission(addr);
  Wire.write(0x00);
  if (Wire.endTransmission(false) != 0) return 0xFF;
  if (Wire.requestFrom((int)addr, 1) != 1) return 0xFF;
  return Wire.read();
}

// ---- QMC5883P / QMC6310 (data 0x01, little-endian) ----
static bool qstNewInit(uint8_t a) {
  bool ok = i2cWrite(a, 0x0A, 0xCF);   // continuous, 200 Hz, max averaging, mode=11
  i2cWrite(a, 0x0B, 0x08);             // range = +/-8 Gauss (same encoding on both chips)
  delay(6);
  return ok;
}

static bool qstNewRead(uint8_t a, int32_t &x, int32_t &y, int32_t &z) {
  Wire.beginTransmission(a);
  Wire.write(0x01);                               // X_LSB
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)a, 6) != 6) return false;
  uint8_t xl = Wire.read(), xh = Wire.read();
  uint8_t yl = Wire.read(), yh = Wire.read();
  uint8_t zl = Wire.read(), zh = Wire.read();
  x = (int16_t)(xl | (xh << 8));
  y = (int16_t)(yl | (yh << 8));
  z = (int16_t)(zl | (zh << 8));
  return true;
}

// ---- classic QMC5883L (data 0x00, little-endian) ----
static bool qmc5883lInit(uint8_t a) {
  bool ok = i2cWrite(a, 0x0B, 0x01);              // SET/RESET period
  i2cWrite(a, 0x09, 0x1D);                        // OSR=512, RNG=8G, ODR=200Hz, CONT
  return ok;
}

static bool qmc5883lRead(uint8_t a, int32_t &x, int32_t &y, int32_t &z) {
  Wire.beginTransmission(a);
  Wire.write(0x00);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)a, 6) != 6) return false;
  uint8_t xl = Wire.read(), xh = Wire.read();
  uint8_t yl = Wire.read(), yh = Wire.read();
  uint8_t zl = Wire.read(), zh = Wire.read();
  x = (int16_t)(xl | (xh << 8));
  y = (int16_t)(yl | (yh << 8));
  z = (int16_t)(zl | (zh << 8));
  return true;
}

// ---- original HMC5883L (data 0x03, big-endian, X/Z/Y order) ----
static bool hmc5883lInit(uint8_t a) {
  i2cWrite(a, 0x00, 0x70);                        // 8-sample average, 15 Hz
  i2cWrite(a, 0x01, 0x20);                        // gain = 1.3 Ga
  return i2cWrite(a, 0x02, 0x00);                 // continuous measurement
}

static bool hmc5883lRead(uint8_t a, int32_t &x, int32_t &y, int32_t &z) {
  Wire.beginTransmission(a);
  Wire.write(0x03);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)a, 6) != 6) return false;
  uint8_t xh = Wire.read(), xl = Wire.read();
  uint8_t zh = Wire.read(), zl = Wire.read();
  uint8_t yh = Wire.read(), yl = Wire.read();
  x = (int16_t)((xh << 8) | xl);
  y = (int16_t)((yh << 8) | yl);
  z = (int16_t)((zh << 8) | zl);
  return true;
}

static bool chipInit(uint8_t t, uint8_t a) {
  switch (t) {
    case CHIP_QST_NEW:  return qstNewInit(a);
    case CHIP_QMC5883L: return qmc5883lInit(a);
    case CHIP_HMC5883L: return hmc5883lInit(a);
    default:            return false;
  }
}

/**
 * Probe the bus and initialize the first supported sensor.
 * Uses the manual cfgAddr when set (0 = AUTO -> try all known addresses).
 */
static bool sensorDetect() {
  chip = CHIP_NONE; curAddr = 0;

  uint8_t candidates[4];
  uint8_t n = 0;
  if (cfgAddr != 0) {
    candidates[n++] = cfgAddr;
  } else {
    for (uint8_t i = 0; i < sizeof(AUTO_ADDRS); i++) candidates[n++] = AUTO_ADDRS[i];
  }

  for (uint8_t i = 0; i < n; i++) {
    uint8_t a = candidates[i];
    if (!i2cProbe(a)) continue;

    uint8_t t;
    if      (a == 0x2C || a == 0x1C) t = CHIP_QST_NEW;   // QMC5883P / QMC6310
    else if (a == 0x0D)              t = CHIP_QMC5883L;
    else if (a == 0x1E)              t = CHIP_HMC5883L;
    else                             t = CHIP_QST_NEW;   // manual unknown: try QST layout

    if (chipInit(t, a)) {
      curAddr = a;
      chip = t;
      char ab[8];
      snprintf(ab, sizeof(ab), "0x%02X", curAddr);
      char msg[96];
      snprintf(msg, sizeof(msg), "[I2C] Sensor: %s @ %s | chip ID 0x00 = 0x%02X",
               chipName(chip), ab, readChipId(curAddr));
      Serial.println(msg);
      sendLine(chipInfoLine());
      return true;
    }
  }
  Serial.println("[I2C] No supported sensor found");
  return false;
}

static bool sensorInit() { return sensorDetect(); }

static bool sensorRead(int32_t &x, int32_t &y, int32_t &z) {
  switch (chip) {
    case CHIP_QST_NEW:  return qstNewRead(curAddr, x, y, z);
    case CHIP_QMC5883L: return qmc5883lRead(curAddr, x, y, z);
    case CHIP_HMC5883L: return hmc5883lRead(curAddr, x, y, z);
    default:            return false;
  }
}

static void handleReadFailure() {
  failCount++;
  if (failCount >= 100) {                    // ~2 s of consecutive failures @50 Hz
    failCount = 0;
    sensorLost = true;
    chip = CHIP_NONE;
    state = ST_CAL;                          // wait for the sensor to come back
    sendLine("ERR=I2C_LOST");
    Serial.println("[I2C] Sensor lost — re-detecting every 2 s");
  }
}

static void probeSensorRecovery(uint32_t now) {
  if (now - lastProbeTick < 2000) return;
  lastProbeTick = now;
  if (sensorDetect()) {
    sensorLost = false;
    sendLine(chipInfoLine());
    startCalibration();                      // fresh baseline for the new session
    Serial.println("[I2C] Sensor recovered");
  } else if (now - lastLostPrint >= 5000) {
    lastLostPrint = now;
    Serial.println("[I2C] Still no sensor — check wiring (SDA=8, SCL=9, 3V3, GND)");
  }
}

static void runBusScan() {
  String found = "";
  for (uint8_t a = 8; a < 120; a++) {
    if (i2cProbe(a)) {
      if (found.length()) found += ",";
      char b[8];
      snprintf(b, sizeof(b), "0x%02X", a);
      found += b;
    }
  }
  String msg = "I=SCAN:";
  msg += (found.length() ? found : String("none"));
  Serial.println("[I2C] " + msg);
  sendLine(msg);
}

static void handleAddr(const String& val) {
  String v = val;
  v.trim();
  uint8_t a;
  if (v.equalsIgnoreCase("AUTO")) {
    a = 0;
  } else {
    long n;
    if (v.startsWith("0x") || v.startsWith("0X")) n = strtol(v.c_str() + 2, nullptr, 16);
    else                                          n = v.toInt();
    if (n < 8 || n > 119) { sendLine("ERR=ADDR_RANGE"); return; }
    a = (uint8_t)n;
  }
  cfgAddr = a;
  nvs.putInt("i2cAddr", (int32_t)cfgAddr);
  sendLine("OK=ADDR");
  if (sensorDetect()) {
    startCalibration();                      // re-baseline for the addressed sensor
  } else {
    sendLine("ERR=ADDR_NO_DEV");
  }
}

#elif SENSOR_TYPE == SENSOR_TYPE_SIM
// ---- Simulator: slow drift + a periodic "vehicle" bump every ~25 s ----
static bool sensorInit() { return true; }

static bool sensorRead(int32_t &x, int32_t &y, int32_t &z) {
  static uint32_t t = 0;
  t++;
  int32_t bump = 0;
  uint32_t phase = t % 12500;                    // ~25 s at the 2 ms read tick
  if (phase > 2500 && phase < 6500) bump = 900;  // a car "arrives" for ~8 s
  x = 1200 + (int32_t)(60.0 * sin(t * 0.01)) + bump;
  y = -800 + (int32_t)(45.0 * cos(t * 0.013)) + bump / 2;
  z = 3000 + (int32_t)(30.0 * sin(t * 0.007));
  return true;
}
#endif  // SENSOR_TYPE

// ----------------------------- Calibration ---------------------------------

static void startCalibration() {
  state = ST_CAL;
  calibrating = true;
  calStart = millis();
  calSumX = calSumY = calSumZ = 0;
  calCount = 0;
  sendState();
}

static void resetBaseline() {
  baseX = (float)rawX;
  baseY = (float)rawY;
  baseZ = (float)rawZ;
  if (state != ST_CAL) {
    state = ST_CLEAR;
    sendState();
  }
  sendBaseline();
}

// ----------------------------- Commands ------------------------------------

static void applyIntParam(const String& key, const String& val, int32_t &target,
                          int32_t lo, int32_t hi) {
  if (!val.length()) {
    sendLine("ERR=" + key + "_EMPTY");
    return;
  }
  int32_t v = val.toInt();
  if (v < lo || v > hi) {
    sendLine("ERR=" + key + "_RANGE");
    return;
  }
  target = v;
  saveParams();
  sendLine("OK=" + key);
}

static void handleCommand(const String& line) {
  int eq = line.indexOf('=');
  String key = (eq < 0) ? line : line.substring(0, eq);
  String val = (eq < 0) ? String("") : line.substring(eq + 1);
  key.trim();
  key.toUpperCase();
  val.trim();

  if (key == "ON") {
    applyIntParam("ON", val, TH_ON, 1, 100000);
  } else if (key == "OFF") {
    applyIntParam("OFF", val, TH_OFF, 0, 100000);
  } else if (key == "ONT") {
    applyIntParam("ONT", val, ON_TIME_MS, 0, 600000);
  } else if (key == "OFFT") {
    applyIntParam("OFFT", val, OFF_TIME_MS, 0, 600000);
  } else if (key == "ALPHA") {
    float a = val.toFloat();
    if (a <= 0.0f || a > 1.0f) {
      sendLine("ERR=ALPHA_RANGE");
      return;
    }
    BASELINE_ALPHA = a;
    saveParams();
    sendLine("OK=ALPHA");
  } else if (key == "CAL") {
    startCalibration();
    sendLine("OK=CAL");
  } else if (key == "RS") {
    resetBaseline();
    sendLine("OK=RS");
  } else if (key == "SHOW") {
    streamOn = true;
    sendParams();          // answer with the current parameter set
    sendLine("OK=SHOW");
  } else if (key == "HIDE") {
    streamOn = false;
    sendLine("OK=HIDE");
  } else if (key == "RAW") {
    rawStreamOn = true;
    sendLine("OK=RAW");
  } else if (key == "NRAW") {
    rawStreamOn = false;
    sendLine("OK=NRAW");
#if SENSOR_TYPE == SENSOR_TYPE_QMC
  } else if (key == "SCAN") {
    runBusScan();
  } else if (key == "ADDR") {
    handleAddr(val);
#endif
  } else {
    sendLine("ERR=UNKNOWN_CMD");
  }
}

// ----------------------------- BLE callbacks -------------------------------

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String chunk(c->getValue().c_str());
    rxLine += chunk;
    int idx;
    while ((idx = rxLine.indexOf('\n')) >= 0) {
      String line = rxLine.substring(0, idx);
      rxLine = rxLine.substring(idx + 1);
      line.trim();
      if (line.length()) handleCommand(line);
    }
    if (rxLine.length() > 128) rxLine = "";   // safety valve
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    deviceConnected = true;
    needInitMsg = true;
    connectAt = millis();
  }
  void onDisconnect(BLEServer *s) override {
    deviceConnected = false;
    BLEDevice::startAdvertising();            // keep discoverable for the next run
  }
};

// ----------------------------- Setup / Loop --------------------------------

void setup() {
  Serial.begin(115200);
  delay(200);

#if LED_PIN >= 0
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
#endif

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);
  Wire.setTimeOut(50);                        // 50 ms bus timeout — never block the loop

  nvs.begin("ms-cfg", false);
  loadParams();

  Serial.println();
  Serial.println("=== Metal Sensor v1.1 — I2C auto-detect (0x2C/0x1C/0x0D/0x1E) ===");

#if SENSOR_TYPE == SENSOR_TYPE_QMC
  sensorLost = !sensorDetect();
  if (sensorLost) {
    Serial.println("[I2C] Will retry every 2 s. Wiring: SDA=GPIO8, SCL=GPIO9, 3V3, GND");
  }
#endif

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(135);                     // allow longer notify payloads

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *svc = bleServer->createService(SVC_UUID);

  BLECharacteristic *rxChar = svc->createCharacteristic(
      CHR_RX_UUID, BLECharacteristic::PROPERTY_WRITE);
  rxChar->setCallbacks(new RxCallbacks());

  txChar = svc->createCharacteristic(
      CHR_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());       // client characteristic config

  svc->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("BLE advertising as: " DEVICE_NAME);
  startCalibration();                          // calibrate on boot
}

void loop() {
  uint32_t now = millis();

  // ---- sensor read @ ~50 Hz ----
  if (now - lastSensorTick >= 20) {
    lastSensorTick = now;

    int32_t x, y, z;
    bool ok = false;
#if SENSOR_TYPE == SENSOR_TYPE_QMC
    if (sensorLost) {
      probeSensorRecovery(now);
    } else {
      ok = sensorRead(x, y, z);
      if (!ok) handleReadFailure();
    }
#else
    ok = sensorRead(x, y, z);
#endif

    if (ok) {
      rawX = x; rawY = y; rawZ = z;

      switch (state) {

        case ST_CAL: {
          calSumX += x; calSumY += y; calSumZ += z;
          calCount++;
          delta = 0;
          if (now - calStart >= 2000 && calCount > 0) {
            baseX = (float)(calSumX / (double)calCount);
            baseY = (float)(calSumY / (double)calCount);
            baseZ = (float)(calSumZ / (double)calCount);
            calibrating = false;
            state = ST_CLEAR;
            sendState();
            sendBaseline();
            Serial.println("Calibration done.");
          }
          break;
        }

        case ST_CLEAR: {
          // Baseline only tracks while CLEAR — never while a car is present.
          baseX += ((float)x - baseX) * BASELINE_ALPHA;
          baseY += ((float)y - baseY) * BASELINE_ALPHA;
          baseZ += ((float)z - baseZ) * BASELINE_ALPHA;
          delta = (int32_t)(fabsf((float)x - baseX) +
                            fabsf((float)y - baseY) +
                            fabsf((float)z - baseZ));
          if (delta >= TH_ON) {
            state = ST_DETECTING;
            detectStart = now;
            sendState();
          }
          break;
        }

        case ST_DETECTING: {
          delta = (int32_t)(fabsf((float)x - baseX) +
                            fabsf((float)y - baseY) +
                            fabsf((float)z - baseZ));
          if (delta < TH_OFF) {
            state = ST_CLEAR;                  // brief glitch, back to CLEAR
            sendState();
          } else if (delta >= TH_ON && (now - detectStart) >= (uint32_t)ON_TIME_MS) {
            state = ST_OCCUPIED;               // confirmed: vehicle present
            releaseStart = 0;
            sendState();
          }
          break;
        }

        case ST_OCCUPIED: {
          delta = (int32_t)(fabsf((float)x - baseX) +
                            fabsf((float)y - baseY) +
                            fabsf((float)z - baseZ));
          if (delta <= TH_OFF) {
            if (releaseStart == 0) releaseStart = now;
            if ((now - releaseStart) >= (uint32_t)OFF_TIME_MS) {
              state = ST_CLEAR;                // vehicle confirmed gone
              releaseStart = 0;
              sendState();
            }
          } else {
            releaseStart = 0;                  // still occupied, restart the timer
          }
          break;
        }
      }

#if LED_PIN >= 0
      // LED gives immediate feedback: ON as soon as the threshold is crossed
      // (DETECTING) and stays ON while the vehicle is present (OCCUPIED).
      digitalWrite(LED_PIN,
                   (state == ST_DETECTING || state == ST_OCCUPIED) ? HIGH : LOW);
#endif
    }
  }

  // ---- initial S/B/A/I right after connect ----
  if (deviceConnected && needInitMsg && (now - connectAt) >= 300) {
    needInitMsg = false;
    sendState();
    sendBaseline();
    sendParams();
#if SENSOR_TYPE == SENSOR_TYPE_QMC
    sendLine(chipInfoLine());                  // e.g. I=CHIP:QMC5883P/QMC6310@0x2C
#endif
  }

  // ---- live stream @ 5 Hz ----
  if (deviceConnected && streamOn && (now - lastStreamTick) >= 200) {
    lastStreamTick = now;
    if (rawStreamOn) sendRaw();
    char buf[24];
    snprintf(buf, sizeof(buf), "D=%d", (int)delta);
    sendLine(String(buf));
    if ((now - lastBaseTick) >= 2000) {
      lastBaseTick = now;
      sendBaseline();
    }
  }
}
