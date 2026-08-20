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
- `Checkbox` — labeled native `<input type="checkbox">` + `<label>`, added MS11 (Services &
  Sub-services, 2026-08-19; `docs/architecture/product-ms11-sub-services-design.md` §5.1) —
  the first-ever consumer of the `Checkbox` primitive `DESIGN_SYSTEM.md` §85 had listed by
  name but left unbuilt. No `error`/`hint` props (unlike `Input`/`Textarea`) — the sub-services
  checklist it was built for has no per-item validation, only a list-level save error. First
  and only consumer: `features/dashboard/ProfileEditorPage.tsx`'s sub-services checklist.
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
- `Skeleton` — shared loading placeholder (MS1 — Visual Foundation & Motion System,
  2026-08-20). `variant: 'text'|'rect'|'circle'` (default `'rect'`); `lines` (default `1`,
  `text` variant only — renders that many shimmer lines, the last one shortened when
  `lines > 1`); `radius` overrides the variant's default border-radius (`rect` →
  `--radius-lg`, `circle` → `50%`, `text` line → `--radius-sm`). Deliberately has no
  width/height prop — callers size the placeholder to match real content via
  `className`/`style`, same as the two ad hoc blocks it replaces. Uses the shared
  `pronto-skeleton` shimmer keyframe from `styles/motion.css`. Replaces the duplicated
  inline `.skeleton` blocks in `ProfessionalList.module.css` and
  `StartTimePicker.module.css`.
- `Badge` — generic status/label pill, per DESIGN_SYSTEM.md §33 (covers the "מומלץ עבורך"
  recommended-badge treatment on its own) and backs the refactored `StatusBadge` below.
  `tone: 'neutral'|'primary'|'success'|'warning'|'error'|'info'` (default `'neutral'`);
  `size: 'sm'|'md'` (default `'md'`, an exact visual match for `StatusBadge`'s previous
  standalone sizing — 28px height, `--space-3` inline padding, `--font-size-small`
  semibold); optional `icon`.
- `FilterChip` / `FilterChipGroup` — single-select filter chips, per DESIGN_SYSTEM.md §34.
  `FilterChipGroup` renders `role="radiogroup"` wrapping `FilterChip`s (`role="radio"`,
  a real `<button>` so native Tab focus + Enter/Space activation work with no extra
  wiring). Deliberately **not** a roving-tabindex/arrow-key widget — every chip is
  individually tabbable, per the MS1 plan's corrected scope decision (the current/near-term
  consumer, `ProfessionalList`'s sort chips, doesn't need arrow-key nav). Each chip gets a
  44px touch target via inset expansion (`::after`) while staying visually compact (36px),
  satisfying DESIGN_SYSTEM §73's minimum touch target without changing the chip's look.
  Replaces `ProfessionalList.module.css`'s inline `.chip`/`.chipActive`.
- `EmptyState` — shared empty/error-state surface, covering both DESIGN_SYSTEM.md §60
  (empty) and §61 (error) with one component switched by `tone: 'neutral'|'error'` (default
  `'neutral'`; `'error'` adds `role="alert"` and swaps the default icon). `icon` overrides
  the tone's default icon (`Inbox`/`AlertTriangle` from `lucide-react`); `title`/
  `description`/`action` (e.g. a `<Button>`). No card/border wrapper — plain surface, per
  the project's anti-generic-SaaS-container rule. Also accepts `mascotState`, a
  forward-compat placeholder typed loosely as `string` (not `MascotState`, to avoid a
  build-time dependency on `Mascot.tsx` from before it existed) — **currently accepted but
  not rendered**; a follow-up task should tighten its type and render `<Mascot state=
  {mascotState} ... />` in place of the default icon. Consolidates 4 duplicated empty-state
  blocks + 5 duplicated error-copy blocks found across the codebase; MS1 only creates the
  component, migrating the 9 pages that had ad hoc copies is later-milestone work.
