# `storage`

## Purpose

Image upload/retrieval behind a `StorageClient` abstraction, swappable between a
local-disk fake (`local`, default) and real AWS S3 (`s3`) via `pronto.storage.mode` —
mirrors the `auth.email.EmailSender` mock/real split from Milestone 1.

Implements `docs/architecture/api-contract-issues.md` §2.3–2.4 and §3.2–3.3.

## Responsibilities

- `POST /api/storage/images` — backend-proxied multipart upload (not pre-signed S3 URLs;
  see `api-contract-issues.md` §2.3 for why). Validates content-type (`image/jpeg` /
  `image/png` / `image/webp` only → `400 UNSUPPORTED_IMAGE_TYPE`) and size (8 MB cap →
  `413 IMAGE_TOO_LARGE`, enforced both by `spring.servlet.multipart.max-file-size` and a
  defense-in-depth re-check in `StorageService`). Generates the object key
  (`customers/{callerId}/issues/temp/{uuid}.{ext}`) and delegates to `StorageClient`.
  Creates **no database row** — an `issue_images` row can't exist until an `issues` row
  does (`issue_images.issue_id NOT NULL`), so this package never touches the DB at all.
- `GET /api/storage/images/**` — backend-proxied retrieval, used by **both** storage
  modes (not local-mode-only): `imageUrl` always points back at this same endpoint, in
  local mode serving straight off disk and in `s3` mode downloading from S3 server-side
  and streaming the bytes back (the S3 bucket blocks all public access, so a raw S3 URL
  would always 403 — see `client.S3StorageClient`'s Javadoc). Mapped with a trailing
  wildcard, not `{key}`, since the key itself contains `/` characters (§2.4's
  implementation-note gotcha). Ownership is enforced purely from the key's embedded
  `customers/{callerId}/...` prefix — always `403 FORBIDDEN` on a mismatch (never `404`,
  to avoid leaking existence via a distinguishable error code).
- Both endpoints require `role = CUSTOMER`, checked via
  `common.security.RoleRequiredInterceptor` (which itself calls
  `common.security.RoleGuard`) — see "Role enforcement" below.

## Key classes

| Class | Role |
|---|---|
| `client.StorageClient` | The swappable abstraction: `upload`/`download`/`exists`/`resolveUrl`. See "Deviation from the contract doc" below for why `resolveUrl` was added beyond the doc's exact interface snippet. |
| `client.LocalDiskStorageClient` | Default (`pronto.storage.mode=local`). Writes/reads under `pronto.storage.local.base-dir`, preserving `key` as a relative file path. Rejects any key that would resolve outside the base directory (path-traversal defense — real, since `GET /api/storage/images/**` accepts a key as caller-supplied request-path input). |
| `client.S3StorageClient` | Real AWS SDK v2 implementation (`pronto.storage.mode=s3`). Activates by setting `pronto.storage.mode=s3` plus real credentials/bucket/region. `resolveUrl` returns a backend-proxied `GET /api/storage/images/**` URL (same shape as `LocalDiskStorageClient`), not a raw S3 URL — the bucket blocks all public access. |
| `client.StoredObject` | `(key, url, contentType, sizeBytes)` — result of a successful upload. |
| `client.StorageException` | Unchecked, thrown by a `StorageClient` implementation on a genuine I/O failure; callers translate it to `502 STORAGE_SERVICE_ERROR`. |
| `ImageContentType` | The 3 accepted content-types ↔ file-extension mapping — single source of truth used both when generating an upload key and when re-deriving `Content-Type` for `GET /api/storage/images/**` (local storage doesn't separately persist content-type metadata). |
| `ImageKeyUtils` | Parses the `customers/{callerId}/...` key format — the sole ownership mechanism (§3.3, no DB row exists to record it). Pure/stateless, used by both this package and `issues`. |
| `service.StorageService` | Business logic for both endpoints (validation, key generation, ownership/existence checks). |
| `controller.StorageController` | `/api/storage/images` POST + `/api/storage/images/**` GET. |
| `config.StorageWebConfig` | Registers `common.security.RoleRequiredInterceptor(role = "CUSTOMER")` for `/api/storage/**` (see "Role enforcement" below). |
| `dto.ImageUploadResponse` / `dto.RetrievedImage` | Response/internal-transfer shapes. |

## Interactions with other packages

- Depended on by `issues` for `client.StorageClient` (resolving `imageUrl` for
  `issue_images` at issue-creation time, §2.2 step 6) and `ImageKeyUtils`/`ImageContentType`
  (the `imageKeys` ownership check shared by both `/classify` and `POST /api/issues`, §3.3).
- Depended on by `ai` (`service.ClassificationService`) to resolve `imageKeys` to raw bytes
  for the real OpenAI client — never via the public `imageUrl` (see `ai/README.md`'s
  "image reachability" note).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and
  `RoleGuard`/`AuthenticatedUser`.

## Role enforcement (deviation from `@PreAuthorize`, flagged)

Both endpoints are gated by `common.security.RoleRequiredInterceptor` (registered for
`/api/storage/**` by `config.StorageWebConfig`), which calls
`common.security.RoleGuard.requireRole(principal, "CUSTOMER")` from `preHandle` — before
DispatcherServlet invokes the controller method — rather than declarative `@PreAuthorize`.
Wiring `@EnableMethodSecurity` plus a custom `AccessDeniedHandler` (so a denial still
produces the standard error envelope, not Spring Security's default blank 403 body) would
require changes to `auth.config.SecurityConfig` — out of bounds for this task (`auth` was
not to be touched). `RoleGuard` reuses the exact same JWT `role` claim and the exact same
`ApiException`/`GlobalExceptionHandler` envelope every other error in the app already goes
through — not a parallel mechanism, just enforced one phase earlier than argument
resolution instead of via annotation. Flagged to `pronto-lead`: migrating to
`@PreAuthorize` is a reasonable follow-up if `auth.SecurityConfig` becomes touchable in a
later milestone.

**Ordering bug, fixed (QA-reported, post-Milestone-2):** the role check originally lived as
the first line of each controller method body (`RoleGuard.requireRole` called directly),
which ran *after* Spring resolved `@RequestParam("file") MultipartFile file` for
`POST /api/storage/images`. A professional-role caller sending no `file` part therefore got
`400 VALIDATION_ERROR` instead of `403 FORBIDDEN`. Moving the check into
`RoleRequiredInterceptor.preHandle` (which always runs before argument resolution) fixed
it; re-verified against a real local Postgres. The in-controller-body `RoleGuard` calls
were removed as redundant once the interceptor covers every `/api/storage/**` route.

## Deviation from the contract doc's exact `StorageClient` interface, flagged

`api-contract-issues.md` §3.2 shows `StorageClient` with only `upload`/`download`/`exists`.
A fourth method, `resolveUrl(String key)`, was added because §2.2 step 6 requires
re-deriving the *same* URL an already-uploaded key originally resolved to, at
issue-creation time — but no `issue_images` row (and therefore no stored URL) exists at
upload time (§3.3), so nothing in the system remembers that URL anywhere else. Both
implementations compute the URL deterministically from the key alone, so `resolveUrl`
needs no new state, and `upload` itself is implemented in terms of it. The alternative
(re-calling `upload`) was rejected: it would rewrite the object's bytes for no reason and
require `issues` to hold onto raw image bytes across the classify→confirm gap, which it
never otherwise does.

## Data model

No tables owned by this package (per design — see `api-contract-issues.md` §3.3 for why
uploaded images have no DB row until `issues` creates one). Its output (a resolved URL) is
stored by `issues` in `issue_images.image_url` (`docs/architecture/data-model.md` §2.7).

## Assumptions / judgment calls made during implementation

- 8 MB max upload size and the `image/jpeg`/`image/png`/`image/webp` allow-list are
  recommendations from `api-contract-issues.md` §2.3, not hard requirements — easy to
  tune via `application.yml`/`ImageContentType` without a migration.
- Local storage base directory defaults to `./data/uploads` (relative to the backend
  process's working directory) — fine for local dev/QA, should be set to an absolute,
  durable path via `STORAGE_LOCAL_BASE_DIR` in any real deployment still using local mode.
- `pronto.storage.public-base-url` (shared by both storage modes, not local-mode-specific)
  defaults to `http://localhost:${server.port}` — must be overridden
  (`STORAGE_PUBLIC_BASE_URL`) in any environment where the backend isn't reachable at
  `localhost` from the frontend's perspective.
- S3 bucket-privacy/access-policy: decided — backend-proxied (same pattern as local mode),
  not public-read and not pre-signed URLs, since the bucket blocks all public access.
  `S3StorageClient.resolveUrl` returns a `GET /api/storage/images/**` URL, not a raw S3
  URL; `StorageController`/`StorageService` already worked for this generically through the
  `StorageClient` interface, no controller/service changes were needed.
- No orphaned-upload cleanup job (a customer who uploads photos and abandons the New Issue
  flow leaves those objects in storage forever, since no DB row ever tracked them) — flagged
  as a known, accepted MVP gap in the contract doc §4, not built here.

## Status

Implemented in **Milestone 2 (Issue creation & AI classification)**, per
`docs/architecture/implementation-plan.md`. Unit-tested (`ImageKeyUtilsTest`,
`ImageContentTypeTest`, `LocalDiskStorageClientTest`, `StorageServiceTest` —
upload/download round-trip, path-traversal rejection, content-type/size validation,
ownership-mismatch/not-found retrieval branches). Manually smoke-tested end-to-end against
a real local Postgres + local-disk storage: upload → retrieve (byte-for-byte match) →
cross-customer retrieval correctly `403`s → unsupported content-type correctly
`400`s. Full milestone QA sign-off is `pronto-qa`'s call, not asserted here.

Post-Milestone-2 bug fix (QA-reported): the role-check-ordering bug described above under
"Role enforcement" has been fixed and re-verified against a real local Postgres —
professional token + missing `file` part on `POST /api/storage/images` now correctly
`403`s instead of `400`.
