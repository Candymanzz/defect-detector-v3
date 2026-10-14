import { appEnv } from "./config/env";

type Entry = { channel: string; event: string; details: Record<string, unknown>; recorded_at: string };
const pending: Entry[] = [];
let scheduled = false;

export function logDiagnostic(channel: string, event: string, details: Record<string, unknown> = {}) {
  if (typeof window === "undefined" || typeof window.addEventListener !== "function") return;
  const entry = { channel, event, details, recorded_at: new Date().toISOString() };
  if (window.electronAPI?.logDiagnostic) {
    window.electronAPI.logDiagnostic(entry);
    return;
  }
  if (pending.length >= 512) return;
  pending.push(entry);
  if (!scheduled) { scheduled = true; window.setTimeout(flush, 500); }
}

function flush() {
  if (typeof window === "undefined") { pending.length = 0; scheduled = false; return; }
  const batch = pending.splice(0, 20);
  void fetch(appEnv.apiBaseUrl + "/diagnostics/frontend", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify(batch), keepalive: true, signal: AbortSignal.timeout(3000),
  }).catch(() => {}).finally(() => {
    scheduled = false;
    if (pending.length && typeof window !== "undefined") { scheduled = true; window.setTimeout(flush, 500); }
  });
}

if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
  window.addEventListener("error", event => {
    logDiagnostic("errors", "renderer-error", { message: event.message, file: event.filename, line: event.lineno });
  });
  window.addEventListener("unhandledrejection", event => {
    logDiagnostic("errors", "renderer-rejection", { message: String(event.reason) });
  });
}
