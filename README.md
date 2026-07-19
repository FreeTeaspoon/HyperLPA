# HyperLPA

HyperLPA is a fully native Android Local Profile Assistant built with Kotlin,
Jetpack Compose, and compose-miuix-ui. It uses lpac for SGP.22 operations and
presents the complete workflow as a HyperOS-style application rather than a
Material-themed compatibility layer.

## Implemented

- Profile discovery, enable/disable, rename, delete, download and batch download
- Pending notification processing, eUICC details, memory reset and activity logs
- NBridge, OMAPI, privileged telephony, USB CCID, RemoCard v2 and native BLE readers
- ESTKme RED/RED 2, SimLink and BeeSIM BLE transport protocols
- Profile tags, scheduled reminders, statistics, redaction, custom AIDs, MSS and IMEI
- Typed Navigation 3, predictive back, swipeable main pager and adaptive navigation rail
- Miuix theme controller with system/light/dark, Monet, accent palettes and pure black
- Miuix blur fallback, grouped preferences and DataStore-backed settings

See `docs/reference-audit.md` for the source review and licensing decisions.

## Build

```shell
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Bluetooth readers are opt-in because Android requires nearby-device runtime
permissions. Privileged telephony additionally requires a compatible system
installation or carrier/system permissions.

### Removable eUICC cards

Android OMAPI access is controlled by the removable card's ARA-M certificate
allowlist. A privately signed APK can enumerate SIM slots but cannot open an
ISD-R channel on a card that does not allow that signing certificate. NBridge,
a privileged/root installation, USB CCID, or a vendor-authorized signing key
is required in that case.

For 9eSIM cards, an optional release-like build can use the public MIT-licensed
[9eSIM Community Key](https://github.com/9esim/9eSIMCommunityKey) when
`nineesim-keystore.properties` is configured:

```shell
./gradlew :app:assembleNineEsimRelease
```

The APK is written to
`app/build/outputs/apk/nineEsimRelease/app-nineEsimRelease.apk`. Its expected
signing certificate SHA-1 is
`D1:C0:F4:8B:37:0E:74:D4:EA:47:70:ED:4C:3C:D7:0A:31:98:D3:1F`.
Because this community key is public and differs from HyperLPA's private release
key, this variant is not suitable for Play distribution and cannot update a
private-key build in place; uninstall the other signature first.

## Release signing

Local release builds load signing credentials from the ignored
`keystore.properties` file. Build both Play Store and directly installable
artifacts with:

```shell
./gradlew :app:assembleRelease :app:bundleRelease
```

The signing key and `keystore.properties` are irreplaceable. Back them up in a
password manager or encrypted offline archive; losing the key prevents future
updates signed with the same app identity.

The exported public certificate is available at
`signing/hyperlpa-release-cert.pem`. It is safe to share for API allowlists or
store registration; it cannot be used to sign an app.
