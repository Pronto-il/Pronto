# `storage`

## Purpose

S3 image upload integration.

## Responsibilities

- Issue pre-signed upload URLs, or proxy the upload, to AWS S3 for issue photos (per
  `docs/architecture/overview.md` §3.5).
- Meet the 5s max-upload-time target (PRD §5.1.4).
- Return the resulting object URL for `issues` to store in `issue_images.image_url`.

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Called by `issues` during image attachment as part of issue creation.

## Data model

No tables owned by this package. Its output (an S3 URL) is stored by `issues` in
`issue_images.image_url` (see `docs/architecture/data-model.md` §2.7).

## Status

Stub only, no logic yet — implemented in **Milestone 2 (Issue creation & AI
classification)** per `docs/architecture/implementation-plan.md`.
