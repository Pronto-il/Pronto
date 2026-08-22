/**
 * Thin fetch wrapper for talking to the Spring Boot backend. Feature code must go through
 * the typed functions in `auth.ts`/`users.ts` (etc.) rather than calling this — or
 * `fetch` — directly, per FRONTEND_AGENT.md's "no inline fetch in screens" rule.
 */

/**
 * Backend `server.port` default is 8080 (see `backend/src/main/resources/application.yml`).
 * Overridable via `VITE_API_BASE_URL` so later environments (staging/prod) don't require
 * touching this file.
 */
export const API_BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080'

/**
 * Standard error envelope shape, per `docs/architecture/api-contract.md` §1 (verified
 * against `backend/src/main/java/com/pronto/common/exception/GlobalExceptionHandler.java`
 * and `common/dto/{ErrorResponse,ErrorBody}.java`):
 *
 * ```json
 * {
 *   "timestamp": "2026-08-13T12:34:56Z",
 *   "path": "/api/auth/login",
 *   "error": { "code": "ACCOUNT_LOCKED", "message": "...", "details": null }
 * }
 * ```
 */
interface ErrorEnvelope {
  timestamp?: string;
  path?: string;
  error?: {
    code?: string;
    message?: string;
    details?: unknown;
  };
}

export class ApiError extends Error {
  /** Machine-readable code, one of the backend's `ErrorCode` enum names. */
  code: string;
  /** Nullable payload whose shape depends on `code` (e.g. a field-error array). */
  details: unknown;
  /** HTTP status code. */
  status: number;

  constructor(code: string, message: string, details: unknown, status: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.details = details;
    this.status = status;
  }
}

type TokenGetter = () => string | null;

let tokenGetter: TokenGetter = () => null;

/**
 * Injected by `AuthProvider` at startup so this module can attach the `Authorization`
 * header without importing React or reading `localStorage` directly — keeps the api layer
 * framework-agnostic and the token's source of truth in one place (the auth context).
 */
export function setAuthTokenGetter(getter: TokenGetter): void {
  tokenGetter = getter;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Attach the `Authorization` header when a token is available. Defaults to true. */
  auth?: boolean;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true } = options;

  // `FormData` bodies (multipart/form-data, e.g. registration file uploads) must NOT get
  // a manual `Content-Type` header or `JSON.stringify`'d body — the browser sets
  // `Content-Type: multipart/form-data; boundary=...` itself, which can't be replicated
  // by hand, and `fetch` sends a `FormData` body as-is.
  const isFormData = body instanceof FormData;

  const headers: Record<string, string> = {};
  if (body !== undefined && !isFormData) {
    headers['Content-Type'] = 'application/json';
  }
  if (auth) {
    const token = tokenGetter();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : isFormData ? body : JSON.stringify(body),
    });
  } catch {
    // Network failure (backend unreachable, DNS, CORS, offline, ...) — never a real
    // `ApiError` from the backend. status: 0 signals "no HTTP response at all".
    throw new ApiError('NETWORK_ERROR', 'Network request failed.', null, 0);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    // Empty/non-JSON body — leave payload as null (rare, but shouldn't crash callers).
  }

  if (!response.ok) {
    const envelope = payload as ErrorEnvelope | null;
    throw new ApiError(
      envelope?.error?.code ?? 'UNKNOWN_ERROR',
      envelope?.error?.message ?? 'Request failed.',
      envelope?.error?.details ?? null,
      response.status,
    );
  }

  return payload as T;
}

export const httpClient = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'DELETE' }),
};
