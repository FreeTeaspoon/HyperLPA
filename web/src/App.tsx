import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ArrowLeft, ArrowUpRight, Bell, Check, ChevronRight, Download, Ellipsis, KeyRound, Laptop, LoaderCircle, Monitor, Moon, Plus, RadioTower, RefreshCw, Search, ShieldCheck, Smartphone, Sun, Unplug, Upload, X } from "lucide-react";
import { Toaster, toast } from "sonner";
import { controller } from "@/lib/controller";
import { exportRecovery, importRecovery } from "@/lib/storage";
import type { Command, Identity, Peer, Profile, Reader, Snapshot, ViewState } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { BusyButton, CopyButton, Modal, TextField } from "@/components/common";

type Page = "devices" | "profiles" | "notifications" | "group" | "activity";
type FormDialog = { type: "phone" | "invite" | "recovery" | "download" | "rename" | "tags"; peer?: Peer; profile?: Profile };
type ConfirmDialog = { title: string; description: string; destructive?: boolean; action: () => Promise<unknown> };
const profileName = (p: Profile) => p.nickname || p.name || p.providerName || "Unnamed profile";
const online = (s: ViewState, id: string) => s.connected && Date.now() - (s.online[id] ?? 0) < 60_000;
function errorMessage(error: unknown) { return error instanceof Error ? error.message : "Something went wrong. Try again."; }
function localTime(time: number) { return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(time); }
function dialogOpener() {
  const focused = document.activeElement as HTMLElement | null;
  const triggerId = focused?.closest("[role=menu]")?.getAttribute("aria-labelledby");
  return triggerId ? document.getElementById(triggerId) : focused;
}

function NoItems({ icon: Icon = Smartphone, title, description, children }: { icon?: typeof Smartphone; title: string; description: string; children?: React.ReactNode }) {
  return <Empty><EmptyHeader><EmptyMedia><Icon /></EmptyMedia><EmptyTitle>{title}</EmptyTitle><EmptyDescription>{description}</EmptyDescription></EmptyHeader>
    {children && <EmptyContent>{children}</EmptyContent>}</Empty>;
}
function Choose({ value, onChange, label, children }: { value: string; onChange: (value: string) => void; label: string; children: React.ReactNode }) {
  return <Select value={value} onValueChange={onChange}><SelectTrigger aria-label={label}><SelectValue placeholder={label} /></SelectTrigger>
    <SelectContent><SelectGroup>{children}</SelectGroup></SelectContent></Select>;
}
function ThemeButton() {
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme ?? "system");
  return <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="Appearance" title="Appearance"><Sun className="light-icon" /><Moon className="dark-icon" /></Button></DropdownMenuTrigger>
    <DropdownMenuContent align="end"><DropdownMenuGroup>{(["system", "light", "dark"] as const).map(value => <DropdownMenuItem key={value} onSelect={() => {
      setTheme(value); if (value === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = value;
      try { localStorage.setItem("hyperlpa-theme", value); } catch { toast.error("Could not save appearance preference."); }
    }}>{value === "system" ? <Monitor /> : value === "light" ? <Sun /> : <Moon />}<span className="capitalize">{value}</span>{theme === value && <Check className="menu-check" />}</DropdownMenuItem>)}</DropdownMenuGroup></DropdownMenuContent>
  </DropdownMenu>;
}

