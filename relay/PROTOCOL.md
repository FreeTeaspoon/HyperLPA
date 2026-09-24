# Device protocol v1

The relay is an authenticated, bounded mailbox. It does not interpret commands.
The Android wire models are in `app/hyperlpa/remote/DeviceProtocol.kt`.

## Relay HTTP API

All responses use `Cache-Control: no-store`. All device connections require TLS.

| Request | Authentication | Body / result |
| --- | --- | --- |
| `GET /health` | None | `{"protocol":1}` |
| `POST /v1/register` | `X-Enrollment-Key` | Body has `id` and `token`. Result has `protocol`. |
| `POST /v1/session` | `X-Device-Id` and `Authorization: Bearer <token>` | Returns a random, single-use `ticket`, valid for 30 seconds. |
| `GET /v1/socket?ticket=…` | Ticket | WebSocket upgrade. Replaces the device's previous socket. |
| `POST /v1/unregister` | Device ID and bearer token | Deletes the registration, tickets, and its queued messages. |

IDs are lowercase UUID strings. Tokens are random 32-byte values encoded as
unpadded base64url. Registration with the same ID and token is idempotent;
changing a registered token is rejected. Registration bodies are limited to 4 KiB.
The enrollment key is not retained by the Android app after registration.

## WebSocket mailbox

Client sends `{"type":"send","message":<envelope>}` or
`{"type":"ack","id":"<envelope-id>"}`.

Server sends `ready`, `message`, `sent`, or `error` frames. A `message` frame
contains an envelope under `message`; a `sent` frame contains its `id`.
`sent` means the relay accepted ciphertext, not that the target performed an
operation. The socket also accepts `ping` and replies `pong` using hibernation.

An envelope contains `v`, `id`, `from`, `to`, `pair`, `issued`, `expires`, `nonce`,
and `body`. Times are integer Unix milliseconds. Version is 1. The relay enforces
the authenticated sender, a registered recipient, a maximum two-minute lifetime,
at most 30 seconds of forward clock skew, and base64url bounds. Only the recipient
can acknowledge a message. Pending envelopes are replayed when it reconnects.

Limits are 64 registered devices, 64 queued envelopes / 32 MB per recipient,
1.5 million characters per frame, 2,048 client frames per minute per device,
and 60 authenticated HTTP calls per minute per device. Enrollment has global and
source-IP rate limits. These are private-group limits, not a public SaaS quota model.

## End-to-end payload

The pair invitation is `hyperlpa-pair:` followed by unpadded base64url UTF-8 JSON:
`version`, `relay`, `device`, `name`, `pair`, `secret`, `expires`.
The random pair secret is 32 bytes and expires as an invitation after ten minutes.
The recipient proves possession with an encrypted `pair` message. The inviter
consumes the invitation, asks its user to approve, and replies `paired` on approval.
Before approval, no LPA commands are accepted.

Key derivation follows RFC 5869, with SHA-256, the decoded pair secret as input key
material, UTF-8 `HyperLPA/pair/v1/<pair>` as salt, and UTF-8 `<from>/<to>` as info.
Use the first 32 output bytes. AES-GCM uses that key, a random 12-byte nonce, and a
128-bit tag appended to the ciphertext. Encode nonce and ciphertext/tag in
unpadded base64url. Associated data is this UTF-8 string, with no trailing newline:

```text
v\nid\nfrom\nto\npair\nissued\nexpires
```

Each field above is replaced by its value; `\n` denotes one newline byte. DeviceCrypto
and its JVM tests provide the reference implementation. Both routing and timestamps
are authenticated; the peer checks them again after relay delivery.

The decrypted UTF-8 JSON is a `DeviceMessage`. `hello` / `hello_reply` carry presence.
`command` carries a unique `requestId` and typed `DeviceCommand`. `result` carries
progress or a final `outcome` with `done: true`. `status` queries the durable request
record. `missing` means no record is available and never authorizes retrying a
mutation. `decision` and `cancel` refer to an active download request. `unpair`
removes the peer's key. All non-pairing commands require an approved peer.

`phone_notifications_request` asks a source phone for its locally stored Android
notification history. The source replies with `phone_notifications`, including
`phoneNotificationsAvailable` and a bounded list, only if sharing is enabled and
that peer is explicitly allowed. `phone_notifications_changed` prompts an open
controller to request a fresh snapshot. `phone_notifications_delete` sends an entry
ID for deletion on the source; it is ignored without that peer's authorization.
Notification history is never a relay-side archive and is not tied to an eUICC
reader or EID. Revocation clears viewers when delivered, but cannot erase copies
exported or saved elsewhere.

A snapshot larger than 600,000 UTF-8 bytes is split into `part` messages with a
`DevicePart` containing `transfer`, zero-based `index`, `count`, and base64url
`data`. Parts are independently encrypted. Reassembly is scoped to the authenticated
peer and transfer ID, bounded to 28 parts and 16.8 MB total buffered bytes across
at most four transfers. Only a complete, unexpired message reaches the dispatcher.
Progress snapshots may omit artwork and history and mark `artworkIncluded: false`;
controllers retain the previous values until a complete snapshot arrives.

Every mutating command includes the host's reader ID and current EID. It never
falls back to another reader or profile. Destructive actions require the existing
UI confirmation; the target checks the command's confirmation flag too. The last
enabled profile has a separate disconnect confirmation. Confirmation of one
request cannot confirm a different request's download.

The host commits a request fingerprint before execution and a final outcome before
reporting completion. Duplicate request IDs can retrieve the stored result only
when their fingerprint matches. After process restart, unfinished records become
unverified. Hardware mutation and flash storage cannot be one atomic transaction,
so callers must display this uncertainty and refresh rather than resubmit.
