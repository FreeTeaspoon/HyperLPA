import { DurableObject } from "cloudflare:workers";

export interface Env {
  RELAY: DurableObjectNamespace<Relay>;
  ENROLLMENT_KEY: string;
}

const MAX_FRAME = 1_500_000;
const MAX_PENDING = 64;
const MAX_DEVICES = 64;
const ID = /^[a-f0-9-]{36}$/;
const SECRET = /^[A-Za-z0-9_-]{43}$/;

export interface Envelope {
  v: number;
  id: string;
  from: string;
  to: string;
  pair: string;
  issued: number;
  expires: number;
  nonce: string;
  body: string;
}

export function validEnvelope(e: Envelope, sender: string, now: number): boolean {
  return e?.v === 1 && ID.test(e.id) && e.from === sender && ID.test(e.to) &&
    e.to !== sender && ID.test(e.pair) && Number.isSafeInteger(e.issued) &&
    Number.isSafeInteger(e.expires) && e.issued <= now + 30_000 &&
    e.expires > now && e.expires <= e.issued + 120_000 &&
    typeof e.nonce === "string" && /^[A-Za-z0-9_-]{16}$/.test(e.nonce) &&
    typeof e.body === "string" && e.body.length >= 22 && e.body.length <= MAX_FRAME - 1024 &&
    /^[A-Za-z0-9_-]+$/.test(e.body);
}

const json = (value: unknown, status = 200) => Response.json(value, {
  status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
});
const digest = async (value: string) => Array.from(new Uint8Array(
  await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
)).map(v => v.toString(16).padStart(2, "0")).join("");
const randomSecret = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (new URL(request.url).pathname === "/health") return json({ protocol: 1 });
    return env.RELAY.getByName("hyperlpa-v1").fetch(request);
  },
};

