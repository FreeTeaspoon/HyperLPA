import { env, SELF, runInDurableObject } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { digest, Groups } from "../src/groups";
type Device = { id: string; token: string };
const secret = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const device = (): Device => ({ id: crypto.randomUUID(), token: secret() });
const post = (path: string, body: unknown, d?: Device, enrollment = false, origin?: string) => SELF.fetch("https://relay.test" + path, {
  method: "POST", headers: { "Content-Type": "application/json", ...(d ? { "X-Device-Id": d.id, Authorization: "Bearer " + d.token } : {}),
    ...(enrollment ? { "X-Enrollment-Key": "test-only-enrollment-key" } : {}), ...(origin ? { Origin: origin } : {}) }, body: JSON.stringify(body),
});
async function register(d = device()) { expect((await post("/v1/register", d, undefined, true)).status).toBe(200); return d; }
async function owner() { const d = await register(); expect((await post("/v1/group/create", {}, d, true)).status).toBe(200); return d; }
async function invited(o: Device) {
  const ticket = secret();
  expect((await post("/v1/group/invite", { hash: await digest(ticket) }, o)).status).toBe(200);
  const d = device(); expect((await post("/v1/group/join", { ...d, ticket })).status).toBe(200); return { d, ticket };
}
beforeEach(async () => {
  await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (_instance, state) => {
    for (const table of ["devices", "tickets", "messages", "rates", "group_owner", "group_members", "group_invites"])
      state.storage.sql.exec(`DELETE FROM ${table}`);
  });
});
describe("trusted group", () => {
  it("requires the deployment key for explicit ownership and has only one owner even in a race", async () => {
    const a = await register(), b = await register();
    expect((await post("/v1/group/create", {}, a)).status).toBe(401);
    const responses = await Promise.all([post("/v1/group/create", {}, a, true), post("/v1/group/create", {}, b, true)]);
    expect(responses.map(r => r.status).sort()).toEqual([200, 409]);
  });
  it("consumes enrollment capabilities once and requires owner approval", async () => {
    const o = await owner(); const { d, ticket } = await invited(o);
    expect((await post("/v1/group/join", { ...device(), ticket })).status).toBe(401);
    const state = await (await post("/v1/group/state", {}, d)).json() as any;
    expect(state.members[0].status).toBe("pending");
    expect((await post("/v1/group/approve", { id: d.id }, d)).status).toBe(403);
    expect((await post("/v1/group/approve", { id: d.id }, o)).status).toBe(200);
    expect((await post("/v1/group/state", {}, d)).status).toBe(200);
  });
  it("keeps provisioning opaque, recipient scoped, and monotonic", async () => {
    const o = await owner(); const { d } = await invited(o); const { d: other } = await invited(o);
    await post("/v1/group/approve", { id: d.id }, o);
    const body = { id: d.id, revision: 1, package: secret() + secret() };
    expect((await post("/v1/group/publish", body, o)).status).toBe(200);
    expect((await post("/v1/group/publish", body, o)).status).toBe(409);
    expect((await (await post("/v1/group/state", {}, d)).json() as any).package).toBe(body.package);
    expect((await (await post("/v1/group/state", {}, other)).json() as any).package).toBe("");
    expect((await post("/v1/group/publish", { ...body, revision: 2 }, d)).status).toBe(403);
  });
  it("revokes device credentials, socket tickets, and prevents re-registration", async () => {
    const o = await owner(); const { d } = await invited(o);
    await post("/v1/group/approve", { id: d.id }, o);
    const { ticket } = await (await post("/v1/session", {}, d)).json() as any;
    expect((await post("/v1/group/revoke", { id: d.id }, o)).status).toBe(200);
    expect((await post("/v1/session", {}, d)).status).toBe(401);
    expect((await post("/v1/register", d, undefined, true)).status).toBe(403);
    expect((await SELF.fetch("https://relay.test/v1/socket?ticket=" + ticket, { headers: { Upgrade: "websocket" } })).status).toBe(401);
    expect((await post("/v1/group/revoke", { id: o.id }, o)).status).toBe(400);
  });
  it("uses exact allowed browser origins and preserves native clients", async () => {
    const d = device();
    expect((await post("/v1/register", d, undefined, true, "https://evil.example")).status).toBe(403);
    expect((await post("/v1/register", d, undefined, true, "https://dashboard.test")).headers.get("Access-Control-Allow-Origin")).toBe("https://dashboard.test");
    expect((await post("/v1/session", {}, d)).status).toBe(200);
    const preflight = await SELF.fetch("https://relay.test/v1/session", { method: "OPTIONS", headers: { Origin: "https://dashboard.test" } });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("Access-Control-Allow-Origin")).toBe("https://dashboard.test");
  });
  it("bounds pending and active controller routing to their approved group", async () => {
    const o = await owner(), phone = await register(), legacy = await register();
    const { d } = await invited(o);
    expect((await post("/v1/group/add-phone", { id: phone.id }, o)).status).toBe(200);
    expect((await post("/v1/group/add-phone", { id: phone.id }, o)).status).toBe(200);
    await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (_instance, state) => {
      const groups = new Groups(state);
      expect(groups.allowed(d.id, o.id)).toBe(true);
      expect(groups.allowed(phone.id, d.id)).toBe(false);
      expect(groups.allowed(d.id, legacy.id)).toBe(false);
    });
    await post("/v1/group/approve", { id: d.id }, o);
    await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (_instance, state) => {
      const groups = new Groups(state);
      expect(groups.allowed(d.id, phone.id)).toBe(true);
      expect(groups.allowed(phone.id, d.id)).toBe(true);
      expect(groups.allowed(d.id, legacy.id)).toBe(false);
    });
    await post("/v1/group/revoke", { id: d.id }, o);
    await runInDurableObject(env.RELAY.getByName("hyperlpa-v1"), async (_instance, state) => {
      expect(new Groups(state).allowed(d.id, phone.id)).toBe(false);
      expect(state.storage.sql.exec("SELECT id FROM devices WHERE id = ?", d.id).toArray()).toEqual([]);
    });
  });
  it("rejects malformed JSON field types without coercion", async () => {
    const o = await owner();
    expect((await post("/v1/group/join", { id: [o.id], token: secret(), ticket: secret() })).status).toBe(400);
    expect((await post("/v1/group/revoke", { id: [o.id] }, o)).status).toBe(400);
    expect((await post("/v1/group/invite", { hash: ["a".repeat(64)] }, o)).status).toBe(400);
  });
});
