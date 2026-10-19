/*
  SMART MICRO-PUMP — WATER-ONLY BENCH RESEARCH PROTOTYPE (wiring verified)
  Not a medical device. Never connect to a person or use for IV delivery.
  Commands on Serial (newline terminated):
    SET_FLOW 2.0 | START | PAUSE | STOP | ACK_ALARM | RESUME
    CAL 140 | CAL_RESULT 10.0 | CAL_STOP | STATUS
*/
#include <Arduino.h>
#include <Preferences.h>
#include <Wire.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Adafruit_INA219.h>
// The watchdog is an ESP32 hardware feature. The browser simulator has no task watchdog.
#if defined(ESP32)
#include <esp_task_wdt.h>
#endif


struct CalibrationPoint { uint8_t pwm; float dropsPerSecond; float mlPerMin; };

enum PumpState { IDLE, CALIBRATING, RUNNING, PAUSED, COMPLETE, OCCLUSION_ALARM, FAULT };

enum FaultReason { NO_FAULT, STOP_PRESSED, OCCLUSION, NO_FLOW, PRESSURE_SENSOR_FAULT, IR_SENSOR_FAULT, NETWORK_TIMEOUT };


// Forward declarations
void IRAM_ATTR onDrop();
void writePumpPwm(uint8_t duty);
void pumpOff();
void alarmOutputs(bool active);
void latchFault(FaultReason why);
bool physicalStopPressed();
float readPressureKpa(bool &valid);
bool conditionsSafe();
void beginRun();
void pausePump();
void controlAndSafety();
void handleCommand(String command, bool remote);

constexpr int AIN1_PIN = 25;
constexpr int AIN2_PIN = 26;
constexpr int PUMP_PWM_PIN = 27;
constexpr int DROP_PIN = 32;
constexpr int STOP_PIN = 33;
constexpr int HX710B_DOUT_PIN = 13;
constexpr int HX710B_SCK_PIN = 14;
constexpr int ALARM_LED_PIN = 4;
constexpr int BUZZER_PIN = 23;
constexpr int SPEED_POT_PIN = 34;
constexpr int I2C_SDA_PIN = 21;
constexpr int I2C_SCL_PIN = 22;
constexpr uint8_t OLED_I2C_ADDRESS = 0x3C;

// Password-protected local Wi-Fi access point for the companion app.
// Change both values before sharing the device; the password must be at least 8 characters.
constexpr char DEFAULT_WIFI_AP_SSID[] = "WaterBench-ESP32";
constexpr char WIFI_AP_PASSWORD[] = "WaterBench2026";
constexpr uint32_t APP_LINK_TIMEOUT_MS = 10000;
constexpr uint32_t WIFI_RESTART_DELAY_MS = 750;

// ---- Experiment-specific constants: calibrate these with water before use ----
constexpr float KP = 28.0f;                 // PWM counts per (mL/min) error
constexpr float KI = 4.0f;                  // PWM counts per (mL/min*s) error
constexpr float KD = 2.0f;                  // PWM counts per (mL/min)/s error
constexpr uint8_t MIN_PWM = 55;             // experimentally find the pump's reliable start value
constexpr uint8_t MAX_PWM = 180;            // local hardware rate clamp; never exceed without revalidation
constexpr float LOCAL_MAX_FLOW_ML_MIN = 5.0f;
constexpr float DEFAULT_DROPS_PER_ML = 20.0f; // REPLACE using CAL_RESULT measurement
constexpr float DEFAULT_TARGET_FLOW_ML_MIN = 2.0f;
constexpr uint32_t SENSOR_TIMEOUT_MS = 12000;
constexpr float OCCLUSION_PRESSURE_KPA = 30.0f; // This module measures 0-40 kPa.
// Calibrate counts-per-kPa with a known pressure reference before using displayed kPa as a measurement.
constexpr float HX710B_COUNTS_PER_KPA = 100000.0f;
constexpr uint32_t HX710B_READY_TIMEOUT_MS = 120;
constexpr uint32_t CONTROL_PERIOD_MS = 250;
constexpr uint32_t DROP_DEBOUNCE_US = 2500;
constexpr uint32_t NETWORK_COMMAND_TIMEOUT_MS = 30000;
constexpr uint32_t STARTUP_NO_FLOW_GRACE_MS = 4000;
constexpr uint32_t DOSE_TARGET_DROPS = 3;  // Stop and sound the alarm after this many detected drops.
constexpr uint32_t WDT_TIMEOUT_S = 4;
constexpr uint32_t PWM_FREQ_HZ = 18000;
constexpr uint8_t PWM_BITS = 8;

