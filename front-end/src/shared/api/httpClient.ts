import { clientLog, elapsedMs } from "../lib/clientLogger";

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export type RequestOptions = {
  method?: HttpMethod;
  query?: Record<string, string | number | boolean | null | undefined>;
  body?: unknown;
  headers?: HeadersInit;
  signal?: AbortSignal;
  timeoutMs?: number;
};

export class HttpError extends Error {
  readonly status: number;
  readonly statusText: string;
  readonly responseBody: string;

  constructor(response: Response, responseBody: string) {
    super(`HTTP ${response.status} ${response.statusText}`);
    this.name = "HttpError";
    this.status = response.status;
    this.statusText = response.statusText;
    this.responseBody = responseBody;
  }
}

export class HttpClient {
  private readonly normalizedBaseUrl: string;

  constructor(baseUrl: string) {
    this.normalizedBaseUrl = baseUrl.replace(/\/+$/, "");
  }

  url(path: string, query?: RequestOptions["query"]) {
    const url = new URL(normalizePath(path), `${this.normalizedBaseUrl}/`);

    if (query) {
      for (const [key, value] of Object.entries(query)) {
        if (value !== null && value !== undefined) {
          url.searchParams.set(key, String(value));
        }
      }
    }

    return url.toString();
  }

  async text(path: string, options: RequestOptions = {}) {
    const response = await this.fetch(path, options);
    return response.text();
  }

  async json<T>(path: string, options: RequestOptions = {}) {
    const response = await this.fetch(path, options);

    if (response.status === 204) {
      return undefined as T;
    }

    return response.json() as Promise<T>;
  }

  async arrayBuffer(path: string, options: RequestOptions = {}) {
    const response = await this.fetch(path, options);
    return response.arrayBuffer();
  }

  async fetch(path: string, options: RequestOptions = {}) {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), options.timeoutMs ?? 15000);
    const headers = new Headers(options.headers);
    const hasBody = options.body !== undefined;

    if (hasBody && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }

    if (!headers.has("Accept")) {
      headers.set("Accept", "application/json, text/plain, */*");
    }

    const abortListener = () => controller.abort();
    options.signal?.addEventListener("abort", abortListener, { once: true });

    const method = options.method ?? (hasBody ? "POST" : "GET");
    const startedAt = performance.now();
    const logPath = `/${normalizePath(path)}`;

    try {
      const response = await fetch(this.url(path, options.query), {
        method,
        headers,
        body: hasBody ? JSON.stringify(options.body) : undefined,
        signal: controller.signal,
      });

      clientLog(
        "http",
        { method, path: logPath, status: response.status, ms: elapsedMs(startedAt) },
        response.ok ? "info" : "warn",
      );

      if (!response.ok) {
        throw new HttpError(response, await safeReadText(response));
      }

      return response;
    } catch (error) {
      if (!(error instanceof HttpError)) {
        clientLog(
          "http_failed",
          { method, path: logPath, ms: elapsedMs(startedAt), message: error instanceof Error ? error.message : String(error) },
          "error",
        );
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
      options.signal?.removeEventListener("abort", abortListener);
    }
  }
}

async function safeReadText(response: Response) {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

function normalizePath(path: string) {
  return path.replace(/^\/+/, "");
}