function Setup({ onRun }: { onRun: (fn: () => Promise<unknown>) => Promise<void> }) {
  const [mode, setMode] = useState("create");
  const [relay, setRelay] = useState(""); const [key, setKey] = useState(""); const [name, setName] = useState("");
  const [code, setCode] = useState(""); const [recovery, setRecovery] = useState<File>();
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const submit = async () => {
    setBusy(true); setError("");
    try {
      if (mode === "create") await controller.create(relay, key, name);
      else if (mode === "join") await controller.join(code, name);
      else {
        if (!recovery || recovery.size > 3_000_000) throw new Error("Choose a HyperLPA recovery file smaller than 3 MB.");
        await controller.restore(await importRecovery(await recovery.text(), key));
      }
    } catch (error) { setError(errorMessage(error)); }
    finally { setBusy(false); setKey(""); }
  };
  void onRun;
  return <section className="setup"><div className="setup-mark"><RadioTower /></div><h1>Your phones, together.</h1>
    <p className="intro">Connect HyperLPA to manage your eSIM profiles and shared notifications.</p>
    <Tabs value={mode} onValueChange={value => { setMode(value); setError(""); setKey(""); }}>
      <TabsList className="setup-tabs"><TabsTrigger value="create">Create group</TabsTrigger><TabsTrigger value="join">Join group</TabsTrigger><TabsTrigger value="restore">Restore</TabsTrigger></TabsList>
    <TabsContent value={mode}><form onSubmit={event => { event.preventDefault(); void submit(); }} className="setup-form">
      <FieldGroup>
        {mode === "create" && <><TextField label="Relay address" placeholder="https://hyperlpa-relay.example.workers.dev" value={relay} onChange={e => setRelay(e.target.value)} type="url" required autoComplete="url" />
          <TextField label="Enrollment key" type="password" autoComplete="off" value={key} onChange={e => setKey(e.target.value)} required maxLength={256} hint="The private key configured in your Cloudflare relay. Used only during setup." /></>}
        {mode === "join" && <Field><FieldLabel htmlFor="group-code">Group invitation</FieldLabel><Textarea id="group-code" value={code} onChange={e => setCode(e.target.value)} placeholder="hyperlpa-group:…" required maxLength={4096} spellCheck={false} autoComplete="off" />
          <FieldDescription>Ask the owner for an invitation. They will approve this browser before granting phone access.</FieldDescription></Field>}
        {mode !== "restore" && <TextField label="Browser name" value={name} onChange={e => setName(e.target.value)} placeholder="Personal laptop" autoComplete="off" maxLength={80} required />}
        {mode === "restore" && <><TextField label="Recovery file" type="file" accept=".json,.hyperlpa" onChange={e => setRecovery(e.target.files?.[0])} required />
          <TextField label="Recovery key" type="password" value={key} onChange={e => setKey(e.target.value)} autoComplete="off" required hint="Restores this device identity. Its previous window will disconnect." /></>}
      </FieldGroup>
      {error && <Alert variant="destructive" role="alert"><AlertTitle>Could not {mode === "restore" ? "restore" : "connect"}</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
      <BusyButton type="submit" busy={busy} className="setup-submit">{mode === "create" ? "Create trusted group" : mode === "join" ? "Request to join" : "Restore device"}<ArrowUpRight data-icon="inline-end" /></BusyButton>
    </form></TabsContent></Tabs>
    {mode === "create" && <p className="setup-help">Deploy the relay first, then allow this website’s origin in its configuration. <a href="https://github.com/FreeTeaspoon/HyperLPA/blob/main/web/README.md" target="_blank" rel="noreferrer">Setup guide<ArrowUpRight /></a></p>}
  </section>;
}

export function App() {
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const [page, setPage] = useState<Page>("devices");
  const [phoneId, setPhoneId] = useState(""); const [readerId, setReaderId] = useState("");
  const [form, setForm] = useState<FormDialog>(); const [formOpen, setFormOpen] = useState(false);
  const [formGeneration, setFormGeneration] = useState(0);
  const formOpener = useRef<HTMLElement | null>(null), confirmOpener = useRef<HTMLElement | null>(null);
  const [confirm, setConfirm] = useState<ConfirmDialog>(); const [confirmOpen, setConfirmOpen] = useState(false);
  const [busy, setBusy] = useState(false); const [manage, setManage] = useState(false);
  const [browserId, setBrowserId] = useState(""); const [query, setQuery] = useState("");
  const identity = state.identity;
  const owner = identity?.role === "owner";
  const phones = identity?.peers.filter(p => p.kind === "phone") ?? [];
  const browsers = identity?.peers.filter(p => p.kind === "browser") ?? [];
  const eligiblePhones = phones.filter(p => page === "profiles" ? p.profiles : page === "notifications" ? p.notifications : true);
  const phone = eligiblePhones.find(p => p.id === phoneId) ?? eligiblePhones.find(p => p.approved) ?? eligiblePhones[0];
  const catalogs = phone ? Object.entries(state.snapshots).filter(([key]) => key.startsWith(phone.id + "/")).map(([, snapshot]) => snapshot) : [];
  const readers = [...new Map(catalogs.flatMap(s => s.lpa.readers).filter(r => !r.deviceId).map(r => [r.id, r])).values()];
  const reader = readers.find(r => r.id === readerId) ?? readers[0];
  const snapshot = phone && reader ? state.snapshots[`${phone.id}/${reader.id}`] : undefined;
  const profiles = snapshot?.lpa.profiles.map(profile => ({ ...profile,
    tags: snapshot.metadata?.[profile.iccid]?.tags ?? profile.tags,
    isPinned: snapshot.metadata?.[profile.iccid]?.isPinned ?? profile.isPinned,
  })).sort((a, b) => Number(!!b.isPinned) - Number(!!a.isPinned)) ?? [];
  const pending = phone ? identity?.pending.find(p => p.peer === phone.id) : undefined;
  const phoneOnline = phone ? online(state, phone.id) : false;
  const lastRead = useRef("");
  useEffect(() => { void controller.init(); }, []);
  useEffect(() => { lastRead.current = ""; setReaderId(""); }, [phone?.id]);
  useEffect(() => {
    if (page !== "profiles" || !phone?.approved || !reader || !state.connected || !online(state, phone.id) || pending) return;
    const key = `${phone.id}/${reader.id}`;
    if (snapshot || lastRead.current === key) return;
    lastRead.current = key;
    void controller.command(phone.id, { action: "REFRESH", readerId: reader.id, eid: reader.eid ?? "" }).catch(error => toast.error(errorMessage(error)));
  }, [page, phone?.id, phone?.approved, reader?.id, state.connected, state.online, snapshot, pending]);
  useEffect(() => {
    if (page === "notifications" && phone?.approved && phone.notifications && state.connected && online(state, phone.id))
      void controller.requestNotifications(phone.id).catch(error => toast.error(errorMessage(error)));
  }, [page, phone?.id, phone?.approved, phone?.notifications, state.connected, phoneOnline]);
  const run = async (fn: () => Promise<unknown>) => {
    try { await fn(); } catch (error) { toast.error(errorMessage(error)); }
  };
  const showForm = (value: FormDialog) => { formOpener.current = dialogOpener(); setForm(value); setFormGeneration(n => n + 1); setFormOpen(true); };
  const ask = (value: ConfirmDialog) => { confirmOpener.current = dialogOpener(); setConfirm(value); setConfirmOpen(true); };
  const selectPhone = (value: string) => { setPhoneId(value); setReaderId(""); setQuery(""); };
  const command = (value: Command) => {
    if (!phone || !reader) return Promise.reject(new Error("Choose a phone and reader."));
    return controller.command(phone.id, { readerId: reader.id, eid: snapshot?.lpa.euiccInfo?.eid ?? reader.eid ?? "", ...value });
  };
  const refreshing = state.activities.some(a => a.peer === phone?.id && ["Sending", "Running"].includes(a.status) && Date.now() - a.time < 60_000);
  const phonePicker = <Choose value={phone?.id ?? ""} onChange={selectPhone} label="Phone">{eligiblePhones.filter(p => p.approved).map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</Choose>;
  return <><a className="skip-link" href="#main">Skip to content</a><header className="app-header"><div className="header-inner">
    <a href="#" className="brand" onClick={event => { event.preventDefault(); setPage("devices"); }}><RadioTower /><span>HyperLPA</span></a>
    <div className="header-actions">{identity && <span className="connection-state"><span className={cn("connection-dot", state.connected && "connected")} />{state.connected ? "Connected" : "Disconnected"}</span>}
      <ThemeButton />{identity && <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="Browser settings"><Ellipsis /></Button></DropdownMenuTrigger>
        <DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem onSelect={() => showForm({ type: "recovery" })}><KeyRound />Recovery export</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => controller.reconnect()}><RefreshCw />Reconnect</DropdownMenuItem></DropdownMenuGroup><DropdownMenuSeparator />
          <DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => ask({ title: "Forget this browser?", description: "This clears the identity stored here. It does not revoke the device on the relay. Save a recovery export first, or ask the owner to enroll a new browser.", destructive: true, action: () => controller.forgetLocal() })}><Unplug />Forget this browser</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent>
      </DropdownMenu>}</div>
  </div></header><main id="main" className="shell" tabIndex={-1}>
    {!state.loaded ? <div className="initial-loading" role="status"><LoaderCircle className="spin" /><span>Opening dashboard</span></div> : !identity ? <><Setup onRun={run} />{state.error && <Alert variant="destructive"><AlertDescription>{state.error}</AlertDescription></Alert>}</> : <>
      <div className="page-heading"><div><h1>{({ devices: "Devices", profiles: "Profiles", notifications: "Notifications", group: "Trusted group", activity: "Activity" })[page]}</h1>
        <p>{page === "devices" ? `${phones.length} ${phones.length === 1 ? "phone" : "phones"} · ${phones.filter(p => online(state, p.id)).length} online` :
          page === "group" ? `Managed by ${owner ? "this browser" : identity.peers.find(p => p.id === identity.owner)?.name ?? "your owner device"}` :
          page === "activity" ? "Operations from this browser session" : phone?.name ?? "Your connected phones"}</p></div>
        {owner && page === "devices" && <Button onClick={() => showForm({ type: "phone" })} disabled={!state.connected}><Plus data-icon="inline-start" />Add phone</Button>}
        {page === "group" && <div className="toolbar"><Button variant="outline" onClick={() => showForm({ type: "recovery" })}><KeyRound data-icon="inline-start" />Recovery</Button>
          {owner && <Button onClick={() => showForm({ type: "invite" })} disabled={!state.connected}><Plus data-icon="inline-start" />Invite browser</Button>}</div>}
      </div>
      <Tabs value={page} onValueChange={value => { setPage(value as Page); setQuery(""); }}><TabsList className="main-tabs">
        <TabsTrigger value="devices">Devices</TabsTrigger><TabsTrigger value="profiles">Profiles</TabsTrigger><TabsTrigger value="notifications">Notifications</TabsTrigger><TabsTrigger value="group">Group</TabsTrigger><TabsTrigger value="activity">Activity</TabsTrigger>
      </TabsList><TabsContent value={page}>
      {state.error && <Alert variant="destructive" className="page-alert"><AlertTitle>Connection needs attention</AlertTitle><AlertDescription>{state.error}<Button variant="ghost" size="sm" onClick={() => controller.reconnect()}>Reconnect</Button></AlertDescription></Alert>}
      {!owner && !identity.peers.find(p => p.id === identity.owner)?.approved && <Alert className="page-alert"><ShieldCheck /><AlertTitle>Waiting for owner approval</AlertTitle><AlertDescription>Keep this page open. Ask the owner to approve {identity.name} in their group settings.</AlertDescription></Alert>}
      {page === "devices" && (phones.length ? <div className="device-grid">{phones.map(p => {
        const connected = online(state, p.id); const catalog = state.snapshots[p.id + "/"];
        const deviceReaders = catalog?.lpa.readers.filter(r => !r.deviceId) ?? [];
        return <button className="device-tile" key={p.id} onClick={() => { selectPhone(p.id); setPage(p.profiles ? "profiles" : "notifications"); }}>
          <div className="tile-top"><div className="device-icon"><Smartphone /></div><ChevronRight /></div>
          <div className="tile-title">{p.name}</div><div className="tile-meta">{!p.approved ? "Approve the group owner on this phone" : connected ? "Online" : "Offline"}</div>
          <div className="tile-readers">{!p.approved ? <span>Settings → Remote devices</span> : deviceReaders.length ? deviceReaders.slice(0, 4).map(r => <span key={r.id}><span>{r.name}</span><span>{r.available ? "Available" : "Unavailable"}</span></span>) : <span>{p.profiles ? connected ? "Discovering readers" : "Waiting for connection" : "Notification access"}</span>}</div>
        </button>;
      })}</div> : <NoItems title={owner ? "Add your first phone" : "No phones shared yet"} description={owner ? "Create a pairing code in HyperLPA → Settings → Remote devices, then add it here." : "The owner can grant this browser access to phones in group settings."}>{owner && <Button variant="outline" onClick={() => showForm({ type: "phone" })} disabled={!state.connected}><Plus />Add phone</Button>}</NoItems>)}
      {page === "profiles" && <>
        {phones.some(p => p.approved && p.profiles) ? <><div className="content-toolbar"><div className="toolbar picks">{phonePicker}{readers.length > 0 && <Choose value={reader?.id ?? ""} onChange={setReaderId} label="Reader">{readers.map(r => <SelectItem value={r.id} key={r.id}>{r.name}</SelectItem>)}</Choose>}</div>
          <div className="toolbar"><BusyButton variant="ghost" busy={refreshing} disabled={!phone || !online(state, phone.id) || !!pending || !phone.profiles} onClick={() => void run(() => reader ? command({ action: "REFRESH" }) : controller.readers(phone!.id))}><RefreshCw data-icon="inline-start" />Refresh</BusyButton>
            <Button variant="outline" disabled={!snapshot || !!pending || !phone || !online(state, phone.id)} onClick={() => showForm({ type: "download" })}><Download data-icon="inline-start" />Download</Button></div></div>
          {pending && <Alert className="page-alert"><LoaderCircle className="spin" /><AlertTitle>{pending.action === "DOWNLOAD" ? "Download in progress" : "Operation awaiting a verified result"}</AlertTitle><AlertDescription>Changes run on the phone. Reconnecting queries their status without repeating them.
            <div className="toolbar"><Button variant="outline" size="sm" onClick={() => void run(() => controller.queryPending(pending.id))}>Check status</Button>
              {Date.now() - pending.started > 150_000 && <Button variant="ghost" size="sm" onClick={() => ask({ title: "Resolve an unknown outcome?", description: "The phone may have completed the change. This clears the pending request without repeating it. You must refresh the reader before another change.", action: () => controller.acknowledgeUnknown(pending.id) })}>Resolve unknown outcome</Button>}</div></AlertDescription></Alert>}
          {snapshot?.lpa.pendingProfileDownload && pending?.action === "DOWNLOAD" && <Alert className="page-alert"><Download /><AlertTitle>Confirm profile installation</AlertTitle><AlertDescription>{profileName(snapshot.lpa.pendingProfileDownload.profile)}
            <div className="toolbar"><Button variant="outline" onClick={() => void run(() => controller.decision(phone!.id, pending.id, false))}>Cancel download</Button><Button onClick={() => void run(() => controller.decision(phone!.id, pending.id, true))}>Install profile</Button></div></AlertDescription></Alert>}
          {snapshot ? <><div className="reader-summary"><span>{reader?.name}</span>{snapshot.lpa.euiccInfo && <span className="identifier">{snapshot.lpa.euiccInfo.eid}<CopyButton value={snapshot.lpa.euiccInfo.eid} label="Copy EID" /></span>}{!phone || !online(state, phone.id) ? <span>Offline · last received data</span> : null}</div>
            {profiles.length ? <div className="records">{profiles.map(profile => <div className="profile-row" key={profile.iccid}>
              <div className="profile-symbol"><RadioTower /></div><div className="record-main"><div className="record-title"><span>{profileName(profile)}</span>{profile.isPinned && <span className="quiet-label">Pinned</span>}</div>
                <div className="record-subtitle">{profile.providerName || "Unknown provider"}</div><div className="identifier">{profile.iccid}<CopyButton value={profile.iccid} label="Copy ICCID" /></div>
                {!!profile.tags?.length && <div className="record-tags">{profile.tags.join(" · ")}</div>}</div><span className="profile-state">{profile.state === "ENABLED" ? "Enabled" : "Disabled"}</span>
              <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={`Actions for ${profileName(profile)}`} disabled={!!pending || !phone || !online(state, phone.id)}><Ellipsis /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup>
                <DropdownMenuItem onSelect={() => ask({ title: `${profile.state === "ENABLED" ? "Disable" : "Enable"} ${profileName(profile)}?`, description: profile.state === "ENABLED" && snapshot.lpa.profiles.filter(p => p.state === "ENABLED").length === 1 ? "This is the last enabled profile. Disabling it can disconnect the phone. Keep Wi-Fi or another data SIM available." : "Changing the active profile can interrupt the phone’s mobile connection.", action: () => command({ action: "SWITCH", iccid: profile.iccid, enabled: profile.state !== "ENABLED", confirmed: true }) })}>{profile.state === "ENABLED" ? "Disable" : "Enable"}</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => showForm({ type: "rename", profile })}>Rename</DropdownMenuItem><DropdownMenuItem onSelect={() => showForm({ type: "tags", profile })}>Edit tags</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => void run(() => command({ action: "PIN", iccid: profile.iccid, enabled: !profile.isPinned }))}>{profile.isPinned ? "Unpin" : "Pin"}</DropdownMenuItem>
              </DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => ask({ title: `Delete ${profileName(profile)}?`, description: "This permanently deletes the profile from the eUICC. You may need a new activation code to install it again.", destructive: true, action: () => command({ action: "DELETE", iccid: profile.iccid, confirmed: true }) })}>Delete profile</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu>
            </div>)}</div> : <NoItems icon={RadioTower} title="No profiles installed" description="Download a profile using its carrier activation code." />}
            {!!snapshot.lpa.notifications?.length && <section className="subsection"><h2>Pending eUICC notifications</h2><div className="records">{snapshot.lpa.notifications.map(n => <div className="record-row" key={n.sequenceNumber}><div className="record-main"><span>{n.operation.toLowerCase()}</span><div className="record-subtitle">{n.address}</div></div><Button variant="outline" disabled={!!pending || !phone || !online(state, phone.id)} onClick={() => void run(() => command({ action: "PROCESS_NOTIFICATION", number: n.sequenceNumber }))}>Process</Button></div>)}</div></section>}
          </> : <NoItems icon={RadioTower} title={phone && !online(state, phone.id) ? "Phone is offline" : refreshing ? "Reading profiles" : readers.length ? "Refresh this reader" : "No compatible reader available"} description={phone && !online(state, phone.id) ? "Keep remote access enabled and allow HyperLPA to run in the background." : "The phone must have a working local eUICC reader."} />}
        </> : <NoItems icon={RadioTower} title="No profile access" description={owner ? "Enroll a phone to manage its eSIM profiles." : "Ask the owner to grant profile access to a phone."} />}
      </>}
      {page === "notifications" && <>
        {phones.some(p => p.approved && p.notifications) ? <><div className="content-toolbar"><div className="toolbar picks">{phonePicker}<div className="search-field"><Search /><Input value={query} onChange={e => setQuery(e.target.value)} placeholder="Find a notification" aria-label="Find a notification" /></div></div><Button variant="ghost" disabled={!phone || !online(state, phone.id) || !phone.notifications} onClick={() => void run(() => controller.requestNotifications(phone!.id))}><RefreshCw data-icon="inline-start" />Refresh</Button></div>
          {phone && online(state, phone.id) && state.notifications[phone.id]?.available ? <>
            {state.notifications[phone.id].entries.filter(n => `${n.appLabel} ${n.title} ${n.text}`.toLowerCase().includes(query.toLowerCase())).length ? <div className="records">{state.notifications[phone.id].entries.filter(n => `${n.appLabel} ${n.title} ${n.text}`.toLowerCase().includes(query.toLowerCase())).sort((a, b) => b.timestamp - a.timestamp).map(n => <article className="notification-row" key={n.id}>
              <div className="record-main"><div className="notification-meta"><span>{n.appLabel || n.packageName}</span><time dateTime={new Date(n.timestamp).toISOString()}>{localTime(n.timestamp)}</time></div><h2>{n.title || "Notification"}</h2><p>{n.text}</p></div>
              <div className="toolbar"><CopyButton value={n.text} label="Copy notification text" /><DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={`Actions for notification ${n.title}`}><Ellipsis /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => ask({ title: "Delete this history entry?", description: "Removes this entry from HyperLPA’s notification history on the source phone. It does not delete the original message.", destructive: true, action: () => controller.deleteNotification(phone.id, n.id) })}>Delete history entry</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu></div>
            </article>)}</div> : <NoItems icon={Bell} title={query ? "No matching notifications" : "No notifications yet"} description={query ? "Try a different word or clear your search." : "Notifications from the apps allowed on this phone will appear here."} />}
          </> : <NoItems icon={Bell} title={phone && !online(state, phone.id) ? "Phone is offline" : "Notification access required"} description="On the phone, enable notification sharing, choose apps, and allow the group owner to view notifications. Android may hide sensitive content." />}
          <p className="page-note">Shared Android notifications. SMS sending and full conversation history are not supported.</p>
        </> : <NoItems icon={Bell} title="No notifications shared" description="Add a phone or ask the owner to grant this browser notification access." />}
      </>}
      {page === "group" && <>
        <div className="content-toolbar"><h2>Devices and browsers</h2>{owner && identity.peers.length > 0 && <Button variant="ghost" onClick={() => setManage(!manage)}>{manage ? "Done" : "Manage"}</Button>}</div>
        <div className="records"><div className="record-row"><Laptop /><div className="record-main"><span>{identity.name}</span><div className="record-subtitle">This browser · {owner ? "Owner" : "Controller"}</div></div><ShieldCheck /></div>
          {identity.peers.map(peer => <div className="record-row" key={peer.id}>{peer.kind === "phone" ? <Smartphone /> : <Laptop />}<div className="record-main"><span>{peer.name}</span><div className="record-subtitle">{peer.kind === "owner" ? "Owner" : peer.kind === "phone" ? "Phone" : "Browser"} · {peer.approved ? online(state, peer.id) ? "Online" : "Offline" : "Awaiting approval"}
            {owner && peer.kind === "phone" && peer.approved && state.phoneRevisions[peer.id] !== identity.revision && " · Group update pending"}</div></div>
            {owner && peer.kind === "browser" && !peer.approved && <BusyButton busy={busy} disabled={!state.connected} onClick={() => { setBusy(true); void run(() => controller.approveBrowser(peer.id)).finally(() => setBusy(false)); }}>Approve</BusyButton>}
            {owner && manage && <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={`Manage ${peer.name}`}><Ellipsis /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem onSelect={() => showForm({ type: "rename", peer })}>Rename</DropdownMenuItem><DropdownMenuItem variant="destructive" onSelect={() => ask({ title: `Remove ${peer.name}?`, description: "Blocks this device on the relay immediately. Phones apply the membership removal when the owner and phone reconnect. Data already copied elsewhere cannot be erased.", destructive: true, action: () => controller.revoke(peer.id) })}>Remove device</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu>}
          </div>)}</div>
        {owner && browsers.some(p => p.approved) && phones.some(p => p.approved) && <section className="subsection"><div className="content-toolbar"><h2>Browser permissions</h2><Choose value={browserId || browsers.find(p => p.approved)?.id || ""} onChange={setBrowserId} label="Browser">{browsers.filter(p => p.approved).map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</Choose></div>
          <div className="records">{phones.filter(p => p.approved).map(p => {
            const browser = browserId || browsers.find(b => b.approved)?.id || "";
            const grant = identity.grants[p.id]?.[browser];
            return <div className="permission-row" key={p.id}><span>{p.name}</span><div className="permission-options">
              <label><Checkbox aria-label={`Manage profiles on ${p.name}`} checked={grant?.profiles ?? false} disabled={busy || !state.connected} onCheckedChange={checked => { setBusy(true); void run(() => controller.permission(browser, p.id, checked === true, grant?.notifications ?? false)).finally(() => setBusy(false)); }} />Profiles</label>
              <label><Checkbox aria-label={`View notifications on ${p.name}`} checked={grant?.notifications ?? false} disabled={busy || !state.connected} onCheckedChange={checked => { setBusy(true); void run(() => controller.permission(browser, p.id, grant?.profiles ?? false, checked === true)).finally(() => setBusy(false)); }} />Notifications</label>
            </div></div>;
          })}</div><p className="page-note">Notification access also requires sharing permission on each phone. The owner must be online to apply group changes.</p></section>}
        <section className="subsection"><h2>Relay</h2><p className="relay-address">{identity.relay}<CopyButton value={identity.relay} label="Copy relay address" /></p><p className="page-note">Keys are kept in this browser. Save a recovery export before clearing site storage.</p></section>
      </>}
      {page === "activity" && (state.activities.length || identity.pending.length ? <div className="records">{identity.pending.filter(p => !state.activities.some(a => a.id === p.id)).map(p => <div className="record-row" key={p.id}><RefreshCw /><div className="record-main"><span>{p.action.toLowerCase()} · result pending</span><div className="record-subtitle">Recovered after reconnect</div></div><Button variant="outline" onClick={() => void run(() => controller.queryPending(p.id))}>Check status</Button></div>)}
        {state.activities.map(a => <div className="record-row activity-row" key={a.id}><div className="record-main"><span>{a.label}</span><div className="record-subtitle">{identity.peers.find(p => p.id === a.peer)?.name ?? "Device"} · {localTime(a.time)}</div>{a.detail && <p className="activity-detail">{a.detail}</p>}</div><span className={cn("activity-status", ["Failed", "Outcome unverified", "Rejected by relay"].includes(a.status) && "text-destructive")}>{a.status}</span></div>)}</div> : <NoItems icon={RefreshCw} title="No activity yet" description="Device requests and their results appear here during this browser session." />)}
    </TabsContent></Tabs></>}
  </main>
    <FormModal key={formGeneration} returnFocus={formOpener.current} form={form} open={formOpen} onClose={() => setFormOpen(false)} identity={identity} command={command} />
    <Modal returnFocus={confirmOpener.current} open={confirmOpen} onClose={() => { if (!busy) setConfirmOpen(false); }} title={confirm?.title ?? "Confirm"} description={confirm?.description} footer={<><Button variant="outline" disabled={busy} onClick={() => setConfirmOpen(false)}>Cancel</Button><BusyButton variant={confirm?.destructive ? "destructive" : "default"} busy={busy} onClick={async () => {
      if (!confirm) return; setBusy(true);
      try { await confirm.action(); setConfirmOpen(false); } catch (error) { toast.error(errorMessage(error)); } finally { setBusy(false); }
    }}>Confirm</BusyButton></>} />
    <Toaster position="bottom-center" richColors={false} />
  </>;
}

