# `issues`

## Purpose

Issue creation, category selection, and image metadata; orchestrates the `ai` package for
classification.

## Responsibilities

- Owns the `Issue` and `IssueImage` JPA entities (`issues`, `issue_images` tables).
- Exposes the "New Issue" flow: description + optional images → AI-suggested category
  (via `ai`, a stateless preview call) → customer confirms/overrides → issue persisted.
- Coordinates with `storage` to upload attached images before/alongside issue creation.

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Calls `ai` for the category suggestion (issue rows are only persisted after the
  customer confirms/edits the suggestion — see `docs/architecture/data-model.md` §3 item
  7).
- Calls `storage` for S3 image upload, storing the resulting URL in `issue_images`.
- Consumed by `bookings` — an order is created against a confirmed, persisted issue.

## Data model

Owns `issues` (§2.6) and `issue_images` (§2.7) in
`docs/architecture/data-model.md`.

## Status

Stub only, no logic yet — implemented in **Milestone 2 (Issue creation & AI
classification)** per `docs/architecture/implementation-plan.md`.
