import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ArrowLeft, ArrowUpRight, Bell, Check, ChevronRight, Download, Ellipsis, History, Info, KeyRound, Languages, Laptop, LoaderCircle, Monitor, Moon, Plus, RadioTower, RefreshCw, RotateCcw, Search, Settings2, ShieldCheck, Smartphone, SlidersHorizontal, Sun, Trash2, Unplug, Upload, X } from "lucide-react";
import { Toaster, toast } from "sonner";
import { commandLabel, controller } from "@/lib/controller";
import { parseInvitation, relayOrigin } from "@/lib/crypto";
import { exportRecovery, importRecovery } from "@/lib/storage";
import { getLanguage, getLanguagePreference, languages, setLanguage, t, useLanguage } from "@/lib/i18n";
import type { Command, Identity, Peer, Profile, Reader, RemoteSettings, Snapshot, ViewState } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useTabInput } from "@/lib/use-tab-input";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { BusyButton, CopyButton, Modal, TextField, useFormErrors, validateField } from "@/components/common";

type Page = "devices" | "profiles" | "notifications" | "history" | "tools" | "group" | "activity";
type FormDialog = { type: "phone" | "invite" | "recovery" | "download" | "batch" | "profile" | "rename" | "tags"; peer?: Peer; profile?: Profile };
type ConfirmDialog = { title: string; description: string; destructive?: boolean; actionLabel: string; action: () => Promise<unknown> };
const profileName = (p: Profile) => p.nickname || p.name || p.providerName || t("Unnamed profile");
const defaultRemoteSettings: RemoteSettings = { autoLoadProfiles: true, autoLoadRemoteReaders: false, enableNBridge: true, enableOmapi: true, enableTelephony: false, enableUsbCcid: true, enableBle: false, enableRemote: false, notificationInitialLoad: true, notificationAfterSwitch: true, notificationAfterDelete: true, notificationBeforeDownload: true, notificationAfterDownload: true, notificationAutoSend: true, notificationAutoRemove: true, scheduledReminders: true, loadOperatorIcons: false, estimateProfileSize: false, hideProfileDeletion: false, hideEuiccMemoryReset: false, apduLogging: false };
const online = (s: ViewState, id: string) => s.connected && Date.now() - (s.online[id] ?? 0) < 60_000;
const pageTitles = { devices: "Devices", profiles: "Profiles", notifications: "Notifications", history: "eUICC history", tools: "Phone tools", group: "Trusted group", activity: "Activity" } satisfies Record<Page, string>;
function errorMessage(error: unknown) { return t(error instanceof Error ? error.message : "Something went wrong. Try again."); }
function localTime(time: number) { return new Intl.DateTimeFormat(getLanguage(), { dateStyle: "medium", timeStyle: "short" }).format(time); }
function localNumber(value: number) { return new Intl.NumberFormat(getLanguage()).format(value); }
function historyStatus(status: string) {
  if (status === "SUCCEEDED" || status === "SUCCESS") return t("Completed");
  if (status === "FAILED") return t("Failed");
  return t("Unknown");
}
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
function AppearanceButton() {
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme ?? "system");
  return <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t("Appearance")} title={t("Appearance")}><Sun className="light-icon" /><Moon className="dark-icon" /></Button></DropdownMenuTrigger>
    <DropdownMenuContent align="end"><DropdownMenuRadioGroup aria-label={t("Appearance")} value={theme} onValueChange={value => {
      setTheme(value); if (value === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = value;
      try { localStorage.setItem("hyperlpa-theme", value); } catch { toast.error(t("Could not save appearance preference.")); }
    }}>{(["system", "light", "dark"] as const).map(value => <DropdownMenuRadioItem key={value} value={value}>
      {value === "system" ? <Monitor /> : value === "light" ? <Sun /> : <Moon />}
      {getLanguage() === "en" ? <span className="capitalize">{value}</span> : t({ system: "System", light: "Light", dark: "Dark" }[value])}
    </DropdownMenuRadioItem>)}</DropdownMenuRadioGroup></DropdownMenuContent>
  </DropdownMenu>;
}

function LanguageButton() {
  return <DropdownMenu>
    <DropdownMenuTrigger asChild>
      <Button variant="ghost" size="icon" aria-label={t("Language")} title={t("Language")}>
        <Languages />
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end">
      <DropdownMenuRadioGroup aria-label={t("Language")} value={getLanguagePreference()} onValueChange={setLanguage}>
        <DropdownMenuRadioItem value="system">{t("Use browser language")}</DropdownMenuRadioItem>
        <DropdownMenuSeparator />
        {Object.entries(languages).map(([locale, name]) => (
          <DropdownMenuRadioItem key={locale} value={locale} textValue={name}>
            <span lang={locale}>{name}</span>
          </DropdownMenuRadioItem>
        ))}
      </DropdownMenuRadioGroup>
    </DropdownMenuContent>
  </DropdownMenu>;
}

