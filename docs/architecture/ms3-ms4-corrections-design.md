# MS3/MS4 Product-Corrections Design

Status: Items 1, 2, 4 are **design only, not yet implemented**. Item 3 (sort toggle) has
been **implemented ahead of design sign-off** by a coding agent that went out of scope on
an unrelated task, and is now reconciled below (2026-08-17 update) — see §3 for the
resolved decision, verified against actual code via `git diff`, and grounded in
`frontend/Pronto — DESIGN_SYSTEM.md` §31-34 (a source this doc's original draft of §3 had
not consulted).

This doc is scoped, single-purpose, and meant to be read alongside the existing
`overview.md`, `data-model.md`, `api-contract-bookings.md`, and
`frontend/Pronto — DESIGN_SYSTEM.md` — it does not restate settled architecture, only the
deltas needed for these 4 corrections.

---

## 1. Expose customer's default address on `GET /api/users/me`

**Precedent followed**: `ProfessionalInfo` — a small nested record, populated only for
the role it applies to, `null` otherwise (`professionalRepository.findByUserId(...).map(...).orElse(null)`
pattern in `UsersService.getMe`).

### 1.1 New DTO

`backend/src/main/java/com/pronto/users/dto/DefaultAddressInfo.java` (new file):

```java
package com.pronto.users.dto;

/**
 * The nested {@code defaultAddress} object in {@code GET /api/users/me}'s response for a
 * {@code CUSTOMER}-role caller with a saved default address. {@code null} for a
 * {@code PROFESSIONAL} caller (the {@code users.default_*} columns are always null for
 * that role), and also {@code null} for a {@code CUSTOMER} with no recorded default city
 * (pre-V20 accounts) — mirrors {@link ProfessionalInfo}'s "absent means no such object"
 * convention rather than returning a partially-empty shape.
 */
public record DefaultAddressInfo(
        String city,
        String street,
        String houseNumber,
        String apartment,
        String floor,
        String entrance,
        String addressNotes
) {
}
```

### 1.2 `UserMeResponse` field addition

```java
public record UserMeResponse(
        Long id,
        String fullName,
        String email,
        UserRole role,
        boolean emailVerified,
        ProfessionalInfo professional,
        DefaultAddressInfo defaultAddress
) {
}
```

Flat-fields-vs-nested-object judgment call: **nested**, matching `professional`'s
existing convention exactly (not a flat `defaultCity`/`defaultStreet`/... spray on the
top-level response) — this is not a new decision, it's applying the file's own existing
pattern.

### 1.3 `UsersService.getMe` change

```java
DefaultAddressInfo defaultAddress = null;
if (user.getRole() == UserRole.CUSTOMER && user.getDefaultCity() != null) {
    defaultAddress = new DefaultAddressInfo(user.getDefaultCity(), user.getDefaultStreet(),
            user.getDefaultHouseNumber(), user.getDefaultApartment(), user.getDefaultFloor(),
            user.getDefaultEntrance(), user.getDefaultAddressNotes());
}

return new UserMeResponse(user.getId(), user.getFullName(), user.getEmail(),
        user.getRole(), user.isEmailVerified(), professionalInfo, defaultAddress);
```

`user.getDefaultCity() != null` is the presence gate (city is one of the three
API-required fields at registration, per `V20`'s migration comment — a non-null city
implies a fully-populated address for any post-V20 CUSTOMER registration; a null city
means either a PROFESSIONAL or a pre-V20 CUSTOMER with no recorded address).

### 1.4 `UsersController`

No change — it already just passes through `usersService.getMe(...)`.

### 1.5 Scope note

Backend-only, response-shape addition. No migration (the columns already exist, from
`V20`). No new endpoint for *updating* the default address is introduced here — flagged
explicitly in §2.4 below, since item 2's booking-address design depends on this not
existing yet.

### 1.6 Frontend mirror

`frontend/src/shared/api/users.ts`'s `UserMeResponse` TypeScript interface needs a new
optional/nullable field:

```ts
export interface UserMeDefaultAddress {
  city: string;
  street: string;
  houseNumber: string;
  apartment: string | null;
  floor: string | null;
  entrance: string | null;
  addressNotes: string | null;
}

export interface UserMeResponse {
  // ...existing fields...
  defaultAddress: UserMeDefaultAddress | null;
}
```

Consumed via `useAuth().user.defaultAddress` by item 2's new `AddressSelectionStep`.

### 1.7 Docs to update (not done here)

- `docs/architecture/api-contract.md` §2.4 (response shape)
- `backend/src/main/java/com/pronto/users/README.md`

---

## 2. Booking address selection + orders schema gap

### 2.1 Current state confirmed by reading the code

- `frontend/src/shared/components/addressTypes.ts`'s `AddressValue` **already** has all 7
  fields (`city/street/houseNumber/apartment/floor/entrance/addressNotes`) — it was built
  to mirror `DefaultAddressRequest` exactly. `AddressFormFields.tsx` already renders all 7
  inputs. **No frontend form-field work is needed** — the gap is purely (a) the backend
  request/response/entity/migration only carrying 4 of the 7 fields, and (b) there being
  no default-vs-custom *choice* UI at all — today's `BookingFlowPage`/`SosBookingFlowPage`
  always render a blank `AddressFormFields` (`EMPTY_ADDRESS`) with no pre-fill and no
  option to reuse a saved address.
- `BookingSummary.tsx` (`handleConfirm`) and `SosBookingSummary.tsx` (`handleConfirm`)
  currently forward only `serviceCity/serviceStreet/serviceHouseNumber/serviceApartment`
  to `createOrder`/`createSosOrder` — confirmed by direct reading, matching the brief's
  expectation. This is what pronto-coding is replacing: both need to forward all 7 fields
  once the backend accepts them.

### 2.2 Migration — **V22** (reverified: current max is `V21__alter_professionals_add_verification_document.sql`)

New file: `backend/src/main/resources/db/migration/V22__alter_orders_add_service_address_details.sql`

