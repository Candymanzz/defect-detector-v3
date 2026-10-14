import { afterEach, describe, expect, it, vi } from "vitest";
import { OrchestratorWebSocketClient } from "./clientWebSocket";

class FakeSocket extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = FakeSocket.CONNECTING;
  constructor() {
    super();
    FakeSocket.instances.push(this);
  }
  close() { this.readyState = 3; }
  open() { this.readyState = FakeSocket.OPEN; this.dispatchEvent(new Event("open")); }
  message() {
    this.dispatchEvent(new MessageEvent("message", {
      data: JSON.stringify({ type: "server.light_brightness_ack", payload: { brightness_percent: 57 } }),
    }));
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  FakeSocket.instances = [];
});

describe("WebSocket session isolation", () => {
  it("ignores late brightness, status and close events from a previous connection", () => {
    vi.useFakeTimers();
    vi.stubGlobal("window", { setTimeout, clearTimeout });
    vi.stubGlobal("WebSocket", FakeSocket);
    const client = new OrchestratorWebSocketClient("ws://localhost/");
    const received = vi.fn();
    client.onMessage(received);
    client.connect();
    const oldSocket = FakeSocket.instances[0];
    oldSocket.open();
    client.disconnect();
    client.connect();
    const currentSocket = FakeSocket.instances[1];
    currentSocket.open();

    oldSocket.message();
    oldSocket.dispatchEvent(new Event("error"));
    oldSocket.dispatchEvent(new Event("close"));
    oldSocket.open();
    expect(received).not.toHaveBeenCalled();
    expect(client.snapshot.state).toBe("open");
    expect(client.isOpen).toBe(true);
    vi.runAllTimers();
    expect(FakeSocket.instances).toHaveLength(2);

    currentSocket.message();
    expect(received).toHaveBeenCalledTimes(1);
    client.disconnect();
  });
});
