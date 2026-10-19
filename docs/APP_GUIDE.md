# Triveni App Guide

Triveni is an Android companion app for a local ESP32 peristaltic-pump research prototype. It connects directly to the controller over its Wi-Fi access point. There is no cloud service or account.

> **Bench research only. Water only.** Triveni and this controller are not medical devices. Never connect the pump to a person, animal, IV line, or medication. Firmware limits, sensors, and this app do not replace supervision or physical safety measures.

## What the app does

- Shows controller connection, state, faults, flow and drop readings, motor output, pressure, current, and session progress when the device reports them.
- Sends pump commands such as start, pause, resume, stop, and alarm acknowledgement to the ESP32.
- Provides a calibration workflow and keeps local history of telemetry, sessions, calibration, and events.
- Exports selected records as CSV, JSON, or PDF using Android's share sheet.
- Offers Glass, Light, and OLED appearance themes.
- Lets the operator choose a saved device entry and change the controller's Wi-Fi network name while it is in an allowed safe state.

Pump control and safety interlocks run on the ESP32. The app is a remote interface; do not rely on the phone or Wi-Fi link as the safety mechanism.

## Connect to the controller

1. Flash the firmware in [`../firmware/esp32/smart_pump.ino`](../firmware/esp32/smart_pump.ino) to the supported ESP32 hardware.
2. Power the controller and watch the serial console at **115200 baud**. On first boot, the firmware creates and stores a random access-point password and prints it to the serial console. Store it securely; the source code does not contain a shared default password.
3. On the phone, join the controller's Wi-Fi network. The default name for a new controller is `Triveni-ESP32`.
4. Keep the phone connected to that local network and open Triveni. The app polls `http://192.168.4.1/api/status` and sends commands to `http://192.168.4.1/api/command`.
5. Check that the dashboard reports a live connection, expected sensor availability, and a safe controller state before using any controls.

The device's local API uses HTTP on the isolated access point, not TLS. Do not expose it to a shared or public network. The firmware rejects requests with a browser `Origin` header and applies request throttles, but those controls do not make an untrusted network safe.

## Main screens

### Dashboard

Shows the current controller state and readings. Use the stop control when you need to request a stop from the app. Verify the physical STOP input independently; it remains an important safety control if the phone, app, or network fails.

### Calibration

Use calibration only as a supervised water-bench procedure with the assembled sensor and tubing setup. The app can record measured calibration values. Sensor scaling and firmware calibration constants must be checked against actual measurements; displayed values are not clinical or precision measurements.

### History and export

History is stored locally on the phone. From Export, choose the device, date range, record categories, and CSV, JSON, or PDF format. The app creates a file and opens Android's share sheet. Treat exports as potentially sensitive operational records and share them only with intended recipients.

Android backup is disabled for the app, so this local history is not included in Android Auto Backup. Export records if you need to retain them outside the current app installation.

### Settings and themes

Settings contains device selection, device settings, appearance, and export options. Glass is the default appearance; Light and OLED are alternatives. Theme selection is saved locally and applied when the activity is recreated.

### Rename the device Wi-Fi network

Open Settings → Device Settings. Enter a network name of 1–31 letters, numbers, hyphens, or underscores. Rename is accepted only while the controller is `IDLE`, or safely `PAUSED` with no active fault. Confirm the change. The ESP32 restarts its Wi-Fi access point; reconnect the phone to the new network before returning to the app. The access-point password remains separately generated and stored by the firmware.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Dashboard is disconnected | Join the ESP32's Wi-Fi network; check that it is powered; confirm the phone can reach `192.168.4.1`; then reopen or resume the app. |
| Can't join the network | Read the password from the ESP32 serial console during first boot and confirm the network name. Android may warn that the Wi-Fi network has no internet; remain connected to it for local control. |
| Readings are missing or implausible | Check sensor wiring, power, ground, sensor presence, tubing, and firmware calibration against a measured water test. Do not use displayed values as verified physical measurements. |
| Network rename appears to disconnect the app | This is expected. Join the new SSID in Android Wi-Fi settings and reconnect. |
| A command is rejected or the pump won't start | Check controller state, active fault, physical STOP, sensor faults, and the firmware's safety conditions. Clear a fault only after identifying and correcting its cause. |
| Export fails | Check that at least one record category is selected, the date range contains records, and the device has available storage. Choose a compatible target in Android's share sheet. |
| Theme appears unchanged | Re-select the theme in Settings and allow the activity to redraw. If needed, leave and reopen Settings or restart the app. |

## Safety and limits

- The current firmware configuration has a three-drop target and a maximum flow clamp. These are prototype values, not validated dose controls.
- Pressure, flow, current, and drop measurements depend on the sensors, wiring, tubing, and calibration. The pressure conversion constant explicitly requires calibration against a known reference.
- Keep the physical STOP input and firmware interlocks connected and independently verified.
- Test with water on a supervised bench. Do not use the prototype to deliver medication or fluids to a living subject.
- A network password and app signing do not certify the hardware or guarantee Google Play Protect approval.

## Build the Android app

Requirements: JDK 17, Android SDK API 34, and Gradle 8.7 or newer. From the repository root in PowerShell:

```powershell
gradle assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For the configured Windows workstation, create the signed release with:

```powershell
./scripts/build_release.ps1
```

The helper reads the release keystore and a DPAPI-encrypted password from outside version control, runs `gradle assembleRelease`, then removes the signing variables from its process environment. The release APK is written to `app/build/outputs/apk/release/app-release.apk`. Keep signing keys and passwords in a protected secret store; do not commit them.

The Android application ID is still `com.waterbench.controller` to preserve its established package identity. A replacement signing certificate has been configured because the former key was exposed in repository history. An APK signed with the replacement certificate cannot update an installation signed with the former certificate; Android may require uninstalling the old app first. Uninstalling removes app-local history. Back up data through the app's export feature before changing installations if those records matter.

Signing does not guarantee a Play Protect verdict. Review the release in Google Play Console or on target devices before distribution. The checked-in `dist/WaterBench-Controller-v1.1-release.apk` predates the Triveni rebrand; build a fresh release to get the Triveni app label.

## Project files

- Android screens, themes, persistence, and export: [`../app/src/main/`](../app/src/main/)
- ESP32 firmware and local API: [`../firmware/esp32/smart_pump.ino`](../firmware/esp32/smart_pump.ino)
- Launcher artwork: [`../icons/`](../icons/)
- Release build helper: [`../scripts/build_release.ps1`](../scripts/build_release.ps1)
- Project overview and screenshots: [`../README.md`](../README.md)