// Calibration table is a worksheet starter, not a pump model. Populate during water tests.

CalibrationPoint calibrationTable[] = {
  {60, 0.0f, 0.0f}, {100, 0.0f, 0.0f}, {140, 0.0f, 0.0f}, {180, 0.0f, 0.0f}
};
constexpr size_t CAL_POINTS = sizeof(calibrationTable) / sizeof(calibrationTable[0]);




volatile uint32_t totalDrops = 0;
volatile uint32_t windowDrops = 0;
volatile uint32_t lastDropUs = 0;
volatile uint32_t lastDropMs = 0;
PumpState state = IDLE;
FaultReason fault = NO_FAULT;
Preferences preferences;
WebServer server(80);
Adafruit_SSD1306 display(128, 64, &Wire, -1);
Adafruit_INA219 ina219;
bool oledPresent = false;
bool ina219Present = false;
float pumpBusVoltageV = 0.0f;
float pumpCurrentMa = 0.0f;

float targetFlowMlMin = DEFAULT_TARGET_FLOW_ML_MIN;
float dropsPerMl = DEFAULT_DROPS_PER_ML;
float measuredDropsSec = 0.0f, measuredFlowMlMin = 0.0f;
float pressureKpa = 0.0f;
float integral = 0.0f, priorError = 0.0f;
uint8_t pwmDuty = 0;
uint8_t knobMaxPwm = MAX_PWM;
int32_t hx710bZeroRaw = 0;
bool hx710bPresent = false;
uint32_t lastControlMs = 0, runStartedMs = 0;
// A recent USB command is the only truthful indication available that the USB control app is communicating.
uint32_t lastUsbCommandMs = 0;
// Updated by an app status poll or command. This indicates a real app exchange, not merely Wi-Fi power.
uint32_t lastAppContactMs = 0;
uint32_t runStartDrops = 0;
uint32_t calibrationStartDrops = 0, calibrationStartMs = 0;
uint8_t calibrationPwm = 0;
String wifiApSsid = DEFAULT_WIFI_AP_SSID;
bool wifiRestartPending = false;
uint32_t wifiRestartAtMs = 0;
// This bench controller remains local: the companion app uses the ESP32's own Wi-Fi network.

const char *stateName(PumpState s) {
  switch (s) { case IDLE:return "IDLE"; case CALIBRATING:return "CALIBRATING"; case RUNNING:return "RUNNING"; case PAUSED:return "PAUSED"; case COMPLETE:return "COMPLETE"; case OCCLUSION_ALARM:return "OCCLUSION_ALARM"; default:return "FAULT"; }
}
const char *faultName(FaultReason f) {
  switch (f) { case NO_FAULT:return "NONE"; case STOP_PRESSED:return "PHYSICAL_STOP"; case OCCLUSION:return "OCCLUSION"; case NO_FLOW:return "NO_FLOW_OR_AIR"; case PRESSURE_SENSOR_FAULT:return "PRESSURE_SENSOR"; case IR_SENSOR_FAULT:return "IR_SENSOR"; default:return "NETWORK_TIMEOUT"; }
}

