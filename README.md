# HyperLPA

HyperLPA is a fully native Android Local Profile Assistant built with Kotlin,
Jetpack Compose, and compose-miuix-ui. It uses lpac for SGP.22 operations and
presents the complete workflow as a HyperOS-style application rather than a
Material-themed compatibility layer.

## Implemented

- Profile discovery, enable/disable, rename, delete, QR/deep-link download and durable batch provisioning
- Pending notification processing and history, eUICC details, memory reset and privacy-safe activity logs
- NBridge, OMAPI, USB CCID, RemoCard v2, native BLE readers and an optional privileged telephony build
- ESTKme RED/RED 2, SimLink and BeeSIM BLE transport protocols
- Profile tags, scheduled reminders, statistics, redaction, custom AIDs, MSS and IMEI
- Opt-in Nekoko-compatible operator artwork plus measured and predicted profile storage sizes
- Password-encrypted manual backup/restore and redacted support-report export
- Typed Navigation 3, predictive back, swipeable main pager and adaptive navigation rail
- Miuix theme controller with system/light/dark, Monet, accent palettes and pure black
- Miuix blur fallback, grouped preferences and DataStore-backed settings

See `docs/reference-audit.md` for the source review and licensing decisions.

## Build

```shell
./gradlew :app:assembleDebug
```

ABI-specific APKs are written to `app/build/outputs/apk/debug/`.

Bluetooth readers are opt-in because Android requires nearby-device runtime
permissions. For transport authentication, HyperLPA only discovers readers that
have already been paired in Android settings. The ordinary `debug`, `release`, and `nineEsimRelease` variants do
not declare `READ_PHONE_STATE`, `READ_PRIVILEGED_PHONE_STATE`, or
`MODIFY_PHONE_STATE`, and their telephony backend is disabled at compile time.

### Privileged telephony builds

Telephony APDU access is isolated to explicitly named builds with distinct app
IDs. A development APK can be built with:

```shell
./gradlew :app:assemblePrivilegedDebug
```

This produces ABI-specific APKs under
`app/build/outputs/apk/privilegedDebug/` with the
`app.hyperlpa.privileged.debug` application ID. A signed distribution build is:

```shell
./gradlew :app:assemblePrivilegedRelease
```

It uses the private release key, writes APKs under
`app/build/outputs/apk/privilegedRelease/`, and has the
`app.hyperlpa.privileged` application ID. Only the two `privileged*` variants
merge the phone-state and protected telephony permissions. Sideloading one does
not grant those permissions: the device must install or allowlist it as a
compatible privileged/system app, or otherwise provide the required platform
access. The app also keeps this backend off until it is enabled in settings.

### Removable eUICC cards

Android OMAPI access is controlled by the removable card's ARA-M certificate
allowlist. A privately signed APK can enumerate SIM slots but cannot open an
ISD-R channel on a card that does not allow that signing certificate. NBridge,
a privileged/root installation, USB CCID, or a vendor-authorized signing key
is required in that case.

For 9eSIM cards, the standard release uses the public MIT-licensed
[9eSIM Community Key](https://github.com/9esim/9eSIMCommunityKey) when
`nineesim-keystore.properties` is configured:

```shell
./gradlew :app:assembleRelease
```

The ABI-specific APKs are written to `app/build/outputs/apk/release/` and use
the `app.hyperlpa` application ID. Their expected signing certificate SHA-1 is
`D1:C0:F4:8B:37:0E:74:D4:EA:47:70:ED:4C:3C:D7:0A:31:98:D3:1F`.
This matches the signer used by NekokoLPA2's standard 9eSIM build. Because the
community key is public, this variant is not suitable for Play distribution.
Updates must keep the same application ID and certificate.

`assembleNineEsimRelease` remains available as a side-by-side build. It uses the
same 9eSIM signer with the distinct `app.hyperlpa.nineesim` application ID and
the `-9esim` version suffix.

### NBridge trust

HyperLPA accepts NBridge/OTBridge only when both its provider authority and APK
signing certificate match a verified upstream release. The allowlist currently
covers the published OTBridge `v1.1.0`, `v1.2.0`, and historical `latest`
artifacts from [iebb/OTBridge](https://github.com/iebb/OTBridge). An unknown or
newly rotated certificate is rejected as unavailable instead of receiving raw
eUICC APDUs. When OTBridge rotates its signing key, verify the new release APK
out of band and update the SHA-256 allowlist in `NBridgeReaderProvider.kt`
before enabling it here.

## Release signing

Privileged release builds load private signing credentials from the ignored
`keystore.properties` file. `assemblePrivilegedRelease` and
`bundlePrivilegedRelease` fail before building if that file is absent; they
never fall back to a public or debug key. The standard release instead requires
the 9eSIM community signing configuration described above.

Build directly installable, ABI-specific APKs with:

```shell
./gradlew :app:assembleRelease
```

The APKs are written to `app/build/outputs/apk/release/`. Build the app bundle
separately with `./gradlew :app:bundleRelease`; the AAB is written to
`app/build/outputs/bundle/release/app-release.aab`. Because it uses a public
community key, this standard bundle is not intended for Play distribution.

The private signing key and `keystore.properties` are irreplaceable. Back them
up in a password manager or encrypted offline archive; losing the key prevents
future updates to the privileged release identity.

The exported public certificate is available at
`signing/hyperlpa-release-cert.pem`. It is safe to share for API allowlists or
store registration; it cannot be used to sign an app.

Release versions can be supplied without editing the build script:

```shell
./gradlew :app:bundleRelease \
  -PhyperLpaVersionCode=42 \
  -PhyperLpaVersionName=1.2.0
```

Distribution builds skip R8 code and resource shrinking for faster APK builds.
Native symbol-table generation remains enabled; keep those symbols with each
release so native crash reports can be symbolicated.

The manual **Release** GitHub Actions workflow builds both the standard 9eSIM
and explicitly privileged release identities. It expects release-environment-only
secrets named `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` for the privileged identity and
checks out the public 9eSIM key at the audited revision. It verifies the standard
artifacts against `signing/9esim-community-cert.pem`, privileged artifacts
against `signing/hyperlpa-release-cert.pem`, and verifies the APKs' permission
separation before uploading artifacts. Configure a GitHub environment named `release`,
restrict its deployment branch to `main`, require at least one independent
reviewer, and scope the four signing secrets to that environment. The workflow
itself also refuses to run away from `main`; the environment rule is the
separate human-approval and secret-release boundary.

## Verification

Run the same checks as continuous integration with:

```shell
./gradlew --dependency-verification strict \
  :app:lintDebug :app:lintPrivilegedDebug \
  :app:testDebugUnitTest :libs:lpac-jni:testDebugUnitTest \
  :app:assembleDebug :app:assemblePrivilegedDebug :app:assembleDebugAndroidTest
```

Instrumentation and Compose accessibility smoke tests compile with
`./gradlew :app:assembleDebugAndroidTest` and can run on an API 28+ emulator or
device with `./gradlew :app:connectedDebugAndroidTest`.

Gradle dependency verification is strict in CI and is backed by
`gradle/verification-metadata.xml`. When intentionally updating a dependency,
regenerate only the required SHA-256 entries with
`./gradlew --write-verification-metadata sha256 <affected tasks>`, review the
artifact coordinates and checksum diff, then rerun the strict verification
command above.
