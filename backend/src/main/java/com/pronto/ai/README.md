# `ai`

## Purpose

OpenAI client wrapper and classification service, kept separate from `issues` so it's
independently testable/mockable.

## Responsibilities

- Server-side-only OpenAI API client (API key never reaches the client).
- Given an issue description (and optional images), request a suggested `categories`
  entry from the fixed 8-category list plus a short explanation.
- Stateless: this package does not persist anything — the suggestion is a preview
  returned to `issues`, which is the one that decides whether/what to persist.

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Called by `issues` during the "New Issue" flow.
- Reads the fixed `categories` reference table (see `docs/architecture/overview.md`
  §3.8) to constrain/validate its output, but does not own that table.

## Data model

No tables owned by this package. Uses `categories` (read-only) —
`docs/architecture/data-model.md` §2.1.

## Status

Stub only, no logic yet — implemented in **Milestone 2 (Issue creation & AI
classification)** per `docs/architecture/implementation-plan.md`.
