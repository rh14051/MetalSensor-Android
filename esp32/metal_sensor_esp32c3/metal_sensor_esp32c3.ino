/*
 * ============================================================================
 *  Metal Sensor (سنسور فلز) — ESP32-C3 Vehicle Detector Firmware
 * ============================================================================
 *  Companion firmware for the "سنسور فلز" Android tuning app.
 *
 *  Board   : ESP32-C3 (SuperMini / DevKitM-1), Arduino framework
 *  Sensor  : QMC5883L magnetometer on I2C (default)
 *            ...or the built-in simulator (SENSOR_TYPE_SIM) for app testing
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
 *    (anything else)                       -> ERR=UNKNOWN_CMD
 *
 *  TX (notifications to the app):
 *    D=<delta>            current delta (every 200 ms while streaming)
 *    S=CAL|CLEAR|DETECTING|OCCUPIED   state (sent on every change)
 *    B=<bx,by,bz>         baseline (every 2 s while streaming)
 *    R=<x,y,z>            raw values (every 200 ms while RAW enabled)
 *    A=<on,off,ont,offt,alpha>   current parameters (answer to SHOW / on connect)
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
#define LED_PIN  -1      // optional status LED; set a GPIO number or -1 to disable

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

#if SENSOR_TYPE == SENSOR_TYPE_QMC
// ---- QMC5883L (address 0x0D), continuous mode, 8 gauss, 200 Hz ----
#define QMC_ADDR 0x0D

static bool sensorInit() {
  Wire.beginTransmission(QMC_ADDR);
  Wire.write(0x0B); Wire.write(0x01);          // SET/RESET period register
  if (Wire.endTransmission() != 0) return false;
  Wire.beginTransmission(QMC_ADDR);
  Wire.write(0x09); Wire.write(0x1D);          // CONFIG: OSR=512, RNG=8G, ODR=200Hz, CONT mode
  return Wire.endTransmission() == 0;
}

static bool sensorRead(int32_t &x, int32_t &y, int32_t &z) {
  Wire.beginTransmission(QMC_ADDR);
  Wire.write(0x00);                             // data output X MSB? -> QMC is LSB first
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(QMC_ADDR, 6) != 6) return false;
  uint8_t xl = Wire.read(), xh = Wire.read();
  uint8_t yl = Wire.read(), yh = Wire.read();
  uint8_t zl = Wire.read(), zh = Wire.read();
  x = (int16_t)(xl | (xh << 8));
  y = (int16_t)(yl | (yh << 8));
  z = (int16_t)(zl | (zh << 8));
  return true;
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
#endif

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

  nvs.begin("ms-cfg", false);
  loadParams();

  if (!sensorInit()) {
    Serial.println("Sensor init FAILED — check wiring/address!");
  }

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
    if (sensorRead(x, y, z)) {
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
      digitalWrite(LED_PIN, state == ST_OCCUPIED ? HIGH : LOW);
#endif
    }
  }

  // ---- initial S/B/A right after connect ----
  if (deviceConnected && needInitMsg && (now - connectAt) >= 300) {
    needInitMsg = false;
    sendState();
    sendBaseline();
    sendParams();
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
