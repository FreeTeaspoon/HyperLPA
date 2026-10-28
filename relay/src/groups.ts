/** Private deployment membership. Only encrypted provisioning packages reach storage. */
const ID = /^[a-f0-9-]{36}$/;
const SECRET = /^[A-Za-z0-9_-]{43}$/;
const json = (value: unknown, status = 200) => Response.json(value, {
  status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
});
export const digest = async (value: string) => Array.from(new Uint8Array(
  await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
)).map(v => v.toString(16).padStart(2, "0")).join("");
type Member = { id: string; role: string; status: string; package: string; revision: number };

export class Groups {
  constructor(private ctx: DurableObjectState) {
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS group_owner (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), id TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS group_members (id TEXT PRIMARY KEY, role TEXT NOT NULL, status TEXT NOT NULL, package TEXT NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS group_invites (hash TEXT PRIMARY KEY, expires INTEGER NOT NULL);
    `);
  }
  owner(): string | undefined {
    return this.ctx.storage.sql.exec<{ id: string }>("SELECT id FROM group_owner").toArray()[0]?.id;
  }
  member(id: string): Member | undefined {
    return this.ctx.storage.sql.exec<Member>("SELECT * FROM group_members WHERE id = ?", id).toArray()[0];
  }
  revoked(id: string): boolean { return this.member(id)?.status === "revoked"; }
  allowed(from: string, to: string): boolean {
    const a = this.member(from), b = this.member(to), owner = this.owner();
    if (a?.status === "revoked" || b?.status === "revoked") return false;
    if (a?.status === "pending" && to !== owner || b?.status === "pending" && from !== owner) return false;
    if (a?.role === "controller" && to !== owner && !(b?.role === "phone" && b.status === "active")) return false;
    if (b?.role === "controller" && from !== owner && !(a?.role === "phone" && a.status === "active")) return false;
    return true;
  }
  async join(body: Record<string, unknown>): Promise<Response> {
    if (typeof body.id !== "string" || typeof body.token !== "string" || typeof body.ticket !== "string" ||
        !ID.test(body.id) || !SECRET.test(body.token) || !SECRET.test(body.ticket))
      return json({ error: "invalid_request" }, 400);
    const hash = await digest(body.ticket), token = await digest(body.token);
    const invite = this.ctx.storage.sql.exec<{ expires: number }>("SELECT expires FROM group_invites WHERE hash = ?", hash).toArray()[0];
    if (!invite || invite.expires <= Date.now() || !this.owner()) return json({ error: "invalid_invite" }, 401);
    if (this.ctx.storage.sql.exec("SELECT id FROM devices WHERE id = ?", body.id).toArray().length || this.member(body.id))
      return json({ error: "conflict" }, 409);
    if (this.ctx.storage.sql.exec<{ n: number }>("SELECT count(*) n FROM devices").one().n >= 64)
      return json({ error: "device_limit" }, 409);
    // No await between checking the capability and consuming it.
    this.ctx.storage.sql.exec("DELETE FROM group_invites WHERE hash = ?", hash);
    this.ctx.storage.sql.exec("INSERT INTO devices VALUES (?, ?)", body.id, token);
    this.ctx.storage.sql.exec("INSERT INTO group_members(id, role, status) VALUES (?, 'controller', 'pending')", body.id);
    return json({ owner: this.owner() });
  }
  async handle(path: string, body: Record<string, unknown>, device: string, suppliedKey: string, enrollmentKey: string): Promise<Response> {
    let owner = this.owner();
    if (path === "/v1/group/create") {
      if (!suppliedKey || !enrollmentKey || await digest(suppliedKey) !== await digest(enrollmentKey))
        return json({ error: "unauthorized" }, 401);
      owner = this.owner();
      if (owner && owner !== device) return json({ error: "group_exists" }, 409);
      this.ctx.storage.sql.exec("INSERT OR IGNORE INTO group_owner VALUES (1, ?)", device);
      this.ctx.storage.sql.exec("INSERT OR IGNORE INTO group_members(id, role, status) VALUES (?, 'owner', 'active')", device);
      return json({ owner: device });
    }
    const member = this.member(device);
    if (!member || !owner) return json({ error: "not_a_member" }, 403);
    if (path === "/v1/group/state") {
      const members = device === owner
        ? this.ctx.storage.sql.exec("SELECT id, role, status, revision FROM group_members").toArray()
        : [{ id: member.id, role: member.role, status: member.status, revision: member.revision }];
      return json({ owner, members, package: member.package, revision: member.revision });
    }
    if (device !== owner) return json({ error: "owner_required" }, 403);
    if (path === "/v1/group/invite") {
      if (typeof body.hash !== "string" || !/^[a-f0-9]{64}$/.test(body.hash)) return json({ error: "invalid_request" }, 400);
      this.ctx.storage.sql.exec("DELETE FROM group_invites");
      this.ctx.storage.sql.exec("INSERT INTO group_invites VALUES (?, ?)", body.hash, Date.now() + 600_000);
      return json({ ok: true });
    }
    if (typeof body.id !== "string" || !ID.test(body.id) || body.id === owner && path !== "/v1/group/publish") return json({ error: "invalid_device" }, 400);
    if (path === "/v1/group/add-phone") {
      const existing = this.member(body.id);
      if (existing?.role === "phone" && existing.status === "active") return json({ ok: true });
      if (!this.ctx.storage.sql.exec("SELECT id FROM devices WHERE id = ?", body.id).toArray().length || this.member(body.id))
        return json({ error: "conflict" }, 409);
      this.ctx.storage.sql.exec("INSERT INTO group_members(id, role, status) VALUES (?, 'phone', 'active')", body.id);
      return json({ ok: true });
    }
    const target = this.member(body.id);
    if (!target || target.status === "revoked") return json({ error: "not_a_member" }, 404);
    if (path === "/v1/group/approve" && target.role === "controller") {
      this.ctx.storage.sql.exec("UPDATE group_members SET status = 'active' WHERE id = ?", body.id);
      return json({ ok: true });
    }
    if (path === "/v1/group/publish" && ["controller", "owner"].includes(target.role) && target.status === "active") {
      const revision = body.revision;
      if (typeof revision !== "number" || !Number.isSafeInteger(revision) || revision <= target.revision || typeof body.package !== "string" ||
          body.package.length < 40 || body.package.length > 1_900_000 || !/^[A-Za-z0-9_-]+$/.test(body.package))
        return json({ error: "invalid_package" }, 409);
      this.ctx.storage.sql.exec("UPDATE group_members SET package = ?, revision = ? WHERE id = ?", body.package, revision, body.id);
      return json({ ok: true });
    }
    if (path === "/v1/group/revoke") {
      this.ctx.storage.sql.exec("UPDATE group_members SET status = 'revoked', package = '' WHERE id = ?", body.id);
      // Keep the revocation tombstone while freeing a registration slot.
      this.ctx.storage.sql.exec("DELETE FROM devices WHERE id = ?", body.id);
      this.ctx.storage.sql.exec("DELETE FROM tickets WHERE device = ?", body.id);
      this.ctx.storage.sql.exec("DELETE FROM messages WHERE sender = ? OR recipient = ?", body.id, body.id);
      this.ctx.getWebSockets(body.id).forEach(ws => ws.close(4003, "Removed from trusted group"));
      return json({ ok: true });
    }
    return json({ error: "not_found" }, 404);
  }
}