String makeStatusJson() {
  uint32_t doseDrops = 0;
  if (state == RUNNING || state == CALIBRATING || state == PAUSED || state == COMPLETE) {
    noInterrupts(); doseDrops = totalDrops - runStartDrops; interrupts();
  }
  doseDrops = min(doseDrops, DOSE_TARGET_DROPS);
  const bool appActive = lastAppContactMs != 0 && millis() - lastAppContactMs <= APP_LINK_TIMEOUT_MS;
  String json = "{";
  json += "\"state\":\"" + String(stateName(state)) + "\",";
  json += "\"fault\":\"" + String(faultName(fault)) + "\",";
  json += "\"appLinkActive\":" + String(appActive ? "true" : "false") + ",";
  json += "\"wifiClients\":" + String(WiFi.softAPgetStationNum()) + ",";
  json += "\"wifiName\":\"" + wifiApSsid + "\",";
  json += "\"targetDrops\":" + String(DOSE_TARGET_DROPS) + ",";
  json += "\"dropsDelivered\":" + String(doseDrops) + ",";
  json += "\"dropsRemaining\":" + String(DOSE_TARGET_DROPS - doseDrops) + ",";
  json += "\"targetFlowMlMin\":" + String(targetFlowMlMin, 3) + ",";
  json += "\"flowMlMin\":" + String(measuredFlowMlMin, 3) + ",";
  json += "\"dropsPerSecond\":" + String(measuredDropsSec, 3) + ",";
  json += "\"pwm\":" + String(pwmDuty) + ",";
  json += "\"motorPercent\":" + String((uint8_t)roundf(100.0f * pwmDuty / 255.0f)) + ",";
  json += "\"motorVoltageEstimateV\":" + String(5.0f * pwmDuty / 255.0f, 2) + ",";
  json += "\"busVoltageV\":" + String(pumpBusVoltageV, 2) + ",";
  json += "\"currentMa\":" + String(pumpCurrentMa, 0) + ",";
  json += "\"pressureKpa\":" + String(pressureKpa, 2) + ",";
  json += "\"pressureSensorPresent\":" + String(hx710bPresent ? "true" : "false") + ",";
  json += "\"currentMonitorPresent\":" + String(ina219Present ? "true" : "false") + ",";
  json += "\"physicalStopPressed\":" + String(physicalStopPressed() ? "true" : "false");
  json += "}";
  return json;
}
void sendCorsHeaders() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}
void handleStatusApi() {
  lastAppContactMs = millis();
  sendCorsHeaders();
  server.send(200, "application/json", makeStatusJson());
}
void handleCommandApi() {
  lastAppContactMs = millis();
  String command = server.hasArg("command") ? server.arg("command") : server.arg("plain");
  // Accept either a form field (command=START) or {"command":"START"} JSON.
  if (command.startsWith("{")) {
    int key = command.indexOf("\"command\"");
    int colon = command.indexOf(':', key);
    int firstQuote = command.indexOf('"', colon + 1);
    int lastQuote = command.indexOf('"', firstQuote + 1);
    command = (key >= 0 && colon >= 0 && firstQuote >= 0 && lastQuote > firstQuote) ? command.substring(firstQuote + 1, lastQuote) : "";
  }
  handleCommand(command, true);
  sendCorsHeaders();
  server.send(200, "application/json", makeStatusJson());
}
void handleApiOptions() { sendCorsHeaders(); server.send(204); }
bool isValidWifiName(const String &name) {
  if (name.length() < 1 || name.length() > 31) return false;
  for (size_t i = 0; i < name.length(); ++i) {
    char c = name.charAt(i);
    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
  }
  return true;
}

void restartWirelessAp() {
  WiFi.softAPdisconnect(true);
  delay(100);
  WiFi.mode(WIFI_AP);
  bool started = WiFi.softAP(wifiApSsid.c_str(), WIFI_AP_PASSWORD, 6, false, 4);
  lastAppContactMs = 0;
  if (started) Serial.printf("Wi-Fi renamed: %s ready at http://%s/api/status\n", wifiApSsid.c_str(), WiFi.softAPIP().toString().c_str());
  else Serial.println("Wi-Fi AP could not restart; USB Serial control remains available.");
}

void startWirelessApi() {
  WiFi.mode(WIFI_AP);
  bool started = WiFi.softAP(wifiApSsid.c_str(), WIFI_AP_PASSWORD, 6, false, 4);
  server.on("/api/status", HTTP_GET, handleStatusApi);
  server.on("/api/command", HTTP_POST, handleCommandApi);
  server.on("/api/status", HTTP_OPTIONS, handleApiOptions);
  server.on("/api/command", HTTP_OPTIONS, handleApiOptions);
  server.onNotFound([]() { sendCorsHeaders(); server.send(404, "application/json", "{\"error\":\"not found\"}"); });
  server.begin();
  if (started) Serial.printf("Wi-Fi AP %s ready at http://%s/api/status\n", wifiApSsid.c_str(), WiFi.softAPIP().toString().c_str());
  else Serial.println("Wi-Fi AP could not start; USB Serial control remains available.");
}

void IRAM_ATTR onDrop() {
  uint32_t now = micros();
  if ((uint32_t)(now - lastDropUs) >= DROP_DEBOUNCE_US) {
    totalDrops++; windowDrops++; lastDropUs = now; lastDropMs = millis();
  }
}

