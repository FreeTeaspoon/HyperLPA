# HyperLPA encrypted relay

This Cloudflare Worker and SQLite Durable Object connect paired HyperLPA Android
devices across networks. Both phones make outbound HTTPS and WebSocket connections.
No port forwarding, public phone address, VPN, Docker host, or Supabase project is
needed. You control the Cloudflare account and the relay's enrollment key.

The relay forwards encrypted app commands. The phone attached to the eUICC runs
the existing LPA engine, including profile downloads and their SM-DP+ connections.
The controlling phone receives profiles, metadata, progress, confirmation screens,
and results. SIM1 and SIM2 appear in the existing active-reader dropdown as
`Device name · SIM1` and `Device name · SIM2`.

## Deploy

Use Node.js 22 or later and an account with Workers and SQLite Durable Objects
enabled. Review your account's [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
and [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).
The Worker uses [WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/).
Device heartbeats and operations still generate requests and storage writes.

```sh
cd relay
npm ci
npm run check
npm test
npx wrangler login
npx wrangler deploy
npx wrangler secret put ENROLLMENT_KEY
```

Generate a random enrollment key of at least 32 bytes and keep it in a password
manager. Paste it when `wrangler secret put` prompts. Registration stays disabled
until this secret exists. Alternatively, deploy code and the secret together using
Wrangler's `--secrets-file` option with a protected, untracked JSON file.
See Cloudflare's [secret configuration](https://developers.cloudflare.com/workers/configuration/secrets/).
Never add the enrollment key to `wrangler.jsonc`, an APK, Git, or public logs.

Wrangler prints an HTTPS `workers.dev` origin. A custom Worker domain also works.
Verify `GET /health` returns `{"protocol":1}`. Keep the relay address as an origin,
without a path, query, or credentials. Each deployment is one private device group,
with a maximum of 64 registered devices and 32 pairings per phone.

For development, put a disposable `ENROLLMENT_KEY` in an ignored `.dev.vars` file
and run `npm run dev`. The Android client always requires HTTPS with a trusted
certificate. It deliberately has no cleartext or certificate-bypass development mode.

## Connect phones

1. Install HyperLPA on each phone. Verify its reader works locally and grant that
   reader's required Android permissions.
2. Open **Settings → Remote devices**, enter the same relay origin and enrollment
   key, and give each phone a distinct name.
3. Enable **Remote access** on each phone. This starts a foreground service.
4. On one phone, create and copy a pairing code. Transfer it privately to the other
   phone and paste it into its pairing field. Codes expire after ten minutes and
   are consumed by the first pairing request.
5. Approve the incoming request on the phone that created the code. Approval gives
   both phones control of each other's compatible readers. Pair other devices
   separately; pairing does not transitively authorize an entire group.
6. Return to Profiles and select `Device name · SIM1` or `Device name · SIM2` in
   **Active reader**. Use the existing profile and notification screens.

Supported remote actions include refresh, download with preview/confirmation and
progress, enable/disable, rename/delete profiles, eUICC names, tags, pinning,
reminders, custom/provider icons, notification processing/deletion/resend/history
deletion, SM-DP+ changes, SM-DS discovery, and confirmed eUICC memory reset.
The existing batch-download coordinator uses the selected remote reader as well.
App-wide preferences, backups, and Android permissions remain per phone.
Phone notification sharing is separate from eUICC notifications. On the source
phone, grant notification access, enable sharing, choose apps and authorize each
paired viewer in **Settings → Remote devices**. The source keeps up to 150 entries
for seven days in an encrypted file outside backup storage. Viewers receive
encrypted, temporary snapshots and can request deletion on the source. The relay
only holds unacknowledged ciphertext for up to two minutes. Android may redact
sensitive notifications before the listener sees them. There is no cloud archive
or offline browser history in this version.

The target must be online and have a usable local reader. This feature does not
give the ordinary APK protected telephony privileges or bypass a card's signing
allowlist. The relay cannot wake a force-stopped phone. On HyperOS, allow background
autostart and unrestricted battery use for unattended access. Reopen the app after
force-stop. A profile switch can interrupt the target's mobile data; Wi-Fi or a
second data SIM helps keep the connection available during that operation.

