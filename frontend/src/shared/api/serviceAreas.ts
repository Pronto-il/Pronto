import { httpClient } from './httpClient';

/**
 * `GET /api/service-areas` — the canonical, closed list of Israeli service regions and the
 * cities inside each (MS4 Part A).
 *
 * **Why this is a fetch and not a static module, unlike `categories.ts`.** The backend owns this
 * list already: `professionals.service_region_id` and `professional_service_cities` are foreign
 * keys into `service_regions`/`service_cities`, and registration validates against them. A static
 * TS mirror would be a *second* copy of a list the server enforces — which is exactly the
 * duplication `categories.ts` demonstrates the cost of (it is a hand-maintained copy of
 * `V10`/`V31` that has to be edited whenever a migration touches categories, and there is nothing
 * that fails if somebody forgets).
 *
 * Public/unauthenticated, because professional registration needs it before an account exists.
 *
 * Every screen that offers a region or a city reads from here. No component defines its own
 * region array or its own region→city map: `citiesForRegion()` below is the region filter, and it
 * is derived from the same payload the selects are built from, so the two cannot disagree.
 */
export interface ServiceCityResponse {
  id: number;
  /** Stable machine-readable handle. Persist `id`; display `nameHe`. */
  code: string;
  nameHe: string;
  nameEn: string;
  displayOrder: number;
}

export interface ServiceRegionResponse {
  id: number;
  code: string;
  nameHe: string;
  nameEn: string;
  displayOrder: number;
  cities: ServiceCityResponse[];
}

export function getServiceAreas(): Promise<ServiceRegionResponse[]> {
  return httpClient.get<ServiceRegionResponse[]>('/api/service-areas');
}

/**
 * The cities offered for a region — MS4 §3's "changing region should update the available city
 * options", expressed once here rather than inside whichever form needed it first.
 *
 * Returns `[]` for a region that isn't in the catalogue (or for `null`, i.e. nothing chosen yet),
 * which is what lets a form render "choose a region first" without a special case.
 */
export function citiesForRegion(
  regions: ServiceRegionResponse[] | null,
  regionId: number | null,
): ServiceCityResponse[] {
  if (!regions || regionId === null) {
    return [];
  }
  return regions.find((region) => region.id === regionId)?.cities ?? [];
}

/** Every city across every region, in catalogue order — for resolving ids to labels. */
export function allCities(regions: ServiceRegionResponse[] | null): ServiceCityResponse[] {
  return (regions ?? []).flatMap((region) => region.cities);
}

/**
 * The region a city belongs to, or `null` if the catalogue doesn't have that city.
 *
 * Used when *loading* an existing professional into the editor: the profile response carries the
 * region id directly, but this is what lets the editor recover a coherent state from a city list
 * alone, and what `regionForCities` below is built on.
 */
export function regionForCity(
  regions: ServiceRegionResponse[] | null,
  cityId: number,
): ServiceRegionResponse | null {
  return (regions ?? []).find((region) => region.cities.some((city) => city.id === cityId)) ?? null;
}

/** Hebrew labels for a set of city ids, in catalogue order. Unknown ids are dropped, not rendered as blanks. */
export function cityNames(regions: ServiceRegionResponse[] | null, cityIds: number[]): string[] {
  const selected = new Set(cityIds);
  return allCities(regions)
    .filter((city) => selected.has(city.id))
    .map((city) => city.nameHe);
}
