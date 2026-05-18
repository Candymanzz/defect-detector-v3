type AppEnv = {
  apiBaseUrl: string;
  apiRequestBaseUrl: string;
  wsUrl: string;
  mode: string;
  isDev: boolean;
  isProd: boolean;
};

const DEFAULT_API_BASE_URL = "http://127.0.0.1:8099";
const DEFAULT_WS_URL = "ws://127.0.0.1:8765/";
const configuredApiBaseUrl = normalizeUrl(
  readEnv(import.meta.env.VITE_API_BASE_URL, DEFAULT_API_BASE_URL),
  ["http:", "https:"],
).replace(/\/+$/, "");

export const appEnv: AppEnv = Object.freeze({
  apiBaseUrl: configuredApiBaseUrl,
  apiRequestBaseUrl: resolveApiRequestBaseUrl(configuredApiBaseUrl),
  wsUrl: normalizeUrl(readEnv(import.meta.env.VITE_WS_URL, DEFAULT_WS_URL), ["ws:", "wss:"]),
  mode: import.meta.env.MODE,
  isDev: import.meta.env.DEV,
  isProd: import.meta.env.PROD,
});

function readEnv(value: string | undefined, fallback: string) {
  return value && value.trim() ? value : fallback;
}

function resolveApiRequestBaseUrl(apiBaseUrl: string) {
  if (import.meta.env.DEV && globalThis.location?.origin) {
    return globalThis.location.origin;
  }

  return apiBaseUrl;
}

function normalizeUrl(value: string, allowedProtocols: string[]) {
  const trimmed = value.trim();

  if (!trimmed) {
    throw new Error("Environment URL cannot be empty");
  }

  const url = new URL(trimmed);

  if (!allowedProtocols.includes(url.protocol)) {
    throw new Error(`Unsupported URL protocol: ${url.protocol}`);
  }

  return url.toString();
}
