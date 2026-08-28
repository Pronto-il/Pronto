/**
 * The exact slice of the Google Maps JS API this codebase touches, and nothing else.
 *
 * **Why not `@types/google.maps`.** That package types the entire Maps platform — maps, markers,
 * drawing, street view, visualisation — to describe the four calls in `googlePlaces.ts`. Pronto
 * loads the API at runtime from Google's CDN, so the types are compile-time only and buy nothing
 * a hand-written declaration does not: this file is ~40 lines, has no version to keep in step
 * with the CDN's, and fails to compile the moment somebody reaches for an API that has not been
 * declared here — which is a feature, since every such reach is a new dependency on a third
 * party that should be a deliberate decision rather than an autocomplete accident.
 *
 * Kept deliberately narrow: adding a field here is the checkpoint where "we now also depend on
 * X" gets noticed.
 */

interface GooglePlacePrediction {
  placeId: string;
  text: { toString(): string };
}

interface GoogleAutocompleteSuggestion {
  placePrediction?: GooglePlacePrediction | null;
}

interface GoogleAddressComponent {
  longText: string | null;
  types: string[];
}

interface GoogleLatLng {
  lat(): number;
  lng(): number;
}

interface GooglePlace {
  id: string;
  formattedAddress: string | null;
  addressComponents: GoogleAddressComponent[] | null;
  location: GoogleLatLng | null;
  fetchFields(request: { fields: string[]; sessionToken?: unknown }): Promise<unknown>;
}

interface GooglePlacesLibrary {
  AutocompleteSessionToken: new () => object;
  Place: new (options: { id: string; requestedLanguage?: string }) => GooglePlace;
  AutocompleteSuggestion: {
    fetchAutocompleteSuggestions(request: {
      input: string;
      includedRegionCodes?: string[];
      language?: string;
      region?: string;
      sessionToken?: unknown;
    }): Promise<{ suggestions: GoogleAutocompleteSuggestion[] }>;
  };
}

interface GoogleMapsNamespace {
  importLibrary(name: 'places'): Promise<GooglePlacesLibrary>;
}

interface Window {
  google?: { maps?: GoogleMapsNamespace };
}