function Setup({ onRun }: { onRun: (fn: () => Promise<unknown>) => Promise<void> }) {
  const [mode, setMode] = useState("create");
  const [relay, setRelay] = useState(""); const [key, setKey] = useState(""); const [name, setName] = useState("");
  const [code, setCode] = useState(""); const [recovery, setRecovery] = useState<File>();
  const [busy, setBusy] = useState(false); const errors = useFormErrors();
  const { error } = errors;
  const submit = async () => {
    setBusy(true); errors.clear();
    try {
      if (mode !== "restore") await validateField("browser-name", () => {
        if (!name.trim()) throw new Error("Enter a browser name.");
      });
      if (mode === "create") {
        await validateField("relay-address", () => {
          try { return relayOrigin(relay); } catch { throw new Error("Enter an HTTPS relay address without a path, query or credentials."); }
        });
        await controller.create(relay, key, name);
      }
      else if (mode === "join") {
        await validateField("group-code", () => {
          try { return parseInvitation(code.trim(), "hyperlpa-group:"); } catch { throw new Error("Paste a valid pairing code from a current group invitation, or ask the owner for a new invitation."); }
        });
        await controller.join(code, name);
      }
      else {
        await validateField("recovery-file", () => {
          if (!recovery || recovery.size > 3_000_000) throw new Error("Choose a HyperLPA recovery file smaller than 3 MB.");
        });
        const restored = await validateField("recovery-key", async () => importRecovery(await recovery!.text(), key));
        await controller.restore(restored);
      }
    } catch (error) { errors.report(error); }
    finally { setBusy(false); }
  };
  void onRun;
  return <section className="setup"><div className="setup-mark"><RadioTower /></div><h1>{t("Your phones, together.")}</h1>
    <p className="intro">{t("Connect HyperLPA to manage your eSIM profiles and shared notifications.")}</p>
    <Tabs value={mode} onValueChange={value => { setMode(value); errors.clear(); setKey(""); }}>
      <TabsList className="setup-tabs"><TabsTrigger value="create">{t("Create group")}</TabsTrigger><TabsTrigger value="join">{t("Join group")}</TabsTrigger><TabsTrigger value="restore">{t("Restore")}</TabsTrigger></TabsList>
    <TabsContent value={mode}><form onSubmit={event => { event.preventDefault(); void submit(); }} className="setup-form" onChangeCapture={errors.clear}>
      <FieldGroup>
        {mode === "create" && <><TextField {...errors.props("relay-address")} label={t("Relay address")} placeholder="https://hyperlpa-relay.example.workers.dev" value={relay} onChange={e => setRelay(e.target.value)} type="url" required autoComplete="url">{errors.message("relay-address")}</TextField>
          <TextField {...errors.props("enrollment-key")} label={t("Enrollment key")} type="password" autoComplete="off" value={key} onChange={e => setKey(e.target.value)} required maxLength={256} hint={t("The private key configured in your Cloudflare relay. Used only during setup.")}>{errors.message("enrollment-key")}</TextField></>}
        {mode === "join" && <Field><FieldLabel htmlFor="group-code">{t("Group invitation")}</FieldLabel><Textarea {...errors.props("group-code")} id="group-code" value={code} onChange={e => setCode(e.target.value)} placeholder="hyperlpa-group:…" required maxLength={4096} spellCheck={false} autoComplete="off" />{errors.message("group-code")}
          <FieldDescription>{t("Ask the owner for an invitation. They will approve this browser before granting phone access.")}</FieldDescription></Field>}
        {mode !== "restore" && <TextField {...errors.props("browser-name")} label={t("Browser name")} value={name} onChange={e => setName(e.target.value)} placeholder={t("Personal laptop")} autoComplete="off" maxLength={80} required>{errors.message("browser-name")}</TextField>}
        {mode === "restore" && <><TextField {...errors.props("recovery-file")} label={t("Recovery file")} type="file" accept=".json,.hyperlpa" onChange={e => setRecovery(e.target.files?.[0])} required>{errors.message("recovery-file")}</TextField>
          <TextField {...errors.props("recovery-key")} label={t("Recovery key")} type="password" value={key} onChange={e => setKey(e.target.value)} autoComplete="off" required hint={t("Restores this device identity. Its previous window will disconnect.")}>{errors.message("recovery-key")}</TextField></>}
      </FieldGroup>
      {error && !errors.field && <Alert variant="destructive" role="alert"><AlertTitle>{mode === "restore" ? t("Could not restore") : t("Could not connect")}</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
      <BusyButton type="submit" busy={busy} className="setup-submit">{mode === "create" ? t("Create trusted group") : mode === "join" ? t("Request to join") : t("Restore device")}<ArrowUpRight data-icon="inline-end" /></BusyButton>
    </form></TabsContent></Tabs>
    {mode === "create" && <p className="setup-help">{t("Deploy the relay first, then allow this website’s origin in its configuration.")} <a href="https://github.com/FreeTeaspoon/HyperLPA/blob/main/web/README.md" target="_blank" rel="noreferrer">{t("Setup guide")}<ArrowUpRight /></a></p>}
  </section>;
}

export function App() {
  const language = useLanguage();
  const tabInput = useTabInput();
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const [page, setPage] = useState<Page>("devices");
  const focusPage = useRef(false);
  const navigate = (next: Page) => {
    if (next === page) {
      document.querySelector<HTMLElement>(".page-heading h1")?.focus({ preventScroll: true });
      window.scrollTo({ top: 0, behavior: "instant" });
    } else { focusPage.current = true; setPage(next); }
  };
  useEffect(() => {
    document.title = state.identity ? `${t(pageTitles[page])} · HyperLPA` : "HyperLPA";
  }, [language, page, state.identity]);
  useEffect(() => {
    if (focusPage.current) {
      focusPage.current = false;
      document.querySelector<HTMLElement>(".page-heading h1")?.focus({ preventScroll: true });
      window.scrollTo({ top: 0, behavior: "instant" });
    }
  }, [page]);
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
  const phonePicker = <Choose value={phone?.id ?? ""} onChange={selectPhone} label={t("Phone")}>{eligiblePhones.filter(p => p.approved).map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</Choose>;
  return <><a className="skip-link" href="#main">{t("Skip to content")}</a><header className="app-header"><div className="header-inner">
    <a href="#" className="brand" onClick={event => { event.preventDefault(); navigate("devices"); }}><RadioTower /><span>HyperLPA</span></a>
    <div className="header-actions">{identity && <span className="connection-state"><span className={cn("connection-dot", state.connected && "connected")} /><span>{state.connected ? t("Connected") : t("Disconnected")}</span></span>}
      <LanguageButton /><AppearanceButton />{identity && <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t("Browser settings")}><Ellipsis /></Button></DropdownMenuTrigger>
        <DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem onSelect={() => showForm({ type: "recovery" })}><KeyRound />{t("Recovery export")}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => controller.reconnect()}><RefreshCw />{t("Reconnect")}</DropdownMenuItem></DropdownMenuGroup><DropdownMenuSeparator />
          <DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => ask({ actionLabel: t("Forget browser"), title: t("Forget this browser?"), description: t("This clears the identity stored here. It does not revoke the device on the relay. Save a recovery export first, or ask the owner to enroll a new browser."), destructive: true, action: () => controller.forgetLocal() })}><Unplug />{t("Forget this browser")}</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent>
      </DropdownMenu>}</div>
  </div></header><main id="main" className="shell" tabIndex={-1}>
    {!state.loaded ? <div className="initial-loading" role="status"><LoaderCircle className="spin" /><span>{t("Opening dashboard")}</span></div> : !identity ? <><Setup onRun={run} />{state.error && <Alert variant="destructive"><AlertDescription>{t(state.error)}</AlertDescription></Alert>}</> : <>
      <div className="page-heading"><div><h1 tabIndex={-1}>{t(pageTitles[page])}</h1>
        <p>{page === "devices" ? t(phones.length === 1 ? "{count} phone · {online} online" : "{count} phones · {online} online", {
          count: localNumber(phones.length), online: localNumber(phones.filter(peer => online(state, peer.id)).length),
        }) : page === "group" ? (owner ? t("Managed by this browser") : t("Managed by {owner}", {
          owner: identity.peers.find(peer => peer.id === identity.owner)?.name ?? t("your owner device"),
        })) :
          page === "activity" ? t("Operations from this browser session") : page === "tools" ? phone?.name ?? t("Choose a phone") : phone?.name ?? t("Your connected phones")}</p></div>
        {owner && page === "devices" && <Button onClick={() => showForm({ type: "phone" })} disabled={!state.connected}><Plus data-icon="inline-start" />{t("Add phone")}</Button>}
        {page === "group" && <div className="toolbar"><Button variant="outline" onClick={() => showForm({ type: "recovery" })}><KeyRound data-icon="inline-start" />{t("Recovery")}</Button>
          {owner && <Button onClick={() => showForm({ type: "invite" })} disabled={!state.connected}><Plus data-icon="inline-start" />{t("Invite browser")}</Button>}</div>}
      </div>
      <Tabs value={page} onValueChange={value => { setPage(value as Page); setQuery(""); }}><TabsList className="main-tabs" {...tabInput}>
        <TabsTrigger value="devices">{t("Devices")}</TabsTrigger><TabsTrigger value="profiles">{t("Profiles")}</TabsTrigger><TabsTrigger value="notifications">{t("Notifications")}</TabsTrigger><TabsTrigger value="history">{t("History")}</TabsTrigger><TabsTrigger value="tools">{t("Tools")}</TabsTrigger><TabsTrigger value="group">{t("Group")}</TabsTrigger><TabsTrigger value="activity">{t("Activity")}</TabsTrigger>
      </TabsList><TabsContent value={page}>
      {state.error && <Alert variant="destructive" className="page-alert"><AlertTitle>{t("Connection needs attention")}</AlertTitle><AlertDescription>{t(state.error)}<Button variant="ghost" size="sm" onClick={() => controller.reconnect()}>{t("Reconnect")}</Button></AlertDescription></Alert>}
      {!owner && !identity.peers.find(p => p.id === identity.owner)?.approved && <Alert className="page-alert"><ShieldCheck /><AlertTitle>{t("Waiting for owner approval")}</AlertTitle><AlertDescription>{t("Keep this page open. Ask the owner to approve {browser} in their group settings.", { browser: identity.name })}</AlertDescription></Alert>}
      {page === "devices" && (phones.length ? <div className="device-grid">{phones.map(p => {
        const connected = online(state, p.id); const catalog = state.snapshots[p.id + "/"];
        const deviceReaders = catalog?.lpa.readers.filter(r => !r.deviceId) ?? [];
        return <button className="device-tile" key={p.id} onClick={() => { selectPhone(p.id); navigate(p.profiles ? "profiles" : "notifications"); }}>
          <div className="tile-top"><div className="device-icon"><Smartphone /></div><ChevronRight /></div>
          <div className="tile-title">{p.name}</div><div className="tile-meta">{!p.approved ? t("Approve the group owner on this phone") : connected ? t("Online") : t("Offline")}</div>
          <div className="tile-readers">{!p.approved ? <span>{t("Settings → Remote devices")}</span> : deviceReaders.length ? deviceReaders.slice(0, 4).map(r => <span key={r.id}><span>{r.name}</span><span>{r.available ? t("Available") : t("Unavailable")}</span></span>) : <span>{p.profiles ? connected ? t("Discovering readers") : t("Waiting for connection") : t("Notification access")}</span>}</div>
        </button>;
      })}</div> : <NoItems title={owner ? t("Add your first phone") : t("No phones shared yet")} description={owner ? t("Create a pairing code in HyperLPA → Settings → Remote devices, then add it here.") : t("The owner can grant this browser access to phones in group settings.")}>{owner && <Button variant="outline" onClick={() => showForm({ type: "phone" })} disabled={!state.connected}><Plus />{t("Add phone")}</Button>}</NoItems>)}
      {page === "profiles" && <>
        {phones.some(p => p.approved && p.profiles) ? <><div className="content-toolbar"><div className="toolbar picks">{phonePicker}{readers.length > 0 && <Choose value={reader?.id ?? ""} onChange={setReaderId} label={t("Reader")}>{readers.map(r => <SelectItem value={r.id} key={r.id}>{r.name}</SelectItem>)}</Choose>}</div>
          <div className="toolbar"><BusyButton variant="ghost" busy={refreshing} disabled={!phone || !online(state, phone.id) || !!pending || !phone.profiles} onClick={() => void run(() => reader ? command({ action: "REFRESH" }) : controller.readers(phone!.id))}><RefreshCw data-icon="inline-start" />{t("Refresh")}</BusyButton>
            <Button variant="outline" disabled={!snapshot || !!pending || !phone || !online(state, phone.id)} onClick={() => showForm({ type: "download" })}><Download data-icon="inline-start" />{t("Download")}</Button>
            <Button variant="ghost" disabled={!snapshot || !!pending || !phone || !online(state, phone.id)} onClick={() => showForm({ type: "batch" })}><Plus data-icon="inline-start" />{t("Batch")}</Button></div></div>
          {pending && <Alert className="page-alert"><LoaderCircle className="spin" /><AlertTitle>{pending.action === "DOWNLOAD" ? t("Download in progress") : t("Operation awaiting a verified result")}</AlertTitle><AlertDescription>{t("Changes run on the phone. Reconnecting queries their status without repeating them.")}
            <div className="toolbar"><Button variant="outline" size="sm" onClick={() => void run(() => controller.queryPending(pending.id))}>{t("Check status")}</Button>
              {Date.now() - pending.started > 150_000 && <Button variant="ghost" size="sm" onClick={() => ask({ actionLabel: t("Resolve outcome"), title: t("Resolve an unknown outcome?"), description: t("The phone may have completed the change. This clears the pending request without repeating it. You must refresh the reader before another change."), action: () => controller.acknowledgeUnknown(pending.id) })}>{t("Resolve unknown outcome")}</Button>}</div></AlertDescription></Alert>}
          {snapshot?.lpa.pendingProfileDownload && pending?.action === "DOWNLOAD" && <Alert className="page-alert"><Download /><AlertTitle>{t("Confirm profile installation")}</AlertTitle><AlertDescription>{profileName(snapshot.lpa.pendingProfileDownload.profile)}
            <div className="toolbar"><Button variant="outline" onClick={() => void run(() => controller.decision(phone!.id, pending.id, false))}>{t("Cancel download")}</Button><Button onClick={() => void run(() => controller.decision(phone!.id, pending.id, true))}>{t("Install profile")}</Button></div></AlertDescription></Alert>}
          {snapshot ? <><div className="reader-summary"><span>{reader?.name}</span>{snapshot.lpa.euiccInfo && <span className="identifier">{snapshot.lpa.euiccInfo.eid}<CopyButton value={snapshot.lpa.euiccInfo.eid} label={t("Copy EID")} /></span>}{!phone || !online(state, phone.id) ? <span>{t("Offline · last received data")}</span> : null}</div>
            {profiles.length ? <div className="records">{profiles.map(profile => <div className="profile-row" key={profile.iccid}>
              <div className="profile-symbol"><RadioTower /></div><div className="record-main"><div className="record-title"><span>{profileName(profile)}</span>{profile.isPinned && <span className="quiet-label">{t("Pinned")}</span>}</div>
                <div className="record-subtitle">{profile.providerName || t("Unknown provider")}</div><div className="identifier">{profile.iccid}<CopyButton value={profile.iccid} label={t("Copy ICCID")} /></div>
                {!!profile.tags?.length && <div className="record-tags">{profile.tags.join(" · ")}</div>}</div><span className="profile-state">{profile.state === "ENABLED" ? t("Enabled") : t("Disabled")}</span>
              <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t("Actions for {profile}", { profile: profileName(profile) })} disabled={!!pending || !phone || !online(state, phone.id)}><Ellipsis /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup>
                <DropdownMenuItem onSelect={() => ask({ actionLabel: profile.state === "ENABLED" ? t("Disable profile") : t("Enable profile"), title: t(profile.state === "ENABLED" ? "Disable {profile}?" : "Enable {profile}?", { profile: profileName(profile) }), description: profile.state === "ENABLED" && snapshot.lpa.profiles.filter(p => p.state === "ENABLED").length === 1 ? t("This is the last enabled profile. Disabling it can disconnect the phone. Keep Wi-Fi or another data SIM available.") : t("Changing the active profile can interrupt the phone’s mobile connection."), action: () => command({ action: "SWITCH", iccid: profile.iccid, enabled: profile.state !== "ENABLED", confirmed: true }) })}>{profile.state === "ENABLED" ? t("Disable") : t("Enable")}</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => showForm({ type: "profile", profile })}>{t("View details")}</DropdownMenuItem><DropdownMenuItem onSelect={() => showForm({ type: "rename", profile })}>{t("Rename")}</DropdownMenuItem><DropdownMenuItem onSelect={() => showForm({ type: "tags", profile })}>{t("Edit tags")}</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => void run(() => command({ action: "PIN", iccid: profile.iccid, enabled: !profile.isPinned }))}>{profile.isPinned ? t("Unpin") : t("Pin")}</DropdownMenuItem>
              </DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => ask({ actionLabel: t("Delete profile"), title: t("Delete {profile}?", { profile: profileName(profile) }), description: t("This permanently deletes the profile from the eUICC. You may need a new activation code to install it again."), destructive: true, action: () => command({ action: "DELETE", iccid: profile.iccid, confirmed: true }) })}>{t("Delete profile")}</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu>
            </div>)}</div> : <NoItems icon={RadioTower} title={t("No profiles installed")} description={t("Download a profile using its carrier activation code.")} />}
            {!!snapshot.lpa.notifications?.length && <section className="subsection"><h2>{t("Pending eUICC notifications")}</h2><div className="records">{snapshot.lpa.notifications.map(n => <div className="record-row" key={n.sequenceNumber}><div className="record-main"><span>{t(n.operation.toLowerCase())}</span><div className="record-subtitle">{n.address}</div></div><Button variant="outline" disabled={!!pending || !phone || !online(state, phone.id)} onClick={() => void run(() => command({ action: "PROCESS_NOTIFICATION", number: n.sequenceNumber }))}>{t("Process")}</Button></div>)}</div></section>}
          </> : <NoItems icon={RadioTower} title={phone && !online(state, phone.id) ? t("Phone is offline") : refreshing ? t("Reading profiles") : readers.length ? t("Refresh this reader") : t("No compatible reader available")} description={phone && !online(state, phone.id) ? t("Keep remote access enabled and allow HyperLPA to run in the background.") : t("The phone must have a working local eUICC reader.")} />}
        </> : <NoItems icon={RadioTower} title={t("No profile access")} description={owner ? t("Enroll a phone to manage its eSIM profiles.") : t("Ask the owner to grant profile access to a phone.")} />}
      </>}
      {page === "notifications" && <>
        {phones.some(p => p.approved && p.notifications) ? <><div className="content-toolbar"><div className="toolbar picks">{phonePicker}<div className="search-field"><Search /><Input value={query} onChange={e => setQuery(e.target.value)} placeholder={t("Find a notification")} aria-label={t("Find a notification")} /></div></div><Button variant="ghost" disabled={!phone || !online(state, phone.id) || !phone.notifications} onClick={() => void run(() => controller.requestNotifications(phone!.id))}><RefreshCw data-icon="inline-start" />{t("Refresh")}</Button></div>
          {phone && online(state, phone.id) && state.notifications[phone.id]?.available ? <>
            {state.notifications[phone.id].entries.filter(n => `${n.appLabel} ${n.title} ${n.text}`.toLowerCase().includes(query.toLowerCase())).length ? <div className="records">{state.notifications[phone.id].entries.filter(n => `${n.appLabel} ${n.title} ${n.text}`.toLowerCase().includes(query.toLowerCase())).sort((a, b) => b.timestamp - a.timestamp).map(n => <article className="notification-row" key={n.id}>
              <div className="record-main"><div className="notification-meta"><span>{n.appLabel || n.packageName}</span><time dateTime={new Date(n.timestamp).toISOString()}>{localTime(n.timestamp)}</time></div><h2>{n.title || t("Notification")}</h2><p>{n.text}</p></div>
              <div className="toolbar"><CopyButton value={n.text} label={t("Copy notification text")} /><DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t("Actions for notification {title}", { title: n.title })}><Ellipsis /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => ask({ actionLabel: t("Delete entry"), title: t("Delete this history entry?"), description: t("Removes this entry from HyperLPA’s notification history on the source phone. It does not delete the original message."), destructive: true, action: () => controller.deleteNotification(phone.id, n.id) })}>{t("Delete history entry")}</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu></div>
            </article>)}</div> : <NoItems icon={Bell} title={query ? t("No matching notifications") : t("No notifications yet")} description={query ? t("Try a different word or clear your search.") : t("Notifications from the apps allowed on this phone will appear here.")} />}
          </> : <NoItems icon={Bell} title={phone && !online(state, phone.id) ? t("Phone is offline") : t("Notification access required")} description={t("On the phone, enable notification sharing, choose apps, and allow the group owner to view notifications. Android may hide sensitive content.")} />}
          <p className="page-note">{t("Shared Android notifications. SMS sending and full conversation history are not supported.")}</p>
        </> : <NoItems icon={Bell} title={t("No notifications shared")} description={t("Add a phone or ask the owner to grant this browser notification access.")} />}
      </>}
      {page === "history" && <>
        {phones.some(p => p.approved && p.profiles) ? <><div className="content-toolbar"><div className="toolbar picks">{phonePicker}{readers.length > 0 && <Choose value={reader?.id ?? ""} onChange={setReaderId} label={t("Reader")}>{readers.map(r => <SelectItem value={r.id} key={r.id}>{r.name}</SelectItem>)}</Choose>}</div><BusyButton variant="ghost" busy={refreshing} disabled={!phone || !reader || !online(state, phone.id) || !!pending} onClick={() => void run(() => command({ action: "REFRESH" }))}><RefreshCw data-icon="inline-start" />{t("Refresh")}</BusyButton></div>
          {snapshot?.history?.length ? <div className="records">{[...snapshot.history].sort((a, b) => b.timestampEpochMillis - a.timestampEpochMillis).map(entry => <div className="record-row" key={`${entry.timestampEpochMillis}-${entry.sequenceNumber ?? entry.notificationOperation}`}><History /><div className="record-main"><span>{t("{operation} notification", { operation: t(entry.notificationOperation.toLowerCase()) })} · {historyStatus(entry.status)}</span><div className="record-subtitle">{entry.profileName || entry.providerName || "eUICC"} · {localTime(entry.timestampEpochMillis)}</div><div className="record-subtitle identifier">{entry.endpointHost || entry.notificationAddress || t("No endpoint")}</div></div><div className="toolbar"><Button variant="ghost" size="icon" aria-label={t("Resend notification")} disabled={!!pending || !phone || !reader || !online(state, phone.id) || entry.action !== "SEND"} onClick={() => void run(() => command({ action: "RESEND_NOTIFICATION", history: entry }))}><RotateCcw /></Button><Button variant="ghost" size="icon" aria-label={t("Delete history entry")} className="text-destructive" disabled={!!pending || !phone || !reader || !online(state, phone.id)} onClick={() => ask({ actionLabel: t("Delete entry"), title: t("Delete this eUICC history entry?"), description: t("Remove the {operation} entry for {profile}? This cannot be undone.", { operation: t(entry.notificationOperation.toLowerCase()), profile: entry.profileName || entry.providerName || t("this eUICC") }), destructive: true, action: () => command({ action: "DELETE_HISTORY", history: entry, confirmed: true }) })}><Trash2 /></Button></div></div>)}</div> : <NoItems icon={History} title={t("No eUICC history")} description={t("Notification send and delete outcomes appear here after the phone processes them.")} />}
        </> : <NoItems icon={History} title={t("No profile access")} description={t("Grant profile access to a phone to view its eUICC history.")} />}
      </>}
      {page === "tools" && <>
        {phones.some(p => p.approved && p.profiles) ? <><div className="content-toolbar"><div className="toolbar picks">{phonePicker}{readers.length > 0 && <Choose value={reader?.id ?? ""} onChange={setReaderId} label={t("Reader")}>{readers.map(r => <SelectItem value={r.id} key={r.id}>{r.name}</SelectItem>)}</Choose>}</div><BusyButton variant="ghost" busy={refreshing} disabled={!phone || !reader || !online(state, phone.id) || !!pending} onClick={() => void run(() => command({ action: "REFRESH" }))}><RefreshCw data-icon="inline-start" />{t("Refresh")}</BusyButton></div>
          {!snapshot ? <NoItems icon={Settings2} title={t("Refresh a reader")} description={t("Choose a phone and reader to load eUICC tools.")} /> : <>
            <section className="tool-grid"><article className="tool-panel"><div className="tool-heading"><Info /><div><h2>{t("eUICC details")}</h2><p>{t("Hardware and provisioning information from the selected reader.")}</p></div></div>{snapshot.lpa.euiccInfo ? <dl className="detail-list">{Object.entries({ EID: snapshot.lpa.euiccInfo.eid, Product: snapshot.lpa.euiccInfo.productName, "SM-DP+": snapshot.lpa.euiccInfo.defaultSmdpAddress, "Root SM-DS": snapshot.lpa.euiccInfo.rootSmdsAddress, "Free memory": snapshot.lpa.euiccInfo.freeNonVolatileMemory ? t("{count} bytes", { count: localNumber(snapshot.lpa.euiccInfo.freeNonVolatileMemory) }) : t("Unknown"), Capabilities: snapshot.lpa.euiccInfo.rspCapabilities?.join(", ") }).filter(([, value]) => value).map(([key, value]) => <div key={key}><dt>{t(key)}</dt><dd className={key === "EID" ? "identifier" : undefined}>{value ?? ""}</dd></div>)}</dl> : <p className="page-note">{t("No eUICC identity was returned by this reader.")}</p>}</article>
              <article className="tool-panel"><div className="tool-heading"><SlidersHorizontal /><div><h2>{t("Provisioning tools")}</h2><p>{t("These operations run on the phone and may interrupt the active SIM.")}</p></div></div><div className="toolbar tool-actions"><Button variant="outline" disabled={!!pending || !online(state, phone.id)} onClick={() => void run(() => command({ action: "DISCOVER", text: snapshot.lpa.euiccInfo?.defaultSmdpAddress || undefined }))}>{t("Discover profiles")}</Button><Button variant="outline" disabled={!!pending || !online(state, phone.id)} onClick={() => showForm({ type: "download" })}>{t("Set up download")}</Button></div><p className="page-note">{t("Bluetooth, USB, NBridge and remote-reader permissions still need to be enabled in HyperLPA on the phone.")}</p></article></section>
            <section className="subsection"><div className="content-toolbar"><div className="tool-heading"><Settings2 /><div><h2>{t("Phone settings")}</h2><p>{t("Safe, non-secret settings mirrored from the native app.")}</p></div></div></div><div className="records">{Object.entries(snapshot.settings ?? defaultRemoteSettings).map(([key, enabled]) => <label className="setting-row" key={key}><span>{t(key.replaceAll(/([A-Z])/g, " $1").replace(/^./, value => value.toUpperCase()))}</span><Checkbox checked={Boolean(enabled)} disabled={!phone || !online(state, phone.id) || !!pending} onCheckedChange={checked => { const settings = { ...(snapshot.settings ?? defaultRemoteSettings), [key]: checked === true }; void run(() => command({ action: "SETTINGS", settings })); }} /></label>)}</div></section>
            <section className="subsection"><h2>{t("Destructive tools")}</h2><div className="records"><div className="record-row"><RotateCcw /><div className="record-main"><span>{t("Reset eUICC memory")}</span><div className="record-subtitle">{t("Removes profiles and card data. Use the native app if the phone is offline.")}</div></div><Button variant="destructive" disabled={!!pending || !online(state, phone.id)} onClick={() => ask({ actionLabel: t("Reset eUICC"), title: t("Reset this eUICC?"), description: t("This permanently removes installed profiles and card data. Make sure you have another way to connect the phone."), destructive: true, action: () => command({ action: "RESET", confirmed: true }) })}>{t("Reset")}</Button></div></div></section>
          </>}
        </> : <NoItems icon={Settings2} title={t("No profile access")} description={t("Grant profile access to a phone to use its eUICC tools.")} />}
      </>}
      {page === "group" && <>
        <div className="content-toolbar"><h2>{t("Devices and browsers")}</h2>{owner && identity.peers.length > 0 && <Button variant="ghost" onClick={() => setManage(!manage)}>{manage ? t("Done") : t("Manage")}</Button>}</div>
        <div className="records"><div className="record-row"><Laptop /><div className="record-main"><span>{identity.name}</span><div className="record-subtitle">{t("This browser")} · {owner ? t("Owner") : t("Controller")}</div></div><ShieldCheck /></div>
          {identity.peers.map(peer => <div className="record-row" key={peer.id}>{peer.kind === "phone" ? <Smartphone /> : <Laptop />}<div className="record-main"><span>{peer.name}</span><div className="record-subtitle">{peer.kind === "owner" ? t("Owner") : peer.kind === "phone" ? t("Phone") : t("Browser")} · {peer.approved ? online(state, peer.id) ? t("Online") : t("Offline") : t("Awaiting approval")}
            {owner && peer.kind === "phone" && peer.approved && state.phoneRevisions[peer.id] !== identity.revision && <> · {t("Group update pending")}</>}</div></div>
            {owner && peer.kind === "browser" && !peer.approved && <BusyButton busy={busy} disabled={!state.connected} onClick={() => { setBusy(true); void run(() => controller.approveBrowser(peer.id)).finally(() => setBusy(false)); }}>{t("Approve")}</BusyButton>}
            {owner && manage && <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t("Manage {device}", { device: peer.name })}><Ellipsis /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem onSelect={() => showForm({ type: "rename", peer })}>{t("Rename")}</DropdownMenuItem><DropdownMenuItem variant="destructive" onSelect={() => ask({ actionLabel: t("Remove device"), title: t("Remove {device}?", { device: peer.name }), description: t("Blocks this device on the relay immediately. Phones apply the membership removal when the owner and phone reconnect. Data already copied elsewhere cannot be erased."), destructive: true, action: () => controller.revoke(peer.id) })}>{t("Remove device")}</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu>}
          </div>)}</div>
        {owner && browsers.some(p => p.approved) && phones.some(p => p.approved) && <section className="subsection"><div className="content-toolbar"><h2>{t("Browser permissions")}</h2><Choose value={browserId || browsers.find(p => p.approved)?.id || ""} onChange={setBrowserId} label={t("Browser")}>{browsers.filter(p => p.approved).map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</Choose></div>
          <div className="records">{phones.filter(p => p.approved).map(p => {
            const browser = browserId || browsers.find(b => b.approved)?.id || "";
            const grant = identity.grants[p.id]?.[browser];
            return <div className="permission-row" key={p.id}><span>{p.name}</span><div className="permission-options">
              <label><Checkbox aria-label={t("Manage profiles on {phone}", { phone: p.name })} checked={grant?.profiles ?? false} disabled={busy || !state.connected} onCheckedChange={checked => { setBusy(true); void run(() => controller.permission(browser, p.id, checked === true, grant?.notifications ?? false)).finally(() => setBusy(false)); }} />{t("Profiles")}</label>
              <label><Checkbox aria-label={t("View notifications on {phone}", { phone: p.name })} checked={grant?.notifications ?? false} disabled={busy || !state.connected} onCheckedChange={checked => { setBusy(true); void run(() => controller.permission(browser, p.id, grant?.profiles ?? false, checked === true)).finally(() => setBusy(false)); }} />{t("Notifications")}</label>
            </div></div>;
          })}</div><p className="page-note">{t("Notification access also requires sharing permission on each phone. The owner must be online to apply group changes.")}</p></section>}
        <section className="subsection"><h2>{t("Relay")}</h2><p className="relay-address">{identity.relay}<CopyButton value={identity.relay} label={t("Copy relay address")} /></p><p className="page-note">{t("Keys are kept in this browser. Save a recovery export before clearing site storage.")}</p></section>
      </>}
      {page === "activity" && (state.activities.length || identity.pending.length ? <div className="records">{identity.pending.filter(p => !state.activities.some(a => a.id === p.id)).map(p => <div className="record-row" key={p.id}><RefreshCw /><div className="record-main"><span>{t(commandLabel(p.action))} · {t("Result pending")}</span><div className="record-subtitle">{t("Recovered after reconnect")}</div></div><Button variant="outline" onClick={() => void run(() => controller.queryPending(p.id))}>{t("Check status")}</Button></div>)}
        {state.activities.map(a => <div className="record-row activity-row" key={a.id}><div className="record-main"><span>{t(a.label)}</span><div className="record-subtitle">{identity.peers.find(p => p.id === a.peer)?.name ?? t("Device")} · {localTime(a.time)}</div>{a.detail && <p className="activity-detail">{t(a.detail)}</p>}</div><span className={cn("activity-status", ["Failed", "Outcome unverified", "Rejected by relay"].includes(a.status) && "text-destructive")}>{t(a.status)}</span></div>)}</div> : <NoItems icon={RefreshCw} title={t("No activity yet")} description={t("Device requests and their results appear here during this browser session.")} />)}
    </TabsContent></Tabs></>}
  </main>
    <FormModal key={formGeneration} returnFocus={formOpener.current} form={form} open={formOpen} onClose={() => setFormOpen(false)} identity={identity} command={command} phoneId={phone?.id} readerId={reader?.id} eid={snapshot?.lpa.euiccInfo?.eid ?? reader?.eid} />
    <Modal returnFocus={confirmOpener.current} open={confirmOpen} onClose={() => { if (!busy) setConfirmOpen(false); }} title={confirm?.title ?? t("Confirm")} description={confirm?.description} footer={<><Button variant="outline" disabled={busy} onClick={() => setConfirmOpen(false)}>{t("Cancel")}</Button><BusyButton variant={confirm?.destructive ? "destructive" : "default"} busy={busy} onClick={async () => {
      if (!confirm) return; setBusy(true);
      try { await confirm.action(); setConfirmOpen(false); } catch (error) { toast.error(errorMessage(error)); } finally { setBusy(false); }
    }}>{confirm?.actionLabel ?? t("Continue")}</BusyButton></>} />
    <Toaster position="bottom-center" closeButton richColors={false} containerAriaLabel={t("Notifications")} toastOptions={{
      closeButtonAriaLabel: t("Close"),
      className: "app-toast", style: { fontFamily: "var(--font-sans)", background: "var(--card)", color: "var(--card-foreground)",
        borderColor: "var(--border)", borderRadius: "13px", boxShadow: "var(--surface-shadow)" },
    }} />
  </>;
}