Remote access is opt-in. Disabling it stops the connection and cancels an outstanding
download preview. A card operation already accepted can finish. Removing a peer
revokes future commands and discards its pairing key. If the other phone is offline,
remove the pairing there too before pairing again. Removing the relay unregisters
this phone and discards its local relay configuration; it requires outstanding
operations to be resolved first.

## Encryption and operation recovery

- Every pair has a random 256-bit secret transferred in the pairing code. The
  enrollment key only admits phones to the relay; it cannot decrypt profile data.
  Exchange pairing codes through a trusted channel. Device names alone are not
  identity proofs. Anyone holding an unused code can request its pairing.
- HKDF-SHA256 derives separate keys for each direction. AES-256-GCM encrypts and
  authenticates messages, with a random 96-bit nonce and authenticated routing,
  protocol, request ID, and expiration fields. TLS authenticates the relay.
- The relay sees IP addresses, opaque device/pair IDs, timing, and message sizes.
  It stores hashed relay tokens and short-lived ciphertext. It never receives
  pairing secrets, activation codes, profile names, or SIM identifiers in plaintext.
  Observability is disabled in the supplied Worker configuration.
- Android stores pair keys, relay tokens, and the operation journal in an atomic,
  Android Keystore-encrypted file outside backup storage. Uninstalling or clearing
  app data requires registration and pairing again.
- Commands expire after two minutes. A target journals a command before performing
  it, binds it to a reader and EID, serializes hardware access, and retains its
  result for 24 hours. A disconnect causes status queries, never an automatic
  repeat of a mutation. Downloads continue on the target while the controller
  reconnects. An unconfirmed preview times out after two minutes.
- Android process death can occur after a card changes but before its result is
  committed. The UI reports an unverified outcome and requires a refresh before
  another change. This is not an exactly-once guarantee from the eUICC. A saved
  success without a fresh snapshot also requires a refresh.
- Large snapshots are split into independently encrypted parts. Frame, transfer,
  inbox, registration, and request-rate limits bound memory and storage. Custom
  artwork is resized for transfer; full-resolution source files stay on the host.

This version uses pair secrets without forward secrecy. Compromise of a pair key
can expose that pair's previously captured traffic. Removing and re-pairing creates
a new key. It has not undergone an independent security audit.

Rotating `ENROLLMENT_KEY` prevents new registrations with the old enrollment key.
It does not revoke existing relay tokens or device pairings. To retire a phone,
remove its pairings on its peers and unregister it when possible. There is no
public relay administration or multi-user account system in this build.

## Protocol and future web UI

[PROTOCOL.md](PROTOCOL.md) describes the portable HTTPS/WebSocket contract.
Android contains no Cloudflare SDK or account identifier. Another relay can
implement that contract; this build includes the Cloudflare backend only.

A browser management UI is planned, **not included in this build**. It should be a
separate paired controller, use WebCrypto-compatible HKDF/AES-GCM, retain keys on
the client, and reuse the same typed commands, reader/EID binding, confirmations,
and status recovery. Browser origin restrictions, CSP, safe key persistence, and
enrollment UX must be implemented before shipping it. Do not move decryption or
carrier downloads into the relay to add a web interface.

## Tests

`npm test` runs the relay in Cloudflare's local Durable Object runtime, including
authentication, single-use ticket races, spoofing, replay/acknowledgement, inbox
bounds, expiry, and revocation. Android JVM tests cover cryptography, tampering,
serialization, and multipart transfer.

`RemoteDevicesIntegrationTest` also has an opt-in live relay test. Put an untracked
`remote-test-config.json` in the debug APK's private `files` directory with
`relay` and `enrollment` fields, then run that instrumentation class. It creates
temporary paired clients, tests the real encrypted network transport, and uses a
simulated card engine for mutations. It unregisters those temporary clients at
the end. Without that file the live test is skipped; the encrypted journal test
still runs. Never use a real carrier activation code in this test.
