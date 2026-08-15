<p align="center">
  <img src="app/src/main/res/drawable-nodpi/about_logo.png" alt="HyperLPA icon" width="160">
</p>

<h1 align="center">HyperLPA</h1>

<p align="center">
  A native Android Local Profile Assistant for managing eSIM profiles through compatible eUICC readers.
</p>

<p align="center">
  <a href="https://github.com/FreeTeaspoon/HyperLPA/actions/workflows/android.yml"><img src="https://github.com/FreeTeaspoon/HyperLPA/actions/workflows/android.yml/badge.svg?branch=main" alt="Android checks"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="GPL-3.0 license"></a>
  <a href="https://github.com/FreeTeaspoon/HyperLPA/releases"><img src="https://img.shields.io/github/v/release/FreeTeaspoon/HyperLPA?display_name=tag&sort=semver" alt="GitHub release"></a>
</p>

HyperLPA implements the user-facing part of GSMA SGP.22 profile management in
Kotlin, Jetpack Compose, and Miuix. The LPA engine is provided by the
`lpac-jni` integration and the app can connect to an eUICC through Android,
USB, Bluetooth, bridge, remote, or explicitly privileged telephony backends.

> [!WARNING]
> eUICC access is hardware-, firmware-, carrier-, and signing-dependent. An
> Android phone having an eSIM is not by itself enough: the device or reader
> must expose a compatible backend, and removable cards may restrict which APK
> certificates can access them.

## What HyperLPA does

- Discover installed eSIM profiles and enable, disable, rename, or delete them.
- Download profiles from activation codes, QR codes, and `lpa:` links.
- Queue multiple downloads with durable, foreground progress and cancellation.
- Process pending eUICC notifications and keep a local outcome history.
- Inspect eUICC identity, capabilities, storage, SM-DP+, SM-DS, and profile data.
- Organise profiles with tags, dates, reminders, provider artwork, and statistics.
- Create password-encrypted backups of app settings, profile metadata, and custom
  artwork. Installed eSIM profiles remain on the eUICC and are not copied into
  the backup.
- Export redacted diagnostics and privacy-safe support reports.
- Use adaptive layouts, predictive back, system/light/dark themes, Monet and
  accent palettes, pure black mode, and a Miuix-style interface.

## Download and install

