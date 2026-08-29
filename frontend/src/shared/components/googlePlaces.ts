import type { ResolvedPlace } from './addressTypes';

/**
 * Address autocomplete, behind a seam.
 *
 * ## Why there is an interface here at all
 *
 * The same reason the backend has `GeocodingProvider`: everything provider-specific — the script
 * tag, the API key, the session token, the response shape — stops at this file. Above it,
 * `AddressFormFields` knows only "give me cities", "give me streets in this city" and "confirm
 * this complete address", which is what makes the component testable in jsdom with no network, no
 * key and no `window.google`.
 *
 * ## The three questions, and why they are three
 *
 * An address is collected as **city, then street, then house number**, and each step is a
 * different question for Google:
 *
 * 1. {@link AddressSuggestionProvider.fetchCitySuggestions} — localities only.
 * 2. {@link AddressSuggestionProvider.fetchStreetSuggestions} — routes only, biased to the chosen
 *    city's coordinates and filtered to results that actually name that city, so a street can
 *    never be paired with a city it does not belong to.
 * 3. {@link AddressSuggestionProvider.resolveFullAddress} — the whole thing, city + street +
 *    house number, resolved to one real building. This is the step that can fail, and failing is
 *    the point: a house number nobody can find is exactly what must not reach the backend.
 *
 * A single free-text box could not express (2) or (3) — it resolved whatever the customer happened
 * to pick, including a street with no number at all, and then trusted a manually-typed number
 * appended to it.
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
 * **Everything here is Places API (New)** — including the final full-address confirmation, which
 * deliberately does not reach for the Geocoding API. Geocoding is on the *backend's* key, and
 * calling it from the browser would either fail on the key restriction or force that restriction
 * to be widened. Reusing the autocomplete session token for the confirmation also means the
 * confirmation is part of the session already being billed, not a second billable unit.
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

/** The three fields that locate a building, as the customer has entered them so far. */
export interface AddressParts {
  city: string;
  street: string;
  houseNumber: string;
}

