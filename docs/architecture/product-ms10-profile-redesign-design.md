# MS10 — Profile UI Redesign

## 0. Scope

Verbatim requirements:

**Professional Profile:**
- Make the profile image circular and centered.
- Clicking the profile image should open it in a larger image viewer, similar to the
  common Facebook-style profile-photo interaction.
- Remove the separate "Add photo" button.
- Use only an Edit/Change profile photo action.
- Improve the overall layout so the page uses the available screen width better and
  does not leave a large empty area on the left.

**Customer Profile:**
- Use the same circular, centered profile-image approach.
- Add the ability for the Customer to edit their profile.
- Improve the field layout: the field label and its value should appear close to each
  other; do not place the label on one far edge and the value on the opposite edge.
- Keep the page visually compact, balanced, and consistent with the Professional
  profile.

This touches `frontend/src/features/dashboard/ProfileEditorPage.tsx`
(+`ProfessionalProfileImageField.tsx`), `frontend/src/app/ProfilePage.tsx`, one new shared
`shared/components` pair (photo + lightbox), and one small backend addition
(`PUT /api/users/me`). No new subsystem, no payment/GPS scope, no change to
`/pro/profile`'s existing bio/serviceArea/city/basePrice fields' meaning.

---

## 1. Investigation findings (re-verified against current code, not assumed)

### 1.1 Professional photo — already circular, not already centered, not click-to-enlarge

`ProfessionalProfileImageField.module.css` `.currentPhoto`: `width/height: 96px;
border-radius: 50%; object-fit: cover` — **already circular**, and already within
`DESIGN_SYSTEM.md` §30's documented "provider profile page: 88–104px" sizing range (the
`ProfessionalProfileImageField.tsx` doc-comment's own citation is accurate). What is
*not* true today:
- It is **not centered** — it sits inline, left-aligned-with-the-row, next to the upload
  widget (`.row { display:flex; align-items:flex-start; gap }`), not presented as its own
  centered hero element.
- **No click-to-enlarge** exists at all — the `<img>` has no `onClick`, no cursor
  affordance, nothing.

### 1.2 "Separate Add photo button" — not literally two buttons, but a real duplicated/mismatched affordance