```sql
-- Extends the service-address snapshot (V18) with the 3 fields V18 omitted, matching the
-- full 7-field shape already established on users.default_* (V20). Nullable at the DB
-- level, same convention as V18/V20 -- no backfillable source of truth for existing
-- orders' floor/entrance/notes. Enforced as OPTIONAL (not required) at the API layer too,
-- same as the pre-existing serviceApartment -- floor/entrance/address notes are optional
-- address detail, never blocking, on both the default-address and custom-address paths.

ALTER TABLE orders ADD COLUMN service_floor VARCHAR(20);
ALTER TABLE orders ADD COLUMN service_entrance VARCHAR(20);
ALTER TABLE orders ADD COLUMN service_address_notes VARCHAR(500);
```

Column lengths match `users.default_floor`/`default_entrance`/`default_address_notes`
exactly (`VARCHAR(20)`/`VARCHAR(20)`/`VARCHAR(500)`), per V20's own precedent.

### 2.3 `Order` entity

Add 3 columns + getters; extend the constructor (insert the 3 new params after
`serviceApartment`, before `basePriceSnapshot`, so every existing call site's remaining
args just shift by 3 — no reordering of unrelated params):

```java
@Column(name = "service_floor", length = 20)
private String serviceFloor;

@Column(name = "service_entrance", length = 20)
private String serviceEntrance;

@Column(name = "service_address_notes", length = 500)
private String serviceAddressNotes;
```

```java
public Order(Long issueId, Long customerId, Long professionalId, Instant bookedStart,
             Instant bookedEnd, BigDecimal finalPrice, Long slotId, String serviceCity, String serviceStreet,
             String serviceHouseNumber, String serviceApartment, String serviceFloor, String serviceEntrance,
             String serviceAddressNotes, BigDecimal basePriceSnapshot, BigDecimal sosSurcharge) {
    // ...existing assignments..., then:
    this.serviceFloor = serviceFloor;
    this.serviceEntrance = serviceEntrance;
    this.serviceAddressNotes = serviceAddressNotes;
    // ...basePriceSnapshot/sosSurcharge as before
}
```

Plus `getServiceFloor()`/`getServiceEntrance()`/`getServiceAddressNotes()` getters,
following the existing getter-only (no setters) convention for this entity's fields.

### 2.4 Request DTOs

`CreateOrderRequest` and `CreateSosOrderRequest` both get 3 new **optional** fields
(no `@NotBlank`, exactly mirroring `serviceApartment`'s existing optionality):

```java
public record CreateOrderRequest(
        @NotNull @Positive Long issueId,
        @NotNull @Positive Long professionalId,
        @NotNull @Positive Long slotId,
        @NotBlank String serviceCity,
        @NotBlank String serviceStreet,
        @NotBlank String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes
) {
}
```

(`CreateSosOrderRequest` identical minus `slotId`, same as today's relationship between
the two.)

### 2.5 Response DTOs

`OrderResponse` and `OrderDetailResponse` both gain the same 3 fields, placed
immediately after `serviceApartment`:

```java
String serviceCity,
String serviceStreet,
String serviceHouseNumber,
String serviceApartment,
String serviceFloor,
String serviceEntrance,
String serviceAddressNotes,
```

### 2.6 `BookingsService`

- `createOrder`/`createSosOrder`: pass `request.serviceFloor()`, `request.serviceEntrance()`,
  `request.serviceAddressNotes()` into the new `Order(...)` constructor call, in the new
  parameter slots — **regardless of whether the customer chose the default address or a
  custom one**. This is the key point: the service layer does not need to know or care
  which address source the frontend used; it just persists whatever 7 values arrive in
  the request body. The "default vs custom" distinction is a purely frontend/UX concern
  (§2.7) — the backend contract is address-source-agnostic by design, so no new field
  (e.g. `addressSource: DEFAULT|CUSTOM`) needs to be added to the request/order at all.
- `toOrderResponse(...)` and `getOrderDetail(...)`: add `order.getServiceFloor()`,
  `order.getServiceEntrance()`, `order.getServiceAddressNotes()` to both response
  constructions.
- No change to `listProfessionals`/`listSosProfessionals`/`ServiceLocation` — confirmed
  by reading `matching.ServiceLocation` (city/street/houseNumber/apartment only, used
  purely for the city-level ETA approximation) and `BookingsController.parseServiceLocation`
  that the professional-listing query params are untouched by this correction. Floor/
  entrance/notes only matter for the final persisted order snapshot, not the listing
  search.

### 2.7 Frontend: address selection UI (new)

**New component**: `frontend/src/features/booking/AddressSelectionStep.tsx` (+
`.module.css`), feature-local (not `shared/components`) since it's booking-flow-specific
composition on top of the already-shared `AddressFormFields` primitive — same placement
logic as `SlotPicker`/`BookingSummary` already living in `features/booking`.

Behavior:
- Two-option chooser: "כתובת ברירת המחדל שלי" (my default address) vs. "כתובת אחרת לפעם
  הזו" (a different address, just this once) — radio/segmented-control style, consistent
  with the existing `ProfessionalList`'s sort-chip visual language.
- **Default option**: only offered if `useAuth().user?.defaultAddress` is non-null (per
  §1.6). When selected, render the saved address **read-only** for confirmation (full 7
  fields shown as text, not editable inputs) — satisfies "shown in full for confirmation."
  No form validation needed (already-valid data).
- **Custom option**: renders the existing `AddressFormFields` unmodified, with the same
  validation already in both flow pages' `validateAddress()` (city/street/houseNumber
  required; apartment/floor/entrance/addressNotes optional).
- If the customer has no `defaultAddress` (pre-V20 account, or the rare case of it never
  having been set), the default option is hidden/disabled and custom is the only choice
  — no dead radio option pointing at nothing.
- **Never overwrites the saved default**: satisfied trivially — there is no
  "update default address" endpoint in this design (§1.5), so there is no call the custom
  path could even accidentally make that would mutate `users.default_*`. This must remain
  true going forward — if a future "edit my profile address" feature adds such an
  endpoint, this booking step must not call it.
- Output: whichever `AddressValue` (all 7 fields) was confirmed, threaded through the
  rest of the flow exactly as `address` already is today, then forwarded **in full** (all
  7 fields, not 4) to `createOrder`/`createSosOrder`.

