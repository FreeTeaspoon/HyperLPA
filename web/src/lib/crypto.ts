import type { Envelope, Invitation, Message } from "./types";
const utf8 = new TextEncoder();
const text = new TextDecoder("utf-8", { fatal: true });
export const uuid = () => crypto.randomUUID();
export function encode(bytes: Uint8Array): string {
  let value = "";
  for (let i = 0; i < bytes.length; i += 8192) value += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
export function decode(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]*$/.test(value)) throw new Error("Invalid encoding.");
  return Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0));
}
export const secret = () => encode(crypto.getRandomValues(new Uint8Array(32)));
export const validId = (value: unknown): value is string => typeof value === "string" && /^[a-f0-9-]{36}$/.test(value);
export function relayOrigin(value: string): string {
  const url = new URL(value.trim());
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname !== "/")
    throw new Error("Enter an HTTPS relay address without a path, query or credentials.");
  return url.origin;
}
export async function hash(value: string) {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", utf8.encode(value))), b => b.toString(16).padStart(2, "0")).join("");
}
async function derived(secretValue: string, salt: string, info: string): Promise<CryptoKey> {
  const raw = decode(secretValue);
  if (raw.length !== 32) throw new Error("Invalid key.");
  const key = await crypto.subtle.importKey("raw", raw, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256", salt: utf8.encode(salt), info: utf8.encode(info) },
    key, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}
const aad = (e: Envelope) => utf8.encode(`${e.v}\n${e.id}\n${e.from}\n${e.to}\n${e.pair}\n${e.issued}\n${e.expires}`);
export async function encrypt(key: string, from: string, to: string, pair: string, message: Message, now = Date.now()): Promise<Envelope> {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const e: Envelope = { v: 1, id: uuid(), from, to, pair, issued: now, expires: now + 120_000, nonce: encode(nonce), body: "" };
  const plaintext = utf8.encode(JSON.stringify(message));
  if (plaintext.length > 600_000) throw new Error("Command is too large.");
  e.body = encode(new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: aad(e) },
    await derived(key, `HyperLPA/pair/v1/${pair}`, `${from}/${to}`), plaintext)));
  return e;
}
export async function decrypt(key: string, e: Envelope, recipient: string, now = Date.now()): Promise<Message> {
  if (e.v !== 1 || e.to !== recipient || e.from === recipient || ![e.id, e.from, e.to, e.pair].every(validId) ||
    !Number.isSafeInteger(e.issued) || !Number.isSafeInteger(e.expires) || e.expires <= now || e.expires <= e.issued ||
    e.expires > e.issued + 120_000 || e.issued > now + 30_000 || e.body.length > 1_498_976 || decode(e.nonce).length !== 12)
    throw new Error("Invalid or expired message.");
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(e.nonce), additionalData: aad(e) },
    await derived(key, `HyperLPA/pair/v1/${e.pair}`, `${e.from}/${e.to}`), decode(e.body));
  return JSON.parse(text.decode(plaintext));
}
export function parseInvitation(code: string, prefix = "hyperlpa-pair:", now = Date.now()): Invitation {
  if (!code.startsWith(prefix) || code.length > 4096) throw new Error("Paste a valid pairing code.");
  const invite = JSON.parse(text.decode(decode(code.slice(prefix.length)))) as Invitation;
  if (invite.version !== 1 || !validId(invite.device) || !validId(invite.pair) || typeof invite.name !== "string" ||
      invite.name.length < 1 || invite.name.length > 80 || decode(invite.secret).length !== 32 ||
      !Number.isSafeInteger(invite.expires) || invite.expires <= now || invite.expires > now + 600_000)
    throw new Error("This pairing code is invalid or expired.");
  invite.relay = relayOrigin(invite.relay);
  if (prefix === "hyperlpa-group:" && (!invite.ticket || decode(invite.ticket).length !== 32)) throw new Error("Invalid group invitation.");
  return invite;
}
export function invitationCode(invite: Invitation) { return "hyperlpa-group:" + encode(utf8.encode(JSON.stringify(invite))); }
export async function sealPackage(key: string, owner: string, recipient: string, revision: number, value: unknown): Promise<string> {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const data = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce,
    additionalData: utf8.encode(`${owner}\n${recipient}\n${revision}`) },
    await derived(key, "HyperLPA/group-package/v1", `${owner}/${recipient}`), utf8.encode(JSON.stringify(value))));
  return encode(new Uint8Array([...nonce, ...data]));
}
export async function openPackage(key: string, owner: string, recipient: string, revision: number, value: string): Promise<unknown> {
  if (value.length > 1_900_000) throw new Error("Group package is too large.");
  const data = decode(value);
  return JSON.parse(text.decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: data.slice(0, 12),
    additionalData: utf8.encode(`${owner}\n${recipient}\n${revision}`) },
    await derived(key, "HyperLPA/group-package/v1", `${owner}/${recipient}`), data.slice(12))));
}

/** Reassembly is bounded across peers and transfers, matching Android DevicePackets. */
export class Packets {
  private assemblies = new Map<string, { expires: number; parts: (Uint8Array | undefined)[] }>();
  accept(peer: string, part: NonNullable<Message["part"]>, expires: number, now = Date.now()): Message | undefined {
    for (const [key, a] of this.assemblies) if (a.expires <= now) this.assemblies.delete(key);
    if (!validId(part.transfer) || !Number.isInteger(part.count) || part.count < 2 || part.count > 28 ||
      !Number.isInteger(part.index) || part.index < 0 || part.index >= part.count || part.data.length > 800_000)
      throw new Error("Invalid message part.");
    const key = `${peer}/${part.transfer}`;
    let a = this.assemblies.get(key);
    if (!a) {
      if (this.assemblies.size >= 4) throw new Error("Too many transfers.");
      a = { expires, parts: Array.from({ length: part.count }) }; this.assemblies.set(key, a);
    }
    if (a.parts.length !== part.count) throw new Error("Transfer changed.");
    a.expires = Math.min(a.expires, expires);
    const bytes = decode(part.data), previous = a.parts[part.index];
    if (bytes.length > 600_000) throw new Error("Part is too large.");
    if (previous) {
      if (encode(previous) !== part.data) throw new Error("Transfer part changed.");
      return;
    }
    const total = [...this.assemblies.values()].reduce((n, x) => n + x.parts.reduce((m, p) => m + (p?.length ?? 0), 0), 0);
    if (total + bytes.length > 16_800_000) throw new Error("Transfer limit exceeded.");
    a.parts[part.index] = bytes;
    if (a.parts.some(p => !p)) return;
    this.assemblies.delete(key);
    const joined = new Uint8Array(a.parts.reduce((n, p) => n + p!.length, 0));
    let offset = 0;
    for (const p of a.parts) { joined.set(p!, offset); offset += p!.length; }
    const message = JSON.parse(text.decode(joined)) as Message;
    if (message.part || message.kind === "part") throw new Error("Nested transfer.");
    return message;
  }
}