void writePumpPwm(uint8_t duty) {
  ledcWrite(PUMP_PWM_PIN, duty);
}
void pumpOff() {
  writePumpPwm(0); pwmDuty = 0;
  digitalWrite(AIN1_PIN, LOW); digitalWrite(AIN2_PIN, LOW);
}
void alarmOutputs(bool active) {
  digitalWrite(ALARM_LED_PIN, active ? HIGH : LOW);
  digitalWrite(BUZZER_PIN, active ? HIGH : LOW);
}
void latchFault(FaultReason why) {
  pumpOff(); fault = why;
  state = (why == OCCLUSION) ? OCCLUSION_ALARM : FAULT;
  alarmOutputs(true);
  Serial.printf("ALARM,%s\n", faultName(why));
}
bool physicalStopPressed() { return digitalRead(STOP_PIN) == LOW; }

// The knob sets an upper speed limit through PWM; it never carries pump current.
void updateSpeedLimit() {
  int raw = analogRead(SPEED_POT_PIN);
  knobMaxPwm = (uint8_t)constrain(map(raw, 0, 4095, MIN_PWM, MAX_PWM), MIN_PWM, MAX_PWM);
}

// HX710B is HX711-style: DOUT goes LOW when a 24-bit sample is ready; SCK clocks it out.
bool readHx710bRaw(int32_t &raw) {
  uint32_t started = millis();
  while (digitalRead(HX710B_DOUT_PIN) == HIGH) {
    if (millis() - started >= HX710B_READY_TIMEOUT_MS) return false;
    delay(1);
  }
  uint32_t value = 0;
  for (uint8_t bit = 0; bit < 24; ++bit) {
    digitalWrite(HX710B_SCK_PIN, HIGH); delayMicroseconds(1);
    value = (value << 1) | (digitalRead(HX710B_DOUT_PIN) ? 1U : 0U);
    digitalWrite(HX710B_SCK_PIN, LOW); delayMicroseconds(1);
  }
  // One extra pulse selects channel A at gain 128 for the next conversion.
  digitalWrite(HX710B_SCK_PIN, HIGH); delayMicroseconds(1);
  digitalWrite(HX710B_SCK_PIN, LOW); delayMicroseconds(1);
  if (value & 0x800000UL) value |= 0xFF000000UL;
  raw = (int32_t)value;
  return true;
}

float readPressureKpa(bool &valid) {
  int32_t raw = 0;
  valid = hx710bPresent && readHx710bRaw(raw);
  if (!valid) return 0.0f;
  float kpa = (raw - hx710bZeroRaw) / HX710B_COUNTS_PER_KPA;
  return constrain(kpa, 0.0f, 40.0f);
}

bool conditionsSafe() {
  bool pressureValid = false;
  float p = readPressureKpa(pressureValid);
  return !physicalStopPressed() && pressureValid && p < OCCLUSION_PRESSURE_KPA;
}
void beginRun() {
  if (!conditionsSafe()) { latchFault(physicalStopPressed() ? STOP_PRESSED : PRESSURE_SENSOR_FAULT); return; }
  integral = 0; priorError = 0; runStartedMs = millis();
  noInterrupts(); runStartDrops = totalDrops; windowDrops = 0; interrupts();
  state = RUNNING; fault = NO_FAULT; alarmOutputs(false);
  updateSpeedLimit();
  pwmDuty = MIN_PWM; digitalWrite(AIN1_PIN, HIGH); digitalWrite(AIN2_PIN, LOW); writePumpPwm(pwmDuty);
}
void pausePump() { pumpOff(); if (state == RUNNING || state == CALIBRATING) state = PAUSED; }

void completeDose() {
  pumpOff();
  state = COMPLETE;
  alarmOutputs(true);  // Active buzzer and LED remain on until acknowledged.
  Serial.printf("COMPLETE,%lu drops: pump stopped\n", (unsigned long)DOSE_TARGET_DROPS);
}