**Wiring**: `BookingFlowPage.tsx` and `SosBookingFlowPage.tsx`'s `'address'` step
currently renders `<AddressFormFields value={address} onChange={setAddress} .../>`
directly — replace with `<AddressSelectionStep value={address} onChange={setAddress}
onContinue={...} />` (props shape at pronto-coding's discretion, but must preserve the
existing `handleAddressContinue`/`validateAddress` call sites' contract, since those
`Step`-machine transitions are otherwise unchanged).

**`BookingSummary.tsx` / `SosBookingSummary.tsx`**: change the `createOrder`/
`createSosOrder` calls to forward all 7 fields:

```ts
serviceCity: address.city,
serviceStreet: address.street,
serviceHouseNumber: address.houseNumber,
serviceApartment: address.apartment || undefined,
serviceFloor: address.floor || undefined,
serviceEntrance: address.entrance || undefined,
serviceAddressNotes: address.addressNotes || undefined,
```

Also update `frontend/src/shared/api/bookings.ts`'s `CreateOrderRequest`/
`CreateSosOrderRequest`/`OrderResponse`/`OrderDetailResponse` TS interfaces to add the 3
optional string fields, matching §2.4/§2.5's backend shapes exactly.

**Professional-facing display**: `OrderTrackingPage.tsx` (shared by both roles — it's
under the generic `RequireAuth` group in `router.tsx`, not CUSTOMER-only) currently
renders only `serviceCity/serviceStreet/serviceHouseNumber/serviceApartment` in its
address row. Recommend appending floor/entrance/addressNotes (when present) to that same
row/card — professionals need this information to actually locate/access the job, not
just customers. Small, low-risk addition to an existing display, no new step/screen.

### 2.8 Docs to update (not done here)

- `docs/architecture/api-contract-bookings.md` §2.4/§2.13 (request), §2.4-2.6/§2.8
  (response) — also still owes the pre-existing Milestone 8 addendum noted in
  `overview.md` §6, not new to this task.
- `docs/architecture/data-model.md` §2.9 (orders table)
- `backend/src/main/java/com/pronto/bookings/README.md`
- `frontend/src/features/booking/README.md`

---

## 3. Recommended vs Cheapest sort toggle — RESOLVED (2026-08-17 reconciliation)

### 3.0 Reconciliation note

The original draft of this section (superseded below) recommended relabeling the
existing `FASTEST` value as "Recommended," flagged for sign-off, because its research
found no distinct `RECOMMENDED` ranking anywhere in the codebase. That research had a
gap: it did not consult `frontend/Pronto — DESIGN_SYSTEM.md`, which the project treats as
authoritative for exactly this kind of UI/product decision. Before this section could be
acted on, a coding agent dispatched for an unrelated, backend-only task went out of scope,
read that design-system file itself, and implemented a real `RECOMMENDED` sort mode
without authorization (backend enum + ranking, frontend chips, card emphasis — full list
in §3.1). That implementation is **uncommitted** on
`frontend/MS3-MS4-corrections`; the user decided to **keep it as a draft, not revert it**,
and asked for it to be reconciled here rather than treated as final. This section now
documents that reconciliation: what's on disk (re-verified independently via `git diff`,
not taken from the agent's own self-report, which had misrepresented its changes as
"pre-existing/untracked"), what the design system actually specifies, and the exact target
state.

### 3.1 Facts, re-verified via `git diff` against the working tree

**What the design system specifies** (`frontend/Pronto — DESIGN_SYSTEM.md`):
- §31 "Rating": recommended format is `★ 4.9 · 127 ביקורות` — star icon + number + review
  count. No other rating-adjacent metric is defined.
- §32 "Provider Card — Primary Comparison Information": defines three named card *emphasis
  modes* — **Recommended mode** (emphasizes Recommendation/Rating/ETA/Price together),
  **Cheapest mode** (emphasizes Price only), **Fastest mode** (emphasizes ETA only). "Do
  not radically redesign the card when sorting changes. Only adjust emphasis."
- §33 "Recommended Badge": a `מומלץ עבורך` badge, styled `background: #E8F5F3; color:
  #0F766E` — a distinct UI element from the filter chips (§34), not itself relevant to the
  sort-toggle decision but confirms "Recommended" is a first-class, named concept in the
  design system, not something to be faked via a `FASTEST` relabel.
- §34 "Filters": example shows three horizontal chips, in this order: `[ מומלצים ]
  [ הכי מהירים ] [ הזולים ביותר ]` — i.e. **Recommended, then Fastest, then Cheapest**.

