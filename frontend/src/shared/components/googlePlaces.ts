import type { ResolvedPlace } from './addressTypes';

/**
 * Address autocomplete, behind a seam.
 *
 * ## Why there is an interface here at all
 *
 * The same reason the backend has `GeocodingProvider`: everything provider-specific — the script
 * tag, the API key, the session token, the response shape — stops at this file. Above it,
 * `AddressAutocompleteField` knows only "give me suggestions" and "resolve this one", which is
 * what makes the component testable in jsdom with no network, no key and no `window.google`.
 *
 * ## The key is a SEPARATE, browser-restricted key
 *
 * `VITE_GOOGLE_MAPS_BROWSER_KEY` is compiled into the bundle and is therefore public. It is
 * **not** `MAPS_API_KEY` — that one is the backend's, lives in Secrets Manager, and has the
 * Geocoding and Routes APIs on it; putting it in a bundle would publish a server credential.
 * This key must be restricted in the Google Console to:
 *
 *   - HTTP referrers: `https://prontohomeservice.com/*`, `https://www.prontohomeservice.com/*`
 *   - APIs: Places API (New) only
 *
 * Referrer restriction is what makes a public key acceptable rather than merely unavoidable: the
 * key is visible to anyone, but usable only from Pronto's own origins.
 *
 * ## Session tokens
 *
 * Google bills autocomplete per *session*, not per keystroke: the sequence of typing plus the one
 * details lookup that follows counts as a single billable unit when they share a token. A token
 * is minted per editing session and discarded after the selection it belongs to, which is both
 * the documented contract and roughly an order of magnitude cheaper than tokenless calls.
 */
export interface AddressSuggestion {
  placeId: string;
  /** What to show in the list — the provider's own single-line description. */
  description: string;
}

export interface AddressSuggestionProvider {
  /** Suggestions for what the customer has typed so far. Returns `[]` rather than throwing when
   *  the query is too short or the provider is unavailable — an autocomplete that explodes on a
   *  keystroke is worse than one that quietly shows nothing. */
  fetchSuggestions(query: string, sessionToken: unknown): Promise<AddressSuggestion[]>;
  /** Full details for a chosen suggestion. Rejects if the place cannot be resolved. */
  resolve(placeId: string, sessionToken: unknown): Promise<ResolvedPlace>;
  /** A fresh billing session. Opaque to callers. */
  newSessionToken(): Promise<unknown>;
  /** Whether this provider can actually reach Google — false when no key is configured. */
  isConfigured(): boolean;
}

const BROWSER_KEY = import.meta.env.VITE_GOOGLE_MAPS_BROWSER_KEY as string | undefined;

/** Israel. Matches `SelectedPlaceValidator`'s service-area box on the backend, which is what
 *  would reject anything outside it anyway — biasing here means the customer never sees a
 *  suggestion the server would refuse. */
const COUNTRY = 'il';

let loaderPromise: Promise<GoogleMapsNamespace> | null = null;

/**
 * Loads the Maps JS API once per page, on demand.
 *
 * Deliberately **not** a `<script>` tag in `index.html`. Address entry is a small fraction of
 * sessions — a professional never touches it — and unconditionally loading a third-party bundle
 * on every page load would cost every user for a feature most of them are not using. Loading it
 * when an address field first mounts costs one round trip the first time and nothing thereafter,
 * because the promise is memoised.
 */
function loadMapsApi(): Promise<GoogleMapsNamespace> {
  if (loaderPromise) {
    return loaderPromise;
  }
  loaderPromise = new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !BROWSER_KEY) {
      reject(new Error('Google Maps browser key is not configured.'));
      return;
    }
    if (window.google?.maps?.importLibrary) {
      resolve(window.google.maps);
      return;
    }
    const script = document.createElement('script');
    script.src =
      `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(BROWSER_KEY)}` +
      '&libraries=places&loading=async&language=he&region=IL';
    script.async = true;
    script.onload = () => {
      if (window.google?.maps) {
        resolve(window.google.maps);
      } else {
        reject(new Error('Google Maps loaded without a maps namespace.'));
      }
    };
    // A failed load must not be memoised as a permanent failure: the customer may simply have
    // been offline for a moment, and a retry on the next mount should be allowed to work.
    script.onerror = () => {
      loaderPromise = null;
      reject(new Error('Google Maps failed to load.'));
    };
    document.head.appendChild(script);
  });
  return loaderPromise;
}

/**
 * Pulls `city` / `street` / `houseNumber` out of Google's address components.
 *
 * `locality` is the ordinary city component, but Israeli results sometimes carry the town only as
 * `administrative_area_level_2` (and, for a few places, `postal_town`) — falling through those in
 * order is what stops a real address resolving with an empty city, which the backend would then
 * reject as ungeocodable.
 */
function extractComponents(components: GoogleAddressComponent[]): {
  city: string;
  street: string;
  houseNumber: string;
} {
  const find = (type: string) =>
    components.find((c) => c.types.includes(type))?.longText ?? '';
  return {
    city: find('locality') || find('postal_town') || find('administrative_area_level_2'),
    street: find('route'),
    houseNumber: find('street_number'),
  };
}

/** The real provider. Constructed lazily so that importing this module has no side effects. */
export const googlePlacesProvider: AddressSuggestionProvider = {
  isConfigured() {
    return Boolean(BROWSER_KEY);
  },

  async newSessionToken() {
    const maps = await loadMapsApi();
    const { AutocompleteSessionToken } = await maps.importLibrary('places');
    return new AutocompleteSessionToken();
  },

  async fetchSuggestions(query, sessionToken) {
    if (query.trim().length < 2) {
      return [];
    }
    try {
      const maps = await loadMapsApi();
      const { AutocompleteSuggestion } = await maps.importLibrary('places');
      const { suggestions } = await AutocompleteSuggestion.fetchAutocompleteSuggestions({
        input: query,
        includedRegionCodes: [COUNTRY],
        language: 'he',
        region: COUNTRY,
        sessionToken,
      });
      return suggestions
        .map((s) => s.placePrediction)
        .filter((p): p is GooglePlacePrediction => Boolean(p?.placeId))
        .map((p) => ({ placeId: p.placeId, description: p.text.toString() }));
    } catch {
      // Swallowed on purpose. A transient provider failure must not break typing; the field
      // shows no suggestions, the customer cannot submit an unresolved address, and the next
      // keystroke retries. Surfacing an error toast per keystroke would be unusable.
      return [];
    }
  },

  async resolve(placeId, sessionToken) {
    const maps = await loadMapsApi();
    const { Place } = await maps.importLibrary('places');
    const place = new Place({ id: placeId, requestedLanguage: 'he' });
    await place.fetchFields({
      fields: ['id', 'formattedAddress', 'addressComponents', 'location'],
      sessionToken,
    });
    const location = place.location;
    if (!location) {
      throw new Error('The selected place has no location.');
    }
    return {
      placeId: place.id,
      formattedAddress: place.formattedAddress ?? '',
      ...extractComponents(place.addressComponents ?? []),
      latitude: location.lat(),
      longitude: location.lng(),
    };
  },
};
