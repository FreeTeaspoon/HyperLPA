import { decode, encode, parseInvitation, relayOrigin, secret, validId } from "./crypto";
import type { Identity, Peer } from "./types";
const aad = new TextEncoder().encode("HyperLPA browser identity v1");
let opening: Promise<IDBDatabase> | undefined;
function database() {
  return opening ??= new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("hyperlpa-browser-v1", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("identity");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error("Browser storage is unavailable. Enable site storage and try again."));
  });
}
async function get<T>(key: string): Promise<T | undefined> {
  const db = await database();
  return new Promise((resolve, reject) => {
    const request = db.transaction("identity").objectStore("identity").get(key);
    request.onsuccess = () => resolve(request.result as T | undefined);
    request.onerror = () => reject(request.error);
  });
}
async function put(key: string, value: unknown) {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction("identity", "readwrite");
    tx.objectStore("identity").put(value, key);
    tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
  });
}
async function localKey() {
  const existing = await get<CryptoKey>("key");
  if (existing) return existing;
  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  await put("key", key); return key;
}
export function validatePeer(value: Peer): Peer {
  if (!value || !validId(value.id) || !validId(value.pair) || typeof value.name !== "string" ||
      value.name.length < 1 || value.name.length > 80 || decode(value.secret).length !== 32 ||
      !["phone", "browser", "owner"].includes(value.kind) || typeof value.approved !== "boolean" ||
      typeof value.profiles !== "boolean" || typeof value.notifications !== "boolean") throw new Error("Invalid device record.");
  return value;
}
export function validateIdentity(value: Identity, keepInvitation = false): Identity {
  if (!value || value.version !== 1 || !validId(value.id) || !validId(value.owner) || decode(value.token).length !== 32 ||
      typeof value.name !== "string" || !value.name.trim() || value.name.length > 80 ||
      !["owner", "controller"].includes(value.role) || value.role === "owner" && value.id !== value.owner ||
      !Array.isArray(value.peers) || value.peers.length > 64 || !Number.isSafeInteger(value.revision) || value.revision < 0 ||
      value.updated !== undefined && (!Number.isSafeInteger(value.updated) || value.updated < 0) ||
      !Array.isArray(value.pending) || value.pending.length > 64 || !value.grants || typeof value.grants !== "object")
    throw new Error("Invalid recovery file.");
  value.relay = relayOrigin(value.relay);
  value.peers.forEach(validatePeer);
  if (new Set(value.peers.map(p => p.id)).size !== value.peers.length) throw new Error("Duplicate device identity.");
  if (Object.keys(value.grants).length > 64) throw new Error("Invalid group grants.");
  for (const [id, grants] of Object.entries(value.grants)) {
    if (!validId(id) || !grants || Object.keys(grants).length > 31) throw new Error("Invalid group grants.");
    for (const [member, peer] of Object.entries(grants)) {
      if (member !== peer.id) throw new Error("Invalid group grant identity.");
      validatePeer(peer);
    }
  }
  for (const p of value.pending) if (!validId(p.id) || !validId(p.peer) || !Number.isSafeInteger(p.started) ||
    typeof p.action !== "string" || typeof p.readerId !== "string") throw new Error("Invalid pending operation.");
  // An old recovery file must never re-activate an enrollment invitation.
  if (keepInvitation && value.invitation) {
    try {
      const parsed = parseInvitation("hyperlpa-group:" + encode(new TextEncoder().encode(JSON.stringify(value.invitation))), "hyperlpa-group:");
      if (parsed.device !== value.id || parsed.relay !== value.relay) delete value.invitation;
    } catch { delete value.invitation; }
  } else delete value.invitation;
  return value;
}
export async function loadIdentity(): Promise<Identity | undefined> {
  const value = await get<{ nonce: string; body: string }>("state");
  if (!value) return;
  const key = await get<CryptoKey>("key");
  if (!key) throw new Error("Browser key is missing. Restore your recovery file.");
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(value.nonce), additionalData: aad }, key, decode(value.body));
  return validateIdentity(JSON.parse(new TextDecoder().decode(plaintext)), true);
}
export async function saveIdentity(value: Identity) {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const body = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: aad },
    await localKey(), new TextEncoder().encode(JSON.stringify(value)));
  await put("state", { nonce: encode(nonce), body: encode(new Uint8Array(body)) });
}
export async function forgetIdentity() { await put("state", undefined); }
export async function exportRecovery(identity: Identity) {
  const recoveryKey = secret();
  const key = await crypto.subtle.importKey("raw", decode(recoveryKey), "AES-GCM", false, ["encrypt"]);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const copy = { ...identity, invitation: undefined };
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: aad }, key,
    new TextEncoder().encode(JSON.stringify(copy)));
  return { recoveryKey, file: JSON.stringify({ format: "hyperlpa-recovery-v1", nonce: encode(nonce), body: encode(new Uint8Array(data)) }) };
}
export async function importRecovery(file: string, recoveryKey: string): Promise<Identity> {
  if (file.length > 3_000_000) throw new Error("Recovery file is too large.");
  try {
    const value = JSON.parse(file);
    if (value.format !== "hyperlpa-recovery-v1" || decode(value.nonce).length !== 12) throw new Error();
    const key = await crypto.subtle.importKey("raw", decode(recoveryKey.trim()), "AES-GCM", false, ["decrypt"]);
    const data = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(value.nonce), additionalData: aad }, key, decode(value.body));
    return validateIdentity(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(data)));
  } catch { throw new Error("The recovery file or recovery key is incorrect."); }
}
