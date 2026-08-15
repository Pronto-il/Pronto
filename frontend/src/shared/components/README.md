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
  a later milestone's per-request service address field.

Each CSS-module file (`ComponentName.module.css`) sits next to its component.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), first consumed by `features/auth`'s
registration/login/verify screens. Extended as later milestones need new shared UI.
