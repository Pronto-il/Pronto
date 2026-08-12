# features/issues

## Purpose
The customer-facing issue reporting flow: describing a problem and getting an
AI-suggested service category.

## Responsibilities
- Home / New Issue screen — text description entry plus optional image attachments.
- AI Review screen — shows the AI-suggested category and explanation, lets the customer
  confirm or override it against the fixed 8-category list.
- Hands off into `features/booking` once a category is confirmed (customer picks
  Standard or SOS).

## Status
Stub only — no screens yet. Implemented in **Milestone 2 — Issue creation & AI
classification** (`docs/architecture/implementation-plan.md`), against the backend
`issues`, `ai`, and `storage` packages.