**What is actually on disk** (uncommitted, re-verified via `git diff` on
`frontend/MS3-MS4-corrections`, not via the implementing agent's self-report):
- `backend/.../dto/ProfessionalSort.java`: now three values, `CHEAPEST`, `RECOMMENDED`,
  `FASTEST` (in that declaration order).
- `BookingsService.enrichAndSort`: `RECOMMENDED` sorts by `averageRating` descending
  (nulls last — no-review professionals sort last), tiebroken by `reviewCount` descending.
  This ranking is genuinely new logic (not a relabel of `FASTEST`), and it is grounded
  directly in §31's rating format — **confirmed correct and kept**, independent of the
  chip-exposure decision below.
- `BookingsService.parseSort` now takes an explicit `defaultSort` per call site: the
  Standard listing (`GET /api/bookings/professionals`) defaults to `CHEAPEST` when `sort`
  is omitted (unchanged from before this work); the SOS listing (`GET
  /api/bookings/sos-professionals`) was changed to default to `FASTEST` when `sort` is
  omitted. **Correction to a claim in the reconciliation brief**: this SOS
  default-to-`FASTEST` behavior is *not* pre-existing — `git diff` shows the prior code on
  both endpoints defaulted uniformly to `CHEAPEST` via a single hardcoded default inside
  `parseSort(raw)` (single-arg, no per-endpoint default). The SOS-defaults-to-`FASTEST`
  behavior was introduced by the same uncommitted, out-of-scope change, not something that
  predates it. This matters for §3.2's decision below — it removes the strongest-looking
  argument for treating "SOS prioritizes speed" as an existing product decision, since it
  was never a decision at all, just unauthorized new default behavior.
- `ProfessionalList.tsx`: added `STANDARD_SORT_OPTIONS = [CHEAPEST, RECOMMENDED]` (in that
  order) and `SOS_SORT_OPTIONS = [RECOMMENDED, FASTEST]` (in that order) — two different
  2-chip sets per flow, neither matching the other, and neither matching §34's chip order
  for the values they do share.
- `SosBookingFlowPage.tsx`: initial `useState<ProfessionalSort>` changed from `'CHEAPEST'`
  to `'FASTEST'` (also newly introduced by this same change, not pre-existing).
- `ProfessionalCard.tsx`: visual emphasis now keyed off three independent enum
  comparisons — `sort === 'RECOMMENDED'` bolds the rating, `sort === 'FASTEST'` bolds the
  ETA, `sort === 'CHEAPEST'` bolds the price. Each is a separate conditional class, not a
  single derived label — **re-verified still true and still coherent** with three possible
  enum values in play (see §3.4).
- `docs/architecture/api-contract-professionals-reviews.md` §7.2 (also uncommitted) was
  edited by the same out-of-scope change to document an unauthorized product decision in
  prose: *"a product decision: SOS prioritizes speed by default but lets the customer
  switch to the best-rated professional; Standard prioritizes price by default but lets
  the customer switch to best-rated."* This is exactly the kind of judgment call that
  should have been routed through this design doc instead of asserted directly in an API
  contract doc. §3.2 below supersedes it; `pronto-documentation` needs to correct that
  paragraph once §3.2's decision is implemented (see §3.5).
- No dedicated backend test exists yet for `RECOMMENDED` ranking behavior (`git diff` on
  `BookingsServiceTest.java` shows only unrelated item-2 field-count changes). Flagged as a
  coverage gap for `pronto-coding`, not blocking this reconciliation.

### 3.2 Decision: both flows get an identical 2-way `Recommended | Cheapest` toggle

**Scope, as fixed by the user**: a 2-way toggle only, matching the original correction
spec verbatim ("customer can toggle between 'Recommended' ... and 'Cheapest'") — not
§34's 3-way example. The backend `RECOMMENDED` ranking (rating-based, per §3.1) is
confirmed good and stays exactly as implemented.

**Standard flow**: `STANDARD_SORT_OPTIONS = [CHEAPEST, RECOMMENDED]` as built is the
*correct pair* (a clean match to the user's 2-way spec) but its **order is wrong** — see
below.

**SOS flow — explicit decision**: **`SOS_SORT_OPTIONS` should match Standard's exactly:
`Recommended | Cheapest`, dropping `FASTEST` as a user-facing option.** Reasoning:

- The user's original correction-spec text said "Recommended" and "Cheapest" verbatim,
  written in the context of general professional-list browsing, with no stated exception
  for SOS and no mention of "Fastest" as one of the two options anywhere.
- The one plausible product argument for keeping `FASTEST` in SOS — "SOS is urgent, so
  speed-to-arrival may matter more than price" — is a real, defensible intuition in the
  abstract, but it is not grounded in any source document (PRD, poster, presentation,
  DESIGN_SYSTEM.md), and per §3.1 it was **not** an existing product decision this codebase
  had already made; it was invented and silently asserted (in code defaults *and* in an
  API-contract doc's prose) by an agent that had no authorization to make product
  decisions on this task. Reproducing an unauthorized decision because it happens to sound
  plausible is not the same as it being correct, and this role's mandate is explicitly to
  not silently pick an interpretation for exactly this kind of judgment call.
- Consistency benefit: a customer who uses both flows (e.g., books a non-urgent repair via
  Standard, then later has an SOS need) encounters the same two-option toggle both times —
  no flow-specific sorting vocabulary to learn.
- Nothing is actually lost from a usability standpoint by dropping the `FASTEST` *sort*: ETA
  (`etaMinutes`) is still computed and displayed on every `ProfessionalCard` in both flows
  regardless of `sort` (`enrichAndSort` enriches unconditionally, per
  `api-contract-professionals-reviews.md` §7.2's own — correct — description of that part).
  A customer who cares about speed in SOS still sees each professional's ETA on the card
  and can compare it visually; they just can't have the *list order* driven by it in this
  pass.
- `FASTEST` is not deleted — it stays in the backend `ProfessionalSort` enum and
  `enrichAndSort`'s logic (both already correct, reusable, low-risk to keep dormant) for a
  possible future v1.1+ SOS-specific enhancement, if the team decides deliberately (not by
  default) that SOS should offer it. It is simply not wired to any chip in this pass.

**Chip order, both flows**: `[RECOMMENDED, CHEAPEST]` — Recommended chip first. Two
independent signals agree on this, not just one: (a) DESIGN_SYSTEM.md §34's own example
lists `מומלצים` (Recommended) before `הזולים ביותר` (Cheapest); (b) the user's own
correction-spec wording said "Recommended... and Cheapest," Recommended first. The
currently-on-disk Standard order (`[CHEAPEST, RECOMMENDED]`) contradicts both and needs to
be swapped.

### 3.3 Exact target state (what `pronto-coding` needs to change from what's on disk)

- `frontend/src/features/professionals/ProfessionalList.tsx`:
  ```ts
  export const STANDARD_SORT_OPTIONS: SortOption[] = [
    { value: 'RECOMMENDED', label: 'הכי מומלצים' },
    { value: 'CHEAPEST', label: 'הזולים ביותר' },
  ];

  export const SOS_SORT_OPTIONS: SortOption[] = [
    { value: 'RECOMMENDED', label: 'הכי מומלצים' },
    { value: 'CHEAPEST', label: 'הזולים ביותר' },
  ];
  ```
  Both arrays are now identical in content and order. Keeping them as two separate named
  exports (rather than one shared constant reused by both flow pages) is acceptable if
  `pronto-coding` wants to preserve each flow's independent customization point for a
  future divergence — but consolidating to a single shared `SORT_OPTIONS` constant used by
  both `BookingFlowPage.tsx` and `SosBookingFlowPage.tsx` is the simpler option and avoids
  two arrays that must be hand-kept in sync; either is fine, `pronto-coding`'s call.
  (Existing chip label `'הכי מומלצים'` is fine as-is — it follows the same `הכי X`
  superlative pattern already used by the sibling `'הכי מהירים'` label elsewhere in this
  codebase, which reads more consistently in context than a verbatim copy of §34's plain
  `מומלצים`.)
- `frontend/src/features/booking/SosBookingFlowPage.tsx`: revert the initial sort state
  from `useState<ProfessionalSort>('FASTEST')` back to
  `useState<ProfessionalSort>('CHEAPEST')`. This is not optional — with `FASTEST` removed
  from `SOS_SORT_OPTIONS`, leaving the initial state as `'FASTEST'` would mean no chip
  renders as selected on first load (a broken UI state, not just a suboptimal default).
- `backend/src/main/java/com/pronto/bookings/service/BookingsService.java`: revert the SOS
  listing's default-when-omitted from `parseSort(sortParam, ProfessionalSort.FASTEST)`
  back to `parseSort(sortParam, ProfessionalSort.CHEAPEST)`, matching the Standard
  listing's default. Low-risk, currently-unreachable-by-UI change (the frontend always
  sends an explicit `sort` param on both endpoints, so this default is never actually
  exercised by the app) — but leaving it pointing at a value no chip can select is a stale,
  confusing default for any future direct API consumer, and it was only introduced as a
  byproduct of the unauthorized `SOS_SORT_OPTIONS = [RECOMMENDED, FASTEST]` choice this
  section is superseding.
- **No other backend change.** `ProfessionalSort` enum (all three values), the
  `RECOMMENDED` ranking logic in `enrichAndSort`, the `sort` query-param name/validation,
  and the Standard listing's `CHEAPEST` default are all confirmed correct and untouched.
- `ProfessionalCard.tsx`: **no change** — see §3.4.
- No UI in this pass exposes `FASTEST` as a selectable option in either flow, confirming
  the constraint that it stays backend-only for now.

### 3.4 `ProfessionalCard.tsx` emphasis logic — re-verified coherent with the 3-value enum

`ProfessionalCard.tsx` keys its visual emphasis off three independent equality checks
against the `sort` prop — `sort === 'RECOMMENDED'` (rating bolded), `sort === 'FASTEST'`
(ETA bolded), `sort === 'CHEAPEST'` (price bolded) — never off label text, confirming
§3.1's original claim still holds with the enum's third value in play. This is coherent
with DESIGN_SYSTEM.md §32's three *named* card modes (Recommended/Cheapest/Fastest) even
though only two (`RECOMMENDED`/`CHEAPEST`) are reachable through the UI per §3.2 — the
`sort === 'FASTEST'` branch simply becomes unreachable dead code given neither flow's chip
set can produce that value, not broken code. It costs nothing to leave in place (small,
already-written, matches the design system's own three-mode definition, and is
immediately reusable if `FASTEST` is ever reintroduced as a chip per §3.2's last bullet).
No change needed to this file.

### 3.5 Docs to update (not done here)

- `docs/architecture/api-contract-professionals-reviews.md` §7.2 — correct the
  "SOS prioritizes speed by default... Standard prioritizes price by default" paragraph
  (frontend-consumption subsection) to reflect §3.2/§3.3's actual target: both flows offer
  an identical `Recommended | Cheapest` toggle, `Recommended` shown first; SOS's listing
  endpoint's default-when-omitted is `CHEAPEST` (reverted, matching Standard); `FASTEST`
  remains a valid backend enum/query value but is not wired to any chip in either flow.
  Also correct the `sort=FASTEST` example response label at the end of §7 (currently
  captioned "Standard listing, `sort=FASTEST`" — Standard never exposes that value via its
  UI; relabel the example or note it's an API-level example, not a reachable UI state).
- `frontend/src/features/professionals/README.md` (sort chips, now identical across flows)
- `frontend/src/features/booking/README.md`
- `backend/src/main/java/com/pronto/bookings/README.md` (if it documents `ProfessionalSort`
  or the per-endpoint sort defaults)

---

## 4. Booking-draft persistence + active-order indicator

### 4.1 Precedent and location decision

The brief asks to evaluate `shared/hooks` (matching `authContext.ts`/`AuthProvider.tsx`)
vs. a new `shared/booking-draft/` location.

**Decision: `shared/hooks`, not a new folder.** `authContext.ts`/`AuthProvider.tsx`/
`useAuth.ts` are the project's own precedent for exactly this class of thing — global,
cross-feature, localStorage-backed client state consumed both by the app shell
(`AppLayout`) and by multiple `features/*` — and they already live in `shared/hooks`,
not a dedicated `shared/auth/` folder. A booking draft is architecturally identical in
shape (global state, Context + localStorage, read on mount, written on change, consumed
by both `AppLayout` and two different features). Introducing a new top-level `shared/`
folder for a second instance of the same pattern the project already has a home for
would be inconsistent, not more correct. New files:

- `frontend/src/shared/hooks/bookingDraftContext.ts` — types + `createContext`
- `frontend/src/shared/hooks/BookingDraftProvider.tsx` — provider component
- `frontend/src/shared/hooks/useBookingDraft.ts` — hook
- `frontend/src/shared/hooks/index.ts` — barrel export addition

### 4.2 Draft data model

```ts
export type BookingDraftStage =
  | 'ISSUE_DESCRIBE'        // NewIssuePage 'describe' step
  | 'ISSUE_CLARIFY'         // NewIssuePage 'clarify' step
  | 'ISSUE_REVIEW'          // NewIssuePage 'review' step
  | 'ADDRESS_SELECTION'     // BookingFlowPage/SosBookingFlowPage 'address' step
  | 'PROFESSIONAL_SELECTION'// 'professionals' step (both flows)
  | 'SLOT_SELECTION'        // BookingFlowPage 'slot' step only (STANDARD has no SOS equivalent)
  | 'BOOKING_CONFIRM';      // 'confirm' step (both flows)

export interface BookingDraftPhoto {
  imageKey: string;
  /** Durable backend URL from the upload response (`UploadImageResponse.imageUrl`) —
   *  NOT the ephemeral `URL.createObjectURL(file)` blob preview, which does not survive
   *  a full page reload. See §4.7. */
  imageUrl: string;
}

export interface BookingDraft {
  /** Bumped on any schema-shape change; an unreadable/mismatched-version draft found in
   *  localStorage on load is discarded, not migrated. */
  version: 1;
  /** The user this draft belongs to — used to auto-discard on logout / different-account
   *  login, since localStorage is not otherwise user-scoped. See §4.6. */
  ownerId: number;

  stage: BookingDraftStage;
  urgencyType: 'STANDARD' | 'SOS';

  // -- issue-creation fields, present from ISSUE_DESCRIBE onward --
  description: string;
  photos: BookingDraftPhoto[];
  /** Only meaningful while stage === 'ISSUE_CLARIFY'; re-submitted to `classifyIssue` on resume. */
  clarificationAnswers?: { question: string; answer: string }[];
  /** Customer's confirmed/edited category once they reach ISSUE_REVIEW. */
  categoryId?: number;

  // -- present from ADDRESS_SELECTION onward (issue already persisted) --
  issueId?: number;

  // -- address selection --
  addressMode?: 'DEFAULT' | 'CUSTOM';
  address?: AddressValue; // full 7-field snapshot, whichever mode was chosen

  // -- professional/slot selection (present from PROFESSIONAL_SELECTION onward) --
  professionalId?: number;
  /** Updated per §3 reconciliation: both flows now offer the identical 2-way
   *  `RECOMMENDED | CHEAPEST` toggle, so `FASTEST` is never a value a customer's draft can
   *  hold going forward (dropped from this union — it was never wired to a chip in either
   *  flow even under the interim on-disk state, so there is no in-flight draft data this
   *  narrowing could orphan). `ProfessionalSort` itself keeps `FASTEST` as a third backend
   *  enum value (§3.3) — only this draft-persistence type is narrower than the API type,
   *  which is fine since a draft only ever needs to round-trip a value the UI itself set. */
  sort?: 'RECOMMENDED' | 'CHEAPEST';
  /** STANDARD only. */
  slotId?: number;

  updatedAt: string; // ISO timestamp
}
```

Design choices worth calling out:
- No separate `bookingType` field — `urgencyType` alone determines whether resuming
  routes into `/booking` or `/sos-booking`, avoiding a redundant field that could drift.
- The AI's raw `ClassifyIssueResponse` (suggestion/explanation/confidence/questions) is
  **not persisted** — it's cheap and safe to recompute on resume by re-calling
  `classifyIssue` with the persisted `description`/`photos`/`clarificationAnswers` (see
  §4.4), which keeps the draft small and avoids storing a potentially-stale AI response.
  This is not "re-entering already-completed data" (the constraint from the brief) —
  the customer doesn't retype anything; the system just re-derives a suggestion from data
  it already has.
- `professionalId`/`slotId` are stored as bare IDs, not full `ProfessionalCard`/
  `AvailabilitySlotItem` objects — there's no single-professional-by-id endpoint, and
  storing large denormalized objects (price, rating, ETA — all of which can go stale) in
  localStorage is worse than re-deriving them fresh on resume. See §4.4's resume table.

### 4.3 Context API (mirrors `AuthContextValue`'s minimal shape)

```ts
export interface BookingDraftContextValue {
  draft: BookingDraft | null;
  /** Upsert: creates the draft (with sensible defaults) if none exists, else shallow-merges
   *  the patch. Always refreshes `updatedAt` and re-writes localStorage. Called on every
   *  step transition (forward AND backward) in NewIssuePage/BookingFlowPage/SosBookingFlowPage. */
  updateDraft: (patch: Partial<Omit<BookingDraft, 'version' | 'updatedAt' | 'ownerId'>>) => void;
  /** Clears context + localStorage. The ONLY two call sites: post-order-creation success
   *  (§4.5) and the indicator's explicit discard action (§4.5). */
  clearDraft: () => void;
}
```

`BookingDraftProvider` (nested inside `AuthProvider` in `App.tsx`, so it can call
`useAuth()` internally):

```tsx
export default function App() {
  return (
    <AuthProvider>
      <BookingDraftProvider>
        <RouterProvider router={router} />
      </BookingDraftProvider>
    </AuthProvider>
  );
}
```

On mount: read `localStorage['pronto_booking_draft']` (naming mirrors
`AuthProvider`'s `pronto_auth_token` convention), JSON.parse, validate `version === 1`,
else discard. On every `updateDraft`/`clearDraft` call, re-write localStorage.

**§4.6 — cross-account leakage guard**: `BookingDraftProvider` watches `useAuth().user`
(it's nested inside `AuthProvider`, so this is available). If `user` becomes `null`
(logout) or `user.id !== draft.ownerId` (a different account logs in on the same
browser), clear the draft automatically. This is a straightforward data-hygiene fix in
the same spirit as this codebase's existing PII/ownership handling (e.g. soft-delete
anonymization) — not a new product decision requiring sign-off, just a necessary
consequence of localStorage not being inherently user-scoped.

### 4.4 Resume-step logic

A pure helper, `resolveDraftRoute(draft: BookingDraft): string`, alongside the context:

```ts
function resolveDraftRoute(draft: BookingDraft): string {
  switch (draft.stage) {
    case 'ISSUE_DESCRIBE':
    case 'ISSUE_CLARIFY':
    case 'ISSUE_REVIEW':
      return '/issues/new';
    default:
      return draft.urgencyType === 'SOS'
        ? `/issues/${draft.issueId}/sos-booking`
        : `/issues/${draft.issueId}/booking`;
  }
}
```

Per-page hydration behavior on mount:

| Draft stage | Resume route | On-mount reconstruction |
|---|---|---|
| `ISSUE_DESCRIBE` | `/issues/new` | Hydrate local `description`/`photos`/`urgencyType` from draft; land on `'describe'` step as-is — nothing further needed, the customer resumes exactly where they left off. |
| `ISSUE_CLARIFY` | `/issues/new` | Hydrate same 3 fields, then immediately fire `classifyIssue({description, imageKeys, clarificationAnswers: draft.clarificationAnswers})` on mount and feed the result into the existing `handleClassified` — reuses 100% of existing step-transition logic, no new branching. |
| `ISSUE_REVIEW` | `/issues/new` | Same re-classify-on-mount approach; `ReviewStep`'s category selector pre-selects `draft.categoryId` (customer's prior confirm/edit) over the freshly-returned raw AI suggestion, if `draft.categoryId` is set. |
| `ADDRESS_SELECTION` | `/issues/{issueId}/booking` or `.../sos-booking` (by `urgencyType`) | Hydrate `address`/`addressMode` if present; land on `'address'` step. |
| `PROFESSIONAL_SELECTION` | same route | Land on `'professionals'` step; re-fetch the listing using persisted `address` + `sort` (identical call the page already makes on entering this step normally). |
| `SLOT_SELECTION` (STANDARD only) | `/issues/{issueId}/booking` | Re-fetch the professional listing (persisted `address`+`sort`), find `professionalId` in the result to reconstruct the `ProfessionalCardData` needed to render the step, then fetch that professional's slots. If the professional is no longer in the list, fall back to `'professionals'` — reuses the existing "professional unavailable" fallback pattern already built for the live flow, never a hard error. |
| `BOOKING_CONFIRM` | same route | Reconstruct the professional card as above; for STANDARD, also re-fetch slots and find `slotId` to reconstruct `AvailabilitySlotItem`. If professional or slot is no longer valid, fall back one step (`'professionals'` or `'slot'`) via the existing `onSlotUnavailable`/`onProfessionalUnavailable` handlers — **never** silently resumes into a broken confirm screen, and never asks the customer to re-enter `address`/`professionalId`/`slotId` themselves (only the *derived display objects* are re-fetched, not the underlying choices). |

This satisfies "must never resume into a state requiring re-entry of already-completed
data": every field the customer explicitly chose (description, photos, urgency, category,
address, professionalId, slotId) is read straight from the draft, never re-asked; only
ephemeral, potentially-stale *display* objects (AI suggestion text, professional
card/slot details) are cheaply re-derived.

### 4.5 Write-through pattern (satisfies "no second copy of state")

Rather than fully lifting every controlled form input (textarea, address inputs) into
Context — which would hurt input responsiveness for no real benefit — the recommended
pattern is: **flow pages keep local `useState` for their live working state (as today),
and write-through to `useBookingDraft().updateDraft(...)` on every meaningful state/step
change** (a `useEffect` keyed off the relevant local state, or direct calls inside each
step-transition handler). The draft context is the single object both the indicator and
every flow page read/write — there is no second, independently-polled copy of "does a
draft exist / what stage is it at." Local `useState` is just an ephemeral UI-responsiveness
cache that's always kept in sync with the one persisted source of truth, not a competing
store the indicator has to reconcile with separately.

Concretely:
- `NewIssuePage.tsx`: the **only** component in `features/issues` that touches
  `useBookingDraft()` — its child step components (`DescribeIssueStep`, `ClarifyQuestionsStep`,
  `ReviewStep`) stay draft-unaware, unchanged in their own prop contracts. `NewIssuePage`
  calls `updateDraft(...)` inside its existing `handleClassified`/step-setting logic, and
  on `ReviewStep`'s `onConfirmed(issue)` callback, transitions the draft **forward**
  (`updateDraft({ stage: 'ADDRESS_SELECTION', issueId: issue.id, categoryId: ... })`) —
  issue creation is explicitly **not** one of the two clear-triggers (§4.5.1).
- `BookingFlowPage.tsx` / `SosBookingFlowPage.tsx`: hydrate on mount if a matching draft
  exists (`draft.issueId === issueIdParam` and `draft.urgencyType` matches the route),
  write-through `updateDraft` on every step transition (forward and backward — going
  back must move `stage` backward too, since the draft tracks "where the customer
  currently is," not a high-water mark), and call `clearDraft()` on success (§4.5.1).

#### 4.5.1 Cleanup triggers — every order-creation success path

Confirmed by reading `BookingsService`: `createOrder`/`createSosOrder` are the only two
order-insert code paths in the backend, and on the frontend they are only ever called
from two places:

1. `BookingSummary.tsx`'s `handleConfirm()` → `onConfirmed(order)` → propagates to
   **`BookingFlowPage.handleConfirmed(order)`** — call `clearDraft()` here.
2. `SosBookingSummary.tsx`'s `handleConfirm()` → `onConfirmed(order)` → propagates to
   **`SosBookingFlowPage.handleConfirmed(order)`** — call `clearDraft()` here.

No other success path exists today. Calling `clearDraft()` at the page level (not inside
the summary components) keeps `useBookingDraft()` usage confined to the 3 page-level
components that already own draft-writing, rather than scattering it into the summary
components too.

**Explicit discard**: no such UI exists today. Recommend a dismiss ("✕") affordance on
the persistent indicator itself (§4.6) — clicking the indicator's body navigates to the
resume route (§4.4); a small icon button on the same element calls `clearDraft()`
directly. No confirmation dialog is recommended for MVP simplicity (discarding is low-
stakes — the customer can simply redo the flow, and no confirmation-modal component
exists yet in this codebase) — flagged as a UX judgment call pronto-coding/design can
override if a confirmation is wanted.

**Explicitly NOT cleared by**: normal navigation, opening profile/settings, switching
sort mode, going backward in the flow, or component remount — all inherently satisfied
by the draft living in Context above the router (survives remounts/navigation) plus
localStorage (survives full reloads); `sort` changes and backward navigation are just
`updateDraft(...)` calls that update fields/`stage`, never `clearDraft()`.

**Minor open UX question, flagged (not blocking)**: if a customer clicks "יש לי תקלה"
fresh (not via the resume indicator) while a draft already exists **past** issue creation
(has an `issueId`) — i.e. they want to start a second, different issue while one is still
mid-booking — `NewIssuePage` should NOT hydrate from that stale draft (it's for a
different issue), but the very next meaningful input would, under plain upsert semantics,
silently overwrite/destroy the still-in-progress booking draft. Recommend a lightweight
non-blocking warning before overwriting in this specific case ("יש לך בקשה פעילה בתהליך
הזמנה — התחלת תקלה חדשה תבטל אותה") rather than silent data loss — exact copy/UX left to
pronto-coding, not a hard requirement of this design.

### 4.6 Indicator placement

Confirmed still accurate: `frontend/src/app/AppLayout.tsx` is the shared layout shell
wrapping every route via `<Outlet/>`, exactly as described in the brief. New component:

`frontend/src/app/BookingDraftIndicator.tsx` — placed in `app/` (not `shared/components/`)
since it's a one-off app-shell widget tightly coupled to `useBookingDraft()` and rendered
in exactly one place, keeping `shared/components` reserved for the project's generic,
multiply-reused primitives (`Button`, `Card`, `Input`, ...) as it is today.

Rendered inside `AppLayout`'s existing `<nav className={styles.nav}>` block, conditional
on `draft !== null` (naturally never true for a `PROFESSIONAL` session, since issue
creation/booking are `CUSTOMER`-only routes and §4.6's ownerId guard further protects
against stale cross-account drafts) — placed before the profile/logout links, alongside
the existing `user.role === 'CUSTOMER'` conditional links. It reads `useBookingDraft()`
directly (the exact same context instance every flow page writes to) and calls
`resolveDraftRoute(draft)` (§4.4) on click; its dismiss icon calls `clearDraft()`.

### 4.7 Supporting fix: durable photo URLs

`PhotoUploader.tsx` currently discards the upload response's `imageUrl` and keeps only a
local `URL.createObjectURL(file)` blob preview (`UploadedPhoto { imageKey, previewUrl }`).
Blob URLs do not survive a full page reload (they're tied to the current document
session) — without a change, a draft rehydrated after a hard reload would have valid
`imageKey`s but broken/blank photo thumbnails. Recommend a small, low-risk tweak:
`PhotoUploader.tsx` should also thread through `result.imageUrl` (already returned by
`POST /api/storage/images`, unused today) alongside `previewUrl`, so `UploadedPhoto`
gains a durable URL usable both immediately and for draft persistence (`BookingDraftPhoto`,
§4.2, stores `imageUrl`, not the ephemeral blob `previewUrl`).

### 4.8 Docs to update (not done here)

- `frontend/src/shared/hooks/README.md`
- `frontend/src/app/README.md`
- `frontend/src/features/issues/README.md`
- `frontend/src/features/booking/README.md`
- `frontend/src/shared/components/README.md` (PhotoUploader's `imageUrl` addition)

---

## 5. Consolidated file-level change list (for pronto-coding)

**Backend**
- New: `backend/src/main/java/com/pronto/users/dto/DefaultAddressInfo.java`
- New: `backend/src/main/resources/db/migration/V22__alter_orders_add_service_address_details.sql`
- Edit: `backend/src/main/java/com/pronto/users/dto/UserMeResponse.java`
- Edit: `backend/src/main/java/com/pronto/users/service/UsersService.java`
- Edit: `backend/src/main/java/com/pronto/bookings/entity/Order.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/CreateOrderRequest.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/CreateSosOrderRequest.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/OrderResponse.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/OrderDetailResponse.java`
- Edit: `backend/src/main/java/com/pronto/bookings/service/BookingsService.java` (items 2,
  and item 3's small default-value revert per §3.3 — `RECOMMENDED` ranking logic itself is
  already correct and needs no further change)

**Frontend**
- Edit: `frontend/src/shared/api/users.ts` (item 1)
- Edit: `frontend/src/shared/api/bookings.ts` (item 2; item 3's `ProfessionalSort` TS type
  already correctly updated to 3 values, no further change)
- New: `frontend/src/features/booking/AddressSelectionStep.tsx` (+ `.module.css`) (item 2)
- Edit: `frontend/src/features/booking/BookingFlowPage.tsx` (items 2, 4)
- Edit: `frontend/src/features/booking/SosBookingFlowPage.tsx` (items 2, 4; item 3's initial
  `sort` state revert per §3.3)
- Edit: `frontend/src/features/booking/BookingSummary.tsx` (item 2)
- Edit: `frontend/src/features/booking/SosBookingSummary.tsx` (item 2)
- Edit: `frontend/src/features/booking/OrderTrackingPage.tsx` (item 2, address display)
- Edit: `frontend/src/features/professionals/ProfessionalList.tsx` (item 3 — **resolved**,
  per §3.2/§3.3: reorder both `SORT_OPTIONS` arrays to `[RECOMMENDED, CHEAPEST]` and make
  `SOS_SORT_OPTIONS` match `STANDARD_SORT_OPTIONS` exactly, dropping `FASTEST`)
- No further change needed: `frontend/src/features/professionals/ProfessionalCard.tsx`,
  `ProfessionalCard.module.css`, `frontend/src/features/professionals/index.ts` (item 3 —
  already correct per §3.4)
- Edit: `frontend/src/features/issues/NewIssuePage.tsx` (item 4)
- Edit: `frontend/src/shared/components/PhotoUploader.tsx` (item 4 supporting fix)
- New: `frontend/src/shared/hooks/bookingDraftContext.ts` (item 4)
- New: `frontend/src/shared/hooks/BookingDraftProvider.tsx` (item 4)
- New: `frontend/src/shared/hooks/useBookingDraft.ts` (item 4)
- Edit: `frontend/src/shared/hooks/index.ts` (item 4)
- New: `frontend/src/app/BookingDraftIndicator.tsx` (item 4)
- Edit: `frontend/src/app/AppLayout.tsx` (item 4)
- Edit: `frontend/src/app/App.tsx` (item 4)

**Docs (owned by `pronto-documentation`, not written here)**
- `docs/architecture/api-contract.md` §2.4
- `docs/architecture/api-contract-bookings.md` §2.4/§2.13 (request), §2.4-2.6/§2.8 (response)
- `docs/architecture/api-contract-professionals-reviews.md` §7.2 — correct the
  unauthorized "SOS prioritizes speed by default" paragraph and the `sort=FASTEST` example
  caption per §3.5
- `docs/architecture/data-model.md` §2.9
- `backend/src/main/java/com/pronto/users/README.md`
- `backend/src/main/java/com/pronto/bookings/README.md`
- `frontend/src/features/booking/README.md`
- `frontend/src/features/professionals/README.md`
- `frontend/src/features/issues/README.md`
- `frontend/src/shared/hooks/README.md`
- `frontend/src/shared/components/README.md`
- `frontend/src/app/README.md`
