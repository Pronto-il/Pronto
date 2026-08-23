# Frontend MS8 — Professional Profiles, Reviews & Favorites: Design

Status: **DESIGN ONLY, not implemented.** Written by `pronto-planning` on branch
`frontend/MS8` (off `main`, tip after Frontend Milestone 6) against the real backend source
(`backend/src/main/java/com/pronto/{favorites,professionals,reviews}/**`, verified directly,
not copied from prose) and the real current frontend source (`frontend/src/**`, likewise
verified directly). This doc is the scope definition itself — no prior frontend contract doc
named this scope (unlike `api-contract-bookings.md` for Frontend Milestones 3/4).

**Precedent this doc follows**: `docs/architecture/ms3-ms4-corrections-design.md`'s
structure (scoped, single-purpose, read alongside `overview.md`/`data-model.md`/
`api-contract-professionals-reviews.md`/`frontend/Pronto — DESIGN_SYSTEM.md`, not restating
settled architecture).

**What this closes**: the backend feature set informally called "Milestone 8" (fully
specified in `docs/architecture/api-contract-professionals-reviews.md`, backend-complete,
QA-signed-off, **zero frontend consumption** per that doc's own "Frontend remains deferred
project-wide" note) has three leftover, never-built frontend areas:
1. Favorites (add/remove/list) — backend complete, frontend renders a read-only heart only.
2. A professional's own profile self-service (bio/city/price/photo edit) — backend complete,
   zero frontend usage; `ProfilePage.tsx` only shows the narrower `GET /api/users/me` view.
3. Reviews browsing (an individual professional's review list before booking) — backend
   complete, only `POST /api/reviews` (via `CompletionReviewPage.tsx`) is consumed.

The distance/ETA/rating-*display* part of backend Milestone 8 is **not** in this scope — it
was already consumed by Frontend Milestones 3/4 via `ProfessionalCard`/`ProfessionalList`.

---

## 0. Verified backend surface (recap, exact — do not re-derive from prose)

All four verified directly against source, not restated at full length here (see
`api-contract-professionals-reviews.md` §4 for the authoritative endpoint-by-endpoint spec):

| Endpoint | Role | Source verified |
|---|---|---|
| `POST /api/favorites` `{professionalId}` → `204` | CUSTOMER | `favorites/controller/FavoritesController.java`, `favorites/dto/AddFavoriteRequest.java` |
| `DELETE /api/favorites/{professionalId}` → `204` | CUSTOMER | same |
| `GET /api/favorites` → `FavoritesListResponse{favorites: FavoriteProfessionalSummary[]}` | CUSTOMER | `favorites/dto/FavoritesListResponse.java`, `FavoriteProfessionalSummary.java` |
| `GET /api/professionals/me` → `ProfessionalProfileResponse` | PROFESSIONAL | `professionals/controller/ProfessionalsController.java`, `professionals/dto/ProfessionalProfileResponse.java` |
| `PUT /api/professionals/me` (`UpdateProfessionalProfileRequest`) → `ProfessionalProfileResponse` | PROFESSIONAL | `professionals/dto/UpdateProfessionalProfileRequest.java` |
| `POST /api/professionals/me/profile-image` (multipart `file`) → `ProfileImageUploadResponse`, `201` | PROFESSIONAL | `professionals/dto/ProfileImageUploadResponse.java` |
| `GET /api/professionals/{professionalId}` → `ProfessionalProfileResponse` | either role, no route gate | `ProfessionalsWebConfig.java` |
| `GET /api/reviews?professionalId=` → `ReviewListResponse{professionalId, averageRating, reviewCount, reviews: ReviewResponse[]}` | either role, no route gate | `reviews/controller/ReviewsController.java`, `reviews/dto/ReviewListResponse.java` |

Exact DTO field lists (all confirmed against the `.java` records, camelCase on the wire):

```
FavoriteProfessionalSummary: professionalId, fullName, serviceArea, city, basePrice,
  profileImageUrl, averageRating, reviewCount, favoritedAt

ProfessionalProfileResponse: id, categoryId, fullName, serviceArea, city, bio, basePrice,
  profileImageUrl, averageRating, reviewCount, approvalStatus, favorited, createdAt, updatedAt
  (favorited: populated only on the {professionalId} route for a CUSTOMER caller; always
  null on /me and always null for a PROFESSIONAL caller)

UpdateProfessionalProfileRequest (allowlist): fullName, serviceArea, city, bio (optional,
  <=2000 chars), basePrice — deliberately excludes id/categoryId/approvalStatus/rating
  fields/profileImageKey (confirmed by the DTO's own Javadoc)

ProfileImageUploadResponse: imageKey, imageUrl, contentType, sizeBytes

ReviewResponse: id, professionalId, customerId, customerName, orderId, rating, comment,
  createdAt, updatedAt   (already used by CreateReviewRequest's response — reused verbatim)
```

**Confirmed, load-bearing**: `PUT /api/professionals/me`'s `fullName` field updates the
**underlying `users` row**, not a `professionals`-only field (per that DTO's own Javadoc) —
this has a frontend consequence, see §6 Risk 1.

---

## 1. New/extended `shared/api/` client modules

### 1.1 `frontend/src/shared/api/favorites.ts` (new file)

```ts
import { httpClient } from './httpClient';

export interface FavoriteProfessionalSummary {
  professionalId: number;
  fullName: string;
  serviceArea: string;
  city: string;
  basePrice: number;
  profileImageUrl: string | null;
  averageRating: number | null;
  reviewCount: number;
  favoritedAt: string;
}

export interface FavoritesListResponse {
  favorites: FavoriteProfessionalSummary[];
}

/** POST /api/favorites — CUSTOMER only, idempotent (204 even if already favorited). */
export function addFavorite(professionalId: number): Promise<void> {
  return httpClient.post<void>('/api/favorites', { professionalId });
}

/** DELETE /api/favorites/{id} — CUSTOMER only, idempotent (204 even if not favorited). */
export function removeFavorite(professionalId: number): Promise<void> {
  return httpClient.delete<void>(`/api/favorites/${professionalId}`);
}

/** GET /api/favorites — CUSTOMER only, created_at DESC, no pagination. */
export function getFavorites(): Promise<FavoritesListResponse> {
  return httpClient.get<FavoritesListResponse>('/api/favorites');
}
```

### 1.2 `frontend/src/shared/api/professionals.ts` (new file — the `professionals` package's
first frontend client, mirroring `bookings.ts`'s pattern of a dedicated file per backend
package)

```ts
import { httpClient } from './httpClient';

export interface ProfessionalProfileResponse {
  id: number;
  categoryId: number;
  fullName: string;
  serviceArea: string;
  city: string;
  bio: string | null;
  basePrice: number;
  profileImageUrl: string | null;
  averageRating: number | null;
  reviewCount: number;
  approvalStatus: string;
  /** Populated only on getProfessionalProfile() for a CUSTOMER caller; null everywhere else. */
  favorited: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateProfessionalProfileRequest {
  fullName: string;
  serviceArea: string;
  city: string;
  /** Optional, <=2000 chars server-side. */
  bio?: string;
  basePrice: number;
}

export interface ProfileImageUploadResponse {
  imageKey: string;
  imageUrl: string;
  contentType: string;
  sizeBytes: number;
}

/** GET /api/professionals/me — PROFESSIONAL only. */
export function getMyProfessionalProfile(): Promise<ProfessionalProfileResponse> {
  return httpClient.get<ProfessionalProfileResponse>('/api/professionals/me');
}

/** PUT /api/professionals/me — PROFESSIONAL only, allowlist DTO (no categoryId/id/etc). */
export function updateMyProfessionalProfile(
  payload: UpdateProfessionalProfileRequest,
): Promise<ProfessionalProfileResponse> {
  return httpClient.put<ProfessionalProfileResponse>('/api/professionals/me', payload);
}

/** POST /api/professionals/me/profile-image — PROFESSIONAL only, multipart field "file". */
export function uploadProfessionalProfileImage(file: File): Promise<ProfileImageUploadResponse> {
  const formData = new FormData();
  formData.append('file', file);
  return httpClient.post<ProfileImageUploadResponse>('/api/professionals/me/profile-image', formData);
}

/** GET /api/professionals/{id} — either role, no route gate. Public detail view. */
export function getProfessionalProfile(professionalId: number): Promise<ProfessionalProfileResponse> {
  return httpClient.get<ProfessionalProfileResponse>(`/api/professionals/${professionalId}`);
}
```

No changes needed to `httpClient.ts` — it already supports `PUT`/`DELETE` and `FormData`
bodies (used by `auth.ts`'s registration document upload and `storage.ts`'s `uploadImage`).

### 1.3 `frontend/src/shared/api/reviews.ts` (extend existing file)

Add, alongside the existing `CreateReviewRequest`/`createReview`:

```ts
export interface ReviewListResponse {
  professionalId: number;
  averageRating: number | null;
  reviewCount: number;
  reviews: ReviewResponse[];
}

/** GET /api/reviews?professionalId= — either role, no route gate, no pagination. */
export function getReviews(professionalId: number): Promise<ReviewListResponse> {
  return httpClient.get<ReviewListResponse>(`/api/reviews?professionalId=${professionalId}`);
}
```

### 1.4 `frontend/src/shared/api/index.ts` (extend)

Add barrel exports for the three modules above: `addFavorite`/`removeFavorite`/
`getFavorites` + `FavoriteProfessionalSummary`/`FavoritesListResponse`;
`getMyProfessionalProfile`/`updateMyProfessionalProfile`/`uploadProfessionalProfileImage`/
`getProfessionalProfile` + their types; `getReviews` + `ReviewListResponse` added to the
existing `reviews` export line.

---

## 2. The three judgment calls — decisions

### 2.1 Favorites nav placement — DECIDED: top-nav link, `AppLayout.tsx`, CUSTOMER-only

Add a `Link to="/favorites"` in `AppLayout.tsx`'s authenticated nav, CUSTOMER-only, placed
next to the existing `/orders` (`ההזמנות שלי`) link — matching `DESIGN_SYSTEM.md` §52's own
literal desktop-nav mockup verbatim (`בית   ההזמנות שלי   מועדפים`, `[Profile]` at the far
end). Use a `Heart` icon from `lucide-react` (already the icon set in use —
`LogOut`/`User`/`ClipboardList`/`LayoutDashboard`), matching the existing `navLink` styling
and the "once a destination is real, it belongs in nav" precedent `AppLayout.tsx`'s own doc
comment already states (used to justify adding `/orders`/`/pro` in Frontend Milestone 3).

Checked for conflicts: §50 (mobile bottom nav) also lists `מועדפים`, but this project is
desktop-first (per project scope) and `AppLayout.tsx` has no mobile bottom-nav
implementation at all yet — §50/§51 remain out of scope, consistent with every prior
milestone's treatment of them. No conflicting placement found anywhere else in
`DESIGN_SYSTEM.md`. **Decision, not left open**: top-nav link, CUSTOMER-only, next to
`/orders`.

**2026-08-18, same-day revision — overridden by explicit, later user UX decision.** The
user reviewed the implemented milestone and explicitly decided favorites should **not** be
a primary-nav destination: "Favorites is a secondary customer feature," reached via
`Profile -> Favorites` instead. `AppLayout.tsx`'s `/favorites` `Link` was removed;
`ProfilePage.tsx` gained a "מועדפים" link (customer-only) leading to the same `/favorites`
route, styled as a secondary action below the profile details card. The `DESIGN_SYSTEM.md`
§52 mockup match this section originally leaned on is superseded by this explicit product
call — not a re-litigation, a direct override. The `/favorites` route itself, its page, and
the favorite-toggle interaction on `ProfessionalProfilePage.tsx` are all unchanged.

### 2.2 Profile-editor location — DECIDED: new `/pro/profile` tab, NOT an extension of `ProfilePage.tsx`

**Decision**: a new PROFESSIONAL-only page/tab (`ProfileEditorPage.tsx`, route `/pro/profile`,
nested under `ProDashboardLayout`), reading/writing `professionals/me` — not an edit-mode
added to the existing shared `app/ProfilePage.tsx`.

**Reasoning**:
1. **`DESIGN_SYSTEM.md` §53's own professional-dashboard-sidebar mockup lists `▢ פרופיל`
   as a dedicated dashboard item**, alongside `בקשות חדשות`/`עבודות קרובות`/`יומן`/etc. —
   the exact items `ProDashboardLayout.tsx` already implements as tabs (3 of 7 so far). This
   is the closest concrete, already-partially-adopted precedent available; adding a 4th real
   tab extends an established pattern rather than inventing a new one.
2. **Different DTOs, different concerns, already kept separate by the existing code.**
   `ProfilePage.tsx`'s own doc comment states it's a "Read-only display of `GET
   /api/users/me`" — a cross-role, identity/account-level view (name, email, role, default
   address). `professionals/me` is a business-listing profile (bio, city, price, photo) —
   materially different data, different backend package, different endpoint family. Bolting
   write capability for one field subset, for one role only, onto a page whose entire
   existing contract is "read-only, both roles, one DTO" would break that page's single
   clear purpose and couple two unrelated concerns in one file.
3. **No regression to the existing `/profile` link.** The top-nav `הפרופיל שלי` link stays
   exactly as-is (read-only identity view, both roles) — a professional reaches their
   editable business profile through their own dashboard, not through a second, competing
   top-nav destination that would need to somehow coexist with the first.

**Alternative considered and rejected**: extending `ProfilePage.tsx` with a
role-conditional edit mode for `PROFESSIONAL` callers. Rejected — it has real minimal-diff
appeal (the page already renders overlapping fields: category, service area, price) but
fails on (2) above: it would need to silently switch its data source from `users/me` to
`professionals/me` for one role only, mid-component, turning one previously-simple read-only
page into two different pages wearing the same file. The dashboard-tab approach keeps every
existing file's contract unchanged and adds one clearly-scoped new one.

### 2.3 "View profile" vs. "select professional" card affordance — DECIDED: secondary link on the card, primary select button unchanged

**Decision**: `ProfessionalCard.tsx` keeps its existing primary `[ בחירת בעל מקצוע ]` button,
unchanged, still wired to `onSelect()` — **no regression to `BookingFlowPage`/
`SosBookingFlowPage`'s selection logic**. A new **secondary** affordance is added: the
identity block (photo + name) becomes a link/button that navigates to
`/professionals/:professionalId`, passing `{ fromIssueId, urgencyType }` via **router
`state`** (not a query param — see below), rendered only when the card is given an optional
new prop, `viewProfileContext?: { issueId: number; urgencyType: 'STANDARD' | 'SOS' }`, which
`BookingFlowPage`/`SosBookingFlowPage` always pass (both already know their own `issueId`
and urgency type).

**Why router `state`, not a query param on the URL**: `location.state` is deliberately
transient/non-bookmarkable — exactly right for "you got here from an active, already
category-filtered booking flow," a fact that should not survive a page refresh, a shared
link, or a direct visit. A query param would make `?fromIssueId=42` a shareable/bookmarkable
URL that silently implies flow context that may no longer be valid (issue could be
booked/expired by the time the link is opened later, in the wrong browser tab/session,
etc.). Concrete, accepted consequence: a **page refresh on `/professionals/:id` loses the
"select professional" CTA** (state is gone) — degrades to a view-only page, not an error.
Documented as a known, low-impact gap (§6), not silently unconsidered — same category as
this project's other already-accepted low-risk gaps (e.g. `overview.md` §6's duplicate-
`imageKey` gap).

**Why not read the global `useBookingDraft()` context instead of `location.state`**:
considered and rejected. A draft persists across the whole session and isn't scoped to
"the exact listing result the customer is currently looking at" — if a customer had an
unrelated in-progress draft for a different issue while browsing an unrelated professional's
profile (e.g. reached via `/favorites`, which has no flow context at all), reading the draft
would incorrectly offer a "select" CTA for a professional never filtered against that
draft's issue category, risking a category mismatch the backend doesn't itself guard against
at this call path. Router `state`, populated **only** from the one place we control (the
card's own "view profile" link, itself only reachable from an already category-filtered
listing), avoids that entirely.

**What the "select professional" CTA on the detail page actually does** (only rendered when
`location.state` carries `fromIssueId`/`urgencyType`): reuses each flow's own existing
booking-draft resume-hydration, unmodified. Concretely:
- STANDARD: `updateDraft({ stage: 'SLOT_SELECTION', professionalId })`, then
  `navigate(`/issues/${fromIssueId}/booking`)`. `BookingFlowPage`'s existing
  resume-hydration effect (unchanged) sees `draft.stage === 'SLOT_SELECTION'` on mount and
  fetches that professional's slots automatically — the exact same code path an in-flow
  card-button `onSelect()` already triggers.
- SOS: `updateDraft({ stage: 'BOOKING_CONFIRM', professionalId })`, then
  `navigate('/issues/${fromIssueId}/sos-booking')` — same reasoning, SOS has no slot step.
- **Not** via `resolveDraftRoute(draft)` read immediately after `updateDraft` — React state
  updates are async, so the freshly-patched `draft` isn't guaranteed to be visible in the
  same tick; navigate directly using the known `fromIssueId`/`urgencyType` from
  `location.state` instead.

This satisfies the constraint directly: **zero changes to `BookingFlowPage`'s or
`SosBookingFlowPage`'s selection logic** — the detail page's CTA is just another writer into
the same pre-existing draft/resume mechanism `BookingDraftIndicator` already relies on.

**Alternatives considered and rejected**:
- Replacing the primary button with "view profile" and moving `select` only onto the detail
  page — rejected: adds a mandatory extra click/page-load to every booking (a real UX
  regression to an already-shipped, working flow), and isn't required by either §29 (browse
  context) or §43 (a *separate* profile screen) — nothing says the in-flow list card must
  drop its own select action.
- Making the whole card clickable for "view profile" and keeping the button as the only other
  interactive element — rejected: two overlapping/nested click targets on one card is a
  known accessibility and UX anti-pattern (ambiguous which action a click card-body,
  not-on-button triggers); the identity-block-only link avoids overlapping with the button's
  own hit area entirely.

---

## 3. Route plan

Added to `frontend/src/app/router.tsx`:

```
{ element: <RequireAuth />, children: [
    ...,
    { path: 'professionals/:professionalId', element: <ProfessionalProfilePage /> },
] },
{ element: <RequireAuth role="CUSTOMER" />, children: [
    ...,
    { path: 'favorites', element: <FavoritesPage /> },
] },
{ element: <RequireAuth role="PROFESSIONAL" />, children: [
    { element: <ProDashboardLayout />, children: [
        ...,
        { path: 'pro/profile', element: <ProfileEditorPage /> },
    ] },
] },
```

- **`/professionals/:professionalId`** — bare `RequireAuth` (no role param), matching the
  backend's either-role, no-route-gate `GET /api/professionals/{id}`. Renders: photo (large,
  §30's 88-104px profile size), name, numeric rating + review count (§31 format, omitted
  when `averageRating` is null), city/service area, `bio` (if set), `basePrice`, a review
  list (§45 card format, via `getReviews`), a favorite toggle (rendered only when
  `user.role === 'CUSTOMER'`, calling `addFavorite`/`removeFavorite`, initial state from
  `professional.favorited`), and the "select professional" CTA described in §2.3 (rendered
  only when `location.state` carries flow context). `404 NOT_FOUND` → a simple not-found
  message, no crash.
- **`/favorites`** — CUSTOMER-only (matches the backend's CUSTOMER-only `GET
  /api/favorites`), grouped with the existing `/orders`/`/issues/new` CUSTOMER-only route
  block. Lists `getFavorites()`'s entries via a new lean card (§4.2), each with a "remove"
  action and a click-through to `/professionals/:id` (no `viewProfileContext` state passed —
  correctly produces a view-only detail page per §2.3's design, since there is no
  issue/flow context from a favorites list).
- **`/pro/profile`** — PROFESSIONAL-only, nested under the existing `ProDashboardLayout`
  (4th tab, `פרופיל`, added after `יומן זמינות`).

---

## 4. Component/file plan

### 4.1 `features/professionals/` (extended)

- **`ProfessionalCard.tsx`** (extend): add optional `viewProfileContext` prop (§2.3); the
  identity block (photo + name) becomes a `Link`/click target to
  `/professionals/${professionalId}` carrying `state: viewProfileContext` when present.
  Primary button/`onSelect` behavior is otherwise **unchanged**.
- **`ProfessionalProfilePage.tsx`** (new) + `.module.css` — the `/professionals/:id` detail
  screen described in §3. Fetches `getProfessionalProfile(id)` and `getReviews(id)` (the
  latter can run in parallel, review list has its own independent loading/error state so a
  slow/failed review fetch doesn't block the rest of the page).
- **`ReviewList.tsx`** (new, co-located here as this page's only consumer — see §5's naming
  note) — renders §45's review-card format: `customerName` (already server-truncated-style
  as e.g. "משה לוי" — no further truncation applied client-side unless product asks),
  `rating` as 5 stars (★★★★★, filled count = `rating`, distinct from the numeric
  "★ 4.9 · 127" aggregate format §31 already established for cards/headers — these are two
  different, both-correct formats used in two different places per the design system
  itself), a relative "N ימים" age label (§6 Risk 3 — new small utility needed), and
  `comment` (when present). An explicit empty state ("אין עדיין ביקורות") when
  `reviews.length === 0`.
- **`index.ts`** (extend): export `ProfessionalProfilePage` for `router.tsx` to import
  (currently `features/professionals` exports nothing to `router.tsx` — its two existing
  components are consumed internally by `features/booking` only).

### 4.2 `features/favorites/` (new module — mirrors the backend's own `favorites` package
boundary, the same "one frontend feature folder per backend package with a dedicated
screen" pattern `features/notifications`/`features/dashboard` already follow)

- **`FavoritesPage.tsx`** + `.module.css` — fetches `getFavorites()`, renders a list of
  `FavoriteProfessionalCard`, empty state ("אין עדיין מועדפים" + likely a link back to `/`,
  exact copy is `pronto-coding`'s call, §6).
- **`FavoriteProfessionalCard.tsx`** + `.module.css` (new, deliberately **not** a reuse of
  `ProfessionalCard`) — `FavoriteProfessionalSummary` has no `distanceKm`/`etaMinutes`/
  `sameCity` fields at all (confirmed, §0), which `ProfessionalCard`'s prop type requires
  as non-nullable — reusing it would mean fabricating placeholder ETA/distance values with
  no real listing-request context behind them. A lean, dedicated card (photo, name, city,
  price, rating, a "הסרה ממועדפים" button calling `removeFavorite` + optimistic list update,
  click-through to `/professionals/:id`) mirrors the exact "favor the simpler option"
  reasoning the backend's own `FavoriteProfessionalSummary` Javadoc already gives for not
  reusing `bookings.dto.ProfessionalCard` server-side — the same judgment call, made
  consistently on both sides of the API boundary.
- **`index.ts`**, **`README.md`** (new, per this project's per-package doc-comment
  requirement, §7).

### 4.3 `features/dashboard/` (extended)

- **`ProfileEditorPage.tsx`** + `.module.css` (new) — form for `fullName`/`serviceArea`/
  `city`/`bio`/`basePrice` (the exact `UpdateProfessionalProfileRequest` allowlist), plus a
  read-only `categoryId` display (via the existing `getCategoryNameHe` helper — **not**
  editable, matching the backend's own deliberate exclusion; the DTO carries no field to
  change it through). Loads via `getMyProfessionalProfile()` on mount, saves via
  `updateMyProfessionalProfile()`. `approvalStatus` is **not** rendered — auto-approved in
  v1.0 (project-wide confirmed rule), so it carries no actionable information for the
  professional to see today; trivial to add later if that changes.
  **That changed — Production Roadmap MS1 (2026-08-22).** Auto-approval is superseded
  (`overview.md` §2's Professional approval row), and this package now carries
  `features/dashboard/OnboardingStatusNotice.tsx`, which renders whenever the backend reports
  `bookable: false` and links to the surfaces that fix it. This paragraph is kept as the record
  of the original design decision, not as a description of current behaviour.
- **`ProfessionalProfileImageField.tsx`** (new, thin wrapper) — composes the existing
  `shared/components/ImageUploadField.tsx` (per the task's own instruction: reuse it, don't
  invent a new upload pattern) for the pick/preview/remove UI, but — mirroring
  `PhotoUploader.tsx`'s existing "upload immediately on selection" pattern rather than
  `ImageUploadField`'s own "hold a `File` for a later multipart submit" default behavior —
  calls `uploadProfessionalProfileImage(file)` as soon as a file is selected (the backend
  models the image as its **own** endpoint, independent of `PUT /me`'s field save, so there
  is no "submit the whole form together" moment to wait for). Reports the new
  `profileImageUrl` back to the parent page immediately on success so the displayed photo
  updates without a full profile refetch; surfaces an inline error via `ImageUploadField`'s
  existing `error` prop on failure (same pattern `PhotoUploader` uses for per-item errors).
- **`ProDashboardLayout.tsx`** (extend): add a 4th `NavLink` (`/pro/profile`, label
  `פרופיל`), same styling/pattern as the existing 3.
- **`index.ts`** (extend): export `ProfileEditorPage`.

### 4.4 `app/` (extended)

- **`AppLayout.tsx`** (extend, §2.1): add the CUSTOMER-only `/favorites` `Link`.
- **`router.tsx`** (extend, §3): add the three new routes.

### 4.5 `shared/` (extended)

- **`shared/api/favorites.ts`** (new, §1.1), **`shared/api/professionals.ts`** (new, §1.2),
  **`shared/api/reviews.ts`** (extend, §1.3), **`shared/api/index.ts`** (extend, §1.4).
- **`shared/utils/formatDateTime.ts`** (extend) — add a small relative-age helper (e.g.
  `formatRelativeAgeLabel`) for §45's "4 ימים" review-timestamp format, following this
  file's existing "extracted here rather than reimplemented per screen" convention (its own
  doc comment). No such relative-time helper exists yet — every current consumer
  (`SlotPicker`/`BookingSummary`/`OrderTrackingPage`/`MyOrdersPage`/`IncomingRequestCard`)
  uses the absolute `formatDateTimeLabel`/`formatDateLabel`, none render a relative age.
  Exact granularity beyond "N ימים" (hours/weeks/months wording) is left to `pronto-coding`
  to interpolate reasonably, consistent with this file's existing "היום/מחר" precedent — no
  source document specifies it further (§6 Risk 4).
- **`shared/components/ImageUploadField.tsx`** — reused as-is, no changes needed.

---

## 5. Naming note (minor, low-stakes judgment call, flagged for completeness)

`ReviewList.tsx` is placed under `features/professionals/` (its only consumer,
`ProfessionalProfilePage.tsx`, lives there) rather than creating a new `features/reviews/`
module for one presentational component with no route of its own. This mirrors the backend's
own "co-locate until there's a second consumer" posture is not an established written rule
in this codebase, but is consistent with how small, single-consumer pieces are already
placed elsewhere (e.g. `BookingSummary.tsx`/`SlotPicker.tsx` living inside `features/booking`
rather than their own modules). If a second consumer of the review list ever appears, this
is trivially movable.

---

## 6. Risks / open questions (flagged, not silently resolved)

1. **Stale cached `user.fullName` after a professional edits their profile.**
   `PUT /api/professionals/me`'s `fullName` field writes to the underlying `users` row
   (confirmed, §0) — but `AuthProvider.tsx` (read in full) only populates `user` on mount
   (`rehydrate()`, one-time `GET /api/users/me` call) and on `login()`; it exposes **no**
   `refreshUser()`/refetch method today. If a professional changes their display name via
   the new `/pro/profile` editor, `useAuth().user.fullName` (used elsewhere, e.g. anywhere
   that might greet the user by name) will show the **old** name until the next full
   page load or re-login. **Recommendation, not silently assumed**: `AuthProvider` should
   gain a small `refreshUser()` method (re-running the same `getMe()` call `login()` already
   does) that `ProfileEditorPage` calls after a successful save. This is a small, real
   addition to `AuthProvider.tsx` — flagging it explicitly rather than letting
   `pronto-coding` discover the staleness as a live bug during implementation/QA.
2. **Router-`state`-loss on refresh** (§2.3) — a deliberate, accepted degradation (view-only
   fallback, no error), not a defect. Restated here for visibility alongside the other risks.
3. **No empty-state copy specified anywhere** for a professional with zero reviews, or a
   customer with zero favorites — `DESIGN_SYSTEM.md` doesn't give exact wording for either.
   Left to `pronto-coding`'s reasonable judgment (simple, on-brand Hebrew copy, no dead
   links) — flagged so it isn't silently invented and then treated as a settled decision
   later without anyone having chosen it deliberately.
4. **New relative-time formatter has no spec beyond "N ימים"** (§4.5) — granularity for
   very recent (minutes/hours) or old (weeks/months) reviews isn't specified by
   `DESIGN_SYSTEM.md` §45's single example. Left to `pronto-coding` to interpolate
   reasonably.
5. **Newly-registered professionals' `city = NULL` gap** (`api-contract-professionals-
   reviews.md` §9 item 1, still open, unaffected by this doc) — building the self-service
   editor gives a professional a real, in-app way to *fix* this themselves (visit
   `/pro/profile`, set `city`, save) for the first time, but does **not** itself close the
   gap — a professional who never visits the new editor still has `city = NULL` and the
   conservative "different city" ETA default. Not a new risk introduced here, just newly
   remediable — worth noting for `pronto-lead` in case this changes the gap's priority.
6. **A `PROFESSIONAL` caller can open `/professionals/:id` for any professional, including
   themselves**, since the backend route is either-role with no ownership check. Harmless
   (read-only for that role — no favorite toggle, no select CTA, `favorited` is always
   `null` per the backend's own design) but not a designed-for use case either; merely not
   blocked. No action needed, flagged for completeness only.
7. **No pagination on `GET /api/reviews`/`GET /api/favorites`** (confirmed backend behavior,
   `api-contract-professionals-reviews.md` §9 item 7) — a professional with a very large
   review count, or a customer with a very large favorites list, gets an unpaginated full
   list rendered client-side. Consistent with this project's existing MVP-scale tolerance
   for every other unpaginated list endpoint — not a new gap, restated for visibility since
   this is the first UI actually rendering either list.

---

## 7. Documentation follow-ups (for `pronto-documentation`)

Per this project's "every package/module gets a named `.md` doc" rule:
- `frontend/src/features/favorites/README.md` — **new**, module doesn't exist yet.
- `frontend/src/features/professionals/README.md` — **update**, new
  `ProfessionalProfilePage`/`ReviewList`/`viewProfileContext` additions.
- `frontend/src/features/dashboard/README.md` — **update**, new `ProfileEditorPage`/
  `ProfessionalProfileImageField`/4th tab.
- `frontend/src/app/README.md` — **update**, `AppLayout`'s new nav link, `router.tsx`'s new
  routes.
- `frontend/src/shared/api/README.md` — **update**, three new/extended client modules.
- `docs/architecture/implementation-plan.md` — a new milestone entry once this is
  implemented and QA'd (following the existing "Milestone 8"/"MS3-MS4 Product-Corrections
  Pass" entry conventions), not created by this design doc itself.