function FormModal({ form, open, onClose, identity, command, returnFocus }: { returnFocus: HTMLElement | null; form?: FormDialog; open: boolean; onClose: () => void; identity?: Identity; command: (value: Command) => Promise<unknown> }) {
  const [value, setValue] = useState(form?.type === "tags" ? form.profile?.tags?.join(", ") ?? "" : form?.peer?.name ?? form?.profile?.nickname ?? "");
  const [confirmationCode, setConfirmationCode] = useState("");
  const [code, setCode] = useState(""); const [recoveryKey, setRecoveryKey] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const file = useRef<string>("");
  const titles = { phone: "Add a phone", invite: "Invite a browser", recovery: "Recovery export", download: "Download a profile", rename: form?.peer ? "Rename device" : "Rename profile", tags: "Edit profile tags" };
  const descriptions = { phone: "On the phone, create a pairing code in Settings → Remote devices. Approve this browser as group owner after adding it.",
    invite: "Share this code privately. It expires after ten minutes and can be used once. You will approve the browser before granting access.",
    recovery: "The encrypted export restores this device identity. Keep the recovery key separately, preferably in your password manager.",
    download: "Paste the activation code supplied by your carrier. The phone will show a preview before installation.", rename: "", tags: "Separate tags with commas." };
  async function submit() {
    if (!form || !identity) return;
    setBusy(true); setError("");
    try {
      if (form.type === "phone") { await controller.addPhone(value); toast.success("Approve this browser on the phone."); onClose(); }
      if (form.type === "invite") setCode(await controller.inviteBrowser());
      if (form.type === "recovery") {
        const backup = await exportRecovery(identity); file.current = backup.file; setRecoveryKey(backup.recoveryKey);
      }
      if (form.type === "rename") {
        if (form.peer) await controller.rename(form.peer.id, value);
        else await command({ action: "RENAME", iccid: form.profile!.iccid, text: value });
        onClose();
      }
      if (form.type === "tags") {
        const tags = value.split(",").map(s => s.trim()).filter(Boolean);
        if (tags.length > 32 || tags.some(s => s.length > 80)) throw new Error("Use up to 32 tags, each up to 80 characters.");
        await command({ action: "TAGS", iccid: form.profile!.iccid, tags }); onClose();
      }
      if (form.type === "download") {
        const fields = value.trim().replace(/^LPA:/i, "").split("$");
        if (fields[0] !== "1" || !fields[1] || fields.length > 5) throw new Error("Enter an activation code beginning with LPA:1$.");
        const server = new URL("https://" + fields[1]);
        if (server.username || server.password || server.pathname !== "/" || server.search || server.hash) throw new Error("The activation code has an invalid server address.");
        await command({ action: "DOWNLOAD", confirmBeforeInstall: true, download: { smdpAddress: fields[1], matchingId: fields[2] || null,
          smdpOid: fields[3] || null, confirmationCodeRequired: fields[4] === "1", confirmationCode: confirmationCode.trim() || null } }); onClose();
      }
    } catch (error) { setError(errorMessage(error)); } finally { setBusy(false); }
  }
  const generated = !!code || !!recoveryKey;
  return <Modal returnFocus={returnFocus} open={open} onClose={() => { if (!busy) onClose(); }} title={form ? titles[form.type] : "Device"} description={form ? descriptions[form.type] : undefined}
    footer={<><Button variant="outline" onClick={onClose} disabled={busy}>{generated ? "Done" : "Cancel"}</Button>
      {form?.type === "recovery" && recoveryKey ? <Button onClick={() => {
        const url = URL.createObjectURL(new Blob([file.current], { type: "application/json" }));
        const a = document.createElement("a"); a.href = url; a.download = "hyperlpa-recovery.hyperlpa"; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      }}><Download data-icon="inline-start" />Download export</Button> : !generated && <BusyButton type="submit" form="dialog-form" busy={busy} disabled={["phone", "download", "rename"].includes(form?.type ?? "") && !value.trim()}>{form?.type === "invite" ? "Create invitation" : form?.type === "recovery" ? "Generate export" : form?.type === "phone" ? "Connect phone" : form?.type === "download" ? "Get preview" : "Save"}</BusyButton>}</>}>
    <form id="dialog-form" onSubmit={e => { e.preventDefault(); void submit(); }}><FieldGroup>
      {form?.type === "phone" && <Field><FieldLabel htmlFor="phone-pair-code">Phone pairing code</FieldLabel><Textarea id="phone-pair-code" value={value} onChange={e => setValue(e.target.value)} maxLength={4096} required spellCheck={false} autoComplete="off" placeholder="hyperlpa-pair:…" /></Field>}
      {form?.type === "download" && <><TextField label="Activation code" value={value} onChange={e => setValue(e.target.value)} placeholder="LPA:1$…" maxLength={4600} required autoComplete="off" spellCheck={false} />
        <TextField label="Confirmation code" value={confirmationCode} onChange={e => setConfirmationCode(e.target.value)} autoComplete="off" type="password" maxLength={64} hint="Only if required by your carrier." /></>}
      {form?.type === "rename" && <TextField label="Name" value={value} onChange={e => setValue(e.target.value)} maxLength={80} required autoFocus />}
      {form?.type === "tags" && <TextField label="Tags" value={value} onChange={e => setValue(e.target.value)} maxLength={2592} autoFocus />}
      {code && <Field><FieldLabel htmlFor="invitation-code">Invitation code</FieldLabel><div className="copy-field"><Textarea id="invitation-code" value={code} readOnly spellCheck={false} /><CopyButton value={code} label="Copy invitation code" /></div></Field>}
      {recoveryKey && <Field><FieldLabel htmlFor="recovery-key">Recovery key</FieldLabel><div className="copy-field"><Input id="recovery-key" value={recoveryKey} readOnly /><CopyButton value={recoveryKey} label="Copy recovery key" /></div><FieldDescription>Save this key before downloading the export. Anyone with both can control this device identity.</FieldDescription></Field>}
    </FieldGroup></form>
    {error && <Alert variant="destructive" role="alert"><AlertTitle>Could not complete the action</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
  </Modal>;
}