function FormModal({ form, open, onClose, identity, command, returnFocus, phoneId, readerId, eid }: { returnFocus: HTMLElement | null; form?: FormDialog; open: boolean; onClose: () => void; identity?: Identity; command: (value: Command) => Promise<unknown>; phoneId?: string; readerId?: string; eid?: string }) {
  const [value, setValue] = useState(form?.type === "tags" ? form.profile?.tags?.join(", ") ?? "" : form?.peer?.name ?? form?.profile?.nickname ?? "");
  const [confirmationCode, setConfirmationCode] = useState("");
  const [code, setCode] = useState(""); const [recoveryKey, setRecoveryKey] = useState(""); const [batchCodes, setBatchCodes] = useState(""); const [batchProgress, setBatchProgress] = useState(0);
  const [busy, setBusy] = useState(false); const errors = useFormErrors();
  const { error } = errors;
  const file = useRef<string>("");
  const titles = { phone: t("Add a phone"), invite: t("Invite a browser"), recovery: t("Recovery export"), download: t("Download a profile"), batch: t("Batch downloads"), profile: t("Profile details"), rename: form?.peer ? t("Rename device") : t("Rename profile"), tags: t("Edit profile tags") };
  const descriptions = { phone: t("On the phone, create a pairing code in Settings → Remote devices. Approve this browser as group owner after adding it."),
    invite: t("Share this code privately. It expires after ten minutes and can be used once. You will approve the browser before granting access."),
    recovery: t("The encrypted export restores this device identity. Keep the recovery key separately, preferably in your password manager."),
    download: t("Paste the activation code supplied by your carrier. The phone will show a preview before installation."), batch: t("One activation code per line. Each profile is previewed and confirmed in order."), profile: t("Identifiers and policy data returned by the selected reader."), rename: "", tags: t("Separate tags with commas.") };
  async function submit() {
    if (!form || !identity) return;
    setBusy(true); errors.clear();
    try {
      if (form.type === "phone") { await validateField("phone-pair-code", () => {
        try { return parseInvitation(value.trim()); } catch { throw new Error("Paste a current phone pairing code, or create a new code on the phone."); }
      }); await controller.addPhone(value); toast.success(t("Approve this browser on the phone.")); onClose(); }
      if (form.type === "invite") setCode(await controller.inviteBrowser());
      if (form.type === "recovery") {
        const backup = await exportRecovery(identity); file.current = backup.file; setRecoveryKey(backup.recoveryKey);
      }
      if (form.type === "rename") {
        await validateField("name", () => { if (!value.trim()) throw new Error("Enter a name."); });
        if (form.peer) await controller.rename(form.peer.id, value);
        else await command({ action: "RENAME", iccid: form.profile!.iccid, text: value });
        onClose();
      }
      if (form.type === "tags") {
        const tags = value.split(",").map(s => s.trim()).filter(Boolean);
        await validateField("tags", () => { if (tags.length > 32 || tags.some(s => s.length > 80)) throw new Error("Use up to 32 tags, each up to 80 characters."); });
        await command({ action: "TAGS", iccid: form.profile!.iccid, tags }); onClose();
      }
      if (form.type === "download") {
        const fields = await validateField("activation-code", () => {
          const fields = value.trim().replace(/^LPA:/i, "").split("$");
          if (fields[0] !== "1" || !fields[1] || fields.length > 5) throw new Error("Enter an activation code beginning with LPA:1$.");
          let server: URL;
          try { server = new URL("https://" + fields[1]); } catch { throw new Error("Check the server address in your carrier activation code."); }
          if (server.username || server.password || server.pathname !== "/" || server.search || server.hash) throw new Error("The activation code has an invalid server address.");
          return fields;
        });
        await command({ action: "DOWNLOAD", confirmBeforeInstall: true, download: { smdpAddress: fields[1], matchingId: fields[2] || null,
          smdpOid: fields[3] || null, confirmationCodeRequired: fields[4] === "1", confirmationCode: confirmationCode.trim() || null } }); onClose();
      }
      if (form.type === "batch") {
        if (!identity || !phoneId || !readerId || !eid) throw new Error("Choose an online phone and reader first.");
        const requests = await validateField("batch-codes", () => {
          if (!batchCodes.trim()) throw new Error("Enter at least one activation code.");
          return batchCodes.split(/\r?\n/).map(line => line.trim()).filter(Boolean).map(line => {
            const [activation, confirmation] = line.split("|", 2).map(part => part.trim());
            const fields = activation.replace(/^LPA:/i, "").split("$");
            if (fields[0] !== "1" || !fields[1] || fields.length > 5) throw new Error("Every line must be an activation code beginning with LPA:1$.");
            if (fields[4] === "1" && !confirmation) throw new Error("A required confirmation code follows the activation code after |.");
            return { action: "DOWNLOAD", download: { smdpAddress: fields[1], matchingId: fields[2] || null, smdpOid: fields[3] || null, confirmationCodeRequired: fields[4] === "1", confirmationCode: confirmation || null } } satisfies Command;
          });
        });
        setBatchProgress(0);
        await controller.batchDownload(phoneId, readerId, eid, requests, setBatchProgress);
        toast.success(t(requests.length === 1 ? "{count} profile installed" : "{count} profiles installed", { count: localNumber(requests.length) })); onClose();
      }
    } catch (error) { errors.report(error); } finally { setBusy(false); }
  }
  const generated = !!code || !!recoveryKey;
  return <Modal returnFocus={returnFocus} open={open} onClose={() => { if (!busy) onClose(); }} title={form ? titles[form.type] : t("Device")} description={form ? descriptions[form.type] : undefined}
    footer={<><Button variant="outline" onClick={onClose} disabled={busy}>{generated ? t("Done") : t("Cancel")}</Button>
      {form?.type === "profile" ? <Button onClick={onClose}>{t("Done")}</Button> : form?.type === "recovery" && recoveryKey ? <Button onClick={() => {
        const url = URL.createObjectURL(new Blob([file.current], { type: "application/json" }));
        const a = document.createElement("a"); a.href = url; a.download = "hyperlpa-recovery.hyperlpa"; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      }}><Download data-icon="inline-start" />{t("Download export")}</Button> : !generated && <BusyButton type="submit" form="dialog-form" busy={busy}>{form?.type === "invite" ? t("Create invitation") : form?.type === "recovery" ? t("Generate export") : form?.type === "phone" ? t("Connect phone") : form?.type === "download" ? t("Get preview") : form?.type === "batch" ? t("Start batch") : t("Save")}</BusyButton>}</>}>
    <form id="dialog-form" onChangeCapture={errors.clear} onSubmit={e => { e.preventDefault(); void submit(); }}><FieldGroup>
      {form?.type === "phone" && <Field><FieldLabel htmlFor="phone-pair-code">{t("Phone pairing code")}</FieldLabel><Textarea {...errors.props("phone-pair-code")} id="phone-pair-code" value={value} onChange={e => setValue(e.target.value)} maxLength={4096} required spellCheck={false} autoComplete="off" placeholder="hyperlpa-pair:…" />{errors.message("phone-pair-code")}</Field>}
      {form?.type === "download" && <><TextField {...errors.props("activation-code")} label={t("Activation code")} value={value} onChange={e => setValue(e.target.value)} placeholder="LPA:1$…" maxLength={4600} required autoComplete="off" spellCheck={false}>{errors.message("activation-code")}</TextField>
        <TextField {...errors.props("confirmation-code")} label={t("Confirmation code")} value={confirmationCode} onChange={e => setConfirmationCode(e.target.value)} autoComplete="off" type="password" maxLength={64} hint={t("Only if required by your carrier.")}>{errors.message("confirmation-code")}</TextField></>}
      {form?.type === "batch" && <><Field><FieldLabel htmlFor="batch-codes">{t("Activation codes")}</FieldLabel><Textarea {...errors.props("batch-codes")} id="batch-codes" value={batchCodes} onChange={e => setBatchCodes(e.target.value)} placeholder="LPA:1$…" maxLength={100000} rows={8} required spellCheck={false} />{errors.message("batch-codes")}<FieldDescription>{t("Progress: {count}. The browser sends the next code only after the phone reports the previous one completed.", { count: localNumber(batchProgress) })}</FieldDescription></Field></>}
      {form?.type === "profile" && form.profile && <dl className="detail-list">{Object.entries({ Name: profileName(form.profile), Provider: form.profile.providerName, ICCID: form.profile.iccid, State: form.profile.state, Class: form.profile.profileClass, "SM-DP+": form.profile.smdpAddress, "ISD-P AID": form.profile.isdPAid, MCC: form.profile.mcc, MNC: form.profile.mnc, "Profile policy": form.profile.profilePolicyRules?.join(", "), Tags: form.profile.tags?.join(", "), Reminder: form.profile.reminderAt ? localTime(Date.parse(form.profile.reminderAt)) : undefined }).filter(([, item]) => item).map(([key, item]) => <div key={key}><dt>{t(key)}</dt><dd className={key.includes("ICCID") || key.includes("AID") ? "identifier" : undefined}>{key === "State" || key === "Class" ? t(String(item)) : item}</dd></div>)}</dl>}
      {form?.type === "rename" && <TextField {...errors.props("name")} label={t("Name")} value={value} onChange={e => setValue(e.target.value)} maxLength={80} required autoFocus>{errors.message("name")}</TextField>}
      {form?.type === "tags" && <TextField {...errors.props("tags")} label={t("Tags")} value={value} onChange={e => setValue(e.target.value)} maxLength={2592} autoFocus>{errors.message("tags")}</TextField>}
      {code && <Field><FieldLabel htmlFor="invitation-code">{t("Invitation code")}</FieldLabel><div className="copy-field"><Textarea id="invitation-code" value={code} readOnly spellCheck={false} /><CopyButton value={code} label={t("Copy invitation code")} /></div></Field>}
      {recoveryKey && <Field><FieldLabel htmlFor="recovery-key">{t("Recovery key")}</FieldLabel><div className="copy-field"><Input id="recovery-key" value={recoveryKey} readOnly /><CopyButton value={recoveryKey} label={t("Copy recovery key")} /></div><FieldDescription>{t("Save this key before downloading the export. Anyone with both can control this device identity.")}</FieldDescription></Field>}
    </FieldGroup></form>
    {error && !errors.field && <Alert variant="destructive" role="alert"><AlertTitle>{t("Could not complete the action")}</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
  </Modal>;
}
