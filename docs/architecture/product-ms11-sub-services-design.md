# MS11 — Services & Sub-services

Status: **design pass, not yet built.** Written by `pronto-planning`, source of truth for
`pronto-lead` to sequence and `pronto-coding`/`pronto-documentation` to implement/document
against. Builds on `docs/architecture/data-model.md` §2.1 (`categories`) and §2.4
(`professionals`), and `docs/architecture/api-contract-professionals-reviews.md` (the
`professionals` package's existing self-service `/me` surface).

## 0. Scope

Verbatim requirements:

- Add sub-services under every main professional category/service.
- A Professional must be able to select which sub-services they provide.
- A Professional must be able to edit their selected sub-services later.
- The implementation should support future service/sub-service changes without hardcoding
  the entire structure into the UI.
- Reuse the existing category/service architecture where possible.

This is a small, proportionate addition on top of the existing `categories`/
`professionals` structure — **one new reference table, one new join table, one
full-replace endpoint (reusing the exact `PUT /api/availability/working-hours`
precedent), one new read endpoint, and one checklist widget on `/pro/profile`.** It is
**not** a new subsystem, not a matching/filtering feature, and not a reopening of the
single-category-per-professional decision (see §1 for why that distinction matters here).

---

## 1. Distinction from the settled single-category-per-professional model (read this first)

`data-model.md` §2.4 item 2 and §3 item 2 already settled, with explicit sign-off framing,
that `professionals.category_id` is a **single FK** — one category per professional — and
explicitly flagged a `professional_categories` many-to-many join as a *possible future
extension, not built*. That decision is **unaffected by MS11** and is not being reopened
here.

MS11 adds an orthogonal, second-level dimension **within** the one category a professional
already has: which of that category's sub-services they personally perform. A plumber
(`category_id` → Plumbing) can offer a subset of Plumbing's sub-services (e.g. "unclogging"
and "leak repair" but not "water heater replacement") — this is a finer-grained descriptive
attribute of one professional/one category, not a way to associate a professional with more
than one category. `pronto-coding`/QA should not read anything below as multi-category
support.

---

## 2. Data model

### 2.1 `sub_services` — new reference table

