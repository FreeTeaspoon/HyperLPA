/** Synthetic phone connected to the actual local Durable Object relay, never a physical SIM. */
import { decrypt, encode, encrypt, secret, uuid } from "../src/lib/crypto";
import type { Envelope, Message, Peer, Profile, Snapshot } from "../src/lib/types";
export const localRelay = "http://127.0.0.1:8791";
export const remoteRelay = "https://relay.test";
export async function api(path: string, body: unknown, identity?: { id: string; token: string }, enrollment = false) {
  const response = await fetch(localRelay + path, { method: "POST", headers: { "Content-Type": "application/json",
    ...(identity ? { "X-Device-Id": identity.id, Authorization: "Bearer " + identity.token } : {}),
    ...(enrollment ? { "X-Enrollment-Key": "test-only-enrollment-key" } : {}) }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(`Test relay ${path}: ${response.status} ${await response.text()}`);
  return response.json();
}
export class Phone {
  id = uuid(); token = secret(); name = "Xiaomi 14"; eid = "89049032000000000000000000000001";
  readerId = "omapi:SIM1"; mutations = 0; dropMutationResult = false;
  private socket?: WebSocket;
  private pair = { pair: uuid(), secret: secret(), expires: Date.now() + 600_000 };
  peers = new Map<string, Peer>();
  private journal = new Map<string, Message>();
  private downloads = new Map<string, { peer: Peer; request: Record<string, unknown> }>();
  private installed: Profile[] = [];
  private metadata: NonNullable<Snapshot["metadata"]> = {};
  profile: Profile = { iccid: "8944500101234567890", name: "Travel data", nickname: "", providerName: "Example Telecom", state: "ENABLED", tags: ["Travel"] };
  async start() { await api("/v1/register", { id: this.id, token: this.token }, undefined, true); await this.connect(); }
  invitation() { return "hyperlpa-pair:" + encode(new TextEncoder().encode(JSON.stringify({ version: 1, relay: remoteRelay, device: this.id, name: this.name, ...this.pair }))); }
  snapshot(catalog = false): Snapshot {
    return { lpa: { readers: [{ id: this.readerId, name: "SIM 1", kind: "OMAPI", available: true, eid: this.eid }],
      ...(catalog ? {} : { selectedReaderId: this.readerId, euiccInfo: { eid: this.eid, productName: "Test eUICC" } }),
      profiles: catalog ? [] : [this.profile, ...this.installed], notifications: [] }, metadata: this.metadata };
  }
  async connect() {
    const { ticket } = await api("/v1/session", {}, this);
    const socket = this.socket = new WebSocket(localRelay.replace("http", "ws") + "/v1/socket?ticket=" + ticket);
    let receiving = Promise.resolve();
    await new Promise<void>((resolve, reject) => {
      socket.onopen = () => resolve(); socket.onerror = () => reject(new Error("Test phone socket failed."));
    });
    socket.onmessage = event => {
      if (event.data === "pong") return;
      receiving = receiving.then(async () => {
        const frame = JSON.parse(String(event.data));
        if (frame.type !== "message") return;
        const e = frame.message as Envelope;
        let peer = this.peers.get(e.from);
        const message = await decrypt(peer?.secret ?? this.pair.secret, e, this.id);
        if (!peer && message.kind === "pair") {
          peer = { id: e.from, name: message.name!, pair: e.pair, secret: this.pair.secret, kind: "owner", approved: true, profiles: true, notifications: true };
          this.peers.set(peer.id, peer);
          await this.send(peer, { kind: "paired", name: this.name, groupVersion: 1 });
        } else if (peer) await this.handle(peer, message);
        socket.send(JSON.stringify({ type: "ack", id: e.id }));
      });
    };
    for (const peer of this.peers.values()) await this.send(peer, { kind: "hello", name: this.name, groupVersion: 1 });
  }
  private async send(peer: Peer, message: Message) {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: "send", message: await encrypt(peer.secret, this.id, peer.id, peer.pair, message) }));
  }
  private async handle(peer: Peer, message: Message) {
    if (message.kind === "hello") await this.send(peer, { kind: "hello_reply", name: this.name, groupVersion: 1 });
    if (message.kind === "group_sync" && peer.kind === "owner") {
      for (const [id, p] of this.peers) if (p.kind === "browser") this.peers.delete(id);
      for (const grant of message.groupSync!.peers as { id: string; name: string; pair: string; secret: string; canManageProfiles: boolean; canViewNotifications: boolean }[]) {
        const p: Peer = { ...grant, kind: "browser", approved: true, profiles: grant.canManageProfiles, notifications: grant.canViewNotifications };
        this.peers.set(p.id, p); await this.send(p, { kind: "hello", name: this.name });
      }
      await this.send(peer, { kind: "group_synced", revision: message.groupSync!.revision });
    }
    if (message.kind === "phone_notifications_request") await this.send(peer, { kind: "phone_notifications", phoneNotificationsAvailable: peer.notifications,
      phoneNotifications: peer.notifications ? [{ id: uuid(), packageName: "test.messages", appLabel: "Messages", title: "Example service", text: "Your verification code is 482916.", timestamp: Date.now() }] : [] });
    if (message.kind === "status") await this.send(peer, this.journal.get(message.requestId!) ?? { kind: "missing", requestId: message.requestId });
    if (message.kind === "decision" && this.downloads.get(message.requestId!)?.peer.id === peer.id) {
      const profile: Profile = { ...this.profile, name: "New data profile", nickname: "", iccid: "8944500109999999999", state: "DISABLED" };
      if (message.confirmed) { this.mutations++; this.installed.push(profile); }
      const result: Message = { kind: "result", requestId: message.requestId, revision: 2, done: true,
        outcome: { type: message.confirmed ? "app.hyperlpa.domain.model.OperationOutcome.Success" : "app.hyperlpa.domain.model.OperationOutcome.Failed" }, snapshot: this.snapshot() };
      this.journal.set(message.requestId!, result); this.downloads.delete(message.requestId!); await this.send(peer, result);
    }
    if (message.kind === "command" && peer.profiles) {
      const command = message.command!, id = message.requestId!;
      if (this.journal.has(id)) { await this.send(peer, this.journal.get(id)!); return; }
      if (command.action === "DOWNLOAD") {
        const request = command.download as Record<string, unknown>;
        this.downloads.set(id, { peer, request });
        const snapshot = this.snapshot();
        snapshot.lpa.pendingProfileDownload = { profile: { ...this.profile, name: "New data profile", nickname: "" }, request };
        const progress: Message = { kind: "result", requestId: id, revision: 1, snapshot };
        this.journal.set(id, progress); await this.send(peer, progress); return;
      }
      if (command.action === "SWITCH") { this.mutations++; this.profile = { ...this.profile, state: command.enabled ? "ENABLED" : "DISABLED" }; }
      if (command.action === "RENAME") { this.mutations++; this.profile = { ...this.profile, nickname: command.text ?? "" }; }
      if (command.action === "TAGS" || command.action === "PIN") {
        this.mutations++;
        this.metadata[command.iccid!] = { ...this.metadata[command.iccid!], ...(command.action === "TAGS" ? { tags: command.tags as string[] } : { isPinned: command.enabled }) };
      }
      const result: Message = { kind: "result", requestId: id, revision: 1, done: true, outcome: { type: "app.hyperlpa.domain.model.OperationOutcome.Success" } };
      this.journal.set(id, result);
      if (this.dropMutationResult && command.action === "SWITCH") { this.dropMutationResult = false; return; }
      await this.send(peer, { ...result, snapshot: this.snapshot(command.action === "READERS") });
    }
  }
  close() { this.socket?.close(); }
}
