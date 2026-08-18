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

Each CSS-module file (`ComponentName.module.css`) sits next to its component.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), first consumed by `features/auth`'s
registration/login/verify screens. Extended as later milestones need new shared UI —
`StatusBadge` was added in **Frontend Milestone 3 — Standard booking flow (2026-08-16)**.

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
