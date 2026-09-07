import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * **The first address of every page load failed.**
 *
 * `loadMapsApi` used to resolve from `script.onload`, which fires when the Maps bootstrap has been
 * *fetched* — not when the API is usable. At that moment `window.google.maps` exists but
 * `importLibrary` has not been installed on it, and every method in this module goes straight to
 * `maps.importLibrary('places')`. So the first city search, and the first full-address
 * confirmation, threw `TypeError: maps.importLibrary is not a function`.
 *
 * The customer saw "לא הצלחנו לאמת את הכתובת כרגע. יש לנסות שוב בעוד רגע." on the address step and
 * could not continue until they edited a field to force a retry. It read as a flaky network
 * problem rather than a systematic one because `loaderPromise` memoises the resolved namespace and
 * Google finishes initialising a moment later, so the *second* attempt always worked.
 *
 * `loading=async` requires a `callback` parameter — that callback is the documented "the API is
 * ready" signal, and these tests pin that we wait for it.
 */
describe('the Google Maps loader', () => {
  let appended: HTMLScriptElement[];

  beforeEach(() => {
    vi.resetModules();
    appended = [];
    vi.stubEnv('VITE_GOOGLE_MAPS_BROWSER_KEY', 'test-browser-key');
    vi.spyOn(document.head, 'appendChild').mockImplementation((node) => {
      appended.push(node as HTMLScriptElement);
      return node;
    });
    delete (window as unknown as Record<string, unknown>).google;
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
    delete (window as unknown as Record<string, unknown>).google;
  });

  /** The name of the global the loader asked Google to call back. */
  function callbackNameFrom(script: HTMLScriptElement): string {
    return new URL(script.src).searchParams.get('callback')!;
  }

  it('asks Google to call back rather than trusting onload', async () => {
    const { googlePlacesProvider } = await import('./googlePlaces');
    void googlePlacesProvider.newSessionToken().catch(() => undefined);
    await vi.waitFor(() => expect(appended).toHaveLength(1));

    const url = new URL(appended[0].src);
    expect(url.searchParams.get('loading')).toBe('async');
    // The half that was missing. Without it the API initialises after `onload` and the first
    // caller races it.
    expect(url.searchParams.get('callback')).toMatch(/^__prontoMapsReady_/);
    expect(typeof (window as unknown as Record<string, unknown>)[url.searchParams.get('callback')!]).toBe('function');
  });

  it('does not resolve while importLibrary is still missing, and does once the callback fires', async () => {
    const { googlePlacesProvider } = await import('./googlePlaces');

    let settled = false;
    const pending = googlePlacesProvider.newSessionToken().then(
      () => { settled = true; },
      () => { settled = true; },
    );
    await vi.waitFor(() => expect(appended).toHaveLength(1));
    const callbackName = callbackNameFrom(appended[0]);

    // Exactly the state `script.onload` used to resolve on: a maps namespace with no
    // `importLibrary`. Nothing must proceed from here.
    (window as unknown as Record<string, unknown>).google = { maps: {} };
    await Promise.resolve();
    expect(settled).toBe(false);

    // Now the API finishes installing itself and calls back, as `loading=async` documents.
    (window as unknown as Record<string, unknown>).google = {
      maps: {
        importLibrary: async () => ({
          AutocompleteSessionToken: class {},
          AutocompleteSuggestion: { fetchAutocompleteSuggestions: async () => ({ suggestions: [] }) },
          Place: class {},
        }),
      },
    };
    (window as unknown as Record<string, () => void>)[callbackName]();

    await pending;
    expect(settled).toBe(true);
    // The global is not left lying around on `window` afterwards.
    expect((window as unknown as Record<string, unknown>)[callbackName]).toBeUndefined();
  });

  it('the first city search succeeds — the symptom the customer actually hit', async () => {
    const { googlePlacesProvider } = await import('./googlePlaces');

    const search = googlePlacesProvider.fetchCitySuggestions('תל אב', undefined);
    await vi.waitFor(() => expect(appended).toHaveLength(1));
    const callbackName = callbackNameFrom(appended[0]);

    (window as unknown as Record<string, unknown>).google = {
      maps: {
        importLibrary: async () => ({
          AutocompleteSessionToken: class {},
          AutocompleteSuggestion: {
            fetchAutocompleteSuggestions: async () => ({
              suggestions: [
                { placePrediction: { placeId: 'city-tlv', text: { toString: () => 'תל אביב-יפו, ישראל' } } },
              ],
            }),
          },
          Place: class {},
        }),
      },
    };
    (window as unknown as Record<string, () => void>)[callbackName]();

    // Previously `[]`, because `fetchCitySuggestions` swallowed the `importLibrary` TypeError —
    // which is why the failure surfaced as an empty suggestion list rather than an error.
    await expect(search).resolves.toEqual([
      { placeId: 'city-tlv', description: 'תל אביב-יפו, ישראל' },
    ]);
  });

  it('a script that fails to load is not memoised as a permanent failure', async () => {
    const { googlePlacesProvider } = await import('./googlePlaces');

    const first = googlePlacesProvider.newSessionToken();
    await vi.waitFor(() => expect(appended).toHaveLength(1));
    const callbackName = callbackNameFrom(appended[0]);
    appended[0].onerror!(new Event('error'));

    await expect(first).rejects.toThrow(/failed to load/);
    expect((window as unknown as Record<string, unknown>)[callbackName]).toBeUndefined();

    // A retry on the next mount gets a fresh script tag rather than the cached rejection.
    void googlePlacesProvider.newSessionToken().catch(() => undefined);
    await vi.waitFor(() => expect(appended).toHaveLength(2));
  });
});