export interface AddressSuggestionProvider {
  /** Cities matching what the customer has typed. Returns `[]` rather than throwing when the
   *  query is too short or the provider is unavailable — an autocomplete that explodes on a
   *  keystroke is worse than one that quietly shows nothing. */
  fetchCitySuggestions(query: string, sessionToken: unknown): Promise<AddressSuggestion[]>;
  /**
   * Streets matching what the customer has typed, **within `city`**. Same never-throws contract.
   *
   * @param city the already-selected city. Results that do not name it are dropped rather than
   *             shown, so the list cannot offer a street in another town.
   */
  fetchStreetSuggestions(
    query: string,
    city: string,
    sessionToken: unknown,
  ): Promise<AddressSuggestion[]>;
  /** Full details for a chosen suggestion. Rejects if the place cannot be resolved. */
  resolve(placeId: string, sessionToken: unknown): Promise<ResolvedPlace>;
  /**
   * Confirms the complete address and returns the place it resolved to.
   *
   * @returns the resolved place, or `null` when Google can find no building matching this exact
   *          city + street + house number. `null` is a real answer ("that address does not
   *          exist"), which is why it is not an exception: a rejected promise means the provider
   *          could not be asked, and those two must not be shown to the customer as the same
   *          thing.
   */
  resolveFullAddress(parts: AddressParts, sessionToken: unknown): Promise<ResolvedPlace | null>;
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

/** How far around the selected city's centre street suggestions are drawn from. Generous enough
 *  to cover a large municipality plus its outskirts; the city-name filter below is what actually
 *  enforces belonging, this only improves the ordering. */
const CITY_BIAS_RADIUS_METERS = 25_000;

/** Suggestions inspected when confirming a complete address. Google's first answer is usually
 *  right; the next two cover the case where a `premise` for the same street outranks the
 *  `street_address` that carries the house number. Bounded because each one costs a details
 *  lookup. */
const FULL_ADDRESS_CANDIDATES = 3;

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

/**
 * Comparison form for a Hebrew place name.
 *
 * Both sides of every comparison in this file came out of Google, so they agree far more often
 * than free text would — but not always identically: a prediction's description writes the city
 * as it appears in a formatted address, while the component list writes the canonical locality.
 * Punctuation, the maqaf/hyphen family and repeated spaces are the differences that actually show
 * up ("תל אביב-יפו" vs "תל אביב יפו"), so those are what this removes. Nothing here transliterates
 * or stems — this is a tolerance, not a fuzzy matcher.
 */
function normalizeName(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/["'`׳״.,]/g, '')
    // Maqaf (U+05BE), the Unicode dash family (U+2010-U+2015) and the ASCII hyphen.
    .replace(/[־‐-―-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Loose name equality: identical, or one is a qualified form of the other ("תל אביב" ⊂
 *  "תל אביב יפו"). Containment is only accepted for a name long enough that it cannot match
 *  half the country by accident. */
function namesAgree(a: string, b: string): boolean {
  const left = normalizeName(a);
  const right = normalizeName(b);
  if (!left || !right) {
    return false;
  }
  if (left === right) {
    return true;
  }
  const shorter = left.length <= right.length ? left : right;
  const longer = left.length <= right.length ? right : left;
  return shorter.length >= 3 && longer.includes(shorter);
}

/** Does a suggestion's description place it in `city`? */
function describesCity(description: string, city: string): boolean {
  const target = normalizeName(city);
  if (!target) {
    return true;
  }
  return normalizeName(description).includes(target);
}

/**
 * **The gate.** Is the place Google returned actually the address the customer entered?
 *
 * Exported because it is the whole safety property of `resolveFullAddress` and deserves to be
 * tested as a function rather than only through a mocked provider. All three parts must agree:
 *
 * - **House number exactly.** Not "close to" — an off-by-one house number is a different door,
 *   and Google will happily answer a query for number 999 with the nearest thing it has. A result
 *   carrying no `street_number` at all fails too, since there is then nothing confirming the
 *   number the customer typed exists.
 * - **Street and city loosely**, per {@link namesAgree} — those two came from Google's own
 *   suggestion lists, so the only differences expected are spelling-form ones.
 */
export function matchesRequestedAddress(resolved: ResolvedPlace, parts: AddressParts): boolean {
  return (
    resolved.houseNumber.trim() !== '' &&
    resolved.houseNumber.trim() === parts.houseNumber.trim() &&
    namesAgree(resolved.street, parts.street) &&
    namesAgree(resolved.city, parts.city)
  );
}

/** One autocomplete call, normalised. Never throws — see the interface contract. */
async function fetchSuggestions(request: {
  input: string;
  includedPrimaryTypes?: string[];
  locationBias?: { center: { lat: number; lng: number }; radius: number };
  sessionToken: unknown;
}): Promise<AddressSuggestion[]> {
  const maps = await loadMapsApi();
  const { AutocompleteSuggestion } = await maps.importLibrary('places');
  const { suggestions } = await AutocompleteSuggestion.fetchAutocompleteSuggestions({
    input: request.input,
    includedPrimaryTypes: request.includedPrimaryTypes,
    locationBias: request.locationBias,
    includedRegionCodes: [COUNTRY],
    language: 'he',
    region: COUNTRY,
    sessionToken: request.sessionToken,
  });
  return suggestions
    .map((s) => s.placePrediction)
    .filter((p): p is GooglePlacePrediction => Boolean(p?.placeId))
    .map((p) => ({ placeId: p.placeId, description: p.text.toString() }));
}

/** Where a city is, so street suggestions can be biased to it. Memoised per city name for the
 *  lifetime of the page: the coordinates of a city do not change, and re-resolving them on every
 *  keystroke of a street name would be a details lookup per keystroke. */
const cityCentres = new Map<string, { lat: number; lng: number } | null>();

async function cityCentre(city: string, sessionToken: unknown): Promise<{ lat: number; lng: number } | null> {
  const key = normalizeName(city);
  if (!key) {
    return null;
  }
  const cached = cityCentres.get(key);
  if (cached !== undefined) {
    return cached;
  }
  try {
    const [first] = await fetchSuggestions({
      input: city,
      includedPrimaryTypes: ['(cities)'],
      sessionToken,
    });
    if (!first) {
      cityCentres.set(key, null);
      return null;
    }
    const place = await googlePlacesProvider.resolve(first.placeId, sessionToken);
    const centre = { lat: place.latitude, lng: place.longitude };
    cityCentres.set(key, centre);
    return centre;
  } catch {
    // Bias is an optimisation. Failing to compute it must not stop street search, which still
    // works from the city name appended to the query.
    return null;
  }
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

  async fetchCitySuggestions(query, sessionToken) {
    if (query.trim().length < 2) {
      return [];
    }
    try {
      return await fetchSuggestions({
        input: query,
        // The documented Autocomplete (New) collection for localities. Asking for cities rather
        // than filtering addresses afterwards is what makes the first field genuinely a city
        // field instead of a full-address box with a different label.
        includedPrimaryTypes: ['(cities)'],
        sessionToken,
      });
    } catch {
      // Swallowed on purpose. A transient provider failure must not break typing; the field
      // shows no suggestions, the customer cannot submit an unresolved address, and the next
      // keystroke retries. Surfacing an error toast per keystroke would be unusable.
      return [];
    }
  },

  async fetchStreetSuggestions(query, city, sessionToken) {
    if (query.trim().length < 2 || !city.trim()) {
      return [];
    }
    try {
      const centre = await cityCentre(city, sessionToken);
      const results = await fetchSuggestions({
        // The city rides along in the query text as well as in the location bias. The bias orders
        // results; the text is what makes Google interpret "הרצל" as a street *in this town*.
        input: `${query.trim()}, ${city.trim()}`,
        includedPrimaryTypes: ['route'],
        locationBias: centre ? { center: centre, radius: CITY_BIAS_RADIUS_METERS } : undefined,
        sessionToken,
      });
      // No fallback to the unfiltered list when this empties it. "The street must belong to the
      // selected city" is the requirement; showing an out-of-town street because the in-town
      // search found nothing would quietly break exactly that.
      return results.filter((suggestion) => describesCity(suggestion.description, city));
    } catch {
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

  async resolveFullAddress(parts, sessionToken) {
    const { city, street, houseNumber } = parts;
    if (!city.trim() || !street.trim() || !houseNumber.trim()) {
      return null;
    }
    const suggestions = await fetchSuggestions({
      // The order an Israeli address is written in, with the country appended for the same reason
      // `PostalAddress#toQuery()` appends it on the backend: region bias is a preference, and a
      // street name that also exists abroad must not be able to win on it.
      input: `${street.trim()} ${houseNumber.trim()}, ${city.trim()}, ישראל`,
      includedPrimaryTypes: ['street_address', 'premise', 'subpremise'],
      sessionToken,
    });

    for (const suggestion of suggestions.slice(0, FULL_ADDRESS_CANDIDATES)) {
      const place = await this.resolve(suggestion.placeId, sessionToken);
      if (matchesRequestedAddress(place, parts)) {
        return place;
      }
    }
    // Google was reached and had nothing matching this exact building. Not an error — an answer.
    return null;
  },
};
