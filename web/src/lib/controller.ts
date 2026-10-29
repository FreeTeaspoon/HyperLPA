import { decrypt, encrypt, hash, invitationCode, openPackage, Packets, parseInvitation, relayOrigin, sealPackage, secret, uuid, validId } from "./crypto";
import { post, RelayConnection, RelayError } from "./relay";
import { forgetIdentity, loadIdentity, saveIdentity, validateIdentity, validatePeer } from "./storage";
import type { Activity, Command, Envelope, Identity, Message, Peer, Snapshot, ViewState } from "./types";

type GroupResponse = { owner: string; package: string; revision: number; members: { id: string; role: string; status: string; revision: number }[] };
const label = (action: string) => ({ READERS: "Read devices", REFRESH: "Refresh profiles", SWITCH: "Switch profile",
  DELETE: "Delete profile", RENAME: "Rename profile", DOWNLOAD: "Download profile", RESET: "Reset eUICC",
  TAGS: "Update tags", PIN: "Pin profile", PROCESS_NOTIFICATION: "Process eUICC notification" })[action] ?? action.toLowerCase().replaceAll("_", " ");

export class Controller {
  private state: ViewState = { loaded: false, connected: false, online: {}, snapshots: {}, notifications: {}, activities: [], phoneRevisions: {} };
  private listeners = new Set<() => void>();
  private connection?: RelayConnection;
  private timer?: ReturnType<typeof setInterval>;
  private saves: Promise<void> = Promise.resolve();
  private tasks: Promise<unknown> = Promise.resolve();
  private publications: Promise<void> = Promise.resolve();
  private packets = new Packets();
  private seen = new Map<string, number>();
  private reads = new Map<string, { peer: string; readerId: string }>();
  private revisions = new Map<string, number>();
  private initialized = false;
  private refreshingGroup = false;
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; };
  getSnapshot = () => this.state;
  private emit(patch: Partial<ViewState> = {}) {
    this.state = { ...this.state, ...patch }; this.listeners.forEach(fn => fn());
  }
  private get identity() {
    if (!this.state.identity) throw new Error("Create or join a trusted group first.");
    return this.state.identity;
  }
  private peer(id: string) { const peer = this.identity.peers.find(p => p.id === id); if (!peer) throw new Error("Device is no longer trusted."); return peer; }
  private nextRevision() { return Math.max(Date.now(), this.identity.revision + 1); }
  private queue<T>(fn: () => Promise<T>): Promise<T> {
    const next = this.tasks.then(fn); this.tasks = next.catch(() => {}); return next;
  }
  async init() {
    if (this.initialized) return; this.initialized = true;
    try { this.emit({ identity: await loadIdentity(), loaded: true }); if (this.state.identity) this.start(); }
    catch (error) { this.emit({ loaded: true, error: String(error instanceof Error ? error.message : error) }); }
  }
  private async persist(next: Identity) {
    next = { ...next, updated: Math.max(Date.now(), (this.state.identity?.updated ?? 0) + 1) };
    this.emit({ identity: next });
    this.saves = this.saves.catch(() => {}).then(async () => {
      await saveIdentity(next);
      if (next.role === "owner") {
        const value = await sealPackage(next.token, next.id, next.id, next.updated!, next);
        await post(next, "/v1/group/publish", { id: next.id, revision: next.updated, package: value });
      }
    });
    await this.saves;
  }
  private async interruptedSetup(identity: Identity, error: unknown) {
    if (error instanceof RelayError && error.status >= 400 && error.status < 500) {
      await post(identity, "/v1/unregister").catch(() => {});
      await forgetIdentity();
    } else {
      // A lost response can follow a committed owner creation or consumed invitation.
      // Retain the pre-saved token and keys so a reconnect can verify that identity.
      this.emit({ identity, error: "Setup could not be verified. This browser identity was kept. Reconnect when the relay is available and save a recovery export before clearing it." });
      this.start();
    }
  }
  async create(relay: string, enrollment: string, name: string) {
    if (this.state.identity) throw new Error("A group is already configured in this browser.");
    if (!name.trim() || name.trim().length > 80) throw new Error("Enter a browser name, up to 80 characters.");
    const id = uuid();
    const identity: Identity = { version: 1, relay: relayOrigin(relay), id, owner: id, token: secret(), name: name.trim(),
      role: "owner", peers: [], grants: {}, revision: 1, pending: [] };
    // Save before making network changes so an interrupted setup can be recovered.
    await saveIdentity(identity);
    try {
      await post(identity, "/v1/register", { id, token: identity.token }, enrollment);
      await post(identity, "/v1/group/create", {}, enrollment);
    } catch (error) { await this.interruptedSetup(identity, error); throw error; }
    await this.persist(identity); this.start();
  }
  async join(code: string, name: string) {
    if (this.state.identity) throw new Error("A group is already configured in this browser.");
    const invitation = parseInvitation(code.trim(), "hyperlpa-group:");
    if (!name.trim() || name.trim().length > 80) throw new Error("Enter a browser name, up to 80 characters.");
    const owner: Peer = { id: invitation.device, name: invitation.name, pair: invitation.pair, secret: invitation.secret,
      kind: "owner", approved: false, profiles: false, notifications: false };
    const identity: Identity = { version: 1, relay: invitation.relay, id: uuid(), token: secret(), name: name.trim(),
      owner: owner.id, role: "controller", peers: [owner], grants: {}, revision: 0, pending: [] };
    await saveIdentity(identity);
    try { await post(identity, "/v1/group/join", { id: identity.id, token: identity.token, ticket: invitation.ticket }); }
    catch (error) { await this.interruptedSetup(identity, error); throw error; }
    await this.persist(identity); this.start();
  }
  async restore(identity: Identity) {
    await post(identity, "/v1/group/state");
    this.connection?.stop(); clearInterval(this.timer);
    this.emit({ identity: validateIdentity(identity), error: undefined, snapshots: {}, notifications: {}, online: {} });
    await saveIdentity(identity); this.start();
  }
  reconnect() { if (this.state.identity) this.start(); }
  private start() {
    this.connection?.stop(); clearInterval(this.timer);
    this.connection = new RelayConnection(() => this.identity, e => this.receive(e), connected => {
      this.emit({ connected, ...(connected ? { error: undefined } : { online: {}, notifications: {} }) });
      if (connected) void this.onConnected().catch(error => this.report(error));
    }, error => this.emit({ error }), (id, code) => {
      this.activity(id, "Rejected by relay", code.replaceAll("_", " "));
      this.emit({ error: `Relay rejected a request: ${code.replaceAll("_", " ")}. Refresh before trying another change.` });
    });
    this.connection.start();
    this.timer = setInterval(() => {
      this.emit();
      if (this.state.connected) void this.tick().catch(error => this.report(error));
    }, 20_000);
  }
  private report(error: unknown) { this.emit({ error: error instanceof Error ? error.message : "An unexpected error occurred." }); }
  clearError() { this.emit({ error: undefined }); }
  private async onConnected() {
    await this.refreshGroup();
    for (const peer of this.identity.peers) {
      await this.send(peer, { kind: peer.approved ? "hello" : "pair", name: this.identity.name,
        groupOwner: this.identity.role === "owner" && peer.kind === "phone" });
      if (peer.approved && peer.kind === "phone" && peer.profiles) await this.readers(peer.id);
    }
    for (const pending of this.identity.pending) await this.send(this.peer(pending.peer), { kind: "status", requestId: pending.id });
  }
  private async tick() {
    const notifications = Object.fromEntries(Object.entries(this.state.notifications).filter(([id]) => Date.now() - (this.state.online[id] ?? 0) < 60_000));
    this.emit({ notifications });
    await this.refreshGroup();
    for (const peer of this.identity.peers.filter(p => p.approved)) {
      await this.send(peer, { kind: "hello", name: this.identity.name });
      if (peer.kind === "phone" && this.identity.role === "owner" && this.state.phoneRevisions[peer.id] !== this.identity.revision)
        await this.syncPhone(peer);
    }
    for (const operation of this.identity.pending) await this.send(this.peer(operation.peer), { kind: "status", requestId: operation.id });
    for (const [id, value] of this.reads) {
      if (!this.state.activities.some(a => a.id === id && Date.now() - a.time < 60_000)) this.reads.delete(id);
      void value;
    }
  }
  private async send(peer: Peer, message: Message) {
    const e = await encrypt(peer.secret, this.identity.id, peer.id, peer.pair, message);
    this.connection?.send(e);
  }
  async addPhone(code: string) {
    return this.queue(async () => {
      if (this.identity.role !== "owner") throw new Error("Only the owner can enroll phones.");
      const invite = parseInvitation(code.trim());
      if (invite.relay !== this.identity.relay || invite.device === this.identity.id) throw new Error("The phone must use this group's relay.");
      if (this.identity.peers.some(p => p.id === invite.device)) throw new Error("This phone is already enrolled or awaiting approval.");
      const peer: Peer = { id: invite.device, name: invite.name, pair: invite.pair, secret: invite.secret,
        kind: "phone", approved: false, profiles: true, notifications: true };
      await this.persist({ ...this.identity, peers: [...this.identity.peers, peer] });
      await this.send(peer, { kind: "pair", name: this.identity.name, groupOwner: true });
    });
  }
  async inviteBrowser() {
    return this.queue(async () => {
      if (this.identity.role !== "owner") throw new Error("Only the owner can invite browsers.");
      if (this.identity.peers.filter(p => p.kind === "browser").length >= 31) throw new Error("This group has reached its browser limit.");
      const invitation = { version: 1, relay: this.identity.relay, device: this.identity.id, name: this.identity.name,
        pair: uuid(), secret: secret(), ticket: secret(), expires: Date.now() + 600_000 };
      await post(this.identity, "/v1/group/invite", { hash: await hash(invitation.ticket) });
      await this.persist({ ...this.identity, invitation });
      return invitationCode(invitation);
    });
  }
  async approveBrowser(id: string) {
    return this.queue(async () => {
      const peer = this.peer(id);
      if (this.identity.role !== "owner" || peer.kind !== "browser") throw new Error("Owner approval is required.");
      await post(this.identity, "/v1/group/approve", { id });
      await this.persist({ ...this.identity, revision: this.nextRevision(), peers: this.identity.peers.map(p => p.id === id ? { ...p, approved: true } : p) });
      // New browsers start with no phone permissions. The owner explicitly grants access.
      await this.publish();
      await this.send(this.peer(id), { kind: "paired", name: this.identity.name, groupVersion: 1 });
    });
  }
  async permission(browserId: string, phoneId: string, profiles: boolean, notifications: boolean) {
    return this.queue(async () => {
      if (this.identity.role !== "owner" || this.peer(browserId).kind !== "browser" || this.peer(phoneId).kind !== "phone")
        throw new Error("Invalid group permission.");
      const grants = structuredClone(this.identity.grants);
      grants[phoneId] ??= {};
      const previous = grants[phoneId][browserId];
      grants[phoneId][browserId] = { ...(previous ?? { id: browserId, name: this.peer(browserId).name, pair: uuid(), secret: secret(),
        kind: "browser", approved: true }), profiles, notifications };
      await this.persist({ ...this.identity, revision: this.nextRevision(), grants }); await this.publish();
    });
  }
  private publish() {
    const next = this.publications.catch(() => {}).then(() => this.publishNow());
    this.publications = next; return next;
  }
  private async publishNow() {
    const revision = this.nextRevision();
    await this.persist({ ...this.identity, revision });
    for (const browser of this.identity.peers.filter(p => p.kind === "browser" && p.approved)) {
      await this.publishBrowser(browser, revision);
    }
    for (const phone of this.identity.peers.filter(p => p.kind === "phone" && p.approved)) await this.syncPhone(phone);
  }
  private async publishBrowser(browser: Peer, revision: number) {
    const phones = this.identity.peers.filter(p => p.kind === "phone" && p.approved).flatMap(phone => {
      const grant = this.identity.grants[phone.id]?.[browser.id];
      return grant && (grant.profiles || grant.notifications) ? [{ ...phone, pair: grant.pair, secret: grant.secret,
        profiles: grant.profiles, notifications: grant.notifications }] : [];
    });
    const value = await sealPackage(browser.secret, this.identity.id, browser.id, revision, { phones });
    await post(this.identity, "/v1/group/publish", { id: browser.id, revision, package: value });
    await this.send(browser, { kind: "group_changed", revision });
  }
  private async reconcilePackages(group: GroupResponse) {
    const behind = (response: GroupResponse) => this.identity.peers.filter(p => p.kind === "browser" && p.approved &&
      response.members.some(m => m.id === p.id && m.status === "active" && m.revision < this.identity.revision));
    if (!behind(group).length) return;
    // Resume only missing publications after a disconnect or a bounded relay rate limit.
    // Share the publication queue so an older retry cannot overwrite a newer change.
    const next = this.publications.catch(() => {}).then(async () => {
      const latest = await post<GroupResponse>(this.identity, "/v1/group/state");
      for (const browser of behind(latest)) await this.publishBrowser(browser, this.identity.revision);
    });
    this.publications = next; await next;
  }
  private async syncPhone(phone: Peer) {
    const peers = Object.values(this.identity.grants[phone.id] ?? {}).filter(p => p.profiles || p.notifications).map(peer => ({
      id: peer.id, name: peer.name, pair: peer.pair, secret: peer.secret,
      canManageProfiles: peer.profiles, canViewNotifications: peer.notifications,
    }));
    await this.send(phone, { kind: "group_sync", groupSync: { revision: this.identity.revision, peers } });
  }
  async refreshGroup() {
    if (this.refreshingGroup) return;
    this.refreshingGroup = true;
    try {
      const group = await post<GroupResponse>(this.identity, "/v1/group/state");
      if (group.owner !== this.identity.owner) throw new Error("The relay group owner has changed. Re-enroll this browser.");
      if (this.identity.role === "owner") {
        if (group.package && group.revision > (this.identity.updated ?? 0)) {
          const recovered = validateIdentity(await openPackage(this.identity.token, this.identity.id, this.identity.id, group.revision, group.package) as Identity, true);
          if (recovered.id !== this.identity.id || recovered.token !== this.identity.token || recovered.role !== "owner") throw new Error("Invalid owner recovery package.");
          if (group.revision > (this.identity.updated ?? 0)) {
            await saveIdentity(recovered); this.emit({ identity: recovered });
          }
        }
        if (group.revision < (this.identity.updated ?? 0)) await this.persist(this.identity);
        await this.reconcilePackages(group);
      } else if (group.package && group.revision > this.identity.revision) {
        const owner = this.peer(this.identity.owner);
        const data = await openPackage(owner.secret, owner.id, this.identity.id, group.revision, group.package) as { phones: Peer[] };
        if (!Array.isArray(data.phones) || data.phones.length > 64) throw new Error("Invalid group package.");
        const phones = data.phones.map(validatePeer);
        if (phones.some(p => p.kind !== "phone" || !p.approved || p.id === owner.id || p.id === this.identity.id) ||
          new Set(phones.map(p => p.id)).size !== phones.length) throw new Error("Invalid phone membership.");
        const profilePhones = new Set(phones.filter(p => p.profiles).map(p => p.id));
        const notificationPhones = new Set(phones.filter(p => p.notifications).map(p => p.id));
        await this.persist({ ...this.identity, revision: group.revision, peers: [{ ...owner, approved: true }, ...phones],
          pending: this.identity.pending.filter(p => profilePhones.has(p.peer)) });
        // Drop data and unfinished requests for phones whose access was removed.
        this.emit({ notifications: Object.fromEntries(Object.entries(this.state.notifications).filter(([id]) => notificationPhones.has(id))),
          snapshots: Object.fromEntries(Object.entries(this.state.snapshots).filter(([key]) => profilePhones.has(key.split("/")[0]))) });
        for (const phone of phones) {
          await this.send(phone, { kind: "hello", name: this.identity.name });
          if (phone.profiles) await this.readers(phone.id);
        }
      }
    } finally { this.refreshingGroup = false; }
  }
  async revoke(id: string) {
    return this.queue(async () => {
      if (this.identity.role !== "owner") throw new Error("Only the owner can remove devices.");
      const peer = this.peer(id);
      if (peer.kind === "phone" && !peer.approved) {
        await this.send(peer, { kind: "unpair" });
      } else await post(this.identity, "/v1/group/revoke", { id });
      const grants = structuredClone(this.identity.grants); delete grants[id];
      for (const value of Object.values(grants)) delete value[id];
      await this.persist({ ...this.identity, revision: this.nextRevision(), peers: this.identity.peers.filter(p => p.id !== id), grants,
        pending: this.identity.pending.filter(p => p.peer !== id) });
      this.emit({ notifications: {}, snapshots: Object.fromEntries(Object.entries(this.state.snapshots).filter(([key]) => !key.startsWith(id + "/"))) });
      await this.publish();
    });
  }
  async rename(id: string, name: string) {
    return this.queue(async () => {
      if (!name.trim() || name.trim().length > 80) throw new Error("Enter a name, up to 80 characters.");
      if (this.identity.role !== "owner") throw new Error("Only the owner can rename devices.");
      await this.persist({ ...this.identity, revision: this.nextRevision(), peers: this.identity.peers.map(p => p.id === id ? { ...p, name: name.trim() } : p) });
      await this.publish();
    });
  }
  async readers(id: string) { if (this.peer(id).profiles) return this.command(id, { action: "READERS" }); }
  async command(id: string, command: Command) {
    const peer = this.peer(id);
    if (!peer.approved || !peer.profiles || !this.state.connected) throw new Error("This phone is unavailable or profile access is not permitted.");
    if (Date.now() - (this.state.online[id] ?? 0) > 60_000 && command.action !== "READERS") throw new Error("The phone is offline. Wait for it to reconnect.");
    const mutating = !["READERS", "REFRESH", "DISCOVER"].includes(command.action);
    if (this.identity.pending.some(p => p.peer === id)) throw new Error("Resolve the previous operation on this phone before sending another command.");
    if (mutating) {
      const snapshot = this.state.snapshots[`${id}/${command.readerId}`];
      if (!snapshot || snapshot.lpa.euiccInfo?.eid !== command.eid) throw new Error("Refresh this reader before making a change.");
    }
    const requestId = uuid();
    const operation = { id: requestId, peer: id, action: command.action, readerId: command.readerId ?? "", started: Date.now() };
    this.emit({ activities: [{ id: requestId, peer: id, label: label(command.action), status: "Sending", time: Date.now() }, ...this.state.activities].slice(0, 100) });
    if (mutating) await this.persist({ ...this.identity, pending: [...this.identity.pending, operation] });
    else this.reads.set(requestId, { peer: id, readerId: command.readerId ?? "" });
    try { await this.send(peer, { kind: "command", requestId, command }); }
    catch (error) {
      this.activity(requestId, mutating ? "Outcome unverified" : "Failed", "Refresh or query status before trying again.");
      throw error;
    }
    return requestId;
  }
  /** Runs a browser-side queue. Each item is still journaled as an individual phone operation;
   * the next item is never sent until the previous result is committed. */
  async batchDownload(id: string, readerId: string, eid: string, requests: Command[], onProgress?: (index: number) => void) {
    if (!requests.length || requests.length > 32) throw new Error("Add between 1 and 32 activation codes.");
    for (let index = 0; index < requests.length; index++) {
      const requestId = await this.command(id, { ...requests[index], action: "DOWNLOAD", readerId, eid });
      const deadline = Date.now() + 30 * 60_000;
      while (Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 750));
        const pending = this.identity.pending.find(p => p.id === requestId);
        const snapshot = this.state.snapshots[`${id}/${readerId}`];
        if (pending && snapshot?.lpa.pendingProfileDownload) {
          await this.decision(id, requestId, true);
        } else if (!pending) {
          const activity = this.state.activities.find(item => item.id === requestId);
          if (activity?.status === "Completed") break;
          if (activity?.status === "Failed" || activity?.status === "Outcome unverified") throw new Error(activity.detail ?? "A batch item failed.");
        }
        await this.queryPending(requestId);
      }
      if (this.identity.pending.some(p => p.id === requestId)) throw new Error("A batch item did not finish before timeout.");
      onProgress?.(index + 1);
    }
  }
  async decision(id: string, requestId: string, confirmed: boolean) {
    if (!this.identity.pending.some(p => p.peer === id && p.id === requestId && p.action === "DOWNLOAD")) throw new Error("The download is no longer awaiting confirmation.");
    await this.send(this.peer(id), { kind: "decision", requestId, confirmed });
  }
  async requestNotifications(id: string) {
    const peer = this.peer(id);
    if (!peer.notifications || !peer.approved) throw new Error("Notification access is not permitted.");
    await this.send(peer, { kind: "phone_notifications_request" });
  }
  async deleteNotification(id: string, notificationId: string) {
    if (!validId(notificationId)) throw new Error("Invalid notification.");
    const peer = this.peer(id);
    if (!peer.notifications) throw new Error("Notification access is not permitted.");
    await this.send(peer, { kind: "phone_notifications_delete", notificationId });
  }
  async queryPending(id: string) {
    const operation = this.identity.pending.find(p => p.id === id);
    if (operation) await this.send(this.peer(operation.peer), { kind: "status", requestId: id });
  }
  async acknowledgeUnknown(id: string) {
    // This only dismisses the controller journal; it never resends a command.
    const operation = this.identity.pending.find(p => p.id === id);
    if (!operation || Date.now() - operation.started < 150_000) throw new Error("Wait for the command to expire before resolving an unknown outcome.");
    await this.persist({ ...this.identity, pending: this.identity.pending.filter(p => p.id !== id) });
    const snapshots = { ...this.state.snapshots }; delete snapshots[`${operation.peer}/${operation.readerId}`];
    this.emit({ snapshots });
  }
  private activity(id: string, status: string, detail?: string) {
    this.emit({ activities: this.state.activities.map(a => a.id === id ? { ...a, status, detail } : a) });
  }
  private async receive(e: Envelope) {
    for (const [id, expires] of this.seen) if (expires <= Date.now()) this.seen.delete(id);
    if (this.seen.has(e.id)) { this.connection?.ack(e.id); return; }
    let peer = this.identity.peers.find(p => p.id === e.from && p.pair === e.pair);
    const invitation = this.identity.invitation;
    if (!peer && this.identity.role === "owner" && invitation && invitation.expires > Date.now() && invitation.pair === e.pair) {
      const message = await decrypt(invitation.secret, e, this.identity.id);
      if (message.kind !== "pair" || !message.name?.trim() || message.name.length > 80) return;
      peer = { id: e.from, name: message.name, pair: invitation.pair, secret: invitation.secret,
        kind: "browser", approved: false, profiles: false, notifications: false };
      await this.persist({ ...this.identity, invitation: undefined, peers: [...this.identity.peers, peer] });
      await this.send(peer, { kind: "pair_pending", name: this.identity.name });
    } else {
      if (!peer) return;
      let message = await decrypt(peer.secret, e, this.identity.id);
      if (message.kind === "part") {
        if (!peer.approved || !message.part) return;
        const assembled = this.packets.accept(peer.id, message.part, e.expires);
        if (!assembled) { this.connection?.ack(e.id); return; }
        message = assembled;
      }
      if (message.kind === "paired" && !peer.approved) {
        if (peer.kind === "phone" && message.groupVersion !== 1) {
          this.emit({ error: "Update HyperLPA on this phone before enrolling it in a trusted group." }); return;
        }
        if (peer.kind === "phone") await post(this.identity, "/v1/group/add-phone", { id: peer.id });
        await this.persist({ ...this.identity, revision: this.identity.role === "owner" ? this.nextRevision() : this.identity.revision,
          peers: this.identity.peers.map(p => p.id === peer!.id ? { ...p, approved: true } : p) });
        peer = this.peer(peer.id);
        if (peer.kind === "phone") {
          await this.publish();
          await this.send(peer, { kind: "hello", name: this.identity.name });
          await this.readers(peer.id);
        } else await this.refreshGroup();
      } else if (message.kind === "pair" && peer.kind === "browser") {
        await this.send(peer, { kind: peer.approved ? "paired" : "pair_pending", name: this.identity.name, groupVersion: 1 });
      } else if (message.kind === "unpair") {
        if (this.identity.role === "owner") await this.revoke(peer.id);
        else this.emit({ error: "The owner removed this pairing. Enroll this browser again." });
      } else if (peer.approved) await this.handle(peer, message);
    }
    if (this.seen.size >= 4096) this.seen.delete(this.seen.keys().next().value!);
    this.seen.set(e.id, e.expires); this.connection?.ack(e.id);
  }
  private async handle(peer: Peer, message: Message) {
    this.emit({ online: { ...this.state.online, [peer.id]: Date.now() } });
    if (message.kind === "hello") {
      await this.send(peer, { kind: "hello_reply", name: this.identity.name });
      if (peer.kind === "phone" && this.identity.role === "owner") await this.syncPhone(peer);
    }
    if (message.kind === "hello_reply" && peer.kind === "phone" && this.identity.role === "owner" && this.state.phoneRevisions[peer.id] !== this.identity.revision)
      await this.syncPhone(peer);
    if (message.kind === "group_synced") this.emit({ phoneRevisions: { ...this.state.phoneRevisions, [peer.id]: message.revision ?? 0 } });
    if (message.kind === "group_changed" && peer.id === this.identity.owner) await this.refreshGroup();
    if (message.kind === "phone_notifications" && peer.notifications) {
      const entries = (message.phoneNotifications ?? []).slice(0, 150).filter(n => validId(n.id) && typeof n.title === "string" &&
        n.title.length <= 256 && typeof n.text === "string" && n.text.length <= 2048 && typeof n.appLabel === "string" && n.appLabel.length <= 80 &&
        Number.isSafeInteger(n.timestamp) && n.timestamp >= 0 && n.timestamp < 8_640_000_000_000_000);
      this.emit({ notifications: { ...this.state.notifications, [peer.id]: { available: !!message.phoneNotificationsAvailable,
        entries: message.phoneNotificationsAvailable ? entries : [] } } });
    }
    if (message.kind === "phone_notifications_changed" && peer.notifications && this.state.notifications[peer.id]) await this.requestNotifications(peer.id);
    const pending = this.identity.pending.find(p => p.peer === peer.id && p.id === message.requestId);
    const read = message.requestId ? this.reads.get(message.requestId) : undefined;
    if (message.kind === "missing" && pending) {
      this.activity(pending.id, "Outcome unverified", "The phone has no saved result. Do not repeat the operation. Refresh after the command expires."); return;
    }
    if (message.kind !== "result" || !message.requestId || !pending && read?.peer !== peer.id) return;
    const previous = this.revisions.get(message.requestId) ?? -1;
    if ((message.revision ?? 0) < previous) return;
    this.revisions.set(message.requestId, message.revision ?? 0);
    if (message.snapshot && peer.profiles) {
      const snapshot = message.snapshot;
      const reader = snapshot.lpa.selectedReaderId ?? pending?.readerId ?? read?.readerId ?? "";
      const key = `${peer.id}/${reader}`;
      this.emit({ snapshots: { ...this.state.snapshots, [key]: snapshot } });
    }
    this.activity(message.requestId, message.done ? (message.outcome?.type.endsWith("Success") ? "Completed"
      : message.outcome?.type.endsWith("Unverified") ? "Outcome unverified" : "Failed") : "Running",
      message.outcome?.failure?.message);
    if (message.done) {
      this.reads.delete(message.requestId);
      if (pending) await this.persist({ ...this.identity, pending: this.identity.pending.filter(p => p.id !== message.requestId) });
      if (pending && (!message.snapshot || !message.outcome?.type.endsWith("Success"))) {
        const snapshots = { ...this.state.snapshots }; delete snapshots[`${peer.id}/${pending.readerId}`]; this.emit({ snapshots });
      }
    }
  }
  async forgetLocal() {
    this.connection?.stop(); clearInterval(this.timer); await this.saves.catch(() => {}); await forgetIdentity();
    this.emit({ identity: undefined, connected: false, online: {}, snapshots: {}, notifications: {}, activities: [], error: undefined });
  }
}
export const controller = new Controller();
