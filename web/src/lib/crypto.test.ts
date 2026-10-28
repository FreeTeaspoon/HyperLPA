import { describe, expect, it } from "vitest";
import vector from "../../../relay/test/device-protocol-vector.json";
import { decode, decrypt, encode, encrypt, openPackage, Packets, parseInvitation, relayOrigin, sealPackage, secret, uuid } from "./crypto";
import { exportRecovery, importRecovery } from "./storage";
import type { Identity } from "./types";

describe("Android wire compatibility", () => {
  it("decrypts the shared JVM/WebCrypto vector", async () => {
    const message = await decrypt(vector.secret, vector.envelope, vector.envelope.to, 1_000_001);
    expect(message).toEqual(JSON.parse(vector.plaintext));
  });
  it("authenticates routing, direction, expiration and ciphertext", async () => {
    const key = secret(), from = uuid(), to = uuid(), pair = uuid();
    const e = await encrypt(key, from, to, pair, { kind: "hello", name: "Phone" }, 1000);
    expect((await decrypt(key, e, to, 1001)).name).toBe("Phone");
    for (const changed of [{ from: uuid() }, { pair: uuid() }, { expires: e.expires - 1 }, { body: e.body.slice(0, -4) + "AAAA" }])
      await expect(decrypt(key, { ...e, ...changed }, to, 1001)).rejects.toThrow();
    await expect(decrypt(key, e, from, 1001)).rejects.toThrow();
    await expect(decrypt(key, e, to, e.expires)).rejects.toThrow();
  });
  it("isolates durable group packages by recipient and revision", async () => {
    const key = secret(), owner = uuid(), recipient = uuid();
    const box = await sealPackage(key, owner, recipient, 3, { phones: [] });
    expect(await openPackage(key, owner, recipient, 3, box)).toEqual({ phones: [] });
    await expect(openPackage(key, owner, uuid(), 3, box)).rejects.toThrow();
    await expect(openPackage(key, owner, recipient, 2, box)).rejects.toThrow();
    await expect(openPackage(secret(), owner, recipient, 3, box)).rejects.toThrow();
  });
  it("reassembles out-of-order UTF8 parts, scopes peers and rejects conflicts", () => {
    const packets = new Packets(), transfer = uuid(), now = Date.now();
    const bytes = new TextEncoder().encode(JSON.stringify({ kind: "hello", name: "Browser ↔ Android" }));
    const a = { transfer, index: 0, count: 2, data: encode(bytes.slice(0, 15)) };
    const b = { transfer, index: 1, count: 2, data: encode(bytes.slice(15)) };
    expect(packets.accept("a", b, now + 1000)).toBeUndefined();
    expect(packets.accept("b", a, now + 1000)).toBeUndefined();
    expect(packets.accept("a", a, now + 1000)).toEqual({ kind: "hello", name: "Browser ↔ Android" });
    expect(() => packets.accept("b", { ...a, data: encode(new Uint8Array([1])) }, now + 1000)).toThrow();
    expect(() => packets.accept("a", { ...a, count: 29 }, now + 1000)).toThrow();
  });
  it("validates pairing invitations and rejects origins that can expose credentials", () => {
    const invitation = { version: 1, relay: "https://relay.test", device: uuid(), pair: uuid(), secret: secret(), name: "Phone", expires: Date.now() + 60_000 };
    const code = "hyperlpa-pair:" + encode(new TextEncoder().encode(JSON.stringify(invitation)));
    expect(parseInvitation(code)).toEqual(invitation);
    expect(() => parseInvitation(code, "hyperlpa-pair:", invitation.expires)).toThrow();
    for (const origin of ["http://relay.test", "https://user:secret@relay.test", "https://relay.test/path", "https://relay.test?token=x", "https://relay.test#x"])
      expect(() => relayOrigin(origin)).toThrow();
  });
});
describe("recovery exports", () => {
  it("encrypts identity and requires both the export and its separate random key", async () => {
    const id = uuid();
    const identity: Identity = { version: 1, id, owner: id, token: secret(), name: "Laptop", role: "owner", relay: "https://relay.test",
      peers: [], grants: {}, revision: 1, pending: [] };
    const backup = await exportRecovery(identity);
    expect(backup.file).not.toContain(identity.token);
    expect(await importRecovery(backup.file, backup.recoveryKey)).toEqual(identity);
    await expect(importRecovery(backup.file, secret())).rejects.toThrow();
    const changed = JSON.parse(backup.file); changed.body = encode(decode(changed.body).map((byte, i) => i === 20 ? byte ^ 1 : byte));
    await expect(importRecovery(JSON.stringify(changed), backup.recoveryKey)).rejects.toThrow();
  });
});