void controlAndSafety() {
  if (physicalStopPressed()) { if (state != FAULT || fault != STOP_PRESSED) latchFault(STOP_PRESSED); return; }
  if (state == RUNNING) {
    noInterrupts(); uint32_t dropsThisRun = totalDrops - runStartDrops; interrupts();
    if (dropsThisRun >= DOSE_TARGET_DROPS) { completeDose(); return; }
  }
  bool pressureValid = false;
  pressureKpa = readPressureKpa(pressureValid);
  if (!pressureValid) { if (state == RUNNING || state == CALIBRATING) latchFault(PRESSURE_SENSOR_FAULT); return; }
  if (pressureKpa >= OCCLUSION_PRESSURE_KPA && (state == RUNNING || state == CALIBRATING)) { latchFault(OCCLUSION); return; }
  if (state != RUNNING) return;
  updateSpeedLimit();
  uint32_t now = millis();
  if (pwmDuty >= MIN_PWM && now - runStartedMs > STARTUP_NO_FLOW_GRACE_MS && now - lastDropMs > SENSOR_TIMEOUT_MS) { latchFault(NO_FLOW); return; }
  if (now - lastControlMs < CONTROL_PERIOD_MS) return;
  float dt = (now - lastControlMs) / 1000.0f; lastControlMs = now;
  noInterrupts(); uint32_t drops = windowDrops; windowDrops = 0; interrupts();
  measuredDropsSec = drops / dt;
  measuredFlowMlMin = measuredDropsSec * 60.0f / dropsPerMl;
  float error = targetFlowMlMin - measuredFlowMlMin;
  float candidateIntegral = constrain(integral + error * dt, -20.0f, 20.0f);
  float derivative = (error - priorError) / dt;
  float output = KP * error + KI * candidateIntegral + KD * derivative;
  float requested = pwmDuty + output;
  uint8_t constrained = (uint8_t)constrain(requested, (float)MIN_PWM, (float)knobMaxPwm);
  // Only retain the integral when saturation is not forcing the output farther outward.
  if ((requested == constrained) || (requested > knobMaxPwm && error < 0) || (requested < MIN_PWM && error > 0)) integral = candidateIntegral;
  priorError = error; pwmDuty = constrained; writePumpPwm(pwmDuty);
}
void handleCommand(String command, bool remote) {
  command.trim();
  String originalCommand = command;
  command.toUpperCase();
  if (remote) lastAppContactMs = millis();
  else lastUsbCommandMs = millis();
  if (command.startsWith("SET_WIFI_NAME ")) {
    String requestedName = originalCommand.substring(14);
    requestedName.trim();
    if ((state == IDLE || (state == PAUSED && fault == NO_FAULT && conditionsSafe())) && isValidWifiName(requestedName)) {
      wifiApSsid = requestedName;
      preferences.putString("wifiName", wifiApSsid);
      wifiRestartPending = true;
      wifiRestartAtMs = millis() + WIFI_RESTART_DELAY_MS;
      Serial.printf("Wi-Fi rename accepted: reconnect to %s after the network restarts.\n", wifiApSsid.c_str());
    } else Serial.println("REJECTED: Wi-Fi name needs 1-31 letters, numbers, hyphens, or underscores; pump must be idle or safely paused");
  } else if (command.startsWith("SET_FLOW ")) {
    float requested = command.substring(9).toFloat();
    if (requested > 0.0f && requested <= LOCAL_MAX_FLOW_ML_MIN) targetFlowMlMin = requested;
    else Serial.println("REJECTED: flow exceeds local clamp or is invalid");
  } else if (command == "START") {
    if (state == IDLE || state == PAUSED) beginRun();
    else Serial.println("REJECTED: acknowledge faults, then explicitly RESUME");
  } else if (command == "PAUSE") pausePump();
  else if (command == "STOP") latchFault(STOP_PRESSED);
  else if (command == "ACK_ALARM") {
    if (state == COMPLETE) { state = PAUSED; alarmOutputs(false); Serial.println("DOSE COMPLETE: acknowledged; now use START for the next three-drop dose"); }
    else if ((state == FAULT || state == OCCLUSION_ALARM) && conditionsSafe()) { fault = NO_FAULT; state = PAUSED; alarmOutputs(false); Serial.println("ACKNOWLEDGED: now use RESUME"); }
    else Serial.println("REJECTED: release STOP and remove pressure/sensor fault first");
  } else if (command == "RESUME") {
    if (state == PAUSED && fault == NO_FAULT && conditionsSafe()) beginRun();
    else Serial.println("REJECTED: safety conditions are not verified");
  } else if (command.startsWith("CAL ")) {
    if (state == IDLE || state == PAUSED) { calibrationPwm = constrain(command.substring(4).toInt(), MIN_PWM, MAX_PWM); calibrationStartDrops = totalDrops; calibrationStartMs = millis(); state = CALIBRATING; fault = NO_FAULT; alarmOutputs(false); digitalWrite(AIN1_PIN,HIGH); digitalWrite(AIN2_PIN,LOW); pwmDuty = calibrationPwm; writePumpPwm(pwmDuty); Serial.println("CAL running: collect water, then send CAL_RESULT <measured_mL>"); }
  } else if (command.startsWith("CAL_RESULT ")) {
    if (state == CALIBRATING) { float volume = command.substring(11).toFloat(); uint32_t d = totalDrops - calibrationStartDrops; float sec = (millis()-calibrationStartMs)/1000.0f; pumpOff(); state = PAUSED; if (volume > 0.0f && sec > 0.0f) { dropsPerMl = d / volume; preferences.putFloat("dropsMl", dropsPerMl); Serial.printf("CAL,%u,%.3f drops/s,%.3f mL/min,%.3f drops/mL SAVED\n", calibrationPwm, d/sec, volume*60.0f/sec, dropsPerMl); } }
  } else if (command == "CAL_STOP") pausePump();
  else if (command == "STATUS") { /* printed by periodic report */ }
  else Serial.println("Unknown command");
}
void drawStatusDisplay() {
  if (!oledPresent) return;

  uint32_t doseDrops = 0;
  if (state == RUNNING || state == CALIBRATING || state == PAUSED || state == COMPLETE) {
    noInterrupts();
    doseDrops = totalDrops - runStartDrops;
    interrupts();
  }
  doseDrops = min(doseDrops, DOSE_TARGET_DROPS);
  uint32_t dropsRemaining = DOSE_TARGET_DROPS - doseDrops;
  uint32_t elapsedSeconds = (state == RUNNING || state == CALIBRATING || state == PAUSED || state == COMPLETE)
    ? (millis() - runStartedMs) / 1000UL : 0;
  float estimatedMotorV = 5.0f * pwmDuty / 255.0f;
  uint8_t motorPercent = (uint8_t)roundf(100.0f * pwmDuty / 255.0f);
  bool appControlActive = lastAppContactMs != 0 && (millis() - lastAppContactMs) <= APP_LINK_TIMEOUT_MS;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);

  // Top: estimated motor voltage, recent USB-control activity, next-drop estimate.
  display.setCursor(0, 0);  display.printf("M%.1fV", estimatedMotorV);
  display.setCursor(45, 0); display.print(appControlActive ? "APP OK" : "APP --");
  display.setCursor(91, 0);
  if (state == RUNNING && measuredDropsSec > 0.05f) display.printf("N%.1fs", 1.0f / measuredDropsSec);
  else display.print("N--s");
  display.drawFastHLine(0, 9, 128, SSD1306_WHITE);

  // Middle: target drops; centre panel split into remaining and PWM speed; delivered drops.
  display.drawFastVLine(42, 10, 33, SSD1306_WHITE);
  display.drawFastVLine(87, 10, 33, SSD1306_WHITE);
  display.drawFastHLine(43, 26, 43, SSD1306_WHITE);
  display.setCursor(2, 12);  display.print("TARGET");
  display.setCursor(48, 12); display.print("LEFT");
  display.setCursor(91, 12); display.print("DONE");
  display.setTextSize(2);
  display.setCursor(14, 20);  display.printf("%lu", (unsigned long)DOSE_TARGET_DROPS);
  display.setCursor(58, 17);  display.printf("%lu", (unsigned long)dropsRemaining);
  display.setCursor(102, 20); display.printf("%lu", (unsigned long)doseDrops);
  display.setTextSize(1);
  display.setCursor(47, 29); display.printf("SPD %u%%", motorPercent);

  // Bottom: elapsed time; motor speed plus measured current; pressure or alarm status.
  display.drawFastHLine(0, 43, 128, SSD1306_WHITE);
  display.drawFastVLine(42, 44, 20, SSD1306_WHITE);
  display.drawFastVLine(87, 44, 20, SSD1306_WHITE);
  display.setCursor(1, 46);  display.print("TIME");
  display.setCursor(45, 46); display.print("SPD / I");
  display.setCursor(90, 46); display.print(fault != NO_FAULT ? "ALARM" : "PRESS");
  display.setCursor(1, 56);  display.printf("%lus", (unsigned long)elapsedSeconds);
  display.setCursor(45, 56);
  if (ina219Present) display.printf("%u%% %.0fm", motorPercent, pumpCurrentMa);
  else display.printf("%u%% --m", motorPercent);
  display.setCursor(90, 56);
  if (fault != NO_FAULT) display.print("FAULT");
  else if (state == COMPLETE) display.print("DONE");
  else display.printf("%.0fkPa", pressureKpa);
  display.display();
}