Re-verified: there are not two buttons in the literal sense the brief guesses at. There is
one `ImageUploadField` button, reused generically from `shared/components`. But:
- `ProfessionalProfileImageField.tsx` passes it `label="החלפת תמונה"` ("change/replace
  photo") for its field caption.
- `ImageUploadField.tsx` (`shared/components/ImageUploadField.tsx` line 69-72) **ignores
  that `label` for the button's own text** — the upload-trigger button is hardcoded to
  render `<ImagePlus /> הוספת תמונה` ("**Add photo**"), regardless of what caption the
  caller passed.

So the current UI actually shows: a static current-photo `<img>` (no action attached to
it at all) sitting beside a *separate* small field whose caption says "change photo" but
whose only interactive control literally reads "**Add photo**". That mismatch — a photo
with no affordance, next to an unrelated-looking "Add photo" control — is what reads as
"a separate Add photo button" and is the concrete thing to remove. Confirmed: this is a
real finding, not a re-statement of an assumption.

**Decision**: stop reusing `ImageUploadField` for this case. Build a dedicated component
(§2.1) with exactly one interactive affordance on the photo itself, captioned correctly.

### 1.3 "Large empty area on the left" — re-verified, root cause identified

`ProfileEditorPage.module.css` `.card { max-width: 480px }`. `ProfileEditorPage` renders
inside `ProDashboardLayout`'s `.content` (`ProDashboardLayout.module.css`), which is
`flex: 1` inside `.wrapper` (`flex-direction: row` at ≥640px, sidebar `width: 220px` +
`gap: var(--space-6)` first), inside `.page-container` (`index.css`, `max-width: 1200px`,
centered). At a typical desktop width the `.content` column is comfortably 700–900px+
wide, but the actual card is capped at 480px with no auto margins. A block element
narrower than its flex-item container, with no centering, aligns to its writing-mode
start edge — under `dir="rtl"` that is the **physical right** (adjacent to the sidebar),
so the leftover ~250–450px of unused width lands entirely on the **physical left**.
Confirmed: this exactly matches the complaint, root-caused to one CSS rule plus the
absence of any width-filling layout for the freed space.

### 1.4 Customer has no photo field anywhere — data model and UI both confirmed empty

`users` table (`data-model.md` §2.2, mirrored in `backend/.../users/entity/User.java`)
has no `profile_image_key`/photo column of any kind — the only such column in the whole
schema is `professionals.profile_image_key` (`api-contract-professionals-reviews.md`
§1.1, `V15`). `ProfilePage.tsx` renders no image at all today. See §3.1 for how this
maps to the ambiguity flagged there.

### 1.5 `PUT`/`PATCH /api/users/me` — confirmed absent

`backend/src/main/java/com/pronto/users/controller/UsersController.java` has exactly two
mappings, `GET` and `DELETE /me`. No write path exists. Confirmed by inspection (not by
grep alone) — see §4 for the new endpoint design.

### 1.6 Customer field-layout bug — confirmed exact match

`app/ProfilePage.module.css` `.row { display:flex; justify-content:space-between }`,
`.row dd { text-align:end }` — `dt` (label) sits at the row's start edge, `dd` (value) is
pushed to the row's opposite end. This is precisely "label on one far edge, value on the
opposite edge." Confirmed.

### 1.7 `Modal` primitive — investigated, not reused for the photo viewer

`shared/components/Modal.tsx` (added by the weekly-calendar-redesign work) is a
form-dialog/bottom-sheet shape: fixed header with a title and a bordered close row,
`body` with padding, optional footer, three width tokens (420/560/720px on desktop). A
Facebook-style enlarged-photo viewer wants close to the opposite: a large/near-fullscreen
image, no title, no footer, minimal-to-no chrome around the image, dark overlay. Forcing
`Modal` to also serve this shape would mean adding `noHeader`/`noPadding`/`fullBleed`-style
props purely for one caller, diluting a primitive that was just introduced for a
different, cleaner purpose. See §2.2 for the resulting decision.

---

## 2. New/changed frontend components

### 2.1 `ProfilePhoto` (new, `shared/components/ProfilePhoto.tsx` + `.module.css`)

Replaces `dashboard/ProfessionalProfileImageField.tsx`'s job, and is designed to be
reusable by the customer profile too (§3.1's "avatar" option).

Props:
```ts
interface ProfilePhotoProps {
  imageUrl: string | null;
  /** Fallback initial(s) shown in the circle when imageUrl is null. */
  fallbackInitial: string;
  size?: 96 | 104; // DESIGN_SYSTEM.md §30 profile-page range; default 104
  /** Omit entirely to render a non-interactive, non-editable avatar (customer default option, §3.1). */
  onUpload?: (file: File) => void;
  isUploading?: boolean;
  uploadError?: string;
}
```

Renders:
- A circular `<button type="button">` (or plain `<div>` if `onUpload` is omitted and
  there's nothing to click into) wrapping the photo/initials-fallback, centered via
  `margin-inline: auto` in its own block, `cursor: zoom-in`. Click → opens `ImageLightbox`
  (§2.2) with the current `imageUrl` (disabled/no-op if `imageUrl` is null — an empty
  avatar has nothing to enlarge).
- Exactly **one** edit affordance when `onUpload` is supplied: a small round icon button
  (camera/pencil, `lucide-react`) overlapping the photo's bottom-inline-end edge,
  `aria-label="עריכת תמונת פרופיל"`, wired to a hidden `<input type="file" accept="image/*">`
  via `ref` + `.click()` — clicking it opens the OS file picker directly, no intermediate
  "Add"/"Replace" two-step. On file pick, calls `onUpload(file)` immediately (preserves
  the existing "upload on selection" behavior both `ProfessionalProfileImageField` and
  `DESIGN_SYSTEM.md`'s `PhotoUploader` precedent already establish — not changed by this
  redesign).
- No second button anywhere, no reused `ImageUploadField` — resolves §1.2's finding
  directly. Its own upload-trigger button is captioned in code, not hardcoded generic
  text: `"עריכת תמונה"` ("edit photo").

Built on a plain hidden file input (the same low-level primitive `ImageUploadField`
itself wraps), not on `ImageUploadField` — justified by §1.2/§1.7's findings: neither
existing component's shape fits "one circular photo, one edit-in-place affordance,
click-to-enlarge."

### 2.2 `ImageLightbox` (new, `shared/components/ImageLightbox.tsx` + `.module.css`)

```ts
interface ImageLightboxProps {
  isOpen: boolean;
  onClose: () => void;
  imageUrl: string;
  alt?: string;
}
```

A new, small, dedicated component — **not** a `Modal` variant (§1.7). Renders via
`createPortal(document.body)`, locks body scroll while open, closes on `Escape` and on
overlay click — the same three behaviors `Modal` already implements, reimplemented here
in ~25-30 lines rather than parameterizing `Modal` for a visual shape it wasn't designed
for. Markup: full-viewport dark overlay (`rgba(0,0,0,0.85)`), a single close (`X`) button
top-corner, and the image centered, `max-width: 90vw; max-height: 90vh; object-fit:
contain; border-radius: var(--radius-md)`. No title, no footer, no form chrome — this is
the "Facebook-style" interaction the requirement names.

**Why a new component instead of extending `Modal`**: `Modal`'s API (`title`, `footer`,
`size: 'small'|'normal'|'large'`) is shaped around form dialogs and only just landed for
that one purpose. A photo lightbox's layout requirements (full-bleed image, no padding
box, near-fullscreen, image-driven `max-width`/`max-height` rather than a fixed px token)
don't map onto any of `Modal`'s existing props without adding new ones
(`noHeader`/`noPadding`/`fullBleed`) whose only caller would be this one screen. A small,
purpose-built component is more proportionate than growing a just-introduced shared
primitive's surface area for a single, structurally-different use.

### 2.3 `ProfileEditorPage.tsx` (professional, `/pro/profile`) — layout

Replace the current `.card { max-width: 480px }` single-column card with a responsive
two-region layout:

- **< 900px** (matches the existing `ProDashboardLayout` mobile/desktop split precedent
  of switching at a fixed breakpoint): single column, unchanged relative order —
  `ProfilePhoto` centered at the top, form fields stacked below. `.card`'s `max-width`
  raised modestly (e.g. `560px`, still centered via `margin-inline: auto`) so remaining
  slack is distributed evenly rather than dumped on one side, addressing §1.3's finding
  even in the narrow case.
- **≥ 900px**: a two-column CSS grid — `grid-template-columns: 240px 1fr` (photo column;
  form column) — inside a container whose own `max-width` is raised to roughly `880px`
  (not unbounded — still capped so text inputs don't stretch to unreadable widths) and is
  itself centered within `.content` via `margin-inline: auto`. The photo column holds
  `ProfilePhoto` plus the existing read-only "תחום שירות" (category) row. The form column
  holds the existing `fullName`/`serviceArea`/`city`/`bio`/`basePrice` fields — optionally
  laid out two-per-row via its own inner grid for the shorter fields
  (`serviceArea`/`city`, `basePrice`) if `pronto-coding` wants the extra polish; not
  required to satisfy this requirement, listed as an optional refinement, not a blocker.

This directly serves "uses the available screen width better" (two real columns actually
occupy the freed space) rather than only re-centering the same narrow card, while still
capping total width so the page doesn't feel unbalanced at very wide desktop viewports.

### 2.4 `app/ProfilePage.tsx` (customer, shared `/profile`) — edit mode + layout fix

**Scope decision (stated plainly, not left ambiguous)**: the new edit capability applies
**only to a `CUSTOMER` caller** of this shared route. A `PROFESSIONAL` caller of `/profile`
keeps the current read-only display (only gets the photo-treatment/field-adjacency layout
fixes below, applied universally). Reasoning: `fullName` is a shared `users` column
already editable by a professional at the dedicated `/pro/profile` screen
(`ProfileEditorPage`'s own doc-comment already establishes this writes the underlying
`users` row); letting the same field be edited from two different screens for the same
role would create a duplicate editing surface with no product ask for it. Nothing in the
"Customer Profile" requirement bullets asks for professional-facing changes beyond the
shared layout/photo fixes both roles get.

**Structure for a `CUSTOMER` caller**:
- `ProfilePhoto` at the top, centered (§3.1 decides whether it's upload-capable or a
  static avatar).
- Editable fields — `fullName`, `phone`, and the `defaultAddress` sub-fields (§4) —
  rendered with the same `Input` components `ProfileEditorPage` already uses (label
  above the control). This both fixes §1.6's "label/value close together" complaint for
  free (label-above-input is inherently adjacent) and satisfies "consistent with the
  Professional profile" by literally reusing the same field component/pattern.
- Read-only fields that remain (`email`, `role`) keep a compact label+value display, but
  the `.row` CSS changes from `justify-content: space-between` to a tight, adjacent
  layout: label directly above value (mirroring the editable fields' own visual rhythm)
  rather than pushed to the row's two far ends.
- One `"שמירת שינויים"` save button at the bottom, same pattern/copy as
  `ProfileEditorPage`.
- Existing account-deletion flow, logout, and (for a `CUSTOMER`) the `/favorites` link
  are unchanged, kept below the (now-editable) card, same as today.

**Structure for a `PROFESSIONAL` caller** (unchanged data, fixed layout only): same
read-only fields as today (`fullName`/`email`/role/`categoryId`/`serviceArea`/
`basePrice`), `ProfilePhoto` rendered non-interactively (no `onUpload`) showing the
professional's existing `profile_image_key`-backed photo if the page has access to it —
**flagged**: today's `GET /api/users/me` response has no `professional.profileImageUrl`
field (only `categoryId`/`serviceArea`/`basePrice`, per `api-contract.md` §2.4's current
professional example). Adding it here is a one-field, low-risk `UserMeResponse` addition
(mirrors the already-resolved pattern `professionals.dto.ProfessionalProfileResponse`
uses) — recommended, but noted explicitly since it's a small scope addition beyond pure
layout, listed under §6.

---

## 3. Ambiguities requiring lead/user decision

### 3.1 FLAGGED — does "same circular, centered profile-image approach" mean customer photo *upload*, or avatar styling parity only?

Cannot be resolved with confidence from the source docs alone; presenting both readings
with a recommendation, per standing practice for genuine ambiguity.

- **Reading A — styling parity only (recommended default for this design doc)**: the
  customer gets a `ProfilePhoto` rendered with `onUpload` omitted — a circular,
  centered **initials avatar** (first letter of `fullName`, colored background per
  `DESIGN_SYSTEM.md`'s existing avatar/token conventions), sized/positioned identically
  to the professional's photo treatment, but no upload capability and no click-to-enlarge
  (nothing to enlarge). No DB migration, no new endpoint, no `profileImageUrl` field on
  `UserMeResponse`.
  - Why recommended: the "Customer Profile" bullets list *two* separate asks —
    "same circular, centered profile-image approach" and, as its own separate bullet,
    "add the ability to edit their profile" (which the surrounding context ties to
    text fields, not photos — nothing in the Customer bullets mentions upload or
    click-to-enlarge, both of which *are* explicitly named only under "Professional
    Profile"). Also matches this doc's own closing scope framing ("a profile-page
    redesign + a small edit-endpoint addition, not a new subsystem") and the explicit
    confirmation in §1.4 that no photo field exists anywhere yet for a customer.
- **Reading B — full feature parity**: add real customer photo upload, mirroring the
  professional's existing pattern almost exactly:
  - New migration `V?__alter_users_add_profile_image.sql` — `users.profile_image_key
    VARCHAR(500) NULL`.
  - New endpoint `POST /api/users/me/profile-image` (CUSTOMER role), storing under
    `customers/{userId}/profile/{uuid}.{ext}`, mirroring
    `POST /api/professionals/me/profile-image` (`api-contract-professionals-reviews.md`
    §4.3) exactly — same `StorageService#uploadWithKey`, same `ImageContentType`
    allow-list, same `201` response shape (`imageKey`/`imageUrl`/`contentType`/
    `sizeBytes`).
  - `UserMeResponse` gains a resolved `profileImageUrl: string | null` (mirrors
    `defaultAddress`/`phone`'s existing "customer-only, else null" convention, since
    only a `CUSTOMER` would ever have one under this reading).
  - `ProfilePhoto` is then used with `onUpload` supplied on the customer page too, and
    click-to-enlarge (`ImageLightbox`) becomes meaningful for a customer as well —
    the requirement's "same... approach" would then read as full parity, including the
    interactions the Professional bullets spell out.

Both readings use the exact same `ProfilePhoto`/`ImageLightbox` components (§2.1/§2.2) —
picking one over the other is a `props`/scope decision on the customer page, not a
different component design, so this ambiguity does not block building the shared photo
components now. **Needs pronto-lead/user sign-off before `pronto-coding` starts on the
customer page specifically** — flagging rather than silently picking, per standing
practice, since it changes the migration/endpoint surface materially (Reading B adds one
migration + one endpoint + one response field; Reading A adds none).

### 3.2 FLAGGED (amendment, not silent contradiction) — `defaultAddress`/`phone` become editable, reversing two "read-only, no endpoint" contract sentences

`api-contract.md` §2.4 currently states, verbatim: *"Read-only — no endpoint exists in
this API to update the default address; it is only ever set once, at registration"* and
the equivalent sentence for `phone`. §4 of this doc designs `PUT /api/users/me` to make
both editable. This is a deliberate amendment with a stated reason (not a silent
reinterpretation): `orders.service_city`/`service_street`/`service_house_number`/
`service_apartment` are captured as their own snapshot at order-creation time
(`V18__alter_orders_add_service_address.sql`, `api-contract-professionals-reviews.md`
§1.4) — decoupled from `users.default_*` — so retroactively editing a customer's saved
default address has no correctness impact on any existing or in-flight order. Recommend
proceeding with this amendment (it's what "add the ability for the Customer to edit their
profile" most naturally requires, given `fullName`/`email`-only editing would be a very
thin interpretation of "edit their profile"), but calling it out explicitly since it
reverses previously-settled contract language rather than only adding new language.
**Needs pronto-lead/user sign-off**, same as §3.1.

### 3.3 Not flagged, decided directly (per task framing): email stays read-only

`email` is excluded from the editable field set. Reasoning stated directly since the task
brief already flagged this as "almost certainly not": changing email would need to
re-trigger the email-verification flow (`emailVerified` reset to `false`, a new
verification code issued, a re-verify screen) — a materially different, unrequested
feature, not a field-length/validation-only change like `fullName`/`phone`/address. No
sign-off needed for this exclusion; noted for completeness.

---

## 4. Backend: `PUT /api/users/me`

Auth: **yes**. Role: **CUSTOMER only**. Covers §2.4/§3.2's editable-field set — `fullName`,
`phone`, `defaultAddress`. Does **not** cover the photo (§3.1's Reading B, if chosen, adds
its own separate endpoint, §3.1).

### 4.1 Role gating

New `users.config.UsersWebConfig`, mirroring `reviews.config.ReviewsWebConfig`'s existing
"same literal path, different HTTP methods need different role gates" precedent exactly
(`GET`/`DELETE /api/users/me` stay either-role/no gate; only `PUT` is `CUSTOMER`-only):

```java
registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), "PUT"))
        .addPathPatterns("/api/users/me");
```

### 4.2 Request DTO — `users.dto.UpdateUserMeRequest`

```json
{
  "fullName": "ישראל ישראלי",
  "phone": "0501234567",
  "defaultAddress": {
    "city": "תל אביב",
    "street": "אלנבי",
    "houseNumber": "12",
    "apartment": "4",
    "floor": "2",
    "entrance": "א",
    "addressNotes": "קוד כניסה 1234"
  }
}
```

- `fullName`: `@NotBlank @Size(max = 150)` — mirrors `users.full_name` column.
- `phone`: `@NotBlank @Size(max = 20)` — required whenever this endpoint is called
  (mirrors the existing registration-time requirement for a `CUSTOMER`,
  `CustomerRegistrationData.phone`; there is no "leave phone unset" state for a
  `CUSTOMER` today, so the update endpoint doesn't introduce one).
- `defaultAddress`: nullable object at the top level is **not** offered — always
  required in the request body, `@NotNull @Valid`, with `city`/`street`/`houseNumber`
  `@NotBlank` inside it and `apartment`/`floor`/`entrance`/`addressNotes` optional —
  i.e. reuse the exact same shape/validation as the existing
  `auth.dto.DefaultAddressRequest`. This also lets a pre-`V20` customer (who registered
  before default addresses existed, currently `defaultAddress: null` on `GET /me`) supply
  one for the first time via this same endpoint — no separate "add address" endpoint
  needed.
  - **Implementation-detail note for `pronto-coding`, not a blocking decision**: reusing
    `auth.dto.DefaultAddressRequest` directly from `users` would add a new
    `users → auth` package dependency edge. Recommend a small `users.dto`-local record
    with an identical shape/annotations instead (same pattern already used for
    `users.dto.DefaultAddressInfo` existing independently from `auth`'s registration
    DTOs) — avoids the new cross-package edge, no functional difference.

### 4.3 Behavior

1. Resolve caller, defense-in-depth `403 FORBIDDEN` if `role != CUSTOMER` (route-level
   gate already prevents this in practice, same belt-and-suspenders convention
   `professionals`/`bookings` already use).
2. Load the active `User` row (`loadActiveUser`, already exists in `UsersService`).
3. Set `fullName`, `phone`, `defaultCity`/`defaultStreet`/`defaultHouseNumber`/
   `defaultApartment`/`defaultFloor`/`defaultEntrance`/`defaultAddressNotes` via the
   entity's existing setters (all already present on `User.java` — no new column, no new
   migration for this part).
4. `save()`, return the same shape `getMe()` already returns (`UserMeResponse`) — no new
   response DTO needed.

### 4.4 Status codes / error codes

`200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN`. No new
`ErrorCode` values needed — proportionate to "a small edit-endpoint addition."

### 4.5 Frontend client (`shared/api/users.ts`)

```ts
export interface UpdateUserMeRequest {
  fullName: string;
  phone: string;
  defaultAddress: {
    city: string;
    street: string;
    houseNumber: string;
    apartment?: string;
    floor?: string;
    entrance?: string;
    addressNotes?: string;
  };
}

export function updateMe(payload: UpdateUserMeRequest): Promise<UserMeResponse> {
  return httpClient.put<UserMeResponse>('/api/users/me', payload);
}
```

---

## 5. Migration/endpoint summary if §3.1 Reading B is chosen instead

Kept separate from §4 so §4 alone is buildable regardless of the §3.1 outcome:

| Item | Change |
|---|---|
| Migration | New `V?__alter_users_add_profile_image.sql`: `users.profile_image_key VARCHAR(500) NULL`. |
| Endpoint | `POST /api/users/me/profile-image`, CUSTOMER role, multipart `file` — mirrors `POST /api/professionals/me/profile-image` exactly (same `StorageService`/`ImageContentType`/response shape). |
| `UserMeResponse` | New field `profileImageUrl: string \| null` (CUSTOMER-only-else-null convention, same as `defaultAddress`/`phone`). |
| Frontend | `ProfilePhoto` on `app/ProfilePage.tsx` gets `onUpload` wired to a new `uploadMyProfileImage(file)` client function; `ImageLightbox` becomes reachable from the customer page too. |

---

## 6. Small additional scope flagged in §2.4 — professional's own photo on the shared `/profile` page

`UserMeResponse.professional` (`users.dto.ProfessionalInfo`) currently carries only
`categoryId`/`serviceArea`/`basePrice` — no `profileImageUrl`. To show a professional
caller's existing photo on the shared `/profile` page (§2.4's read-only branch) needs one
additional field added to `ProfessionalInfo`/`UsersService.getMe()`, resolved the same way
`professionals.dto.ProfessionalProfileResponse.profileImageUrl` already is. Small,
low-risk, recommended — but called out explicitly since it's scope beyond pure CSS/layout
and touches a response DTO that other code paths also read.

---

## 7. Non-goals (explicit)

- No change to `/pro/profile`'s existing `serviceArea`/`bio`/`basePrice`/category fields
  or their validation — only the photo widget and page layout change.
- No change to `PUT /api/professionals/me`'s existing behavior/DTO.
- No email-change/re-verification flow (§3.3).
- No photo gallery/history for either role — "at most one profile image at a time,"
  same as the professional's existing behavior, is unchanged/extended, not redesigned.
- No mobile-specific redesign beyond the existing `<640px`/`<900px` breakpoints already
  used elsewhere in this codebase — desktop-first responsive, per project-wide scope.

---

## 8. Files this design touches (for `pronto-lead` sequencing)

**New:**
- `frontend/src/shared/components/ProfilePhoto.tsx` + `.module.css` (+ doc entry in
  `shared/components`'s README, per this project's "every package/module needs a named
  `.md` doc" rule).
- `frontend/src/shared/components/ImageLightbox.tsx` + `.module.css` (same doc
  requirement).
- `backend/src/main/java/com/pronto/users/dto/UpdateUserMeRequest.java` (+ nested address
  request record, §4.2).
- `backend/src/main/java/com/pronto/users/config/UsersWebConfig.java`.

**Changed:**
- `frontend/src/features/dashboard/ProfileEditorPage.tsx` + `.module.css` (layout, §2.3).
- `frontend/src/features/dashboard/ProfessionalProfileImageField.tsx` — retired, replaced
  by `ProfilePhoto` (§2.1); delete the file once the new usage lands.
- `frontend/src/app/ProfilePage.tsx` + `.module.css` (edit mode + layout, §2.4).
- `frontend/src/shared/api/users.ts` (`UpdateUserMeRequest`, `updateMe`, §4.5; and
  `profileImageUrl` on `UserMeResponse`/`ProfessionalInfo` if §6 is taken).
- `backend/src/main/java/com/pronto/users/controller/UsersController.java` (`PUT /me`).
- `backend/src/main/java/com/pronto/users/service/UsersService.java` (`updateMe`).
- `backend/src/main/java/com/pronto/users/entity/User.java` — no change needed for §4
  (all setters already exist); would need `profileImageKey` field only under §3.1
  Reading B.
- `docs/architecture/api-contract.md` §2.4 — amend the `defaultAddress`/`phone`
  "read-only, no endpoint" sentences per §3.2, and add the new §2.6 `PUT /api/users/me`
  entry.
- `docs/architecture/data-model.md` §2.2 — no column change needed for §4; would need a
  `profile_image_key` row only under §3.1 Reading B.
