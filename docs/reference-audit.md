# Reference source audit

Source was inspected directly through July 21, 2026. The implementation is not based
solely on screenshots or README files.

| Project | Inspected revision | Source areas reviewed | License decision |
| --- | --- | --- | --- |
| NZB Direct | `954b49a4b28ed27f07b02642fb722c505dbf16a3` | `ui/theme/Theme.kt`, `ui/NzbDirectApp.kt`, blur helpers, responsive content, settings screens, DataStore | Appearance and floating-navigation behavior ported at the repository owner's explicit request; Liquid Glass helpers retain their Apache-2.0 headers |
| Mishka | `223b5aa1b03a456d7564563d944011ee93d9ac0d` | app theme, Navigation 3 shell, pager state, adaptive top bars, rail layout, grouped settings, blur and dialogs | GPL-compatible project; UI rewritten for this app |
| InstallerX-Revived | `221af84834a1b481cb5fa7f9a1becad6a93fb610` | Miuix theme bridge, adaptive layout, backdrop, dialogs, cards, settings and predictive back | GPL-compatible project; patterns used selectively |
| compose-miuix-ui/miuix | `b459d861561e0d9f117c87184db5330c05388f2f` | component implementations and example app for theme, app bars, navigation, preferences, blur, overlays, haptics and overscroll | Apache-2.0 dependency and implementation reference |
| NekokoLPA2 | `7d5a426d8d836caf5e7d98d9428e58c036f03a6d` | reader adapters, profile manager, download flow, notifications, tags/reminders, operator-icon catalog resolution, profile-size prediction, settings, platform channels and responsive pages | MIT-compatible feature and cloud-data reference; mascot artwork and installation telemetry excluded |
| OpenEUICC | `d9c89d311f34325e1bd7e4b35d7e750e72faafac` | `lpac-jni`, channel abstractions, OMAPI, USB CCID, download wizard, notifications and diagnostics | GPL-3.0-compatible engine integration |
| OTBridge | `90682357afe4d722d7ab130a3df45c3b2b1b50e5` (`v1.2.0`) | NBridge package/provider contract, exported ContentProvider surface, release artifacts and signer certificates | MIT-compatible interoperability reference; accepted release certificates are pinned locally and require explicit rotation |
| 9eSIM Community Key | `ddc15a8b8c873d42faa6dcda5db03f5dbc4124c4` | public Android signing certificate and published SHA-1/SHA-256 fingerprints | MIT-licensed optional signing input; key excluded from this source tree |

## UI conclusions

- Main destinations own their complete scaffold and top app bar inside a horizontal pager.
- Phone navigation uses a regular or floating Miuix navigation bar; wide windows use a rail.
- Wide pages use fixed small app bars and centered content; phones use collapsible large titles.
- Runtime-shader blur captures the page backdrop and falls back to an opaque Miuix surface.
- Settings use `SmallTitle` headings and grouped Miuix preference cards.
- Detail routes use typed Navigation 3 entries and Miuix `NavDisplay` predictive transitions.
- Loading, empty, error and content states share one reusable state container.

## Feature conclusions

- Reader discovery and selection must be independent from profile operations.
- LPA operations are serialized because the native lpac context is not thread safe.
- Profile switching may invalidate the active reader and requires a reconnect-and-refresh path.
- Notifications need explicit send, remove and automatic processing policies per operation type.
- Tags, reminders, redaction, layout, reader types, AID lists, MSS, IMEI and diagnostics are user settings, not hard-coded UI state.

## Security conclusions

- Treat eUICC responses, QR/deep-link payloads, bridge providers, remote-reader traffic,
  cloud catalogs and backup files as untrusted input with explicit size and format limits.
- Authenticate an NBridge implementation by both its exact package/provider contract and
  an allowlisted signing-certificate SHA-256 digest before exposing or auto-connecting it.
- Never retry a state-changing eUICC command after a transport failure until an
  authoritative refresh proves that the first command did not take effect.
- Keep release credentials in a protected GitHub environment, pin CI actions by commit,
  checksum Gradle dependencies, and verify every generated APK and app bundle before upload.
