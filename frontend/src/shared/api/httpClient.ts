/**
 * Thin fetch wrapper for talking to the Spring Boot backend. Feature code must go through
 * the typed functions in `auth.ts`/`users.ts` (etc.) rather than calling this — or
 * `fetch` — directly, per FRONTEND_AGENT.md's "no inline fetch in screens" rule.
 */

import { getGuestSessionToken } from './guestSessionStore';

/**
 * Backend `server.port` default is 8080 (see `backend/src/main/resources/application.yml`).
 * Overridable via `VITE_API_BASE_URL` so later environments (staging/prod) don't require
 * touching this file.
 *
 * Production MS4 narrowed the fallback to development builds only. It used to apply
 * unconditionally, which meant a production build with no `VITE_API_BASE_URL` shipped a bundle
 * that called `http://localhost:8080` from every user's browser — a defect that is invisible in
 * source, invisible in CI, and only observable as "the app does nothing" once deployed. Vite
 * inlines `import.meta.env.DEV` as a literal, so in a production bundle this collapses to the
 * configured value with no branch left behind.
 *
 * The empty-string case below is unreachable in practice: `vite.config.ts` fails the production
 * build outright when the variable is missing or points at a development host. It is written as a
 * same-origin fallback rather than a thrown error so that a bundle which somehow got past that
 * check degrades to relative URLs instead of a blank screen.
 */
const configuredApiBaseUrl = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.trim()

export const API_BASE_URL: string =
  configuredApiBaseUrl && configuredApiBaseUrl.length > 0
    ? configuredApiBaseUrl
    : import.meta.env.DEV
      ? 'http://localhost:8080'
      : ''

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

/**
 * Guest upload session header (`auth.security.GuestSessionTokenService.HEADER`).
 *
 * Attached to every request that has one stored, alongside — never instead of — `Authorization`.
 * Both travel together on purpose: a visitor who uploaded photos as a guest and then registered
 * mid-flow is simultaneously an authenticated customer and the owner of `guests/{id}/...` keys, and
 * the backend accepts a key matching either identity. Dropping the guest header the moment they
 * signed in is precisely how their own photos would vanish at the worst possible moment.
 *
 * Only the storage and issues routes look at it; everywhere else it is ignored, and sending it is
 * cheaper than maintaining a list of which calls need it.
 */
const GUEST_SESSION_HEADER = 'X-Pronto-Guest-Session';

function applyGuestSessionHeader(headers: Record<string, string>): void {
  const guestSessionToken = getGuestSessionToken();
  if (guestSessionToken) {
    headers[GUEST_SESSION_HEADER] = guestSessionToken;
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

/**
 * The token this module would attach right now, or `null`.
 *
 * Exposed for the one api-layer decision that turns on "is there an account behind this call":
 * `storage.ts`'s `uploadImage` mints a guest upload session only when there is not, so a signed-in
 * customer's upload is byte-for-byte the request it always was. Read-only — the source of truth is
 * still the auth context that injected the getter.
 */
export function getAuthToken(): string | null {
  return tokenGetter();
}

type UnauthorizedHandler = () => void;

let unauthorizedHandler: UnauthorizedHandler = () => {};

/**
 * Injected by `AuthProvider`, called once per request that was sent **with** a token and came
 * back `401 UNAUTHORIZED` — i.e. the token the app is holding is no longer accepted by the
 * backend (expired, or its user row was deleted).
 *
 * Without this, a token that expires while a long-lived screen stays open (the professional
 * dashboard polls its calendar every 25s, so a tab left open overnight always outlives the 24h
 * `pronto.jwt.expiration-seconds`) produced a silently broken session: `usePolling` keeps the
 * last successful response on screen when a tick fails, so the calendar still rendered its
 * stale segments and every write the professional then attempted — `PATCH`/`DELETE
 * /api/availability/blocks/{id}` being the ones QA hit — came back
 * `UNAUTHORIZED / Missing, invalid, or expired authentication token` behind a generic Hebrew
 * error banner, with no way to recover short of clearing `localStorage` by hand.
 *
 * Deliberately only fires for requests that actually carried a token: a `401` on a request sent
 * without one is a login/credential failure (`POST /api/auth/login` answers
 * `401 INVALID_CREDENTIALS`), which must stay the caller's own error to render.
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler;
}

type PhoneVerificationRequiredHandler = () => void;

let phoneVerificationRequiredHandler: PhoneVerificationRequiredHandler = () => {};

/**
 * Production MS1. Injected by `AppLayout`, called once per response carrying
 * `403 PHONE_VERIFICATION_REQUIRED` — the backend's answer when an account whose phone has never
 * been verified attempts a marketplace mutation (creating an issue or an order, activating SOS).
 *
 * Registered globally rather than handled per screen for the same reason the `401` handler is: the
 * gate fires on four different endpoints reached from at least six screens, and a per-screen copy
 * is one that gets forgotten on the seventh. The error is still thrown so the calling screen keeps
 * its own error rendering; this only routes the user somewhere they can actually fix it.
 *
 * The handler is UX. The rule is `users.service.ContactVerificationGuard`, and it holds against a
 * direct API call with a perfectly valid token whatever this client does.
 */
export function setPhoneVerificationRequiredHandler(handler: PhoneVerificationRequiredHandler): void {
  phoneVerificationRequiredHandler = handler;
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
  let sentWithToken = false;
  if (auth) {
    const token = tokenGetter();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
      sentWithToken = true;
    }
  }
  applyGuestSessionHeader(headers);

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
    throw toApiError(payload, response.status, sentWithToken);
  }

  return payload as T;
}

