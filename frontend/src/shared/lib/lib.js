export const toDataUrl = (base64) => (base64 ? `data:image/png;base64,${base64}` : "");

export const formatDuration = (durationMs) =>
  durationMs < 1000 ? `${Math.round(durationMs)} ms` : `${(durationMs / 1000).toFixed(2)} s`;

export const normalizeApiBaseUrl = (value) => {
  const trimmed = typeof value === "string" ? value.trim() : "";
  return trimmed ? trimmed.replace(/\/+$/, "") : "";
};

export const getDesktopBridge = () =>
  typeof window !== "undefined" && window.defectDetectorDesktop
    ? window.defectDetectorDesktop
    : null;

export const writeDesktopLog = (entry) => {
  const desktopBridge = getDesktopBridge();
  if (!desktopBridge?.writeLog) return;
  desktopBridge.writeLog(entry).catch(() => {
    // Logging must never break the operator workflow.
  });
};

export const readLocalStorage = (key) => {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
};

export const writeLocalStorage = (key, value) => {
  try {
    window.localStorage.setItem(key, String(value));
  } catch {
    // Local persistence is best-effort only.
  }
};

export const getErrorMessage = async (response, fallbackMessage) => {
  try {
    const payload = await response.json();
    if (payload?.detail) return String(payload.detail);
  } catch {
    // Ignore malformed error body and return fallback below.
  }
  return fallbackMessage;
};
