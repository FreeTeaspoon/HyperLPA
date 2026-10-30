import { afterEach, describe, expect, it, vi } from "vitest";
import { RelayConnection } from "./relay";
import type { Envelope, Identity } from "./types";

class Socket {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = 1;
  frames: string[] = [];
  onmessage?: (event: { data: string }) => void;
  close() {}
  send(raw: string) { this.frames.push(raw); }
  constructor(_url: URL) { Socket.instances.push(this); }
}
let connection: RelayConnection | undefined;
afterEach(() => { connection?.stop(); vi.unstubAllGlobals(); Socket.instances = []; });

describe("presence transport negotiation", () => {
  it.each([true, false])("keeps commands durable with signal capability %s", async signals => {
    vi.stubGlobal("WebSocket", Socket);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ticket: "test-ticket" })));
    const connected = vi.fn();
    connection = new RelayConnection(() => ({ id: "browser", token: "token", relay: "https://relay.test" }) as Identity,
      async () => {}, connected, vi.fn(), vi.fn());
    connection.start();
    await vi.waitFor(() => expect(Socket.instances).toHaveLength(1));
    const socket = Socket.instances[0];
    socket.onmessage!({ data: JSON.stringify({ type: "ready", ...(signals ? { signals: true } : {}) }) });
    await vi.waitFor(() => expect(connected).toHaveBeenLastCalledWith(true));
    const envelope = { id: "encrypted-message" } as Envelope;
    connection.send(envelope, true);
    connection.send(envelope);
    expect(socket.frames.map(raw => JSON.parse(raw).type)).toEqual([signals ? "signal" : "send", "send"]);
  });
});
