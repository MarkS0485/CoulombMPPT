# CoulombMPPT

Native Android app for the MPPT solar charge controller whose Chinese
vendor went bust. The original app talked to a cloud API that has been dark
for months; the controller itself still works perfectly. This app replaces
the dead vendor app with a direct Bluetooth Low Energy link, so the
hardware is useful again — and adds long-term history recording, alerts,
and a background polling service the vendor app never had.

Sibling of [`coulombmonitor`](../TEMPLATE%20FOR%20MPPT/coulombmonitor) — shares
the visual identity (brand gradient, Material 3 light theme, `SoCRing`,
`NumberTile`, `BrandTopBar`) but lives in its own Gradle project so the two
apps don't tread on each other.

---

## At a glance

| | |
| --- | --- |
| **Platform** | Android 12+ (minSdk 31, targetSdk / compileSdk 35) |
| **Language** | Kotlin 2.0.21 |
| **UI** | Jetpack Compose + Material 3 (BOM 2024.10.01) |
| **Build** | Android Gradle Plugin 8.13.2, JDK 17, KSP 2.0.21-1.0.27 |
| **Package** | `app.coulombmppt` |
| **Version** | 0.1.0 (versionCode 1) |
| **Multi-controller** | Yes — multiple paired MPPTs share one foreground service |
| **Demo mode** | Yes — synthetic source, no hardware needed |

---

## Build & install

Requires Android Studio Koala+ (or JDK 17 + the Android SDK 35 toolchain).

```bash
# Point at your SDK once
cp local.properties.example local.properties
$EDITOR local.properties     # set sdk.dir

# Debug build + push to a connected device
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`gradle-wrapper.jar` and `gradlew` aren't checked in — open the project in
Android Studio once and it'll regenerate them, or run
`gradle wrapper --gradle-version 8.13.2` from a parent install.

Release builds (`:app:assembleRelease`) are unsigned and have R8/ProGuard
disabled — sign and shrink config still need to be added before any wider
distribution.

### Runtime permissions

The app requests, on first launch into the pairing flow:

- `BLUETOOTH_SCAN` (`neverForLocation` — we don't infer location)
- `BLUETOOTH_CONNECT`
- `POST_NOTIFICATIONS` (Android 13+, for the foreground-service notification
  and critical alerts)

Legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` are declared
with `android:maxSdkVersion="30"` so the adapter is still reachable on
pre-31 devices we may eventually have to support, but `minSdk 31` means
they're effectively never enforced.

A foreground service of type `connectedDevice` runs while at least one
controller is paired and demo mode is off; this is what keeps the BLE poll
loop and history recorder alive when the screen is off.

---

## How it talks to the MPPT

The defunct vendor app speaks Modbus RTU over Nordic UART Service. We do
the same. Full breakdown in
[`docs/BLE_PROTOCOL.md`](docs/BLE_PROTOCOL.md). Short version:

```
Transport:  BLE GATT, NUS service (6E400001-B5A3-F393-E0A9-E50E24DCCA9E)
            We probe both 0002 (write) and 0003 (notify) characteristics
            because the vendor firmware accepts writes on the notify char.
MTU:        64, negotiated post-connect.
Frame:      Modbus RTU  [0x01 slave][fn][payload][CRC16-lo][CRC16-hi]
CRC:        CRC-16/Modbus (poly 0xA001, init 0xFFFF, low byte first)
Telemetry:  fn 0x03  start 0x0001  qty 0x0010   poll ~4 Hz
Settings:   fn 0x03  start 0x1001  qty 0x000F   read on demand
Write:      fn 0x10  start 0x100X  qty 1  value(BE 16-bit), echo-ACKed
Auth:       none
Encryption: none
```

**Live telemetry** (`read(0x0001, 16)` → 10 registers): battery voltage,
PV→battery current, battery→load current, controller temperature, three raw
status enums (codes still to be confirmed on hardware) and a 32-bit
lifetime energy accumulator.

**Settings** (`read(0x1001, 15)` → 9 registers): battery type, timer
hours/minutes, charge-voltage setpoint, output mode, cutoff voltage,
manual-load toggle, voltage-monitor mode, recovery voltage.

