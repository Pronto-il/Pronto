# shared/components

## Purpose
Reusable UI components used across more than one feature (buttons, form fields, layout
primitives, etc.) — not feature-specific components, which live under their own
`features/*` folder.

## Responsibilities
- Generic, presentational building blocks with no feature-specific business logic.
- Consistent RTL/Hebrew-aware styling baseline shared across the app, built on the design
  tokens defined in `src/index.css` (`--color-*`, `--radius-*`, `--space-*`, per
  `Pronto — DESIGN_SYSTEM.md` §84).

## Components
- `Button` — variants `primary`/`secondary`/`ghost`/`destructive`; `loading` prop shows a
  spinner and disables the button (use this on every form submit button to prevent
  double-submission).
- `Input` — labeled text input; `error` renders an inline message below the field, `hint`
  renders quiet supporting text when there's no error.
- `Select` — labeled select with the same states as `Input`; used for service category.
- `Card` — base card (white surface, border, `--radius-lg`, no heavy shadow).
- `PageHeader` — page title + optional description + optional back action (back arrow
  points right, per RTL icon-mirroring rules).
- `ImageUploadField` — single-image upload with an object-URL preview + remove action.
  Image mime types only.
- `DocumentUploadField` — generic file upload (PDF or image) showing filename + a
  file-type icon + remove action instead of an image preview (a PDF can't be previewed).
  Deliberately kept separate from `ImageUploadField`.
- `AddressFormFields` — self-contained address field group (city, street, house number
  required; apartment/floor/entrance/addressNotes optional) driven by a `value`/`onChange`
  pair (`AddressValue`, `EMPTY_ADDRESS` in `addressTypes.ts`). Field names match
  `DefaultAddressRequest.java` exactly. Not coupled to "registration" — built for reuse by
  a later milestone's per-request service address field; that reuse landed in Frontend
  Milestone 3 (`features/booking/BookingFlowPage.tsx`'s service-address step).
- `StatusBadge` — maps an `OrderStatus` (`shared/api/bookings.ts`) to a Hebrew label +
  color, per DESIGN_SYSTEM.md §56 ("use consistent statuses globally... do not assign new
  colors independently on different pages"). Covers all 7 statuses (`PENDING`,
  `CONFIRMED`, `ON_THE_WAY`, `COMPLETED`, `CANCELLED`, `REJECTED`, `EXPIRED`; `EXPIRED`
  shares its color with `CANCELLED`). Every screen that shows an order's status — the
  tracking screen, the customer's my-orders list, the professional's incoming-requests
  feed and jobs list — goes through this one component rather than a per-page badge.
- `Modal` — generic modal primitive (`isOpen`/`onClose`/`title`/`children`/`footer`/`size`),
  added for the professional weekly availability calendar feature (M5, 2026-08-18; design
  `docs/architecture/professional-weekly-calendar-design.md` §7.4/§13/§57-59). One component
  renders both a desktop centered dialog (`420`/`560`/`720px` via `size`) and a mobile bottom
  sheet (`border-radius: 20px 20px 0 0`) — CSS `@media (max-width: 640px)` decides which is
  visually active, no `variant` prop needed from callers (this codebase's existing
  desktop-grid/mobile-day-view breakpoint pattern, already used by `WeeklyCalendarGrid`).
  Portal-rendered into `document.body` (`createPortal`), closes on overlay click and
  `Escape`, locks body scroll while open. First (and, as of M5, only) consumer:
  `features/dashboard/CalendarBlockModal.tsx`.
- `ProfilePhoto` — circular, centered profile photo/avatar (MS10 profile redesign,
  2026-08-19; `docs/architecture/product-ms10-profile-redesign-design.md` §2.1). Renders a
  photo (`imageUrl`) or an initials fallback (`fallbackInitial`), `96`/`104px` sizing per
  `DESIGN_SYSTEM.md`'s profile-page range. Omit `onUpload` for a read-only avatar (no edit
  affordance, e.g. a customer's own photo or a professional viewed by someone else);
  supply it for exactly one edit affordance — a small round icon button overlapping the
  photo's bottom-inline-end edge, wired straight to a hidden `<input type="file">` (no
  separate "Add photo" control). Clicking the photo itself opens `ImageLightbox` when a real
  `imageUrl` exists. Replaces the retired `features/dashboard/ProfessionalProfileImageField.tsx`
  and is also reused, without `onUpload`, on the shared `app/ProfilePage.tsx`.
- `ImageLightbox` — full-viewport, dark-overlay ("Facebook-style") image viewer:
  `isOpen`/`onClose`/`imageUrl`/`alt`. Portal-rendered, closes on `Escape`/overlay click,
  locks body scroll while open — same three behaviors `Modal` has, reimplemented here
  because the visual shape (full-bleed image, no title/footer/padding box) doesn't fit
  `Modal`'s form-dialog-shaped API (see `docs/architecture/product-ms10-profile-redesign-
  design.md` §1.7/§2.2 for the full reasoning). First (and, as of MS10, only) consumer:
  `ProfilePhoto`.

Each CSS-module file (`ComponentName.module.css`) sits next to its component.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), first consumed by `features/auth`'s
registration/login/verify screens. Extended as later milestones need new shared UI —
`StatusBadge` was added in **Frontend Milestone 3 — Standard booking flow (2026-08-16)**;
`ProfilePhoto`/`ImageLightbox` were added in **MS10 — Profile UI Redesign (2026-08-19)**;
`Modal` was added in **the professional weekly availability calendar feature, M5
(2026-08-18)** — see its entry above.

**MS3/MS4 product-corrections pass (2026-08-17)**: `PhotoUploader`'s `UploadedPhoto` shape
gained an `imageUrl` field (`result.imageUrl` from `POST /api/storage/images`'s response,
previously received but discarded — only the ephemeral `previewUrl`
(`URL.createObjectURL(file)`) was kept). At the time, `imageUrl` was described as "durable"
and the field was added as a supporting fix for `shared/hooks`' new booking-draft
persistence (`BookingDraftPhoto.imageUrl`) — without it, a draft rehydrated after a hard
reload would have valid `imageKey`s but broken/blank photo thumbnails.

**Correction, backend MS9 (2026-08-18): that "durable" framing is no longer accurate, and
`imageUrl` no longer does the cross-reload job described above.** `POST /api/storage/images`'s
response `imageUrl` became a presigned URL in backend MS9 (300s TTL, not permanent) — it is
still exactly right for the job it's actually needed for, same-page-load display right after
a successful upload, well within the TTL — but persisting it across a reload/pause-and-resume
gap is no longer safe. `BookingDraftPhoto` (the cross-reload-persisted shape) dropped its own
`imageUrl` field entirely and now persists only `imageKey`, re-resolved to a fresh presigned
URL on resume via a new batch endpoint — see `frontend/src/shared/hooks/README.md`'s
`bookingDraftContext.ts` entry and
`docs/architecture/backend-ms9-presigned-image-urls-design.md` §12.1. `UploadedPhoto.imageUrl`
itself is unaffected — it remains on this component's own shape, unchanged in purpose, doing
only its original same-session job. `PhotoUploader` gained two related, narrower additions to
support draft-resume: `UploadedPhoto.previewUrl` widened from `string` to `string | null`
(`null` is a deliberate sentinel for "not yet re-resolved," used only while
`NewIssuePage.tsx`'s resume flow is fetching fresh presigned URLs for a draft's photos — the
render loop shows the same spinner/`uploadingOverlay` markup already used for a live in-flight
upload), and a new optional `UploadedPhoto.error` string (set only when a resume-time batch
re-resolution fails outright, reusing the existing `itemError` treatment — a distinct case
from a live upload failure).
