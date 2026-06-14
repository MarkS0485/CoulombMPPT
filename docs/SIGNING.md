# Code signing

Release builds of both apps are code-signed in CI (`.github/workflows/release.yml`).

## Where the keys live

**Not in this repo.** The private keys are kept offline by the maintainer and
injected into CI as encrypted **GitHub Actions secrets**. `.gitignore` also
blocks `*.keystore`, `*.pfx`, `keystore.properties`, etc. as a safety net.

| Secret | Used for |
|--------|----------|
| `ANDROID_KEYSTORE_BASE64` | base64 of the Android upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | keystore + key password |
| `ANDROID_KEY_ALIAS` | key alias inside the keystore |
| `WIN_CODESIGN_PFX_BASE64` | base64 of the Windows code-signing `.pfx` |
| `WIN_CODESIGN_PFX_PASSWORD` | password for that `.pfx` |

On a fork these secrets are absent, and the build degrades gracefully: the
Android APK is built **unsigned** and the Windows binaries are **left unsigned**,
rather than failing.

## Android

`android/app/build.gradle.kts` defines a `release` signing config that reads
either a local, git-ignored `android/keystore.properties`:

```properties
storeFile=/absolute/path/to/android-upload.keystore
storePassword=…
keyAlias=coulomb
keyPassword=…
```

…or, when that file is absent, the environment variables `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (what CI sets). Build a signed
release locally with the properties file in place:

```bash
cd android && ./gradlew :app:assembleRelease
```

## Windows (Authenticode)

CI signs `CoulombMppt.exe`/`.dll` **before** building the Inno Setup installer (so
the installer wraps already-signed binaries), then signs the installer too. All
signatures are timestamped, so they remain valid after the certificate expires.

> ⚠️ The current Windows certificate is **self-signed**. It proves the binaries
> haven't been tampered with, but Windows SmartScreen will still warn end users
> with "Unknown Publisher" because the cert isn't from a trusted CA. To ship
> publicly-trusted builds, obtain a CA-issued code-signing certificate (OV/EV,
> stored on a hardware token per current CA/B rules) and repoint the
> `WIN_CODESIGN_PFX_*` secrets at it — no workflow changes are needed.

Sign a local build manually:

```powershell
$cert = Get-Item Cert:\CurrentUser\My\<thumbprint>
Set-AuthenticodeSignature .\CoulombMppt.exe -Certificate $cert `
  -HashAlgorithm SHA256 -TimestampServer http://timestamp.digicert.com
```
