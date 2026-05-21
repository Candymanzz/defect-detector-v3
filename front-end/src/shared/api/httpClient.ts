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
  constructor(private readonly baseUrl: string) {}

  url(path: string, query?: RequestOptions["query"]) {
    const url = new URL(normalizePath(path), `${this.baseUrl}/`);

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

    try {
      const response = await fetch(this.url(path, options.query), {
        method: options.method ?? (hasBody ? "POST" : "GET"),
        headers,
        body: hasBody ? JSON.stringify(options.body) : undefined,
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new HttpError(response, await safeReadText(response));
      }

      return response;
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
  return path.startsWith("/") ? path.slice(1) : path;
}
