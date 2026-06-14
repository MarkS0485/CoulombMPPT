# Contributing to CoulombMPPT

> **Private, proprietary project.** CoulombMPPT is not open source (see
> [LICENSE](LICENSE)). These notes are for the maintainer and any contributor
> working on the codebase **by prior arrangement**. Nothing here grants a
> licence to use or redistribute the code.

This is a **two-app monorepo** that controls an MPPT solar charge controller
over Bluetooth Low Energy:

```
CoulombMPPT/
├── android/   Kotlin / Jetpack Compose app  (JDK 17, Gradle 9)
├── windows/   .NET 10 WPF desktop app        (WPF + WinForms tray + in-proc API)
└── docs/      BLE_PROTOCOL.md — the shared wire protocol
```

The two clients are independent builds but speak the **same Modbus-over-BLE
protocol** to the hardware; keep that protocol layer in sync across both.

---

## Send patches, not file replacements

Every change should be the **smallest diff that does the job**:

- **Touch only the lines you need to.** Don't rewrite a whole file for a few lines.
- **No drive-by reformatting** of untouched code (whitespace, braces, imports).
- **Do one thing per commit / PR.** Keep unrelated changes separate so the diff
  reads as exactly what changed and why.

---

## Building

### Android (`android/`)

Requires JDK 17 + the Android SDK 35 toolchain (Android Studio Koala+).

```bash
cd android
cp local.properties.example local.properties   # set sdk.dir
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Source lives under `app/src/main/kotlin` (the build remaps `sourceSets`); there
is no `java/` tree.

### Windows (`windows/`)

Requires the .NET 10 SDK on Windows.

```bash
dotnet build windows/src/CoulombMppt/CoulombMppt.csproj -c Debug -p:Platform=x64
```

Self-contained publish + installer (what the release workflow does):

```bash
dotnet publish windows/src/CoulombMppt/CoulombMppt.csproj -c Release -r win-x64 --self-contained true -o windows/publish/win-x64
iscc windows/installer/CoulombMppt.iss            # needs Inno Setup 6
```

---

## CI must stay green

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) builds **both** apps on
every push and PR to `main` (Android `assembleDebug` on Ubuntu, Windows
`dotnet build` on Windows). There's no automated test suite yet, so CI is a
compile gate — keep it green. Open PRs against `main`; small, single-purpose
PRs are reviewed fastest. See [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md).

Dependency bumps arrive automatically via
[Dependabot](.github/dependabot.yml) (daily; gradle + nuget + actions).

---

## House rules from the codebase

A little context so a patch fits the grain of the code:

- **One repository object per controller.** Don't hold a global "current repo"
  reference — go through the per-controller lookup (`ServiceLocator`). Multiple
  paired controllers share one foreground service.
- **Source seam = `MpptSource`.** Telemetry comes from a swappable source (real
  BLE, the remote-API relay, or the synthetic demo source). The repository never
  knows which side it's on — keep that boundary clean.
- **`start()` is idempotent on the controller's MAC.** Removing that guard makes
  ViewModels tear down the in-flight BLE connection on every settings emission,
  causing a connect → disconnect → reconnect loop.
- **Mind the polling cadence.** The live BLE poll is sub-second; the history
  recorder downsamples to ~10 s. Don't raise the recorder rate without
  rethinking the retention budget.
- **Don't log telemetry at info level.** Info is shared across screens and
  crash reports; high-rate telemetry belongs at debug level.
- **Protocol changes touch both apps + `docs/BLE_PROTOCOL.md`.** The Android and
  Windows Modbus/BLE layers must agree.

---

## Security

Do **not** open a public issue for a security vulnerability — follow
[SECURITY.md](SECURITY.md). The Windows app exposes a remote HTTPS API with
optional UPnP port-forwarding, so network-facing changes deserve extra care.