- `Mascot` — Pronto's brand mascot, state-driven rather than decorative (MS1). `state:
  'idle'|'running'|'thinking'|'searching'|'found'|'success'`; `size: 'sm'|'md'|'lg'|'xl'`
  (default `'md'`, explicit width/height per size to avoid layout shift); `label` (omit for
  a fully decorative mascot — `aria-hidden`, `alt=""` — supply to make it a meaningful
  image, used as `alt` text); `loop` (default `true` for `running`/`searching`/`thinking`,
  `false` renders the pose statically instead; has no effect on `idle`, always static, or
  `found`/`success`, always one-shot). **4 physical poses back 6 semantic states** — a
  known, documented limitation, not a bug: `idle`/`thinking`/`found` all render the
  `pointing` pose, differentiated only by motion treatment and surrounding page copy, not
  distinct artwork; `running`→`running-wrench`, `searching`→`running-screwdriver` (visually
  distinguishes "coming to you" from "looking for someone"); `success`→`success`. Motion is
  CSS for the looping states (`running`/`searching` bounce + trailing 3-bar motion-lines,
  `thinking` subtle pulse, all in `Mascot.module.css`) and one-shot `framer-motion` for
  `found`'s slide-in and `success`'s pop (`shared/motion/variants.ts`'s
  `mascotSlideIn`/`successPop`). RTL-aware motion-lines placement. Sourced from the 4
  transparent PNGs in `frontend/src/assets/mascot/` — see `frontend/tools/mascot/README.md`
  for how those are produced from the original brand renders. First consumer: `app/
  HomePage.tsx` (see that package's README).
- `ToastViewport` — portaled toast stack (`createPortal` into `document.body`, mirroring
  `Modal.tsx`'s own portal pattern). No props — reads the live stack straight from
  `ToastProvider`'s context via `useToast()` (see `shared/hooks/README.md`'s Toast triad
  entry); renders `null` whenever the stack is empty, so mounting it before any
  `showToast()` caller exists (MS1's own state) has zero visible effect. Docks to the
  viewport's **top-center** (not the bottom corner `ActiveOrderIndicator` FAB occupies) at
  `z-index: var(--z-toast)` (1100, above `Modal`'s `--z-overlay` 1000, so a toast triggered
  from within a modal stays visible). `role="status"`/`aria-live="polite"` on the stack
  container; enter/exit via `AnimatePresence` + the shared `toastTransition` variant,
  respecting OS-level reduced motion via `framer-motion`'s own `useReducedMotion()`.

**MS1 upgrades to existing primitives (2026-08-20, all additive/opt-in — zero behavior
change when the new props are omitted):**
- `Card` gained `interactive?: boolean` (default `false`) — only when `true` does the card
  get CSS hover (border-strong + `--shadow-elevated`, desktop-only via
  `@media (hover: hover) and (pointer: fine)`) and a `scale(0.99)` press. Plain/informational
  `Card` usage (most existing call sites) is visually unchanged.
- `Button` gained a CSS `:active` press-scale (`--motion-press-scale`); its transitions were
  tokenized onto the new duration/ease tokens. No API change.
- `PageHeader` gained `steps?: { current: number; total: number }` (1-indexed `current`),
  rendering a thin animated `role="progressbar"` track below the title/description — closes
  a DESIGN_SYSTEM §38 gap (previously text-only, e.g. "שלב 1 מתוך 3" with no visual bar).
  Omitting `steps` keeps the exact previous text-only behavior. The back button's hit area
  was also bumped to a real 44px `min-height` (was ~26px, a §73 violation).
- `Input`/`Textarea`/`Select`/`Checkbox` got a token pass only (focus transition
  duration/ease onto the new `--duration-*`/`--ease-*` tokens, a consolidated
  `--color-focus-ring` replacing three duplicated hardcoded `rgba(...)` values) — no API
  change.
- `Modal` gained `framer-motion` entrance/exit (`AnimatePresence` + the shared
  `modalTransition` variant — scale+fade on desktop, slide-up on mobile, selected via a
  `matchMedia` read of the same `640px` breakpoint its CSS already uses; instant when
  `useReducedMotion()` is on), plus a focus trap (`Tab`/`Shift+Tab` cycle within the panel's
  focusable elements while open) and focus-restore-on-close (a real, previously-missing
  a11y gap — the one live consumer, `CalendarBlockModal`, was re-verified working). Also
  gained `mobilePresentation?: 'sheet' | 'dialog'` (default `'sheet'`, preserving today's
  actual behavior for the existing consumer) — `'dialog'` opts a future consumer out of the
  bottom-sheet treatment and keeps a centered dialog (with subtle motion) even on narrow
  viewports. Shadow/z-index moved onto `--shadow-modal`/`--z-overlay`.
- `StatusBadge` was refactored to render `Badge` internally (`size="md"`, tone mapping
  unchanged: `PENDING`→info, `CONFIRMED`→primary, `ON_THE_WAY`→info, `COMPLETED`→success,
  `CANCELLED`/`EXPIRED`→neutral, `REJECTED`→error) — public API/output unchanged (still
  just `{ status: OrderStatus }`). Also gained a one-shot CSS fade whenever `status`
  changes (re-keyed wrapper `<span>`, not a `framer-motion` variant, since it only needs to
  play once per status change, not coordinate mount/unmount).

Each CSS-module file (`ComponentName.module.css`) sits next to its component.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), first consumed by `features/auth`'s
registration/login/verify screens. Extended as later milestones need new shared UI —
`StatusBadge` was added in **Frontend Milestone 3 — Standard booking flow (2026-08-16)**;
`ProfilePhoto`/`ImageLightbox` were added in **MS10 — Profile UI Redesign (2026-08-19)**;
`Modal` was added in **the professional weekly availability calendar feature, M5
(2026-08-18)** — see its entry above. `Skeleton`/`Badge`/`FilterChip`/`FilterChipGroup`/
`EmptyState`/`Mascot`/`ToastViewport` were added, and `Card`/`Button`/`PageHeader`/
`Input`-family/`Modal`/`StatusBadge` were upgraded, in **MS1 — Visual Foundation & Motion
System (2026-08-20)** — see their entries above.

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

**MS1 — Visual Foundation & Motion System (2026-08-20)**: 7 new shared primitives
(`Skeleton`, `Badge`, `FilterChip`/`FilterChipGroup`, `EmptyState`, `Mascot`,
`ToastViewport`) and additive upgrades to 9 existing ones (`Card`, `Button`, `PageHeader`,
`Input`, `Textarea`, `Select`, `Checkbox`, `Modal`, `StatusBadge`) — see each entry above for
its exact API. All new/upgraded components are exported from `index.ts`. This milestone
also fixed `app/HomePage.tsx`'s previously-broken mascot image (a 404'd
`/assets/pronto-runner-wrench.png` reference) by wiring in the new `Mascot` component —
see `app/README.md`'s MS1 entry. No product page beyond that one was redesigned; MS1 is
foundation-only — a dev-only `/__design` showcase route (`app/DesignSystemPage.tsx`,
gated behind `import.meta.env.DEV`) demonstrates every new/upgraded primitive from this
milestone; see `app/README.md`'s MS1 entry for that route's own detail.

## `ProfessionIllustration` (2026-08-20)

The one place that knows which drawing represents which profession — a `Record<categoryId,
asset>` over `assets/rollete-animation-images/`, whose files were renamed from their generator
names (`ChatGPT Image Aug 20, 2026, 10_03_49 PM (1).png` …) to the profession each depicts, so
the mapping can be checked by reading it. Consumers pass a `categoryId`; no screen does
filename matching.

| Category | Illustration |
|---|---|
| 1 אינסטלציה | `plumbing.png` — sink trap, wrench |
| 2 חשמל | `electrical.png` — wall outlet, screwdriver |
| 3 מיזוג אוויר | `ac-hvac.png` — split unit, manifold gauges, stepladder |
| 4 תיקון מוצרי חשמל | `appliance-repair.png` — wall-mounted boiler |
| 5 מנעולן | `locksmith.png` — door lock, keyring |
| 6 נגרות | **none supplied** — renders the `Mascot` fallback |
| 7 צביעה | `painting.png` — roller, paint can |
| 8 הנדימן כללי | `general-handyman.png` — full kit, on the move |

Category 6 is deliberately absent from the map rather than pointed at a loosely-related
drawing: showing a plumber for a carpenter would be a quieter bug than a missing image, not a
smaller one. The component renders `Mascot state="idle"` and emits one dev-only console warning
per unmapped id, so the gap is reported instead of swallowed. Adding a carpentry drawing to the
folder and one line to the map completes it.