void setup() {
  Serial.begin(115200);
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  oledPresent = display.begin(SSD1306_SWITCHCAPVCC, OLED_I2C_ADDRESS);
  if (oledPresent) { display.clearDisplay(); display.display(); }
  ina219Present = ina219.begin();
  if (!ina219Present) Serial.println("INA219 not detected: OLED current and voltage fields will show monitor missing.");
  pinMode(AIN1_PIN, OUTPUT); pinMode(AIN2_PIN, OUTPUT); pinMode(ALARM_LED_PIN, OUTPUT); pinMode(BUZZER_PIN, OUTPUT);
  pinMode(STOP_PIN, INPUT); pinMode(DROP_PIN, INPUT); pinMode(SPEED_POT_PIN, INPUT); // STOP uses its external pull-up; the 3.3 V LM393 module drives DROP_PIN directly.
  pinMode(HX710B_DOUT_PIN, INPUT);
  pinMode(HX710B_SCK_PIN, OUTPUT);
  digitalWrite(HX710B_SCK_PIN, LOW);
  int64_t zeroSum = 0;
  int32_t zeroSample = 0;
  uint8_t zeroSamples = 0;
  for (uint8_t i = 0; i < 10; ++i) {
    if (readHx710bRaw(zeroSample)) { zeroSum += zeroSample; ++zeroSamples; }
  }
  hx710bPresent = zeroSamples >= 5;
  if (hx710bPresent) hx710bZeroRaw = (int32_t)(zeroSum / zeroSamples);
  ledcAttach(PUMP_PWM_PIN, PWM_FREQ_HZ, PWM_BITS);
  pumpOff(); alarmOutputs(false);
  attachInterrupt(digitalPinToInterrupt(DROP_PIN), onDrop, FALLING);
  preferences.begin("smartpump", false);
  dropsPerMl = preferences.getFloat("dropsMl", DEFAULT_DROPS_PER_ML);
  wifiApSsid = preferences.getString("wifiName", DEFAULT_WIFI_AP_SSID);
  if (!isValidWifiName(wifiApSsid)) wifiApSsid = DEFAULT_WIFI_AP_SSID;
#if defined(ESP32)
  #if ESP_ARDUINO_VERSION_MAJOR >= 3
    esp_task_wdt_config_t cfg = { .timeout_ms = WDT_TIMEOUT_S * 1000, .idle_core_mask = 0, .trigger_panic = true };
    esp_task_wdt_init(&cfg);
  #else
    esp_task_wdt_init(WDT_TIMEOUT_S, true);
  #endif
  esp_task_wdt_add(NULL);
#endif
  lastControlMs = millis(); lastDropMs = millis();
  startWirelessApi();
  if (hx710bPresent) Serial.println("HX710B zeroed with its hose open to air.");
  else Serial.println("HX710B not detected: pump will remain locked out for safety.");
  Serial.println("Water-only smart pump ready. Pump defaults OFF. Send STATUS or SET_FLOW then START.");
}
void loop() {
#if defined(ESP32)
  esp_task_wdt_reset();
#endif
  server.handleClient();
  if (wifiRestartPending && (int32_t)(millis() - wifiRestartAtMs) >= 0) {
    wifiRestartPending = false;
    restartWirelessAp();
  }
  if (Serial.available()) handleCommand(Serial.readStringUntil('\n'), false);
  controlAndSafety();
  static uint32_t lastReport = 0;
  if (millis() - lastReport >= 1000) {
    lastReport = millis();
    if (ina219Present) {
      pumpBusVoltageV = ina219.getBusVoltage_V();
      pumpCurrentMa = ina219.getCurrent_mA();
    }
    float error = targetFlowMlMin - measuredFlowMlMin;
    Serial.printf("state=%s fault=%s mode=local target=%.3f flow=%.3f drops/s=%.3f pressure=%.2f V=%.2f I=%.0fmA pwm=%u knobMax=%u error=%.3f\n", stateName(state), faultName(fault), targetFlowMlMin, measuredFlowMlMin, measuredDropsSec, pressureKpa, pumpBusVoltageV, pumpCurrentMa, pwmDuty, knobMaxPwm, error);
    drawStatusDisplay();
  }
  delay(1);
}
