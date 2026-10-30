import type { Envelope, Identity } from "./types";
export class RelayError extends Error {
  constructor(public code: string, public status: number) { super(code.replaceAll("_", " ")); }
}
export async function post<T = Record<string, unknown>>(identity: Pick<Identity, "id" | "token" | "relay">,
  path: string, body: unknown = {}, enrollment?: string): Promise<T> {
  const response = await fetch(identity.relay + path, { method: "POST", cache: "no-store", credentials: "omit",
    headers: { "Content-Type": "application/json", "X-Device-Id": identity.id, Authorization: `Bearer ${identity.token}`,
      ...(enrollment ? { "X-Enrollment-Key": enrollment } : {}) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(20_000) });
  if (Number(response.headers.get("content-length")) > 2_100_000) throw new Error("Relay response is too large.");
  const raw = await response.text();
  if (raw.length > 2_100_000) throw new Error("Relay response is too large.");
  let value: Record<string, unknown>;
  try { value = JSON.parse(raw); } catch { throw new Error("The relay returned an invalid response."); }
  if (!response.ok) throw new RelayError(String(value.error ?? "relay_error"), response.status);
  return value as T;
}
export class RelayConnection {
  private socket?: WebSocket;
  private retry?: ReturnType<typeof setTimeout>;
  private heartbeat?: ReturnType<typeof setInterval>;
  private generation = 0;
  private failures = 0;
  private stopped = true;
  private signals = false;
  constructor(private identity: () => Identity, private onEnvelope: (e: Envelope) => Promise<void>,
    private onState: (connected: boolean) => void, private onError: (message: string) => void,
    private onRejected: (id: string, code: string) => void) {}
  start() { this.stop(); this.stopped = false; void this.connect(this.generation); }
  stop() {
    this.stopped = true; this.signals = false; this.generation++; clearTimeout(this.retry); clearInterval(this.heartbeat);
    this.socket?.close(1000); this.socket = undefined; this.onState(false);
  }
  private async connect(generation: number) {
    if (this.stopped || generation !== this.generation) return;
    try {
      const identity = this.identity();
      const { ticket } = await post<{ ticket: string }>(identity, "/v1/session");
      if (this.stopped || generation !== this.generation) return;
      const url = new URL(identity.relay.replace("https:", "wss:") + "/v1/socket");
      url.searchParams.set("ticket", ticket);
      const socket = this.socket = new WebSocket(url);
      // Process messages in arrival order, including asynchronous authentication and durable writes.
      let receiving = Promise.resolve();
      socket.onmessage = event => {
        if (socket !== this.socket || typeof event.data !== "string" || event.data.length > 1_500_000) return;
        if (event.data === "pong") return;
        receiving = receiving.then(async () => {
          const frame = JSON.parse(event.data);
          if (frame.type === "ready") {
            this.signals = frame.signals === true;
            this.failures = 0; this.onState(true);
            this.heartbeat = setInterval(() => { if (socket.readyState === WebSocket.OPEN) socket.send("ping"); }, 25_000);
          } else if (frame.type === "message") await this.onEnvelope(frame.message);
          else if (frame.type === "error") this.onRejected(frame.id, frame.code);
        }).catch(() => { /* Malformed or unauthenticated frames never reach application handlers. */ });
      };
      socket.onclose = event => {
        if (socket !== this.socket) return;
        clearInterval(this.heartbeat); this.onState(false);
        if (event.code === 4001 || event.code === 4003 || event.code === 1008) {
          this.onError(event.code === 4001 ? "This browser identity is open in another window. Close it, then reconnect."
            : "This device was removed or its relay access expired. Restore a valid identity or enroll again.");
          this.stopped = true; return;
        }
        this.schedule(generation);
      };
      socket.onerror = () => this.onError("Could not connect to the relay. Check its address and allowed web origins.");
    } catch (error) {
      if (this.stopped || generation !== this.generation) return;
      this.onState(false);
      this.onError(error instanceof RelayError && [401, 403].includes(error.status)
        ? "Relay access was denied. This browser may have been removed."
        : "Cannot reach the relay. Check your connection and the relay's allowed web origins.");
      if (!(error instanceof RelayError && [401, 403].includes(error.status))) this.schedule(generation);
    }
  }
  private schedule(generation: number) {
    if (this.stopped || generation !== this.generation) return;
    const delay = Math.min(30_000, 1000 * 2 ** this.failures++) + Math.random() * 500;
    this.retry = setTimeout(() => void this.connect(generation), delay);
  }
  send(e: Envelope, ephemeral = false) {
    if (this.socket?.readyState !== WebSocket.OPEN) throw new Error("The relay is disconnected. Reconnect before sending a command.");
    this.socket.send(JSON.stringify({ type: ephemeral && this.signals ? "signal" : "send", message: e }));
  }
  ack(id: string) { if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: "ack", id })); }
}