Use the [GitHub Releases](https://github.com/FreeTeaspoon/HyperLPA/releases)
page for published builds. For a sideloaded phone, download the ordinary APK
whose ABI matches the device:

| APK ABI | Typical target |
| --- | --- |
| `arm64-v8a` | Modern 64-bit phones and tablets |
| `armeabi-v7a` | Older 32-bit ARM devices |
| `x86_64` | Most x86 Android emulators |
| `x86` | Older x86 Android emulators |

The ordinary release uses the `app.hyperlpa` application ID and does not
declare privileged telephony permissions. The distribution build is signed
with the public [9eSIM Community Key](https://github.com/9esim/9eSIMCommunityKey)
so that compatible 9eSIM cards can authorise it. A different removable eUICC
may require its matching vendor/community build, NBridge, USB CCID, or a
privileged/root installation.

Because the ordinary release uses a public community signing key, its AAB is
not intended for Google Play distribution.

Do not install a `privileged` build unless you administer a compatible system
image. Sideloading it does not grant Android's protected telephony permissions;
the APK must be installed or allowlisted with the required platform or carrier
privileges.

## Supported reader backends

Enable the backends you need in **Settings → eUICC readers**. HyperLPA only
discovers a backend when the device and its permissions make that backend
available.

| Backend | Use it for | Important requirements |
| --- | --- | --- |
| Android OMAPI | Secure elements exposed by the device | The device must advertise a usable OMAPI reader. Removable cards also enforce their ARA-M certificate allowlist. |
| NBridge / OTBridge | A compatible installed bridge provider | The provider authority and signing certificate must match HyperLPA's verified trust policy. |
| USB CCID | External smart-card and eUICC readers | A compatible USB CCID reader and USB host/OTG support. |
| Bluetooth LE | External APDU readers | Pair the reader in Android settings first, grant Nearby devices permission, and grant Location on Android versions that require it for discovery. Supported protocol families include ESTKme RED/RED 2, SimLink, and BeeSIM. |
| Remote / RemoCard v2 | A network-attached reader | Configure an HTTPS endpoint. Bearer credentials are optional for servers that do not require them and are stored in Android Keystore-protected storage. Use only a reader you trust. |
| Privileged telephony | The phone's built-in telephony eUICC interface | Only the explicitly privileged builds include this backend. System, carrier, or equivalent platform access is still required. |

## Requirements

- Android 9 / API 28 or newer.
- A compatible eUICC and at least one usable reader backend.
- Network access when downloading a profile or using a remote reader.
- An activation code or QR code from the mobile operator for profile download.
- Camera access only when scanning a QR code.
- Bluetooth Nearby devices permission, and on some older Android versions
  Location permission, when using a Bluetooth reader.

## Build from source

The project uses Gradle 9.6.1, Android Gradle Plugin 9.3.1, Android SDK
Platform 37, NDK `29.0.14206865`, and Java 21.

```shell
git clone --recurse-submodules https://github.com/FreeTeaspoon/HyperLPA.git
cd HyperLPA

# If you already cloned without submodules:
git submodule update --init --recursive

# macOS / Linux
./gradlew :app:assembleDebug

# Windows
./gradlew.bat :app:assembleDebug
```

The ABI-specific debug APKs are written to
`app/build/outputs/apk/debug/`. A development build with the privileged
telephony code path can be assembled without release credentials:

```shell
./gradlew :app:assemblePrivilegedDebug
```

Those APKs are written to `app/build/outputs/apk/privilegedDebug/` and use the
`app.hyperlpa.privileged.debug` application ID. They still require compatible
system or carrier privileges on the target device.

## Release builds

Release packaging is deliberately separated by signing identity and
permission set.

| Variant | Application ID | Signing input | Output |
| --- | --- | --- | --- |
| `release` | `app.hyperlpa` | `nineesim-keystore.properties` using the 9eSIM Community Key | `app/build/outputs/apk/release/` and `app/build/outputs/bundle/release/` |
| `privilegedRelease` | `app.hyperlpa.privileged` | Private `keystore.properties`, pinned to `signing/hyperlpa-release-cert.pem` | `app/build/outputs/apk/privilegedRelease/` and `app/build/outputs/bundle/privilegedRelease/` |
| `nineEsimRelease` | `app.hyperlpa.nineesim` | The 9eSIM Community Key | `app/build/outputs/apk/nineEsimRelease/` |

The signing property files and private key files are ignored by Git. They are
required for the corresponding release tasks and must never be committed.
Each property file contains `storeFile`, `storePassword`, `keyAlias`, and
`keyPassword`. The build verifies the configured certificate against the
public certificate committed under `signing/` before packaging.

Build ordinary release APKs or an app bundle with:

```shell
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

The side-by-side 9eSIM and privileged release tasks are:

```shell
./gradlew :app:assembleNineEsimRelease
./gradlew :app:assemblePrivilegedRelease
```

Set release versions without editing the build script:

```shell
./gradlew :app:bundleRelease \
  -PhyperLpaVersionCode=42 \
  -PhyperLpaVersionName=1.2.0
```

The manual [Release workflow](https://github.com/FreeTeaspoon/HyperLPA/actions/workflows/release.yml)
builds and verifies ordinary and privileged APK/AAB artifacts, checks their
permission separation and signing certificates, and uploads the artifacts to
the workflow run. It runs only from `main` and requires the protected `release`
environment plus the private signing secrets described in the workflow.

## Privacy and security

- App settings and profile metadata are stored locally. Android backup is
  disabled; use HyperLPA's manual backup feature when you need to move them.
- Manual backups use password-based AES-GCM encryption. They can contain
  sensitive identifiers such as ICCIDs, EIDs, IMEI, remote-reader addresses,
  and custom images. The password cannot be recovered, so keep it safe.
- Profile installation sends the activation data to the selected SM-DP+ as
  required by the eSIM provisioning protocol. HyperLPA does not upload
  installation reports.
- Optional operator artwork is fetched from
  [operator-icons.pages.dev](https://operator-icons.pages.dev/). Such a request
  can reveal the device IP address and inferred operator. Optional profile-size
  reference data is downloaded from GitHub. Both caches can be cleared in the
  app's privacy settings.
- Configured remote-reader credentials are kept in Android Keystore-protected
  storage and are excluded from HyperLPA backups. Remote readers require
  authenticated HTTPS endpoints.
- Support reports intentionally omit activation and confirmation codes,
  matching IDs, APDUs, ICCIDs, EIDs, IMEI, profile names, reader IDs, remote
  credentials, and full notification addresses. Review every report before
  sharing it.
- Never post an activation code, eUICC identifier, bearer token, private key,
  or raw APDU trace in an issue or support request.

## Troubleshooting

### No reader is found

Open **Settings → eUICC readers**, enable the relevant backend, then use
**Discover readers now**. For USB, connect the reader with a working OTG
adapter. For Bluetooth, pair the reader in Android settings, turn Bluetooth on,
and grant the requested permissions. For NBridge, install a compatible trusted
bridge. For a remote reader, check that the endpoint is HTTPS and implements
RemoCard v2.

### Access to the eUICC is denied

This usually means the card does not allow the installed APK's signing
certificate, the OMAPI service is not exposed, or the selected backend lacks
platform privileges. Try the card's matching vendor/community build or another
backend such as NBridge, USB CCID, or a privileged installation.

### A download result is uncertain

If the SM-DP+ session completes but the eUICC cannot be reconnected, the profile
may already have been installed. Reconnect the same reader and refresh the
profile list before attempting the download again.

## Verification and development

Run the same lint, unit-test, dependency-verification, and packaging checks used
by continuous integration:

```shell
./gradlew --dependency-verification strict \
  :app:lintDebug \
  :app:lintPrivilegedDebug \
  :app:testDebugUnitTest \
  :libs:lpac-jni:testDebugUnitTest \
  :app:assembleDebug \
  :app:assemblePrivilegedDebug \
  :app:assembleDebugAndroidTest
```

Instrumentation and Compose accessibility tests can be run on an API 28+
emulator or device with:

```shell
./gradlew :app:connectedDebugAndroidTest
```

Dependency verification is backed by
`gradle/verification-metadata.xml`. When changing a dependency, update only the
required SHA-256 entries with `--write-verification-metadata`, review the diff,
and rerun the strict verification command.

## Contributing

Bug reports and pull requests are welcome in the
[GitHub issue tracker](https://github.com/FreeTeaspoon/HyperLPA/issues). Please
include the affected reader backend, Android API level, device model, and a
redacted diagnostic or support report when useful. Do not include secrets or
eUICC credentials.

The source-review and licensing decisions are recorded in
[`docs/reference-audit.md`](docs/reference-audit.md). Third-party attribution
and license information is in [`NOTICE`](NOTICE); the project license is
[`GPL-3.0`](LICENSE).