PV-side V/I aren't in the polled register window — the firmware doesn't
expose them in `0x0001…0x0010`. The app shows
`approxPvWatts = batteryVoltage × chargeCurrent` as a placeholder. A future
Modbus-scan debug screen will sweep `0x0000…0x00FF` to find the real PV
registers.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│ MainActivity → Compose Nav (AppNav)                                      │
│   Controllers home → Unit detail / Pairing / App settings / Diagnostics /│
│                      Logs / Alerts / Info / Controller settings          │
└──────────────────────────────────────────────────────────────────────────┘
                              ▲                                              
                              │ StateFlow                                    
┌──────────────────────────────────────────────────────────────────────────┐
│ ViewModels (one per screen)                                              │
└──────────────────────────────────────────────────────────────────────────┘
                              ▲                                              
                              │ StateFlow / Result                           
┌──────────────────────────────────────────────────────────────────────────┐
│ MpptRepository  (one per paired controller, hand-wired in ServiceLocator)│
│  • latest: StateFlow<MpptLive?>                                          │
│  • settings: StateFlow<MpptSettings?>                                    │
│  • sampleRing: rolling 120-frame buffer for in-memory sparklines         │
│  • diagRing: rolling 64-frame TX/RX buffer for the Diagnostics screen    │
└──────────────────────────────────────────────────────────────────────────┘
        ▲                              ▲                          ▲          
        │                              │                          │          
┌────────────────┐          ┌────────────────────┐    ┌──────────────────┐   
│ BleMpptSource  │          │ HistoryRecorder    │    │ AlertEngine      │   
│  (real BLE)    │          │  10 s sampling →   │    │  thresholds →    │   
│ NusTransport + │          │  Room (30 d ret.)  │    │  Room + heads-up │   
│ Modbus framing │          │                    │    │  notification    │   
└────────────────┘          └────────────────────┘    └──────────────────┘   
        ▲                                                                    
        │  (swappable)                                                       
┌────────────────┐                                                           
│ FakeMpptSource │  ← demo mode: synthetic 12 V lead-acid on a               
│  (preview/demo)│    partly-cloudy diurnal sun curve                        
└────────────────┘                                                           

                              ▼                                              
