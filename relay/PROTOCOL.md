# Device protocol v1

The relay is an authenticated, bounded mailbox. It does not interpret commands.
The Android wire models are in `app/hyperlpa/remote/DeviceProtocol.kt`.

## Relay HTTP API

All responses use `Cache-Control: no-store`. All device connections require TLS.

| Request | Authentication | Body / result |
| --- | --- | --- |
| `GET /health` | None | `{"protocol":1,"groups":1}` |
| `POST /v1/register` | `X-Enrollment-Key` | Body has `id` and `token`. Result has `protocol`. |
| `POST /v1/session` | `X-Device-Id` and `Authorization: Bearer <token>` | Returns a random, single-use `ticket`, valid for 30 seconds. |
| `GET /v1/socket?ticket=…` | Ticket | WebSocket upgrade. Replaces the device's previous socket. |
| `POST /v1/unregister` | Device ID and bearer token | Deletes the registration, tickets, and its queued messages. |

IDs are lowercase UUID strings. Tokens are random 32-byte values encoded as
unpadded base64url. Registration with the same ID and token is idempotent;
changing a registered token is rejected. Registration bodies are limited to 4 KiB.
The enrollment key is not retained by the Android app after registration.

### Browser origins and trusted groups

`WEB_ORIGINS` is an exact comma-separated Origin allowlist for HTTP, CORS
preflight, and WebSocket upgrades. No wildcard or suffix matching is performed.
Native clients without an Origin header retain token authentication. Origin
checking is an additional browser boundary, not a replacement for credentials.

Each deployment optionally has one owner identity. All endpoints below are POST
and use device ID/bearer authentication unless stated otherwise. Bodies are JSON.

| Endpoint | Body | Authority and result |
| --- | --- | --- |
| `/v1/group/create` | `{}` | Registered device plus deployment `X-Enrollment-Key`; establishes the sole owner. Idempotent for that owner. |
| `/v1/group/invite` | `hash` | Owner; SHA-256 hex digest of a random 32-byte ticket. Replaces the previous invitation; expires in ten minutes. |
| `/v1/group/join` | `id`, `token`, `ticket` | One-use ticket, no prior device auth. Registers a pending controller atomically. |
| `/v1/group/approve` | `id` | Owner; activates a pending controller. Does not itself grant phone keys. |
| `/v1/group/add-phone` | `id` | Owner; records an already registered phone after encrypted owner approval. Idempotent for an active phone. |
| `/v1/group/state` | `{}` | Returns `owner`, `members`, own encrypted `package`, and `revision`. Only owner sees the roster; other members see only their own row. |
| `/v1/group/publish` | `id`, `revision`, `package` | Owner; stores an opaque package for an active controller or the owner. Revision must strictly increase. |
| `/v1/group/revoke` | `id` | Owner; blocks a member other than the owner, deletes registration/tickets/queued ciphertext/package, closes sockets with 4003. Keeps a revocation tombstone. |

Pending controllers can exchange ciphertext only with the owner. Active
controllers can exchange it only with the owner and active group phones. Legacy
native pairings otherwise retain their routing behavior. The phone enforces
decrypted group permissions. A revoked ID is rejected by registration, HTTP,
WebSocket admission, and message routing. The owner cannot unregister itself;
`owner_requires_recovery` prevents accidental loss of ownership.

Ordinary bodies are limited to 4 KiB. Package publication accepts 2 MB bodies,
with at most 1,900,000 base64url characters in the package. Invitations and stored
relay tokens are hashed. The member roster, roles, status, revisions, and routing
metadata are visible to the relay; device names and grants are encrypted.

Browser invitations use `hyperlpa-group:` plus base64url JSON with the phone
invitation fields and a random `ticket`. The owner/browser pair secret and ticket
are separate random 32-byte values. An encrypted `pair` proves possession to the
owner, which asks for explicit approval. New browsers start with no phone grants.

Controller packages contain `{ "phones": [...] }` with each allowed phone's ID,
name, distinct `pair`/`secret`, and boolean profile/notification grants. Derive an
AES-256-GCM key with HKDF-SHA256, decoded owner/controller pair secret as input,
UTF-8 `HyperLPA/group-package/v1` as salt, and `<owner>/<recipient>` as info.
Use a random 12-byte nonce and the UTF-8 AAD `<owner>\n<recipient>\n<revision>`.
The package is unpadded base64url of nonce followed by ciphertext and 16-byte tag.
Recipient and revision are authenticated. Controllers only accept increasing
revisions and validated phone records.

The owner's own package uses the same format, owner as both endpoints, and its
random relay token as HKDF input. Its plaintext is the owner identity, grants,
and pending-operation journal. The relay stores only the token hash and cannot
derive that key. It supports refreshing an old encrypted owner export after
restore. It is not an account recovery service and does not rotate a lost token.

### Phone group provisioning

`pair` can carry `groupOwner: true`. Updated Android displays the requested owner
authority before approval and stores at most one owner. `paired` and `hello_reply`
advertise `groupVersion: 1`; the web owner rejects phones without this support.
Existing v1 peers ignore these optional fields.

The approved owner sends encrypted `group_sync` with `groupSync` containing a
monotonic positive `revision` and a full replacement `peers` list. Each peer has
`id`, `name`, `pair`, `secret`, `canManageProfiles`, and `canViewNotifications`.
The phone validates all records before applying the update and replies
`group_synced` with the applied revision. An identical revision is idempotent.
Only peers managed by that owner are replaced. Ordinary pairings cannot be
overwritten. The list has at most 31 controllers and all pairings together at most
32 peers. The owner creates a unique secret per phone/controller relation.

Profile commands and positive installation decisions require `canManageProfiles`.
Notification access requires the controller grant, source sharing enabled, and
source consent for both the controller and its group owner. Removing the owner
locally also removes all peers managed by it. The relay blocks revoked members
immediately; an offline phone learns key changes only on a later owner sync.

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

The trusted-group web controller may also send `SETTINGS`. Its payload contains
only non-secret reader, notification, reminder, artwork, and logging preferences.
Remote-reader URLs, bearer credentials, IMEI, and Android permission grants are
never included in that payload. The phone applies the settings to its local
DataStore and returns them in the next snapshot. Reader permissions and endpoint
credentials still require the native Android settings screens.