/**
 * Turns a parsed error body into an {@link ApiError}, firing the two global side-effect
 * handlers on the way. Shared by `request` above and `upload` below so the dead-session and
 * phone-verification redirects cannot end up applying to only one of the two transports.
 */
function toApiError(payload: unknown, status: number, sentWithToken: boolean): ApiError {
  if (status === 401 && sentWithToken) {
    // The token we hold is no longer accepted — see `setUnauthorizedHandler`. The error is
    // still thrown by the caller so the calling screen keeps its own error handling; the
    // handler only ends the dead session so the app can send the user back to login.
    unauthorizedHandler();
  }
  const envelope = payload as ErrorEnvelope | null;
  if (envelope?.error?.code === 'PHONE_VERIFICATION_REQUIRED') {
    phoneVerificationRequiredHandler();
  }
  return new ApiError(
    envelope?.error?.code ?? 'UNKNOWN_ERROR',
    envelope?.error?.message ?? 'Request failed.',
    envelope?.error?.details ?? null,
    status,
  );
}

export interface UploadOptions {
  /**
   * Called with the fraction of the request body written to the socket, from 0 to 1.
   *
   * Note what this does and does not mean: it is bytes handed to the OS, so it reaches 1
   * when the last byte is sent, *before* the backend has finished forwarding them to S3 and
   * answered. Callers should treat 1 as "uploaded, now waiting on the server", not "done".
   */
  onProgress?: (fraction: number) => void;
  /** Aborts the in-flight upload — e.g. the user removed the photo while it was still going. */
  signal?: AbortSignal;
}

/**
 * `POST` a `FormData` body with upload-progress reporting.
 *
 * This is the one place in the api layer that does not use `fetch`, and the reason is narrow:
 * `fetch` exposes no upload-progress signal at all (`ReadableStream` request bodies would, but
 * are not supported on Safari, which is most of this app's traffic). `XMLHttpRequest` has
 * `upload.onprogress`, so a multi-second photo upload on a mobile uplink can show a real
 * percentage instead of an indeterminate spinner that looks identical to a hung request.
 *
 * Everything else — base URL, bearer token, error envelope, the 401 and
 * `PHONE_VERIFICATION_REQUIRED` handlers — is deliberately identical to `request`, via the
 * shared {@link toApiError}.
 */
function upload<T>(path: string, formData: FormData, options: UploadOptions = {}): Promise<T> {
  const { onProgress, signal } = options;

  return new Promise<T>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new ApiError('ABORTED', 'Upload cancelled.', null, 0));
      return;
    }

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API_BASE_URL}${path}`);

    const token = tokenGetter();
    const sentWithToken = Boolean(token);
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }
    // The guest upload session, for exactly the same reason `request` sends it — and this is the
    // transport that actually needs it, since `POST /api/storage/images` is the route a guest
    // reaches first.
    const guestSessionToken = getGuestSessionToken();
    if (guestSessionToken) {
      xhr.setRequestHeader(GUEST_SESSION_HEADER, guestSessionToken);
    }
    // No manual Content-Type: the browser has to set the multipart boundary itself, exactly
    // as in `request`.

    if (onProgress) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && event.total > 0) {
          onProgress(event.loaded / event.total);
        }
      };
    }

    const onAbort = () => xhr.abort();
    signal?.addEventListener('abort', onAbort);
    const cleanup = () => signal?.removeEventListener('abort', onAbort);

    xhr.onload = () => {
      cleanup();
      let payload: unknown = null;
      try {
        payload = JSON.parse(xhr.responseText);
      } catch {
        // Empty/non-JSON body — same tolerance as `request`.
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(payload as T);
      } else {
        reject(toApiError(payload, xhr.status, sentWithToken));
      }
    };

    // Network failure and abort are separated on purpose: a cancelled upload is a thing the
    // user did, and must not surface as "the network failed".
    xhr.onerror = () => {
      cleanup();
      reject(new ApiError('NETWORK_ERROR', 'Network request failed.', null, 0));
    };
    xhr.ontimeout = () => {
      cleanup();
      reject(new ApiError('NETWORK_ERROR', 'Network request timed out.', null, 0));
    };
    xhr.onabort = () => {
      cleanup();
      reject(new ApiError('ABORTED', 'Upload cancelled.', null, 0));
    };

    xhr.send(formData);
  });
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
  /** Multipart `POST` with upload-progress reporting — see {@link upload}. */
  upload,
};
