import { SELF, env, runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { validEnvelope, type Envelope } from "../src/index";

const sockets: WebSocket[] = [];
const secret = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const device = () => ({ id: crypto.randomUUID(), token: secret() });
type Device = ReturnType<typeof device>;
async function post(path: string, body: unknown, d?: Device, key?: string) {
  return SELF.fetch("https://relay.test" + path, { method: "POST", headers: {
    "Content-Type": "application/json",
    ...(d ? { "X-Device-Id": d.id, Authorization: "Bearer " + d.token } : {}),
    ...(key ? { "X-Enrollment-Key": key } : {}),
  }, body: JSON.stringify(body) });
}
async function register(d = device()) {
  expect((await post("/v1/register", d, undefined, "test-only-enrollment-key")).status).toBe(200);
  return d;
}
async function connect(d: Device) {
  const response = await post("/v1/session", {}, d);
  expect(response.status).toBe(200);
  const { ticket } = await response.json() as { ticket: string };
  const upgrade = await SELF.fetch("https://relay.test/v1/socket?ticket=" + ticket, { headers: { Upgrade: "websocket" } });
  expect(upgrade.status).toBe(101);
  const ws = upgrade.webSocket!;
  sockets.push(ws);
  const queue: any[] = [];
  const waiting: Array<(message: any) => void> = [];
  ws.addEventListener("message", event => {
    const value = JSON.parse(event.data as string);
    const resolver = waiting.shift();
    if (resolver) resolver(value); else queue.push(value);
  });
  ws.accept();
  const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise<any>(resolve => waiting.push(resolve));
  expect((await next()).type).toBe("ready");
  return { ws, next, ticket };
}
function envelope(from: Device, to: Device): Envelope {
  const now = Date.now();
  return { v: 1, id: crypto.randomUUID(), from: from.id, to: to.id, pair: crypto.randomUUID(),
    issued: now, expires: now + 120_000, nonce: "AAAAAAAAAAAAAAAA", body: secret() };
}

beforeEach(async () => {
  await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (_instance, state) => {
    for (const table of ["devices", "tickets", "messages", "rates"]) state.storage.sql.exec(`DELETE FROM ${table}`);
  });
});
afterEach(() => { sockets.splice(0).forEach(ws => ws.close(1000)); });

