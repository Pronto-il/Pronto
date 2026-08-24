import type { CategoryWithSubServicesResponse } from '../../shared/api';

/**
 * Resolving the ids the operator endpoints return into names a person can read.
 *
 * `GET /api/admin/professionals/{id}` returns `categoryIds` and `subServiceIds` — ids only. The
 * names live in the seeded catalog (`GET /api/categories`), which is also what the professional
 * chose from during registration, so this reads the same source rather than a second hardcoded
 * list. There is no static mirror in this package on purpose: the categories/sub-services table
 * is the source of truth, and a copy here would be the thing that goes stale.
 */

export interface ResolvedSubService {
  id: number;
  /** `null` when the id is not in the catalog at all — a sub-service that was retired after this
   *  professional selected it. Shown as such rather than silently dropped. */
  nameHe: string | null;
  /** Whether it belongs to one of the professional's own categories (MS4: they may hold
   *  several). The backend requires at least one that does; anything else is leftover from an
   *  earlier category choice and does not count towards a complete registration. */
  belongsToCategory: boolean;
}

export function findCategoryNameHe(
  catalog: CategoryWithSubServicesResponse[] | null,
  categoryId: number,
): string | null {
  return catalog?.find((category) => category.id === categoryId)?.nameHe ?? null;
}

/**
 * MS4: the names for every category a professional serves, in the order the backend returned
 * them (`categories.display_order`). Ids the catalog does not have are reported as such rather
 * than dropped — on an operator screen, "one of their categories no longer exists" is exactly
 * the kind of thing a reviewer needs to see.
 */
export function findCategoryNamesHe(
  catalog: CategoryWithSubServicesResponse[] | null,
  categoryIds: number[],
): string[] {
  return categoryIds.map(
    (id) => catalog?.find((category) => category.id === id)?.nameHe ?? `תחום ${id} (לא בקטלוג)`,
  );
}

export function resolveSubServices(
  catalog: CategoryWithSubServicesResponse[] | null,
  categoryIds: number[],
  subServiceIds: number[],
): ResolvedSubService[] {
  if (!catalog) {
    return subServiceIds.map((id) => ({ id, nameHe: null, belongsToCategory: false }));
  }
  return subServiceIds.map((id) => {
    const owningCategory = catalog.find((category) =>
      category.subServices.some((subService) => subService.id === id),
    );
    const subService = owningCategory?.subServices.find((candidate) => candidate.id === id);
    return {
      id,
      nameHe: subService?.nameHe ?? null,
      belongsToCategory: owningCategory !== undefined && categoryIds.includes(owningCategory.id),
    };
  });
}