/** Stores only routing metadata, hashed relay credentials, and opaque ciphertext. */
export class Relay extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, token TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS tickets (token TEXT PRIMARY KEY, device TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL, expires INTEGER NOT NULL, payload TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS recipient_messages ON messages(recipient, expires);
      CREATE TABLE IF NOT EXISTS rates (key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
    `);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  private limited(key: string, limit: number): boolean {
    const now = Date.now();
    this.ctx.storage.sql.exec("DELETE FROM rates WHERE expires < ?", now);
    this.ctx.storage.sql.exec(
      "INSERT INTO rates VALUES (?, 1, ?) ON CONFLICT(key) DO UPDATE SET count = count + 1",
      key, now + 60_000,
    );
    return this.ctx.storage.sql.exec<{ count: number }>("SELECT count FROM rates WHERE key = ?", key).one().count > limit;
  }

  private async authenticated(request: Request): Promise<string | null> {
    const id = request.headers.get("X-Device-Id") ?? "";
    const token = request.headers.get("Authorization")?.replace(/^Bearer /, "") ?? "";
    if (!ID.test(id) || !SECRET.test(token)) return null;
    const hashed = await digest(token);
    const row = this.ctx.storage.sql.exec<{ token: string }>("SELECT token FROM devices WHERE id = ?", id).toArray()[0];
    return row?.token === hashed ? id : null;
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/v1/socket" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const token = new URL(request.url).searchParams.get("ticket") ?? "";
      const ticketHash = SECRET.test(token) ? await digest(token) : "";
      const row = SECRET.test(token) ? this.ctx.storage.sql.exec<{ device: string; expires: number }>(
        "SELECT device, expires FROM tickets WHERE token = ?", ticketHash,
      ).toArray()[0] : undefined;
      if (!row || row.expires <= Date.now() ||
        !this.ctx.storage.sql.exec("SELECT id FROM devices WHERE id = ?", row.device).toArray().length)
        return json({ error: "unauthorized" }, 401);
      // No await between checking and consuming a one-use ticket.
      this.ctx.storage.sql.exec("DELETE FROM tickets WHERE token = ?", ticketHash);
      this.ctx.getWebSockets(row.device).forEach(ws => ws.close(4001, "Replaced by a new connection"));
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, [row.device]);
      server.serializeAttachment({ device: row.device });
      this.ctx.storage.sql.exec("DELETE FROM messages WHERE expires <= ?", Date.now());
      server.send(JSON.stringify({ type: "ready", protocol: 1 }));
      for (const message of this.ctx.storage.sql.exec<{ payload: string }>(
        "SELECT payload FROM messages WHERE recipient = ? ORDER BY expires", row.device,
      )) server.send(JSON.stringify({ type: "message", message: JSON.parse(message.payload) }));
      return new Response(null, { status: 101, webSocket: client });
    }
    if (request.method !== "POST") return json({ error: "not_found" }, 404);
    if (Number(request.headers.get("Content-Length") ?? 0) > 4096) return json({ error: "too_large" }, 413);
    let raw = "";
    try {
      const reader = request.body?.getReader();
      if (reader) {
        let size = 0;
        const decoder = new TextDecoder("utf-8", { fatal: true });
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          size += chunk.value.length;
          if (size > 4096) { await reader.cancel(); return json({ error: "too_large" }, 413); }
          raw += decoder.decode(chunk.value, { stream: true });
        }
        raw += decoder.decode();
      }
    } catch { return json({ error: "invalid_request" }, 400); }
    let body: Record<string, string>;
    try { body = JSON.parse(raw || "{}"); } catch { return json({ error: "invalid_json" }, 400); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return json({ error: "invalid_request" }, 400);
    if (path === "/v1/register") {
      // Bound this table using one global counter as well as the edge-supplied source address.
      if (this.limited("enroll", 60) || this.limited("enroll:" + (request.headers.get("CF-Connecting-IP") ?? "local"), 10))
        return json({ error: "rate_limited" }, 429);
      const supplied = request.headers.get("X-Enrollment-Key") ?? "";
      if (!this.env.ENROLLMENT_KEY || supplied.length > 256 ||
        await digest(supplied) !== await digest(this.env.ENROLLMENT_KEY)) return json({ error: "unauthorized" }, 401);
      if (!ID.test(body.id ?? "") || !SECRET.test(body.token ?? "")) return json({ error: "invalid_device" }, 400);
      const token = await digest(body.token);
      const existing = this.ctx.storage.sql.exec<{ token: string }>("SELECT token FROM devices WHERE id = ?", body.id).toArray()[0];
      if (existing) return existing.token === token ? json({ protocol: 1 }) : json({ error: "conflict" }, 409);
      if (this.ctx.storage.sql.exec<{ n: number }>("SELECT count(*) n FROM devices").one().n >= MAX_DEVICES)
        return json({ error: "device_limit" }, 409);
      this.ctx.storage.sql.exec("INSERT INTO devices VALUES (?, ?)", body.id, token);
      return json({ protocol: 1 });
    }
    const device = await this.authenticated(request);
    if (!device) return json({ error: "unauthorized" }, 401);
    if (this.limited("http:" + device, 60)) return json({ error: "rate_limited" }, 429);
    if (path === "/v1/session") {
      const ticket = randomSecret();
      const hashed = await digest(ticket);
      if (!this.ctx.storage.sql.exec("SELECT id FROM devices WHERE id = ?", device).toArray().length)
        return json({ error: "unauthorized" }, 401);
      this.ctx.storage.sql.exec("DELETE FROM tickets WHERE expires < ? OR device = ?", Date.now(), device);
      this.ctx.storage.sql.exec("INSERT INTO tickets VALUES (?, ?, ?)", hashed, device, Date.now() + 30_000);
      return json({ ticket });
    }
    if (path === "/v1/unregister") {
      this.ctx.storage.sql.exec("DELETE FROM devices WHERE id = ?", device);
      this.ctx.storage.sql.exec("DELETE FROM tickets WHERE device = ?", device);
      this.ctx.storage.sql.exec("DELETE FROM messages WHERE sender = ? OR recipient = ?", device, device);
      this.ctx.getWebSockets(device).forEach(ws => ws.close(4003, "Device removed"));
      return json({ removed: true });
    }
    return json({ error: "not_found" }, 404);
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const device = ws.deserializeAttachment()?.device as string;
    if (!device || typeof raw !== "string" || raw.length > MAX_FRAME) { ws.close(1009, "Invalid frame"); return; }
    if (!this.ctx.storage.sql.exec("SELECT id FROM devices WHERE id = ?", device).toArray().length) { ws.close(1008, "Unregistered device"); return; }
    if (this.limited("ws:" + device, 2048)) { ws.close(1008, "Rate limit"); return; }
    let frame;
    try { frame = JSON.parse(raw); } catch { ws.close(1007, "Invalid JSON"); return; }
    if (frame?.type === "ack" && ID.test(frame.id ?? "")) {
      this.ctx.storage.sql.exec("DELETE FROM messages WHERE id = ? AND recipient = ?", frame.id, device);
      return;
    }
    if (frame?.type !== "send" || !validEnvelope(frame.message, device, Date.now())) {
      ws.send(JSON.stringify({ type: "error", code: "invalid_message" })); return;
    }
    const e = frame.message as Envelope;
    this.ctx.storage.sql.exec("DELETE FROM messages WHERE expires <= ?", Date.now());
    const known = this.ctx.storage.sql.exec("SELECT id FROM devices WHERE id = ?", e.to).toArray().length > 0;
    const pending = this.ctx.storage.sql.exec<{ n: number; bytes: number }>(
      "SELECT count(*) n, coalesce(sum(length(payload)), 0) bytes FROM messages WHERE recipient = ?", e.to,
    ).one();
    if (!known || pending.n >= MAX_PENDING || pending.bytes + raw.length > 32_000_000) {
      ws.send(JSON.stringify({ type: "error", id: e.id, code: known ? "inbox_full" : "unknown_device" })); return;
    }
    const existing = this.ctx.storage.sql.exec<{ sender: string; payload: string }>(
      "SELECT sender, payload FROM messages WHERE id = ?", e.id,
    ).toArray()[0];
    const payload = JSON.stringify(e);
    if (existing && (existing.sender !== device || existing.payload !== payload)) {
      ws.send(JSON.stringify({ type: "error", id: e.id, code: "conflict" })); return;
    }
    this.ctx.storage.sql.exec("INSERT OR IGNORE INTO messages VALUES (?, ?, ?, ?, ?)", e.id, device, e.to, e.expires, payload);
    for (const recipient of this.ctx.getWebSockets(e.to)) recipient.send(JSON.stringify({ type: "message", message: e }));
    ws.send(JSON.stringify({ type: "sent", id: e.id }));
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null) await this.ctx.storage.setAlarm(Date.now() + 120_000);
  }

  async alarm(): Promise<void> {
    this.ctx.storage.sql.exec("DELETE FROM messages WHERE expires <= ?", Date.now());
    this.ctx.storage.sql.exec("DELETE FROM tickets WHERE expires <= ?", Date.now());
    this.ctx.storage.sql.exec("DELETE FROM rates WHERE expires <= ?", Date.now());
    if (this.ctx.storage.sql.exec<{ n: number }>("SELECT count(*) n FROM messages").one().n)
      await this.ctx.storage.setAlarm(Date.now() + 120_000);
  }

  webSocketClose(ws: WebSocket, code: number): void { ws.close(code === 1006 ? 1001 : code); }
  webSocketError(ws: WebSocket): void { ws.close(1011, "Connection interrupted"); }
}
