# HyperLPA web

A browser controller for your HyperLPA phones. Vercel serves the static React app;
your existing Cloudflare Worker and Durable Object handle connections and trusted
group membership. There is no account, password database, Convex project, or Vercel
server function to configure.

The dashboard supports reader discovery, profile refresh, enable/disable, delete,
rename, tags, pinning, downloads with a separate installation confirmation, eUICC
notification processing, and Android phone notification mirrors. The phone performs
all card operations. The native app retains its other reader and administration tools.

## Deploy

Use the relay and Android app from this branch together. Older apps still support
ordinary phone pairing, but cannot approve a web trusted-group owner.

1. Build and install the updated Android app on your phones. Keep the same signing
   key when upgrading an existing installation.
2. Update the [Cloudflare relay](../relay/README.md). Keep its existing Durable
   Object binding, migration, and `ENROLLMENT_KEY` secret so registrations survive.
3. Import this public Git repository into Vercel. Select this branch, set the root
   directory to `web`, use the Vite preset, build with `npm run build`, and publish
   `dist`. No frontend environment variables or credentials are needed.
4. In the Worker's variables, set `WEB_ORIGINS` to the exact HTTPS frontend origin,
   such as `https://hyperlpa.example.com`. Separate multiple origins with commas.
   Redeploy the Worker after changing it. Include an individual Vercel preview URL
   only when you intend to trust that preview. Wildcards and trailing slashes do
   not match. Keep this public setting in your deployment configuration if Workers
   Builds or Wrangler manages its variables.
5. Check `<relay>/health` returns `{"protocol":1,"groups":1}`. Open the frontend,
   enter the relay origin and enrollment key, and create the group owner.

For CLI-managed origin configuration, add this top-level field to
`relay/wrangler.jsonc` before deploying:

```json
"vars": { "WEB_ORIGINS": "https://hyperlpa.example.com" }
```

The enrollment key is entered once during owner setup and each phone's relay
registration. It is sent directly to your relay over HTTPS, is never built into
the website, and is not saved in browser identity storage. A public repository
and public frontend do not grant access to the group. Access requires a registered
device's random relay token and its private pairing keys. Protect the Vercel and
Cloudflare projects and review code before deploying it, since code served by the
frontend origin can use that browser's identity.

## Connect phones and browsers

1. On each phone, open **Settings → Remote devices**, register with the same relay,
   and enable remote access. Verify its reader works locally.
2. Create a pairing code on that phone. In the owner dashboard, choose **Add phone**
   and paste it. Approve the incoming **group owner** request on the phone. This
   explicitly allows the owner to authorize browsers for that phone.
3. In **Group**, choose **Invite browser**. Transfer the invitation privately to
   the new browser and use **Join group** there. Invitations expire in ten minutes,
   admit one browser, and are replaced when a new invitation is generated.
4. Approve that browser in the owner's group page. It starts with no phone access.
   Grant profile management or notification access separately for each phone.

Each phone joins the owner once. Each new browser joins the owner once. Browsers
receive distinct keys for each allowed phone, encrypted by the owner. Existing
phone-to-phone pairing secrets are never distributed to the group. Ordinary
pairings retain their existing permissions and are not automatically promoted.

The owner must be open and connected to approve devices or change permissions.
Approved browsers can use their phones while the owner is offline. Offline phones
receive membership changes when they and the owner reconnect. **Group update
pending** remains visible until the phone acknowledges the current revision.
Interrupted encrypted package publication resumes on reconnect or periodic sync.
Permissions cover a whole phone's compatible readers, not individual SIM slots.
The relay allows up to 64 registered devices. This UI allows 31 additional browsers;
a phone's existing ordinary pairings also consume its 32-peer limit.

To share phone notifications, also grant Android notification access, enable
sharing, choose apps, and allow the owner as a viewer on each source phone. Group
browsers need both the owner's notification permission and that local consent.
Messages shown here are Android notification mirrors. There is no direct SMS inbox,
SMS sending, or cloud message archive. Android may redact sensitive contents. The
source keeps up to 150 entries for seven days; the web app keeps snapshots in memory
and clears them on disconnection or permission removal.

## Recovery and removal

Browser keys and credentials live in encrypted IndexedDB with a nonextractable
browser CryptoKey. Clearing site data or changing the frontend origin loses that
local identity. Private browsing storage may disappear when its window closes.

Use **Group → Recovery → Generate export** before relying on the owner. Save both
the encrypted file and its separate random recovery key. Anyone with both can
restore that device, including owner authority. The relay also holds an encrypted
owner package, so an older valid owner export can recover newer membership changes.
That package uses the owner's random token as key material; the relay only stores
the token's hash. It cannot decrypt the package or reset the recovery key.

Restore on a trusted browser at the same or another allowed frontend origin.
Restoring replaces the old identity's WebSocket connection. Use a new browser
invitation for a separate device; do not run copies of one identity concurrently.
Recovery currently supports one owner and does not transfer ownership to another
identity. If all owner identity copies and recovery credentials are lost, set up a
fresh relay deployment and re-enroll the phones. There is no password reset.

Removing a device blocks its relay credentials, closes its sockets, and clears
queued ciphertext immediately. Revoked IDs cannot register again. Remove the old
owner pairing and relay registration on a removed phone before registering it with
a fresh identity. The owner distributes replacement membership to the other phones
when connected. Removing access cannot erase data a device already copied, cancel
a card mutation already accepted, or protect traffic captured with a compromised
pair key. The pair protocol has no forward secrecy.

The browser journals pending mutations before sending and queries their status
after a disconnect. It never automatically repeats them. An unknown result requires
waiting for command expiry, explicitly resolving the uncertainty, and refreshing
the reader before another change. A SIM switch can disconnect the phone; keep Wi-Fi
or another data SIM available.

## Development and checks

Requires Node.js 22.12 or newer. In `web`:

```sh
npm ci
npm run dev
npm run check
npm test
npm run build
```

Use an HTTPS relay with a trusted certificate and allow the dev server's exact
origin in `WEB_ORIGINS`. Do not put an enrollment key in `VITE_*` variables.
`npm run test:ui` starts a fresh local Worker on port 8791 and Vite on 5178. Install
the relay dependencies and Playwright Chromium first:

```sh
npm --prefix ../relay ci
npx playwright install chromium
npm run test:ui
```

These browser tests use the real Worker/Durable Object transport and an encrypted
synthetic phone. They cover enrollment, approvals, profile operations, permissions,
revocation, recovery, themes, narrow layouts, and automated accessibility checks.
They do not perform real SIM or carrier operations. See [DESIGN.md](DESIGN.md) for
the UI reference and validation notes, and [PROTOCOL.md](../relay/PROTOCOL.md) for
the wire contract. This implementation has not had an independent security audit.
