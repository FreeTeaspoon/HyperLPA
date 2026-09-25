# HyperLPA relay

Connect two HyperLPA phones across networks and use the eUICC reader on one from
the other. The relay runs in your Cloudflare account. Both phones connect out over
HTTPS and WebSocket, so there is no server to maintain or port to forward.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/FreeTeaspoon/HyperLPA/tree/main/relay)

The relay forwards encrypted commands and results. The phone with the eUICC does
the card work, including profile downloads. On the controlling phone, its readers
appear in **Active reader** as `Device name · SIM1` and `Device name · SIM2`.

## Before you start

- Install [HyperLPA](https://github.com/FreeTeaspoon/HyperLPA/releases) on both phones. The phone with the eUICC needs a reader that already works locally.
- Have a Cloudflare account that can use Workers and SQLite Durable Objects. Check the [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) and [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/). The relay uses [WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/), but heartbeats and operations still use requests and storage.
- Make a unique enrollment key and save it in your password manager. `openssl rand -hex 32` generates one with 32 random bytes. You will enter the same key on both phones.

## Deploy to Cloudflare

1. Select **Deploy to Cloudflare** above and sign in to Cloudflare and GitHub. Cloudflare creates a copy of the `relay` directory in your GitHub account, provisions the Durable Object, and deploys the Worker. Review the Worker name and account before deploying.
2. When asked for `ENROLLMENT_KEY`, enter the key you saved. Cloudflare stores it as a Worker secret. The field must not be left blank.
3. Copy the deployed HTTPS `workers.dev` address. Open `<your address>/health` and check for `{"protocol":1}`.

Use the relay address as an origin such as `https://hyperlpa-relay.example.workers.dev`.
Do not include `/health`, a trailing path, a query, or credentials. A custom Worker
domain works too. Each deployment is one private device group, with room for up to
64 registered devices and 32 pairings per phone.

Cloudflare's deploy flow creates a separate GitHub repository for the relay and
connects it to Workers Builds for future deployments. [Cloudflare explains the
deploy button and its Git integration](https://developers.cloudflare.com/workers/platform/deploy-buttons/).

### Deploy from the command line

Use this route if you want to deploy from your own checkout. It needs Node.js 22
or later. From the HyperLPA repository root:

```sh
cd relay
npm ci
npm run check
npm test
npx wrangler login
npx wrangler deploy
npx wrangler secret put ENROLLMENT_KEY
```

Paste your saved key when Wrangler prompts. Registration remains disabled until
the secret exists. Wrangler prints the Worker address; check its `/health` endpoint
as above. You can also deploy code and the secret together using Wrangler's
[`--secrets-file` option](https://developers.cloudflare.com/workers/configuration/secrets/)
with a protected, untracked file. Never put the enrollment key in `wrangler.jsonc`,
an APK, Git, or public logs.

## Pair your phones

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

## What you can do

The remote reader supports the same profile workflow as a local reader: refresh,
download with preview and progress, enable or disable, rename, and delete. You can
also manage eUICC names, tags, pinning, reminders, artwork, eUICC notifications,
SM-DP+ settings, SM-DS discovery, and confirmed memory reset. Batch downloads use
the selected remote reader. App settings, backups, and Android permissions stay
on each phone.

Phone notification sharing is a separate option. On the source phone, grant
notification access, turn on sharing, choose apps, and approve each paired viewer
in **Settings → Remote devices**. The source keeps up to 150 entries for seven days
in encrypted local storage. Viewers receive temporary encrypted snapshots and can
ask the source to delete entries. Android may redact sensitive notifications.
There is no cloud archive or offline browser history.

## Keep the connection available

The target must be online and have a usable local reader. This feature does not
give the ordinary APK protected telephony privileges or bypass a card's signing
allowlist. The relay cannot wake a force-stopped phone. On HyperOS, allow background
autostart and unrestricted battery use for unattended access. Reopen the app after
force-stop. A profile switch can interrupt the target's mobile data; Wi-Fi or a
second data SIM helps keep the connection available during that operation.

Remote access is opt-in. Turning it off stops the connection and cancels an
outstanding download preview. A card operation already accepted can finish.
Removing a peer revokes future commands and discards its pairing key. If the
other phone is offline, remove the pairing there too before pairing again.
Removing the relay unregisters this phone and discards its local relay settings;
resolve outstanding operations first.

## Privacy and operation recovery

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

## For developers

[PROTOCOL.md](PROTOCOL.md) describes the HTTPS/WebSocket contract. Android has no
Cloudflare SDK or account identifier. Another backend can implement the contract;
this repository ships the Cloudflare backend. There is no browser controller in
this release.

For local relay development, copy `.dev.vars.example` to the ignored `.dev.vars`,
replace the empty `ENROLLMENT_KEY` with a disposable key, and run `npm run dev`.
The Android client requires HTTPS with a trusted certificate, including in
development.

### Tests

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
