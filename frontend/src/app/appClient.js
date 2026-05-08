import { writeDesktopLog, normalizeApiBaseUrl } from "../shared/lib/lib";
import { DEFAULT_REQUEST_TIMEOUT_MS } from "./constants";

export const createAppClient = (apiBaseUrl) => {
  const apiPath = (path) => {
    const normalizedBaseUrl = normalizeApiBaseUrl(apiBaseUrl);
    if (!normalizedBaseUrl) {
      throw new Error("Backend API URL is not configured");
    }
    return `${normalizedBaseUrl}${path}`;
  };
  const logDesktop = (level, event, fields = {}) => {
    writeDesktopLog({
      level,
      event,
      apiBaseUrl: normalizeApiBaseUrl(apiBaseUrl),
      ...fields
    });
  };
  const apiFetch = async (path, options = {}, logOptions = {}) => {
    const {
      timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
      logHttpError = true,
      expectedStatuses = [],
      event = "backend.request"
    } = logOptions;
    const url = apiPath(path);
    const method = options.method || "GET";
    const controller = new AbortController();
    const startedAt = performance.now();
    const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(url, {
        ...options,
        signal: controller.signal
      });
      const durationMs = Math.round(performance.now() - startedAt);
      const isExpectedStatus = expectedStatuses.includes(response.status);

      if (!response.ok && logHttpError && !isExpectedStatus) {
        logDesktop("error", `${event}.failed`, {
          method,
          path,
          status: response.status,
          statusText: response.statusText,
          duration_ms: durationMs
        });

        if ([502, 503, 504].includes(response.status)) {
          logDesktop("error", "backend.unavailable", {
            method,
            path,
            status: response.status,
            duration_ms: durationMs
          });
        }
      }

      return response;
    } catch (error) {
      const durationMs = Math.round(performance.now() - startedAt);
      const isTimeout = error?.name === "AbortError";

      logDesktop("error", isTimeout ? "backend.request.timeout" : "backend.request.error", {
        method,
        path,
        duration_ms: durationMs,
        timeout_ms: timeoutMs,
        error: error?.message || String(error)
      });

      if (!isTimeout) {
        logDesktop("error", "backend.unavailable", {
          method,
          path,
          duration_ms: durationMs,
          error: error?.message || String(error)
        });
      }

      throw error;
    } finally {
      window.clearTimeout(timeoutId);
    }
  };
  return {
    apiPath,
    logDesktop,
    apiFetch
  };
};