Mirrors `categories`' own column/constraint conventions (`data-model.md` §2.1) as closely
as makes sense for a child reference table, one level down.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `category_id` | `BIGINT` | NO | — | FK → `categories(id)` `ON DELETE RESTRICT` — same core-reference-data policy `categories` itself already gets referenced with (`data-model.md` §0's FK-policy convention: `RESTRICT` for FKs into core entities). A sub-service has no meaning without its parent category, but categories are essentially never deleted in this schema (fixed 8-row seed, no delete path exists anywhere), so this is a defensive default, not an expected operational concern. |
| `code` | `VARCHAR(50)` | NO | — | Stable machine key (e.g. `'plumbing_unclog'`), same role `categories.code` plays. Chosen to be globally unique by construction (each invented code is category-prefixed) even though nothing product-wise requires global uniqueness — see constraint note below. |
| `name_he` | `VARCHAR(100)` | NO | — | Hebrew display name (primary UI language, v1.0, same as `categories.name_he`). |
| `name_en` | `VARCHAR(100)` | NO | — | English display name (internal/dev use, same as `categories.name_en`). |
| `display_order` | `SMALLINT` | NO | — | Fixed UI ordering **within a category** (not global — each category's sub-services are numbered 1..N independently, mirroring `categories.display_order`'s own role one level up). |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |

**Constraints**: PK(`id`); FK(`category_id`) → `categories(id)` `ON DELETE RESTRICT`;
`UNIQUE(code)` (kept globally unique, matching `categories.code`'s own plain `UNIQUE`
convention exactly, rather than a `(category_id, code)` composite unique — simpler, and
harmless since every invented seed code is already category-prefixed and therefore
naturally global-unique; see §2.3).
**Indexes**: `idx_sub_services_category ON (category_id)` — the primary read pattern ("give
me this category's sub-services," both for `GET /api/categories`'s nested list and for
validating a professional's selection against their own `category_id`). Table stays small
(dozens of rows total across all 8 categories), so this index is cheap insurance more than a
load-bearing necessity, matching the same reasoning `data-model.md` used for
`idx_professionals_category`.

### 2.2 `professional_sub_services` — new join table

A professional's selected sub-services — pure many-to-many join between `professionals`
and `sub_services`, no independent meaning beyond the relationship itself. Modeled directly
on `favorites` (`data-model.md` §2.12), the closest existing precedent for "a pure
customer/professional-owned bookmark-shaped join row with a composite PK and no surrogate
`id`."

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `professional_id` | `BIGINT` | NO | — | **PK, part 1**. FK → `professionals(id)` `ON DELETE CASCADE` — same convention this schema already uses for every other professional-owned child row with no independent meaning (`sos_availability`, `professional_working_hours`, `professional_availability_blocks`, `favorites.professional_id`). |
| `sub_service_id` | `BIGINT` | NO | — | **PK, part 2**. FK → `sub_services(id)` `ON DELETE CASCADE` — **deliberately CASCADE, not RESTRICT**, even though `sub_services` is reference data like `categories`. Reasoning: `categories`/`sub_services` themselves use `RESTRICT` when *they* are the referenced side of a core-entity relationship (`professionals.category_id`, `issues.category_id`, `sub_services.category_id` above) — but `professional_sub_services` is not that kind of relationship; it is a pure join/bookmark row exactly like `favorites`, which already uses `CASCADE` on both its FKs including the one pointing at `professionals` (itself a core entity elsewhere). If a `sub_services` row were ever deleted (no delete path exists in v1.0 — only migrations insert rows here, same as `categories`), the only consequence should be that professionals' selections referencing it quietly disappear, not a blocked delete. Low-risk since no delete path exists to actually trigger this today. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | When the professional added this sub-service to their selection — sort key if ever needed, same role `favorites.created_at` plays. **Preserved across an edit** for sub-services that stay selected — see §3.2's note on diff-based (not delete-all) update semantics, which is what makes this column meaningful rather than reset on every save. |

**Constraints**: PK(`professional_id`, `sub_service_id`); FK(`professional_id`) →
`professionals(id)` `ON DELETE CASCADE`; FK(`sub_service_id`) → `sub_services(id)`
`ON DELETE CASCADE`.
**Indexes**: none beyond the PK — the only v1.0 access pattern is "this professional's
selected sub-services," served by the PK's leading `professional_id` column. **Not**
adding an index on `sub_service_id` alone (which would support "which professionals offer
sub-service X") — no such query exists in this pass; see §4 for why that's deliberately
out of scope. Flag to add `idx_professional_sub_services_sub_service ON (sub_service_id)`
if/when a sub-service-based filter feature is ever built.

### 2.3 Seed data — **my own invention, not sourced from any product document, flagged accordingly**

No source document (PRD, poster, presentation, OnePage, or the MS11 task brief itself)
enumerates sub-services per category. The list below is a plausible placeholder so the
reference table isn't empty at launch — **present this to the user/lead as content
requiring product sign-off, not as a decided requirement.** `pronto-coding` should treat
this as the seed migration's starting content, freely editable before/after this ships.

| category (`code`) | sub-service `code` | `name_he` | `display_order` |
|---|---|---|---|
| `plumbing` | `plumbing_unclog` | פתיחת סתימות | 1 |
| `plumbing` | `plumbing_leak_repair` | תיקון נזילות | 2 |
| `plumbing` | `plumbing_faucet_install` | התקנת ברזים | 3 |
| `plumbing` | `plumbing_boiler_replace` | החלפת דוד מים | 4 |
| `plumbing` | `plumbing_toilet_repair` | תיקון אסלות ומקלחות | 5 |
| `electrical` | `electrical_fault_repair` | תיקון תקלות חשמל | 1 |
| `electrical` | `electrical_outlet_install` | התקנת שקעים ומפסקים | 2 |
| `electrical` | `electrical_lighting_install` | התקנת גופי תאורה | 3 |
| `electrical` | `electrical_panel_upgrade` | שדרוג לוח חשמל | 4 |
| `electrical` | `electrical_safety_check` | בדיקות בטיחות חשמל | 5 |
| `ac_hvac` | `hvac_install` | התקנת מזגן | 1 |
| `ac_hvac` | `hvac_repair` | תיקון מזגן | 2 |
| `ac_hvac` | `hvac_maintenance` | ניקוי ותחזוקת מזגן | 3 |
| `ac_hvac` | `hvac_gas_refill` | טעינת גז קירור | 4 |
| `appliance_repair` | `appliance_washer_repair` | תיקון מכונת כביסה | 1 |
| `appliance_repair` | `appliance_fridge_repair` | תיקון מקרר | 2 |
| `appliance_repair` | `appliance_dishwasher_repair` | תיקון מדיח כלים | 3 |
| `appliance_repair` | `appliance_oven_repair` | תיקון תנור/כיריים | 4 |
| `locksmith` | `locksmith_lockout` | פריצת דלת | 1 |
| `locksmith` | `locksmith_cylinder_replace` | החלפת צילינדר | 2 |
| `locksmith` | `locksmith_lock_install` | התקנת מנעול | 3 |
| `locksmith` | `locksmith_key_duplication` | שכפול מפתחות | 4 |
| `carpentry` | `carpentry_furniture_repair` | תיקון והרכבת רהיטים | 1 |
| `carpentry` | `carpentry_cabinet_install` | התקנת ארונות | 2 |
| `carpentry` | `carpentry_custom_woodwork` | עבודות עץ בהתאמה אישית | 3 |
| `carpentry` | `carpentry_door_repair` | תיקון דלתות עץ | 4 |
| `painting` | `painting_interior_walls` | צביעת קירות פנים | 1 |
| `painting` | `painting_exterior` | צביעת חוץ | 2 |
| `painting` | `painting_wall_patching` | שפכטל ותיקוני קיר | 3 |
| `painting` | `painting_ceilings` | צביעת תקרות | 4 |
| `general_handyman` | `handyman_general_repairs` | תיקונים כלליים בבית | 1 |
| `general_handyman` | `handyman_furniture_assembly` | הרכבת רהיטים | 2 |
| `general_handyman` | `handyman_wall_mounting` | תלייה על קיר (מדפים/תמונות) | 3 |
| `general_handyman` | `handyman_routine_maintenance` | תחזוקה שוטפת | 4 |

(34 rows total; `name_en` omitted from the table above for brevity, expected to be a
straightforward English gloss of each `name_he` in the actual migration — same pattern
`categories`' own seed already uses.)

Suggested migrations (next available Flyway version, per the current highest applied
migration `V28__alter_users_add_phone.sql`):

- `V29__create_sub_services.sql` — `CREATE TABLE sub_services` + the seed `INSERT`s above in
  one migration (combining create+seed, unlike `categories`' historical `V1`/`V10` split,
  which was an artifact of `categories` predating the seed-data need being finalized — no
  reason to replicate that split for a table that needs seed data from day one).
- `V30__create_professional_sub_services.sql` — `CREATE TABLE professional_sub_services`,
  empty at migration time (every existing professional starts with zero selected
  sub-services, same "expected onboarding state, not a migration gap" framing
  `professional_working_hours` already used for its own empty-at-migration-time state).

---

## 3. Backend API

### 3.1 `GET /api/categories` — new, answers "support future changes without hardcoding into the UI"

**Recommended: build this.** This is the concrete mechanism that satisfies the brief's
"support future service/sub-service changes without hardcoding the entire structure into
the UI" requirement — without it, the only way to add/rename/reorder a sub-service (or
even a category) would be a frontend code change and redeploy, exactly the failure mode the
requirement calls out.

- **Auth**: none required — public, unauthenticated. Categories/sub-services are
  non-sensitive reference data (the existing static `CATEGORIES` mirror is already
  effectively public, embedded in the frontend bundle), and leaving this endpoint
  unauthenticated is what would let it also someday serve the pre-login registration screen
  (`ProfessionalRegisterForm.tsx`) without redesign — not built this pass (§3.3), but the
  endpoint shape shouldn't foreclose it.
- **Response** — `professionals.dto.CategoryWithSubServicesResponse`, a list, ordered by
  `display_order`:

```json
[
  {
    "id": 1,
    "code": "plumbing",
    "nameHe": "אינסטלציה",
    "nameEn": "Plumbing",
    "displayOrder": 1,
    "subServices": [
      { "id": 101, "code": "plumbing_unclog", "nameHe": "פתיחת סתימות", "nameEn": "Unclogging", "displayOrder": 1 },
      { "id": 102, "code": "plumbing_leak_repair", "nameHe": "תיקון נזילות", "nameEn": "Leak repair", "displayOrder": 2 }
    ]
  }
]
```

- **Implementation**: `professionals.controller.CategoriesController` (new, small,
  read-only — kept in the `professionals` package rather than a new dedicated package,
  following the existing, already-accepted precedent that `Category`/`CategoryRepository`
  live in `professionals` and are consumed cross-package by `issues` directly — see
  `issues.service.IssuesService`'s existing direct dependency on
  `professionals.repository.CategoryRepository`, `issues/README.md`. `SubService`/
  `SubServiceRepository` follow the same placement for the same reason: proportionate for a
  2-person team, no new package needed purely to host two small read-only reference
  entities). One query (`CategoryRepository.findAllByOrderByDisplayOrderAsc` joined with
  `SubServiceRepository.findAllByOrderByCategoryIdAscDisplayOrderAsc`, assembled in memory —
  8 categories × ~4 sub-services each is trivial data volume, no need for a JPA
  `@OneToMany` graph).

### 3.2 `GET`/`PUT /api/professionals/me/sub-services` — select/edit, reusing the working-hours precedent exactly

Same full-replace shape as `PUT /api/availability/working-hours`
(`availability.dto.WorkingHoursUpdateRequest`/`AvailabilityController`/
`AvailabilityService#updateWorkingHours`), per the task brief's explicit instruction to
reuse that precedent rather than inventing a different shape. Both routes: **PROFESSIONAL
only**, gated the same way `GET`/`PUT /api/professionals/me` already are (add the two new
literal paths to `professionals.config.ProfessionalsWebConfig`'s existing
`RoleRequiredInterceptor` registration).

**`GET /api/professionals/me/sub-services`** — `professionals.dto.MySubServicesResponse`:

```json
{ "subServiceIds": [101, 102, 104] }
```

Deliberately returns only ids, not full sub-service objects — the frontend already has (or
fetches once) the full catalog via `GET /api/categories` and only needs to know which ids
are checked. Avoids duplicating `code`/`nameHe`/`nameEn` across two endpoints.

**`PUT /api/professionals/me/sub-services`** — `professionals.dto.UpdateSubServicesRequest`:

```json
{ "subServiceIds": [101, 104, 106] }
```

- `@NotNull List<@NotNull Long> subServiceIds` — see §5 for whether an empty list should be
  rejected (flagged, not decided here).
- Server-side dedupe (wrap in a `Set` before persisting) — defensive; a checkbox-driven UI
  can't produce duplicates, but the endpoint shouldn't rely on that.
- **Validation, in order**:
  1. Every id must exist in `sub_services` — any unknown id → **`400 VALIDATION_ERROR`**
     (this is a body-referenced id pointing at another entity, not a path id naming the
     resource itself, so it follows `api-contract-professionals-reviews.md` §0's existing
     "body-field id referencing another entity → `400 VALIDATION_ERROR`" rule, not `404`).
  2. Every id's `category_id` must equal the caller's own `professionals.category_id` — any
     id belonging to a different category → **`400 CATEGORY_MISMATCH`**, reusing the
     **existing** `ErrorCode.CATEGORY_MISMATCH` (`common/exception/ErrorCode.java`,
     currently used by `bookings.service.BookingsService#categoryMismatch` for "the
     professional's category doesn't match the issue's category"). This is a direct, exact
     semantic fit — "a referenced sub-service's category doesn't match the caller's own
     category" — and reusing it avoids growing the error taxonomy for a case that's already
     named. No new `ErrorCode` value needed for this endpoint.
- **Update semantics — diff, not delete-all-then-reinsert**: load the caller's existing
  `professional_sub_services` rows, compute the symmetric difference against the requested
  id set, delete only the removed rows, insert only the newly-added rows, leave unchanged
  rows untouched (preserves `created_at` for sub-services that stay selected across an
  edit — see §2.2). Same "load existing rows, update-in-place/insert missing, all inside one
  `@Transactional` method" spirit `AvailabilityService#updateWorkingHours` already
  established, adapted for a set-membership join table instead of a fixed 7-row upsert.
- **Response**: same shape as `GET`, the canonical post-save state — matches
  `updateWorkingHours`'s own "full-replace endpoint returns the full resulting list" return
  convention.

### 3.3 What is deliberately *not* changed this pass

- **`shared/api/categories.ts`'s static `CATEGORIES` mirror is left as-is, not replaced.**
  It has ~10 existing call sites across the frontend (registration category `<Select>`,
  `getCategoryNameHe` used on 6+ pages for read-only category-name display, etc.) — see
  `frontend/src/shared/api/index.ts` and the grep results this design was built from.
  Migrating every one of those call sites to fetch from the new `GET /api/categories`
  instead is a real, separable piece of frontend work with no functional bug driving it
  (the static list is small, fixed, and — for the 8 bare category rows — already correct);
  doing it as a side effect of MS11 would be scope creep beyond "add sub-services." The new
  endpoint answers the "hardcoding" concern **for the sub-services axis specifically**
  (the actual MS11 ask) — the pre-existing static-category-list question is orthogonal,
  already-flagged historical debt (`categories.ts`'s own header comment: "Replace this with
  a real fetch if/when a categories endpoint is added") that this design doc surfaces as a
  natural, low-risk follow-up but does not fold into MS11's build scope. Flag to
  `pronto-lead` as a candidate for its own small follow-up ticket, not blocking this one.
- **No customer-facing sub-service filter/matching.** `bookings`'s Standard/SOS
  professional-listing queries still filter only by `issue.category_id` vs.
  `professional.category_id`, unchanged. See §4.
- **No change to `professionals.category_id`, `UpdateProfessionalProfileRequest`, or
  `PUT /api/professionals/me`'s existing behavior** — sub-service selection is a fully
  separate endpoint pair, not folded into the existing profile-edit DTO (mirrors why
  working-hours/blocks are their own endpoints rather than fields on
  `UpdateProfessionalProfileRequest`).

---

## 4. Should sub-services ever factor into matching/filtering? (recommendation, not built)

The task brief only asks for professional-side select/edit — no customer-facing
filter-by-sub-service UI is requested, and none is built here. **Recommendation for a
future pass, not this one**: if this is ever wanted, the natural extension is an *optional*
`subServiceId` query param on `GET /api/bookings/professionals`/`sos-professionals`,
narrowing the existing category-filtered result set further via an `EXISTS` against
`professional_sub_services` — no schema change would be needed beyond the
`idx_professional_sub_services_sub_service` index flagged in §2.2. This is deliberately
**not designed further here** — flagging the shape only so a future task doesn't have to
re-derive it from scratch, not committing to build it. For this pass, sub-services are a
purely descriptive "what I offer" attribute on the professional's own profile.

---

## 5. Frontend

### 5.1 `/pro/profile` — the sub-service checklist

Lives in `ProfileEditorPage.tsx`'s existing form column (`.form`, MS10's `1fr` grid
column — see `product-ms10-profile-redesign-design.md` §2.3/§2.4), as a new section
**below the existing `basePrice` field, above the save button** — the natural place a
"what I offer" attribute belongs, next to the rest of the editable business-profile fields,
without disturbing MS10's just-landed two-column layout.

- On mount, alongside the existing `getMyProfessionalProfile()` call, fetch:
  1. `GET /api/categories` (public, no auth needed, but called authenticated here same as
     every other call on this page — no special-casing needed) — find the entry matching
     `profile.categoryId`, read its `subServices` array as the full checklist option set
     scoped to the professional's own category (never show sub-services from other
     categories — reinforces §1's distinction).
  2. `GET /api/professionals/me/sub-services` — the current `subServiceIds` selection, used
     to pre-check the matching boxes.
- Render as a plain `<fieldset>` of native `<input type="checkbox">` + `<label>` pairs (one
  per sub-service, Hebrew `nameHe`, `dir="rtl"` inherited from the page). **No dedicated
  `Checkbox` primitive exists yet anywhere in this codebase** — `DESIGN_SYSTEM.md` §85 lists
  `Checkbox` as a planned-but-unbuilt primitive name, and no existing screen (favorites,
  reviews, or elsewhere) has an established multi-select-checklist pattern to reuse, despite
  the task context suggesting one might exist — confirmed by inspection, not assumed. Given
  that, building a small `shared/components/Checkbox.tsx` wrapper (label + native input,
  styled per `DESIGN_SYSTEM.md`'s token system) as part of this work is the right-sized
  choice — proportionate, and it's the first real consumer of the primitive
  `DESIGN_SYSTEM.md` already anticipated by name, not an invented one-off pattern.
- **Save behavior — separate from the main form's save button, or the same one?**
  Recommend: **its own small "save" affordance for this section** (e.g. a compact
  `"שמירת תחומי עיסוק"` button directly under the checklist), calling
  `PUT /api/professionals/me/sub-services` independently of the main
  `handleSubmit`/`updateMyProfessionalProfile` flow. Reasoning: they're already two
  separate backend endpoints (§3.2), with independent success/error states and no shared
  validation — folding them into one visual "save" action would imply one atomic operation
  across two unrelated API calls, which is more complex to get right (partial-failure
  handling) for no real product benefit. This does mean the page has two save buttons
  instead of one — an explicit, deliberate proportionality tradeoff, not an oversight,
  flagged for `pronto-lead`/user sign-off since it's a small UX-shape decision (§6 alt: a
  single combined save is possible but requires either two sequential requests behind one
  button with combined error handling, or bundling `subServiceIds` into
  `UpdateProfessionalProfileRequest` and merging the two backend calls into one transaction
  — a bigger backend change than §3.2 designs; not recommended without a concrete reason).
- `shared/api/professionals.ts` gains: `getCategoriesWithSubServices()`
  (`GET /api/categories`, `CategoryWithSubServicesResponse[]`), `getMySubServices()`
  (`GET /api/professionals/me/sub-services`, `MySubServicesResponse`),
  `updateMySubServices(subServiceIds)` (`PUT /api/professionals/me/sub-services`, same
  response shape) — three small additions, shapes verified against the real backend DTOs
  once built, matching this file's existing convention.

### 5.2 Read-only display elsewhere — not built this pass

Neither the public professional-profile page (`ProfessionalProfilePage.tsx`) nor booking
cards show a professional's selected sub-services anywhere in this design — the brief asks
for select/edit only, and §4 already recommends against building customer-facing
consumption of this data yet. Flagged as a natural, low-risk follow-up (a professional's
public profile displaying "what they offer" as a read-only tag list would be a small
addition once `ProfessionalProfileResponse` optionally grows a `subServices` field) but
explicitly **not** part of this pass's scope.

---

## 6. Ambiguities flagged for lead/user sign-off

1. **Seed sub-service content (§2.3) is entirely my own invention** — no source document
   enumerates any. Needs explicit product sign-off before being treated as real content,
   not just "a reference table exists and is non-empty." Trivial to edit later (a plain
   seed migration, `VARCHAR + CHECK`-free reference rows) — not a structural risk, but the
   actual Hebrew wording/coverage per category is a product decision, not an engineering
   one.
2. **Is an empty sub-service selection allowed?** This design allows `subServiceIds: []` to
   be saved (no `@NotEmpty` on the request) — a professional who hasn't picked any
   sub-services yet, or has genuinely opted not to specify any, is a valid, un-blocking
   state. Alternative: require at least one selection once a professional has any category
   assigned (`@NotEmpty`), forcing every professional to specify something. No source
   document expresses a preference either way. Recommend allowing empty (matches this
   project's general bias toward not blocking on optional-feeling fields, e.g. `bio` is
   optional on the main profile), but flagging since "required vs. optional" changes the
   validation annotation and the UI's empty-state messaging.
3. **One always-editable list vs. a separate first-time "onboarding" selection step?** The
   task says "select... and... edit later," which this design reads as **one unified
   always-editable checklist** (no separate onboarding-vs-edit UI distinction — the same
   `/pro/profile` checklist serves both the first selection and every later edit, exactly
   like every other field on that page). An alternative reading — a dedicated
   post-registration "choose your sub-services" step, separate from the ongoing profile
   editor — is not built here since nothing in the brief or the existing registration flow
   (`ProfessionalRegisterForm.tsx`, which doesn't collect sub-services today) suggests a
   distinct onboarding surface is wanted. Flagging the reading explicitly since it's a
   product-shape choice, not purely mechanical.
4. **Two separate save buttons on `/pro/profile` (§5.1)** — flagged above, repeated here
   for visibility: needs sign-off if a single unified save action is preferred instead.
5. **`shared/api/categories.ts`'s static mirror is left untouched (§3.3)** — flagged as a
   deliberate proportionality call, not an oversight; revisit as its own follow-up if
   `pronto-lead`/the user wants the static list fully retired in favor of `GET
   /api/categories` everywhere.

---

## 7. Files this design touches (for `pronto-lead` sequencing)

**New (backend):**
- `backend/src/main/resources/db/migration/V29__create_sub_services.sql`
- `backend/src/main/resources/db/migration/V30__create_professional_sub_services.sql`
- `backend/src/main/java/com/pronto/professionals/entity/SubService.java`
- `backend/src/main/java/com/pronto/professionals/entity/ProfessionalSubService.java` +
  `ProfessionalSubServiceId.java` (composite-key embeddable, mirroring
  `favorites.entity.Favorite`/`FavoriteId`'s existing pattern exactly).
- `backend/src/main/java/com/pronto/professionals/repository/SubServiceRepository.java`
- `backend/src/main/java/com/pronto/professionals/repository/ProfessionalSubServiceRepository.java`
- `backend/src/main/java/com/pronto/professionals/dto/SubServiceResponse.java`
- `backend/src/main/java/com/pronto/professionals/dto/CategoryWithSubServicesResponse.java`
- `backend/src/main/java/com/pronto/professionals/dto/MySubServicesResponse.java`
- `backend/src/main/java/com/pronto/professionals/dto/UpdateSubServicesRequest.java`
- `backend/src/main/java/com/pronto/professionals/controller/CategoriesController.java`
  (new, `GET /api/categories`, public/no route gate).

**Changed (backend):**
- `backend/src/main/java/com/pronto/professionals/controller/ProfessionalsController.java`
  — add `GET`/`PUT /api/professionals/me/sub-services`.
- `backend/src/main/java/com/pronto/professionals/service/ProfessionalsService.java` — add
  `getMySubServices`/`updateMySubServices`.
- `backend/src/main/java/com/pronto/professionals/config/ProfessionalsWebConfig.java` —
  add the two new literal paths to the existing `PROFESSIONAL`-only interceptor
  registration.

**New (frontend):**
- `frontend/src/shared/components/Checkbox.tsx` + `.module.css` (+ doc entry in
  `shared/components`'s README, per this project's "every package/module needs a named
  `.md` doc" rule).

**Changed (frontend):**
- `frontend/src/shared/api/professionals.ts` — add `getCategoriesWithSubServices`,
  `getMySubServices`, `updateMySubServices`, and their response/request types.
- `frontend/src/shared/api/README.md` — document the three additions above, per this
  project's convention of keeping that file in sync with every `shared/api` change.
- `frontend/src/features/dashboard/ProfileEditorPage.tsx` + `.module.css` — new
  sub-services checklist section (§5.1).
- `docs/architecture/data-model.md` §2 — add `sub_services`/`professional_sub_services`
  table specs (§2.1/§2.2 above) once this design is approved; update §1's entity list and
  §6's ER diagram.
- `docs/architecture/api-contract-professionals-reviews.md` (or a new §, `pronto-lead`'s
  call) — document the three new endpoints once implemented.
