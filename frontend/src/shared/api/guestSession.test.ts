import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ensureGuestSessionToken } from './guestSession';
import { clearGuestSession, getGuestSessionToken, writeGuestSession } from './guestSessionStore';
import { setAuthTokenGetter, httpClient } from './httpClient';
import { uploadImage, getPresignedImageUrls } from './storage';

/**
 * The client half of guest image upload.
 *
 * The backend is what actually authorises anything; what can go wrong here is narrower and entirely
 * about plumbing: a session that is never minted, a header that is not attached, a header that is
 * dropped the moment the guest signs in — that last one being the failure that would silently
 * delete a customer's photos at the exact moment they did what we asked.
 */

const GUEST_HEADER = 'X-Pronto-Guest-Session';
const SESSION_STORAGE_KEY = 'pronto_guest_upload_session';

/** Captures what actually went on the wire, for both transports. */
let fetchCalls: Array<{ url: string; headers: Record<string, string> }>;
let xhrCalls: Array<{ url: string; headers: Record<string, string> }>;

class FakeXhr {
  static instances: FakeXhr[] = [];
  status = 201;
  responseText = JSON.stringify({ imageKey: 'guests/x/issues/temp/a.jpg', imageUrl: 'http://x/a' });
  upload = {} as { onprogress?: unknown };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  onabort: (() => void) | null = null;
  private url = '';
  private headers: Record<string, string> = {};

  open(_method: string, url: string) {
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name] = value;
  }

  send() {
    xhrCalls.push({ url: this.url, headers: this.headers });
    queueMicrotask(() => this.onload?.());
  }
}

beforeEach(() => {
  localStorage.clear();
  fetchCalls = [];
  xhrCalls = [];
  setAuthTokenGetter(() => null);

  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      fetchCalls.push({ url, headers: (init.headers ?? {}) as Record<string, string> });
      const body = url.endsWith('/guest-sessions')
        ? { guestSessionToken: 'minted-token', expiresInSeconds: 86400 }
        : { images: [] };
      return { ok: true, status: 200, json: async () => body } as unknown as Response;
    }),
  );
  vi.stubGlobal('XMLHttpRequest', FakeXhr as unknown as typeof XMLHttpRequest);
});

afterEach(() => {
  vi.unstubAllGlobals();
  setAuthTokenGetter(() => null);
});

// ---- 1, 2. A guest starts the normal flow and gets an authorized upload ----

describe('minting', () => {
  it('mints a session on a guest\'s first upload and reuses it afterwards', async () => {
    await uploadImage(new File(['x'], 'a.jpg', { type: 'image/jpeg' }));
    await uploadImage(new File(['y'], 'b.jpg', { type: 'image/jpeg' }));

    const mints = fetchCalls.filter((call) => call.url.endsWith('/api/storage/guest-sessions'));
    expect(mints).toHaveLength(1);
    expect(xhrCalls).toHaveLength(2);
    expect(xhrCalls[0].headers[GUEST_HEADER]).toBe('minted-token');
    expect(xhrCalls[1].headers[GUEST_HEADER]).toBe('minted-token');
  });

  it('never mints for a signed-in customer — their upload is the request it always was', async () => {
    // 14: no extra round trip, no guest header, nothing new on the wire.
    setAuthTokenGetter(() => 'jwt-abc');

    await uploadImage(new File(['x'], 'a.jpg', { type: 'image/jpeg' }));

    expect(fetchCalls.filter((c) => c.url.endsWith('/api/storage/guest-sessions'))).toHaveLength(0);
    expect(xhrCalls[0].headers.Authorization).toBe('Bearer jwt-abc');
    expect(xhrCalls[0].headers[GUEST_HEADER]).toBeUndefined();
  });

  it('never mints for a visitor who only browses', async () => {
    // A session exists because a photo was attached, not because a page was opened.
    await getPresignedImageUrls(['guests/x/issues/temp/a.jpg']);

    expect(fetchCalls.filter((c) => c.url.endsWith('/api/storage/guest-sessions'))).toHaveLength(0);
    expect(getGuestSessionToken()).toBeNull();
  });

  it('replaces a session that expired while the tab was closed', async () => {
    writeGuestSession({ token: 'stale', expiresAt: Date.now() - 1000 });

    expect(getGuestSessionToken()).toBeNull();
    await expect(ensureGuestSessionToken()).resolves.toBe('minted-token');
  });

  it('treats a session about to expire as already gone', async () => {
    // A token that dies mid-request is a failed upload with a confusing error; minting is cheap.
    writeGuestSession({ token: 'nearly-dead', expiresAt: Date.now() + 30_000 });

    await expect(ensureGuestSessionToken()).resolves.toBe('minted-token');
  });

  it('ignores a corrupted stored session rather than sending garbage', () => {
    localStorage.setItem(SESSION_STORAGE_KEY, '{not json');
    expect(getGuestSessionToken()).toBeNull();
  });
});

// ---- 9, 10. Survival across the flow and across the auth transition ----

describe('the guest session header', () => {
  it('rides along on every call that can carry image keys', async () => {
    await ensureGuestSessionToken();

    await getPresignedImageUrls(['guests/x/issues/temp/a.jpg']);
    await httpClient.post('/api/issues', { imageKeys: ['guests/x/issues/temp/a.jpg'] });

    const withHeader = fetchCalls.filter((call) => call.headers[GUEST_HEADER] === 'minted-token');
    expect(withHeader.map((call) => call.url.replace(/^.*?(\/api)/, '$1'))).toEqual(
      expect.arrayContaining(['/api/storage/images/presigned-urls', '/api/issues']),
    );
  });

  it('is sent ALONGSIDE Authorization once the guest registers', async () => {
    // The whole auth transition, in one assertion. Dropping the guest header the moment a JWT
    // appears is exactly how a customer's own photos would become unreadable to them: the keys are
    // still in the guest namespace until the booking commit promotes them.
    await ensureGuestSessionToken();
    setAuthTokenGetter(() => 'jwt-abc');

    await httpClient.post('/api/issues', { imageKeys: ['guests/x/issues/temp/a.jpg'] });

    const call = fetchCalls.at(-1)!;
    expect(call.headers.Authorization).toBe('Bearer jwt-abc');
    expect(call.headers[GUEST_HEADER]).toBe('minted-token');
  });

  it('survives a page reload, because the draft it authorises does', async () => {
    // 9: navigating classification -> address -> matching is a series of renders, but registration
    // is a full document swap. localStorage is why the keys and their proof stay together.
    await ensureGuestSessionToken();
    expect(JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY)!).token).toBe('minted-token');
    expect(getGuestSessionToken()).toBe('minted-token');
  });

  it('is dropped only when the session is explicitly cleared', () => {
    writeGuestSession({ token: 'live', expiresAt: Date.now() + 86_400_000 });
    clearGuestSession();
    expect(getGuestSessionToken()).toBeNull();
  });
});