┌──────────────────────────────────────────────────────────────────────────┐
│ PollingService (foreground, type=connectedDevice)                        │
│  • Watches SettingsStore for pair/unpair                                 │
│  • Starts/stops repo + recorder + alert engine per controller            │
│  • Persistent notification — count of monitored controllers              │
└──────────────────────────────────────────────────────────────────────────┘
```

**Patterns**

- MVVM with Kotlin Flow / StateFlow, same shape as `coulombmonitor`.
- `ServiceLocator` for manual DI — no Hilt/Koin. The whole dependency graph
  reads top-to-bottom in one file.
- One `MpptRepository` *per paired controller*, cached in a
  `ConcurrentHashMap`. Demo mode uses a single shared `FakeMpptSource`-
  backed repo.
- `MpptSource` is the seam between "where does telemetry come from?" and
  the rest of the app. Repository never knows or cares which side it's on.
- Compose + Material 3, light theme only.

---

## Persistence

### Paired controllers — DataStore

`data/store/SettingsStore.kt` keeps the list of paired controllers in a
single JSON-encoded `Preferences` value (`controllers_json`), plus a
selected-id pointer and a `useFakeSource` flag. Each `PairedController`
carries:

- Stable UUID `id`, MAC, optional display name
- Visual identity (site label, icon key, accent ARGB)
- Cached battery profile (`cachedFullV`, `cachedEmptyV`, `cachedRecoverV`)
  — the last setpoints we read from the controller, used to compute SoC
  before the next live read lands
- User-supplied pack spec (chemistry, nominal V, capacity kWh) — what the
  human says the battery is, distinct from what the controller is
  configured for

Legacy single-controller keys (`paired_mac`, `cached_full_v`, …) are still
read once for a transparent migration from earlier builds.

### History — Room

`data/history/HistoryDb.kt` is the Room database (KSP-compiled). Two
tables, both indexed on `(controllerId, tsMs)`.

| Table          | Written by        | Cadence  | Retention |
| -------------- | ----------------- | -------- | --------- |
| `live_sample`  | `HistoryRecorder` | 10 s     | 30 days   |
| `alerts`       | `AlertEngine`     | on edge  | 7 days    |

`live_sample` stores battery V, charge/discharge A, PV W, load W, temp °C,
and computed SoC %. That's ~8.6 k rows/day per controller — comfortable for
a 7-day chart window without downsampling at read time.

Prune runs once on `HistoryRecorder.start()`; long-paused apps clean up
before chart queries fan out.

### Logs — internal storage

`data/log/AppLogger.kt` is an append-only file logger, one file per launch,
named `coulombmppt-yyyyMMdd-HHmmss.log` in the app's `filesDir`. Writes happen
on a single background thread so callers never block on I/O. Files older
than 7 days are deleted at startup. `data/log/CrashHandler.kt` installs an
uncaught-exception handler so fatals land in the log before the process
dies — readable + shareable from the Logs screen.

---

## State of charge

Single formula, used everywhere:

```
SoC% = clamp((vBat − cutoffV) / (chargeV − cutoffV) × 100, 0, 100)
```

The controller's own cutoff and charge setpoints are its real idea of "0%"
and "100%", so we treat them as SoC anchors. This works cleanly across
chemistries — Mark's 24 V LiFePO4 (20.0 V → 0, 25.5 V → 100), a 12 V
lead-acid pack (11.1 V → 0, 14.4 V → 100), anything else — without
hard-coding a chemistry table.

If we haven't read settings yet, the cached profile on `PairedController`
does the job. If neither is available, the firmware's own `socEstimate`
field is the fallback.

---

## Alerts

`data/history/AlertEngine.kt` watches each controller's live frames and
inserts an alert row (and posts a heads-up notification, for criticals) on
the *rising edge* of a threshold crossing. Hysteresis is enforced per
`(controllerId, kind)` so a slow recovery doesn't spam the user.

| Kind                 | Severity | Threshold                       |
| -------------------- | -------- | ------------------------------- |
| Battery overvoltage  | Critical | `fullV  + 0.5 V`                |
| Battery undervoltage | Critical | `emptyV − 0.3 V` (ignored < 1 V — disconnected probe) |
| Solar overcurrent    | Warn     | `15.0 A`                        |
| Load overcurrent     | Warn     | `10.0 A`                        |
| Hysteresis           | —        | 0.2 V / 0.5 A                   |

Battery over/undervoltage are flagged Critical because lithium chemistries
are genuinely dangerous outside their safe band — they get their own
notification channel (`coulombmppt-critical`) and a heads-up presentation.

---

## Screens

Navigation graph lives in `ui/nav/AppNav.kt`.

| Route                | File                              | Purpose |
| -------------------- | --------------------------------- | ------- |
| `controllers`        | `ControllersHomeScreen.kt`        | Landing page. LazyColumn of paired units, each with SoC ring, name, status pill and three inline metrics. Empty-state CTA into pairing. |
| `unit/{controllerId}`| `UnitDetailScreen.kt`             | Per-controller detail. Live tiles, energy-flow strip (PV → batt → load), in-memory sparklines, history chart, info, controller settings entry-point. |
| `pairing`            | `PairingScreen.kt`                | BLE scan (NUS service filter), runtime permission prompts, pair + name. |
| `controller_settings`| `ControllerSettingsScreen.kt`     | Reads the 9 setting registers; voltage setpoints editable; mode pickers fall back to "Unknown" enum values until codes are confirmed on hardware. |
| `app_settings`       | `AppSettingsScreen.kt`            | Demo-mode toggle, "Unpair current controller", danger zone. |
| `diagnostics`        | `DiagnosticsScreen.kt`            | Last 64 Modbus frames TX/RX with hex + decoded summary. |
| `logs`               | `LogsScreen.kt`                   | Live tail of the per-launch log file, with pause/resume, share, and clear. |
| `alerts`             | `AlertsScreen.kt`                 | Recent alert events across every controller, severity-coloured. |
| `info`               | `InfoScreen.kt`                   | Install information — battery profile, topology, notes. Works offline from the cached profile. |

Compose components shared across screens live in `ui/components/`:

- `BrandTopBar` — gradient navy → red top bar matching the brand identity
- `SoCRing` — animated state-of-charge dial
- `NumberTile` — large-value tile with unit + secondary line
- `ChargeStateBadge`, `StatusPill` — connection / charger-state chips
- `EnergyFlow`, `PowerFlow` — animated arrow strip PV → batt → load
- `ChartPanel` — Vico line chart wrapper
- `UnitProfile` — icon + accent resolver for the per-controller identity

---

## Demo mode

Don't have the controller handy? Toggle **App settings → Demo mode** and
the app runs against `FakeMpptSource` — a synthetic 12 V lead-acid setup
on a partly-cloudy diurnal sun curve. Useful for screenshots, layout work,
and showing the app to anyone before plugging into hardware.

In demo mode the foreground service stops the BLE poll loop and the
history recorder — demo data is in-memory only, so there's nothing to
persist and no link to keep alive.

---

## Repository layout

```
CoulombMPPT/
├── app/
│   ├── build.gradle.kts                    # AGP module config
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions, service decl
│       ├── kotlin/app/coulombmppt/
│       │   ├── MainActivity.kt
│       │   ├── CoulombMpptApp.kt              # Application — boots ServiceLocator + PollingService
│       │   ├── data/
│       │   │   ├── ble/                    # BleConstants, BleScanner, NusTransport
│       │   │   ├── modbus/                 # ModbusCrc, ModbusFrame, MpptProtocol
│       │   │   ├── model/                  # MpptLive, MpptSettings, PairedController, BatteryChemistry, ChargerState, DeviceInfo
│       │   │   ├── source/                 # MpptSource interface, BleMpptSource, FakeMpptSource
│       │   │   ├── repo/                   # MpptRepository
│       │   │   ├── store/                  # SettingsStore (DataStore)
│       │   │   ├── history/                # HistoryDb, HistoryRecorder, AlertEngine + entities/DAOs
│       │   │   └── log/                    # AppLogger, CrashHandler
│       │   ├── di/                         # ServiceLocator
│       │   ├── service/                    # PollingService (foreground)
│       │   └── ui/
│       │       ├── nav/                    # AppNav + Routes
│       │       ├── alerts/, controllers/, diagnostics/, info/, logs/,
│       │       │   pairing/, settings/, unit/    # screen + ViewModel pairs
│       │       ├── components/             # SoCRing, NumberTile, BrandTopBar, …
│       │       └── theme/                  # Color, Theme, Type
│       └── res/                            # drawables, strings, themes, backup/extraction rules
├── docs/
│   └── BLE_PROTOCOL.md                     # In-app protocol reference
├── gradle/
│   └── libs.versions.toml                  # Central version catalogue
├── build.gradle.kts                        # Top-level plugins-only
├── settings.gradle.kts                     # Repos, foojay toolchain, :app include
├── local.properties.example                # Copy → local.properties, set sdk.dir
└── README.md                               # You are here
```

The sibling `coulombmonitor` project sits at `../TEMPLATE FOR MPPT/coulombmonitor`
and contributed the design system (`SoCRing`, `NumberTile`, `BrandTopBar`,
brand palette). The decompiled vendor app — the source of truth for the
protocol reverse-engineering — lives at `../decompiled/` with the full
extraction report at `decompiled/BLE_PROTOCOL_EXTRACTED.md`.

---

## Dependencies

Central catalogue in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

| Layer          | Library                                                  | Version           |
| -------------- | -------------------------------------------------------- | ----------------- |
| Build          | Android Gradle Plugin                                    | 8.13.2            |
| Build          | Kotlin                                                   | 2.0.21            |
| Build          | KSP                                                      | 2.0.21-1.0.27     |
| UI             | Compose BOM                                              | 2024.10.01        |
| UI             | Material 3 + material-icons-extended                     | (from BOM)        |
| UI             | Navigation Compose                                       | 2.8.3             |
| UI             | Vico (charts)                                            | 2.0.0-beta.3      |
| Lifecycle      | androidx.lifecycle (runtime-ktx, viewmodel-compose)      | 2.8.6             |
| Lifecycle      | androidx.activity.compose                                | 1.9.3             |
| Concurrency    | kotlinx-coroutines-android                               | 1.9.0             |
| Serialization  | kotlinx-serialization-json                               | 1.7.3             |
| Storage        | androidx.datastore.preferences                           | 1.1.1             |
| Storage        | androidx.room (runtime, ktx, compiler via KSP)           | 2.6.1             |
| BLE            | `android.bluetooth.le.*`                                 | platform — no third-party wrapper |
| Modbus         | Hand-rolled in `data/modbus/` — ~50 lines                | n/a               |

---

## What works in this build

| Feature                                          | Status |
| ------------------------------------------------ | ------ |
| BLE scan + pair (NUS filter)                     | ✓      |
| GATT connect, MTU 64, notify enable              | ✓      |
| Modbus framing, CRC-16, reassembly               | ✓      |
| Live telemetry decode (10 registers)             | ✓      |
| Settings read (9 registers)                      | ✓      |
| Settings write (per-register echo-ACKed)         | ✓      |
| Multi-controller pairing + selection             | ✓      |
| Per-controller battery profile + user pack spec  | ✓      |
| Controllers home + unit detail dashboards        | ✓      |
| Animated PV → batt → load energy flow            | ✓      |
| In-memory sparklines (last ~2 min, 1 Hz)         | ✓      |
| Foreground polling service + persistent notif    | ✓      |
| Room-backed history recording (10 s, 30-day ret.)| ✓      |
| Alert engine + critical heads-up notifications   | ✓      |
| Per-launch file logger + crash handler           | ✓      |
| Diagnostics screen (last 64 TX/RX frames)        | ✓      |
| Demo / fake-data mode                            | ✓      |
| Enum value pickers (battery type, output mode…)  | Codes still TBD — vendor app pulled them from a now-dead cloud endpoint; needs hardware confirmation |
| PV-side V/I telemetry                            | Not exposed in the polled window — a `0x0000…0x00FF` Modbus scan tool will find them |
| Release signing + R8 shrink                      | Not configured |
| Phase-5 in-app docs viewer (`Routes.DOCS`)       | Placeholder route, currently redirects to Info |

---

## Roadmap

In rough priority order:

1. **Hardware-confirm the enum codes** (battery type, output mode, voltage
   monitor mode) so the settings screen stops showing "Unknown".
2. **Modbus scan tool** in the Diagnostics screen — sweep `0x0000…0x00FF`
   to locate PV-side V/I registers.
3. **History chart polish** — multi-series overlay, pinch-zoom, CSV export
   from the Logs share intent.
4. **Release signing + R8** — once the codes are confirmed and the schema
   is stable.
5. **In-app docs viewer** — render `docs/BLE_PROTOCOL.md` from a `DOCS`
   route so the protocol reference travels with the APK.

---

## Notes for contributors

- **Source folder is `app/src/main/kotlin`**, not `java/`. The build script
  remaps `sourceSets.main.java.srcDirs` to match.
- **One repo per controller.** Don't take a global "current repo" reference
  — `ServiceLocator.repositoryFor(id)` is the entry point. The legacy
  `repositoryFor(settings)` overload exists only for screens still
  operating on the selected controller.
- **`MpptRepository.start()` is idempotent on `(macAddress)`.** Without
  that guard, multiple ViewModels each call `start()` on every settings-
  flow emission and tear down the in-flight BLE connection — the source
  ends up in an endless connect → spurious-disconnect → reconnect loop.
- **Polling cadence is in `BleMpptSource.startPolling()`** (250 ms). The
  history recorder downsamples that to 10 s in `HistoryRecorder`; don't
  raise the recorder rate without rethinking the 30-day retention budget.
- **Don't log telemetry at info-level.** `AppLogger.i` is shared across
  screens and crashes; high-rate telemetry goes to `AppLogger.d`.
