import { appEnv } from "../config/env";

/**
 * Лёгкий клиентский логгер: события копятся в памяти и уходят на оркестратор одной пачкой
 * (POST /api/client-log → logs/<сессия>/frontend/client.log). Каждое событие несёт время
 * браузера с точностью до миллисекунды. Тела запросов, картинки и кадры не логируются.
 */

type Level = "info" | "warn" | "error";
type Fields = Record<string, string | number | boolean | null | undefined>;

const ENDPOINT = `${appEnv.apiRequestBaseUrl.replace(/\/+$/, "")}/api/client-log`;
const FLUSH_INTERVAL_MS = 2000;
const FLUSH_BATCH_SIZE = 50;
const MAX_BUFFERED_EVENTS = 1000;
const MAX_STRING_LENGTH = 300;

let buffer: string[] = [];
let flushTimerId: number | null = null;
let installed = false;

export function clientLog(event: string, fields: Fields = {}, level: Level = "info") {
  const entry: Record<string, unknown> = { t: new Date().toISOString(), lvl: level, ev: event };

  for (const [key, value] of Object.entries(fields)) {
    if (value === undefined) {
      continue;
    }
    entry[key] = typeof value === "string" && value.length > MAX_STRING_LENGTH ? `${value.slice(0, MAX_STRING_LENGTH)}…` : value;
  }

  if (buffer.length >= MAX_BUFFERED_EVENTS) {
    buffer.shift();
  }
  buffer.push(JSON.stringify(entry));

  if (buffer.length >= FLUSH_BATCH_SIZE) {
    flush();
  } else if (flushTimerId === null) {
    flushTimerId = window.setTimeout(flush, FLUSH_INTERVAL_MS);
  }
}

/** Миллисекунды с начала измерения; для `ms=` в событиях. */
export function elapsedMs(startedAt: number) {
  return Math.round((performance.now() - startedAt) * 10) / 10;
}

function flush(useBeacon = false) {
  if (flushTimerId !== null) {
    window.clearTimeout(flushTimerId);
    flushTimerId = null;
  }

  if (buffer.length === 0) {
    return;
  }

  const body = `{"events":[${buffer.join(",")}]}`;
  buffer = [];

  try {
    // text/plain — «простой» запрос без CORS preflight.
    if (useBeacon && navigator.sendBeacon) {
      navigator.sendBeacon(ENDPOINT, new Blob([body], { type: "text/plain" }));
      return;
    }

    void fetch(ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body,
      keepalive: true,
    }).catch(() => undefined);
  } catch {
    // логирование не должно ломать приложение
  }
}

/** Глобальные ошибки, жизненный цикл страницы. Вызывать один раз при старте. */
export function installClientLogging() {
  if (installed) {
    return;
  }
  installed = true;

  clientLog("app_start", { url: location.pathname, ua: navigator.userAgent });

  window.addEventListener("error", (event) => {
    clientLog(
      "window_error",
      { message: event.message, source: event.filename, line: event.lineno, col: event.colno },
      "error",
    );
  });

  window.addEventListener("unhandledrejection", (event) => {
    const reason = event.reason;
    clientLog("unhandled_rejection", { message: reason instanceof Error ? reason.message : String(reason) }, "error");
  });

  window.addEventListener("pagehide", () => {
    clientLog("page_hide");
    flush(true);
  });

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      flush(true);
    }
  });
}
