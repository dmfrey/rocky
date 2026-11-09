export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function extractErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: string };
    return body.error ?? response.statusText;
  } catch {
    return response.statusText;
  }
}

// Empty by default (relative paths, same-origin) - only set for deployments where the frontend
// and backend are separate origins (see vite-env.d.ts and manifest.yml). Exported for the places
// that build a backend URL themselves (the SSO sign-in navigation).
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

interface CsrfToken {
  headerName: string;
  token: string;
}

// The backend binds the CSRF token to the server session and hands it out from GET /api/csrf: the
// frontend and backend are separate origins in the Cloud Foundry deployment, so a cookie-based
// double-submit (reading an XSRF cookie from JS) can't work. Fetched lazily, cached in memory, and
// de-duplicated - concurrent first mutations share one in-flight request.
let csrfToken: Promise<CsrfToken> | null = null;

function loadCsrfToken(): Promise<CsrfToken> {
  if (!csrfToken) {
    const pending = (async () => {
      const response = await fetch(`${API_BASE_URL}/api/csrf`, { credentials: "include" });
      if (!response.ok) {
        throw new ApiError(response.status, await extractErrorMessage(response));
      }
      return (await response.json()) as CsrfToken;
    })();
    csrfToken = pending;
    // A failed fetch must not stay cached, or every later mutation would fail with the same error.
    pending.catch(() => {
      if (csrfToken === pending) csrfToken = null;
    });
  }
  return csrfToken;
}

/** Forget the cached token - call whenever the server session may have changed (login, logout). */
export function clearCsrfToken(): void {
  csrfToken = null;
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * fetch() against the backend with credentials, and - for any non-safe method - the CSRF header.
 * A 403 on such a request most likely means the token went stale (new session), so it is refetched
 * and the request retried exactly once. Exported for chat.ts's hand-rolled SSE fetch(), which can't
 * go through request() below.
 */
export async function fetchWithCsrf(path: string, init: RequestInit = {}): Promise<Response> {
  const mutating = !SAFE_METHODS.has((init.method ?? "GET").toUpperCase());

  async function send(): Promise<Response> {
    const headers: Record<string, string> = { ...(init.headers as Record<string, string> | undefined) };
    if (mutating) {
      const { headerName, token } = await loadCsrfToken();
      headers[headerName] = token;
    }
    return fetch(`${API_BASE_URL}${path}`, { ...init, credentials: "include", headers });
  }

  const response = await send();
  if (mutating && response.status === 403) {
    clearCsrfToken();
    return send();
  }
  return response;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetchWithCsrf(path, {
    ...init,
    headers: {
      ...(init?.body && !(init.body instanceof FormData) ? { "Content-Type": "application/json" } : {}),
      ...(init?.headers as Record<string, string> | undefined),
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await extractErrorMessage(response));
  }

  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body !== undefined ? JSON.stringify(body) : undefined }),
  postForm: <T>(path: string, form: FormData) => request<T>(path, { method: "POST", body: form }),
  patch: <T>(path: string, body: unknown) => request<T>(path, { method: "PATCH", body: JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
};