describe("relay authorization and durable delivery", () => {
  it("requires an enrollment key, and never returns another device's token", async () => {
    const d = device();
    expect((await post("/v1/register", d)).status).toBe(401);
    await register(d);
    expect((await post("/v1/register", { ...d, token: secret() }, undefined, "test-only-enrollment-key")).status).toBe(409);
    expect((await post("/v1/session", {}, { ...d, token: secret() })).status).toBe(401);
    expect((await post("/v1/session", {}, d)).status).toBe(200);
  });

  it("consumes socket tickets exactly once", async () => {
    const d = await register();
    const connection = await connect(d);
    const again = await SELF.fetch("https://relay.test/v1/socket?ticket=" + connection.ticket, { headers: { Upgrade: "websocket" } });
    expect(again.status).toBe(401);
  });

  it("consumes a ticket once even when upgrades race", async () => {
    const d = await register();
    const { ticket } = await (await post("/v1/session", {}, d)).json() as { ticket: string };
    const responses = await Promise.all([0, 1].map(() =>
      SELF.fetch("https://relay.test/v1/socket?ticket=" + ticket, { headers: { Upgrade: "websocket" } })));
    expect(responses.map(r => r.status).sort()).toEqual([101, 401]);
    const ws = responses.find(r => r.status === 101)!.webSocket!;
    ws.accept(); sockets.push(ws);
  });

  it("bounds an offline recipient's inbox and expires its ciphertext", async () => {
    const a = await register(), b = await register();
    const sender = await connect(a);
    for (let i = 0; i < 64; i++) {
      sender.ws.send(JSON.stringify({ type: "send", message: envelope(a, b) }));
      expect((await sender.next()).type).toBe("sent");
    }
    sender.ws.send(JSON.stringify({ type: "send", message: envelope(a, b) }));
    expect((await sender.next()).code).toBe("inbox_full");
    await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (instance, state) => {
      state.storage.sql.exec("UPDATE messages SET expires = ?", Date.now() - 1);
      await instance.alarm();
      expect(state.storage.sql.exec("SELECT id FROM messages").toArray()).toHaveLength(0);
    });
  });

  it("rejects streaming oversized registration bodies without trusting Content-Length", async () => {
    const response = await SELF.fetch("https://relay.test/v1/register", { method: "POST",
      body: " ".repeat(4097), headers: { "X-Enrollment-Key": "test-only-enrollment-key" } });
    expect(response.status).toBe(413);
  });

  it("delivers opaque messages, restores unacknowledged messages, and removes acknowledged messages", async () => {
    const a = await register(), b = await register();
    const sender = await connect(a);
    const e = envelope(a, b);
    sender.ws.send(JSON.stringify({ type: "send", message: e }));
    expect(await sender.next()).toEqual({ type: "sent", id: e.id });
    const recipient = await connect(b);
    expect(await recipient.next()).toEqual({ type: "message", message: e });
    recipient.ws.close(1000);
    const again = await connect(b);
    expect((await again.next()).message).toEqual(e);
    again.ws.send(JSON.stringify({ type: "ack", id: e.id }));
    // A second message acts as a barrier after the acknowledgement.
    const reply = envelope(b, a);
    again.ws.send(JSON.stringify({ type: "send", message: reply }));
    expect((await again.next()).type).toBe("sent");
    await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (_instance, state) => {
      expect(state.storage.sql.exec("SELECT id FROM messages WHERE id = ?", e.id).toArray()).toHaveLength(0);
      const stored = state.storage.sql.exec<{ payload: string }>("SELECT payload FROM messages WHERE id = ?", reply.id).one();
      expect(JSON.parse(stored.payload).body).toBe(reply.body);
    });
  });

  it("rejects sender spoofing and expired commands", async () => {
    const a = await register(), b = await register();
    const sender = await connect(a);
    sender.ws.send(JSON.stringify({ type: "send", message: { ...envelope(a, b), from: b.id } }));
    expect((await sender.next()).code).toBe("invalid_message");
    sender.ws.send(JSON.stringify({ type: "send", message: { ...envelope(a, b), expires: Date.now() - 1 } }));
    expect((await sender.next()).code).toBe("invalid_message");
  });

  it("does not allow a sender to acknowledge the recipient's inbox", async () => {
    const a = await register(), b = await register();
    const sender = await connect(a);
    const e = envelope(a, b);
    sender.ws.send(JSON.stringify({ type: "send", message: e }));
    await sender.next();
    sender.ws.send(JSON.stringify({ type: "ack", id: e.id }));
    const recipient = await connect(b);
    expect((await recipient.next()).message.id).toBe(e.id);
  });

  it("revokes relay credentials and outstanding socket tickets on unregister", async () => {
    const d = await register();
    const { ticket } = await (await post("/v1/session", {}, d)).json() as { ticket: string };
    expect((await post("/v1/unregister", {}, d)).status).toBe(200);
    expect((await post("/v1/session", {}, d)).status).toBe(401);
    expect((await SELF.fetch("https://relay.test/v1/socket?ticket=" + ticket, { headers: { Upgrade: "websocket" } })).status).toBe(401);
  });

  it("rejects malformed envelopes and oversized lifetimes", () => {
    const a = device(), b = device(), e = envelope(a, b);
    expect(validEnvelope(e, a.id, Date.now())).toBe(true);
    for (const changed of [{ v: 2 }, { expires: e.issued + 120_001 }, { issued: Date.now() + 60_000 },
      { nonce: "bad" }, { body: "plaintext!" }, { to: a.id }, { id: "not-an-id" }]) {
      expect(validEnvelope({ ...e, ...changed }, a.id, Date.now())).toBe(false);
    }
  });
});
