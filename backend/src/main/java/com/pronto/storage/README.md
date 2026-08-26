# `storage`

## Purpose

Image upload/retrieval behind a `StorageClient` abstraction, swappable between a
local-disk fake (`local`, default) and real AWS S3 (`s3`) via `pronto.storage.mode` —
mirrors the `auth.email.EmailSender` mock/real split from Milestone 1.

Implements `docs/architecture/api-contract-issues.md` §2.3–2.4 and §3.2–3.3.

## Deliberate reversal of the backend-proxying decision (backend MS9, 2026-08-18)

This package's image-*retrieval* mechanism was reversed in backend MS9, by explicit user
instruction — worth stating plainly, up front, since it directly contradicts what this
README (and `client.S3StorageClient`'s own Javadoc) said before this round. Through
Milestone 7, every image fetch — in both `local` and `s3` storage modes — was
backend-proxied through this package's own `GET /api/storage/images/**`, stated at the time
as "a deliberate decision, not a placeholder pending one." That decision is now reversed:
retrieval issues presigned URLs instead (a real AWS S3 presigned GET URL in `s3` mode,
pointing directly at S3; an HMAC-signed URL back to this same route in `local` mode) — see
"Image retrieval — presigned/signed URLs (backend MS9)" below for the full mechanism.
**Why**: a plain HTML `<img src="...">` cannot attach the `Authorization` header the old,
JWT-gated retrieval route required, so every `<img>`-tag consumer of an issue-photo or
profile-image URL failed with `net::ERR_BLOCKED_BY_ORB` — first surfaced by QA during
Frontend Milestone 9. Upload stays backend-proxied and JWT-gated, unaffected by this
reversal — this is about retrieval only. Full design record:
`docs/architecture/backend-ms9-presigned-image-urls-design.md`.

## Responsibilities

- `POST /api/storage/images` — backend-proxied multipart upload (not pre-signed S3 upload
  URLs; see `api-contract-issues.md` §2.3 for why — this is about the *upload* mechanism,
  unchanged by MS9). Validates content-type (`image/jpeg` / `image/png` / `image/webp` only
  → `400 UNSUPPORTED_IMAGE_TYPE`) and size (8 MB cap → `413 IMAGE_TOO_LARGE`, enforced both
  by `spring.servlet.multipart.max-file-size` and a defense-in-depth re-check in
  `StorageService`). Generates the object key
  (`customers/{callerId}/issues/temp/{uuid}.{ext}`) and delegates to `StorageClient`.
  Creates **no database row** — an `issue_images` row can't exist until an `issues` row
  does (`issue_images.issue_id NOT NULL`), so this package never touches the DB at all. The
  upload response's `imageUrl` is now a presigned URL (see below), not a permanent proxy
  URL — see "Image retrieval — presigned/signed URLs (backend MS9)" for why.
- **Image retrieval — presigned/signed URLs (backend MS9, reworked 2026-08-18).** Prior to
  MS9, `GET /api/storage/images/**` was backend-proxied and JWT-gated in both storage
  modes: a plain `<img src="...">` cannot attach an `Authorization` header, so every
  `<img>`-tag consumer of this route failed with `net::ERR_BLOCKED_BY_ORB`
  (`docs/architecture/backend-ms9-presigned-image-urls-design.md` §0 item 1). **This was a
  real, explicit reversal of this package's own prior recorded architecture decision** — see
  "Deliberate reversal of the backend-proxying decision" below — not a silent contradiction.
  The fix replaces `StorageClient#resolveUrl` (permanent, non-expiring proxy URL) with
  `StorageClient#presignUrl(key, expiry)` (time-limited, 300s default TTL via
  `pronto.storage.presigned-url-ttl-seconds`):
  - **S3 mode**: a real AWS S3 presigned GET URL, minted by `S3Presigner`/
    `GetObjectPresignRequest`, pointing directly at S3 — this backend is never touched by
    the actual image `GET` at all.
  - **Local mode**: an HMAC-SHA256-signed query-string URL back to this same
    `GET /api/storage/images/**` route (`?expires={epochSeconds}&sig={base64urlHmac}`),
    signed/verified by `client.LocalHmacUrlSigner` using the dedicated
    `pronto.storage.local.hmac-secret`.
  - **Authorization moved from the Spring Security filter layer to URL-issuance time.**
    `GET /api/storage/images/**` is now `permitAll()` in `auth.config.SecurityConfig`
    (scoped to `HttpMethod.GET` only — `POST` upload is untouched and stays fully
    JWT-gated). The route itself no longer checks a JWT at all; in local mode, the
    `expires`/`sig` HMAC pair *is* the sole real gate (any missing/malformed/expired/
    mismatched combination → `401 UNAUTHORIZED`, deliberately indistinguishable failure
    modes, same anti-enumeration spirit as the pre-existing "always `403`, never `404`, on
    an ownership mismatch" rule). In S3 mode the route is realistically never hit by a
    legitimate client at all (S3 presigned URLs point straight at AWS). The per-key
    ownership check that used to run inside this route (`ImageKeyUtils.belongsTo`/
    `isPubliclyReadable`) still exists, unchanged in substance — it just now runs earlier,
    inside `service.StorageService#getPresignedUrl`/`#getPresignedUrls`, at the moment a
    URL is minted, rather than on every byte-streaming `GET`. See "Role enforcement" below
    for the full mechanism and `docs/architecture/backend-ms9-presigned-image-urls-design.md`
    §1-§4 for the full design record.
  - **New batch endpoint**: `POST /api/storage/images/presigned-urls` (`{ imageKeys }` →
    `{ images: [{ imageKey, imageUrl }] }`, may return fewer entries than requested — a
    missing/unauthorized key is simply omitted, not a batch-level failure). Any
    authenticated caller; per-key authorization reuses the same `authorize()` check as the
    single-key path. Capped at 20 keys per request (`400 VALIDATION_ERROR` above that).
    Added specifically for `frontend/src/features/issues/NewIssuePage.tsx`'s booking-draft
    resume flow — a paused draft only ever persists a photo's raw `imageKey` (never a URL,
    since a URL saved at pause time would be long expired by resume time), so resuming now
    re-resolves every photo's key into a fresh presigned URL via one batch call. See
    `docs/architecture/backend-ms9-presigned-image-urls-design.md` §12 and
    `frontend/src/shared/hooks/README.md`'s `bookingDraftContext.ts` entry.

## Key classes

| Class | Role |
|---|---|
| `client.StorageClient` | The swappable abstraction: `upload`/`download`/`exists`/`presignUrl`. `presignUrl(key, expiry)` replaced `resolveUrl(key)` in backend MS9 (removed, not deprecated-and-kept — see the interface's own Javadoc for why keeping both was rejected). See "Deviation from the contract doc" below for why a fourth method exists at all beyond the doc's original `upload`/`download`/`exists` snippet. |
| `client.LocalDiskStorageClient` | Default (`pronto.storage.mode=local`). Writes/reads under `pronto.storage.local.base-dir`, preserving `key` as a relative file path. Rejects any key that would resolve outside the base directory (path-traversal defense — real, since `GET /api/storage/images/**` accepts a key as caller-supplied request-path input). `presignUrl` builds `{publicBaseUrl}/api/storage/images/{key}?expires=...&sig=...`, delegating the HMAC signing to `client.LocalHmacUrlSigner`. |
| `client.S3StorageClient` | Real AWS SDK v2 implementation (`pronto.storage.mode=s3`). Activates by setting `pronto.storage.mode=s3` plus real credentials/bucket/region. `presignUrl` returns a real AWS S3 presigned GET URL via `S3Presigner`/`GetObjectPresignRequest`, pointing directly at S3 — not a backend-proxied URL (see "Deliberate reversal" below). |
| `client.LocalHmacUrlSigner` | **New, backend MS9.** `@Component`, only registered as a bean when `pronto.storage.mode=local` (same `@ConditionalOnProperty` guard `LocalDiskStorageClient` uses). `sign(key, expiresEpochSeconds)` / `isValid(key, expiresEpochSeconds, signature)` — HMAC-SHA256 over `key + "|" + expiresEpochSeconds`, base64url-encoded without padding, compared with `MessageDigest.isEqual` (constant-time, avoids a timing side-channel). Secret: `pronto.storage.local.hmac-secret`, deliberately separate from `JWT_SECRET` (different blast radius — see its own Javadoc / the design doc §3). |
| `client.StoredObject` | `(key, url, contentType, sizeBytes)` — result of a successful upload. `url` is now a presigned URL (backend MS9), not a permanent proxy URL. |
| `client.StorageException` | Unchecked, thrown by a `StorageClient` implementation on a genuine I/O failure; callers translate it to `502 STORAGE_SERVICE_ERROR`. |
| `ImageContentType` | The 3 accepted content-types ↔ file-extension mapping — single source of truth used both when generating an upload key and when re-deriving `Content-Type` for `GET /api/storage/images/**` (local storage doesn't separately persist content-type metadata). |
| `ImageKeyUtils` | Parses the owner-segment key format — the sole ownership mechanism (§3.3, no DB row exists to record it). Its `OWNER_PATTERN` covers `customers/{callerId}/...` **and** `verification-documents/{callerId}/...` (the latter predates MS1, from the registration-flow separation task; this row previously named only the first, which was stale rather than wrong-by-MS1). Also exposes `isPubliclyReadable`, `true` for `professionals/`-prefixed keys (profile images, no ownership check — see "Role enforcement" below). Pure/stateless, used by this package, `issues`, and (indirectly, via `StorageService`) `professionals`. Unchanged by backend MS9 — this class's rules are still exactly what `StorageService#authorize` (formerly inline in `retrieve`) enforces, just invoked earlier in the request lifecycle. |
| `service.StorageService` | Business logic for both endpoints. **As of backend MS9**: `retrieve(AuthenticatedUser, String)` is retired; replaced by `getPresignedUrl(Long/AuthenticatedUser, String)` (the general, ownership-checked path — reuses the exact `isPubliclyReadable`/`belongsTo` logic the old `retrieve` had, via a private `authorize()` helper), `getPresignedUrlAssumingCallerAuthorized(String)` (a narrow, explicitly-named bypass with **no** ownership check of its own — callable only by code that has already independently established the caller may view the key; sole approved caller today is `issues.service.IssuesService#getById`, see that package's README), `getPresignedUrls(Long, List<String>)` (the batch path backing the new endpoint, per-key failures are caught and simply omitted, never fail the whole batch), and `retrieveBySignedUrl(key, expires, sig)` (verifies the local-mode HMAC signature+expiry, then streams the bytes — this is what `GET /api/storage/images/**` now calls). **As of MS1**: also `getVerificationDocumentUrlForOperator(String key)` — the prefix-locked, single-caller operator read path, see "Operator read path — verification documents" below — and `getPresignedUrlTtlSeconds()`, which exposes the configured TTL so a caller can tell its client when the URL dies instead of duplicating the property. |
| `controller.StorageController` | `/api/storage/images` POST (upload) + `/api/storage/images/**` GET (retrieval, `permitAll()`, no `@AuthenticationPrincipal` — see "Role enforcement") + `/api/storage/images/presigned-urls` POST (new, the batch lookup). |
| `config.StorageWebConfig` | Registers `common.security.RoleRequiredInterceptor(role = "CUSTOMER")` for exactly `POST /api/storage/images` (narrowed from a blanket `/api/storage/**` — see "Role enforcement" below). `GET /api/storage/images/**` has no route-level role gate — and, as of backend MS9, no route-level authentication requirement either (see "Role enforcement"). |
| `dto.ImageUploadResponse` / `dto.RetrievedImage` | Response/internal-transfer shapes. |
| `dto.PresignedImageUrlsRequest` / `dto.PresignedImageUrlEntry` / `dto.PresignedImageUrlsResponse` | **New, backend MS9.** Wire shapes for the batch `POST /api/storage/images/presigned-urls` endpoint. |

## Interactions with other packages

- Depended on by `issues` for `client.StorageClient` (uploading images) and
  `service.StorageService#getPresignedUrl`/`#getPresignedUrlAssumingCallerAuthorized`
  (resolving a presigned `imageUrl` for `issue_images` — as of backend MS9, resolved fresh
  at *read* time in both `create` and `getById`, never persisted resolved; see
  `issues/README.md`) and `ImageKeyUtils`/`ImageContentType` (the `imageKeys`
  ownership/existence check shared by both `/classify` and `POST /api/issues`, §3.3,
  unrelated to and unchanged by MS9's presign work).
- Depended on by `ai` (`service.ClassificationService`) to resolve `imageKeys` to raw bytes
  for the real OpenAI client — never via the public `imageUrl` (see `ai/README.md`'s
  "image reachability" note).
- Depended on by `bookings` (`service.BookingsService#enrichAndSort`), `favorites`
  (`service.FavoritesService#toSummary`), and `professionals`
  (`service.ProfessionalsService#toResponse`/`#uploadProfileImage`) — all four call sites
  resolve a `professionals/{professionalId}/...` profile-image key to a displayable URL via
  `StorageService#getPresignedUrl`. **As of backend MS9**, all three of `bookings`/
  `favorites`/`professionals` inject `StorageService` directly (not `StorageClient`) for
  this — `professionals` previously injected both and dropped the redundant `StorageClient`
  field; `bookings`/`favorites` previously called `StorageClient#resolveUrl` directly and
  now call `StorageService#getPresignedUrl(callerId, key)` instead, so the same
  ownership/visibility check every other presign call site goes through also applies here
  (a no-op in practice for these public-prefix keys, since `isPubliclyReadable` short-circuits
  it, but keeps every caller going through one consistent mechanism, per this project's
  explicit "no per-feature one-off mechanisms" rule). `professionals` also depends on this
  package for `StorageService.uploadWithKey`, for its own
  `professionals/{professionalId}/profile/...` key template — see "Role enforcement" below
  for how retrieval of those keys differs from `issues`' `customers/`-prefixed keys.
- **New, MS1**: depended on by `professionals`
  (`service.ProfessionalApprovalService`) for `getVerificationDocumentUrlForOperator` +
  `getPresignedUrlTtlSeconds` — the operator's read of a professional's verification document.
  This is the *only* approved caller of that method; see "Operator read path — verification
  documents" below. `auth.service.AuthService` remains the sole *writer* of that key namespace
  (it uploads the document during the registration transaction, under
  `verification-documents/{userId}/...`).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and
  `RoleGuard`/`AuthenticatedUser`.

## Role enforcement (deviation from `@PreAuthorize`, flagged)

`POST /api/storage/images` (upload) is gated by `common.security.RoleRequiredInterceptor`
(registered for exactly that literal path by `config.StorageWebConfig`), which calls
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
later milestone. **Unchanged by backend MS9** — this section is about upload only.

`GET /api/storage/images/**` (retrieval) — **reworked, backend MS9 (2026-08-18). This
section previously described a JWT-gated-then-service-layer-authorized retrieval flow; that
flow no longer exists.** The route is now `permitAll()` at
`auth.config.SecurityConfig` (scoped to `HttpMethod.GET` only, so `POST` upload is
untouched and stays fully JWT-gated via the same config's blanket
`.anyRequest().authenticated()`). This was necessary, not optional: a plain HTML
`<img src="...">` cannot attach an `Authorization` header, so as long as this route required
one, every `<img>`-tag consumer of an issue-photo or profile-image URL failed with
`net::ERR_BLOCKED_BY_ORB` — first surfaced by QA during Frontend Milestone 9 (see
`frontend/src/features/dashboard/README.md`'s now-resolved "Open, unresolved issue" note)
and root-caused/fixed by
`docs/architecture/backend-ms9-presigned-image-urls-design.md`.

**`permitAll()` here does not mean "anyone can read any object by key."** It only means
Spring Security's filter chain no longer 401s/403s this request before it reaches the
controller — authorization *moved*, from that filter layer to two other points, depending on
storage mode:

- **Local mode**: the sole real gate is now the HMAC `expires`/`sig` query-string pair
  (§3 of the design doc) that `service.StorageService#retrieveBySignedUrl` verifies on every
  `GET` — missing, malformed, expired, or signature-mismatched all produce an identical
  `401 UNAUTHORIZED` (deliberately indistinguishable, so a caller can't tell *why* a probe
  failed — the same anti-enumeration spirit the pre-existing "always `403`, never `404`, on
  an ownership mismatch" rule already established elsewhere in this package). The only way to
  obtain a valid `sig` is for this backend to have minted one itself, via
  `client.LocalHmacUrlSigner.sign` — which only happens inside an already-authorized
  `StorageService#getPresignedUrl`/`#getPresignedUrlAssumingCallerAuthorized`/
  `#getPresignedUrls` call.
- **S3 mode**: this route is realistically never reached by a legitimate client at all — S3
  presigned URLs point directly at AWS, bypassing this backend entirely. If it somehow were
  hit (e.g. someone hand-constructing a URL against this route while running S3 mode),
  `retrieveBySignedUrl` still rejects with `401 UNAUTHORIZED`, since `LocalHmacUrlSigner` has
  no bean in this mode at all (`Optional` is empty).

**Where the old per-key ownership check went**: `ImageKeyUtils.belongsTo`/
`isPubliclyReadable` — the actual "is this caller allowed to see this key" logic — is
**unchanged in substance**, it just runs at a different, *earlier* point in the request
lifecycle: inside `StorageService`'s private `authorize()` helper, called from
`getPresignedUrl`/`getPresignedUrls` at the moment a URL is *minted* (in response to
whatever endpoint embeds an `imageUrl` in its response — e.g. `GET /api/issues/{id}`, a
professional-listing card, a profile view), not on the later `GET` that actually fetches the
bytes. `customers/{callerId}/...` keys (issue images) still require an exact caller-id match
via `ImageKeyUtils.belongsTo` for the *general* presign path — with one narrow, explicitly-
named exception: `issues.service.IssuesService#getById` calls
`getPresignedUrlAssumingCallerAuthorized` instead, deliberately bypassing this check, because
`getById`'s own broader role-based check (customer owns the issue, OR professional has an
order on it) has already established the caller may view every image on that issue — see
`issues/README.md` for the full reasoning. `professionals/{professionalId}/...` keys
(profile images) still have **no** ownership check at all
(`ImageKeyUtils.isPubliclyReadable`) — unchanged, deliberate, since profile images are shown
to any customer browsing listings, not just their owning professional.

**New, backend MS9**: `POST /api/storage/images/presigned-urls` — a batch counterpart to the
single-key presign path, any authenticated caller, per-key ownership enforced the same way.
See "Responsibilities" above and `service.StorageService#getPresignedUrls`.

## Operator read path — verification documents (Production Roadmap MS1, 2026-08-22)

`service.StorageService#getVerificationDocumentUrlForOperator(String key)` is a **second,
narrower exemption** from `authorize()`, added so a Pronto operator can look at the
verification document a professional uploaded at registration before approving or rejecting
them. It is documented here, at package level, because the constraint it carries is one a
future contributor must find *before* they reach for it — not only by opening the method.

**Why an exemption was needed at all.** `authorize()` resolves ownership out of the key
itself: `ImageKeyUtils`'s `OWNER_PATTERN` covers both `customers/{userId}/...` and
`verification-documents/{userId}/...`, so a verification document is readable only by the
user who uploaded it. That is correct, and it is exactly why it refuses an operator, who by
construction is *not* that user. Verification documents are deliberately **not** under the
`professionals/` public prefix — a profile image is meant to be seen by anyone browsing
listings; a compliance document is not.

**The rejected alternative**, stated so nobody re-proposes it: teaching `authorize()` that an
`ADMIN` may read anything. That would silently widen access to every private key in the
system — including customers' issue photos — on the strength of a role check made in a class
that has no idea what it is being asked to unlock.

**Three independent narrowings instead**, each of which alone would be insufficient:

1. **Prefix-locked.** Only keys beginning `verification-documents/`; anything else is
   `403 FORBIDDEN` outright. It therefore cannot be turned into a general read primitive for
   `customers/`-prefixed issue images even by a caller who gets to choose the key.
2. **Reachable only from the `ADMIN` route.** Its sole caller is
   `professionals.service.ProfessionalApprovalService#getVerificationDocumentUrl`, behind
   `/api/admin/professionals/**`, which `common.security.RoleRequiredInterceptor` gates on
   `ADMIN` in `preHandle` — before argument resolution.
3. **The key is never client-supplied.** That caller reads it off the `professionals` row it
   just loaded by id (`Professional#getVerificationDocumentKey`); no request field reaches this
   parameter. A professional with no document at all is a `404` from the caller, before this
   method is entered.

**Do not add a third caller** without a justification of the same kind, written down. The
method's own Javadoc says so, and this section exists so the rule is visible from the package
doc rather than only from the method body. The same standing constraint already applies to
`#getPresignedUrlAssumingCallerAuthorized` (sole approved caller:
`issues.service.IssuesService#getById`) — this method is deliberately *not* a second caller of
that one, precisely because that Javadoc forbids it without re-justification.

**The minted URL is a bearer capability**, valid for `pronto.storage.presigned-url-ttl-seconds`
(300 by default): anyone holding it can fetch a private compliance document without
authenticating. It **must never be logged, cached in a shared store, or included in an error
message** — and neither must the key, which is the durable half of the same secret. Nothing in
this method or its caller logs either; the caller's audit line records *that* an operator
viewed a professional's document (which operator, whose document) and nothing that would let a
log reader fetch it. QA grepped the logs after exercising the flow and found zero occurrences
of the key, the `verification-documents/` prefix, or the signature/expiry query parameters
(`docs/production-roadmap/reports/MS1-report.md`, Validation 31).

`#getPresignedUrlTtlSeconds()` was added alongside it, so a caller can tell its own client when
to stop relying on a URL (`professionals.dto.VerificationDocumentUrlResponse.expiresInSeconds`)
rather than duplicating the property.

**Known gap, recorded not hidden**: the `403` prefix-lock branch has no unit test —
`grep -rn "ForOperator" backend/src/test/` returns no matches (MS1 report, Validation 33 /
Known Limitation 12). The route-level `ADMIN` gate *is* tested
(`common.security.AdminRouteGatingTest`) and the end-to-end operator flow was live-verified,
but the prefix lock itself is currently proven only by reading it.

**Ordering bug, fixed (QA-reported, post-Milestone-2):** the role check originally lived as
the first line of each controller method body (`RoleGuard.requireRole` called directly),
which ran *after* Spring resolved `@RequestParam("file") MultipartFile file` for
`POST /api/storage/images`. A professional-role caller sending no `file` part therefore got
`400 VALIDATION_ERROR` instead of `403 FORBIDDEN`. Moving the check into
`RoleRequiredInterceptor.preHandle` (which always runs before argument resolution) fixed
it; re-verified against a real local Postgres.

**Profile-image `403`-for-everyone bug, fixed (QA-reported, post-Milestone-7):** when
`professionals.service.ProfessionalsService#uploadProfileImage` (Milestone 7.x) started
generating `professionals/{professionalId}/profile/...` keys and returning their resolved
`GET /api/storage/images/**` URL as `profileImageUrl` in listing/profile responses, both
this route's original blanket-`/api/storage/**` `CUSTOMER`-only gate and
`ImageKeyUtils`'s `customers/`-only ownership pattern meant *every* caller — including the
owning professional — got `403 FORBIDDEN` fetching it: the route rejected a
`PROFESSIONAL`-role caller outright, and even a `CUSTOMER`-role caller failed the
`customers/{callerId}/...` ownership regex (a `professionals/...` key never matches it).
Fixed by narrowing `StorageWebConfig`'s interceptor registration to exactly
`POST /api/storage/images` (leaving `GET` either-role at the time) and adding
`ImageKeyUtils.isPubliclyReadable`/(then) `StorageService#retrieve`'s special case above.
`customers/`-prefixed issue-image behavior (route gate now implicit via
`SecurityConfig`'s blanket authentication instead of this package's interceptor, but the
service-layer ownership check, error codes, and outcomes) was unchanged at the time — this
predates backend MS9's `retrieve` → `getPresignedUrl`/`retrieveBySignedUrl` split above; the
special-casing this bug fix introduced (`isPubliclyReadable`) carried forward unchanged into
`StorageService#authorize`, MS9's directly-descended equivalent of the old inline check.

## Deviation from the contract doc's exact `StorageClient` interface, flagged

`api-contract-issues.md` §3.2 shows `StorageClient` with only `upload`/`download`/`exists`.
A fourth method was added because §2.2 step 6 requires deriving a URL for an already-uploaded
key at issue-creation time, and no `issue_images` row (and therefore no stored URL) exists at
upload time (§3.3), so nothing in the system remembers that URL anywhere else. Originally
`resolveUrl(String key)` (deterministic, non-expiring); **replaced by `presignUrl(String key,
Duration expiry)` in backend MS9** (removed, not deprecated-and-kept — see
`client.StorageClient`'s own Javadoc for the full reasoning: keeping a second,
differently-behaved URL-producing method around risked a future call site silently
reintroducing the `net::ERR_BLOCKED_BY_ORB` bug this migration fixed, or a permanent-URL
leak). Both implementations compute the URL deterministically from the key (plus, now, the
requested expiry) alone, so `presignUrl` needs no new state beyond what `upload` already had
from `resolveUrl`, and `upload` itself is implemented in terms of it. The alternative
(re-calling `upload`) remains rejected for the same original reason: it would rewrite the
object's bytes for no reason and require `issues` to hold onto raw image bytes across the
classify→confirm gap, which it never otherwise does.

## Data model

No tables owned by this package (per design — see `api-contract-issues.md` §3.3 for why
uploaded images have no DB row until `issues` creates one). Its output is a presigned URL
(backend MS9) — deliberately **never persisted anywhere** (a presigned URL is time-limited,
valid only for `pronto.storage.presigned-url-ttl-seconds`). `issues` stores the raw object
*key* it presigns, not the URL, in `issue_images.image_key`
(`docs/architecture/data-model.md` §2.8) — renamed from `image_url` in MS9, once storing a
resolved URL there stopped being safe (see `issues/README.md`).

## Assumptions / judgment calls made during implementation

- 8 MB max upload size and the `image/jpeg`/`image/png`/`image/webp` allow-list are
  recommendations from `api-contract-issues.md` §2.3, not hard requirements — easy to
  tune via `application.yml`/`ImageContentType` without a migration.
- Local storage base directory defaults to `./data/uploads` (relative to the backend
  process's working directory) — fine for local dev/QA, should be set to an absolute,
  durable path via `STORAGE_LOCAL_BASE_DIR` in any real deployment still using local mode.
- `pronto.storage.public-base-url` — **narrowed in scope by backend MS9**: previously shared
  by both storage modes, now **local-mode-only** (used to build
  `{publicBaseUrl}/api/storage/images/{key}?expires=...&sig=...`). S3 mode no longer uses it
  at all — S3 presigned URLs come entirely from `S3Presigner`, pointing directly at the S3
  endpoint. Defaults to `http://localhost:${server.port}` — must be overridden
  (`STORAGE_PUBLIC_BASE_URL`) in any environment where the backend isn't reachable at
  `localhost` from the frontend's perspective and is still running local mode.
- S3 bucket-privacy/access-policy: the bucket blocks all public access (default SSE-S3
  encryption, no public-read policy) — unchanged since this was originally decided.
  **How that private bucket is made viewable was reversed in backend MS9**: originally
  backend-proxied (`S3StorageClient.resolveUrl` returned a `GET /api/storage/images/**` URL,
  never a raw S3 URL); as of MS9, `S3StorageClient.presignUrl` mints a real, time-limited AWS
  S3 presigned GET URL via `S3Presigner`, pointing directly at S3 — the bucket itself is
  never made public, only a specific object becomes fetchable for a bounded window. See
  "Deliberate reversal of the backend-proxying decision" note in `client.S3StorageClient`'s
  own Javadoc and `docs/architecture/backend-ms9-presigned-image-urls-design.md` §5 for the
  full reasoning (in short: backend-proxying required every `<img src>` consumer to attach a
  JWT `Authorization` header, which a plain `<img>` tag cannot do, causing
  `net::ERR_BLOCKED_BY_ORB` on every such request).
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

Post-Milestone-7 bug fix (QA-reported): the profile-image `403`-for-everyone bug described
above under "Role enforcement" has been fixed. `ImageKeyUtilsTest`/`StorageServiceTest`
gained coverage for `isPubliclyReadable`/the `professionals/`-prefixed public-read retrieval
path, plus an explicit regression test proving `customers/`-prefixed ownership enforcement
is untouched. Live-verified against a real local Postgres + local-disk storage + the running
jar: a freshly-uploaded `professionals/{id}/profile/...` image is retrievable by its owning
professional, by a different professional, and by a customer (all `200`, byte-for-byte
match against the owner's fetch); the previously-403ing pre-existing seeded professional's
`profileImageUrl` now also returns `200`; an unauthenticated request to the same URL still
`401`s (SecurityConfig's blanket authentication requirement is untouched). Full backend
suite re-run (83 tests, all passing) and a live `customers/`-prefixed issue-image regression
(owner `200`, cross-customer `403`, a professional token also `403` on a customer's issue
image, `POST /api/storage/images` still `403`s for a professional caller) confirm zero
behavior change to the issue-image path. **This specific "professional token also `403` on a
customer's issue image" outcome describes the state of the world at Milestone 7 and is now
superseded, not still-current** — see "Backend MS9" below: a professional with a confirmed
order on the issue now correctly gets the image, via `issues.service.IssuesService#getById`'s
own broader authorization rule. A professional with **no** order on the issue still correctly
`403`s (or, more precisely as of MS9, is rejected by `getById`'s own role check before any
image-URL resolution is even attempted) — that part of the outcome is unchanged.

**Backend MS9 — presigned image URLs (2026-08-18).** Retrieval was reworked from a
backend-proxied, JWT-gated `GET /api/storage/images/**` to presigned/HMAC-signed URLs, fixing
a real, previously-undocumented-as-fixed `net::ERR_BLOCKED_BY_ORB` bug (a plain `<img src>`
cannot attach an `Authorization` header). This is a genuine, explicit reversal of this
package's own prior "backend-proxied, deliberate decision" architecture choice (see
`client.S3StorageClient`'s Javadoc and the "Deliberate reversal" framing above) — not a
silent contradiction of it. Two more, previously-undiscovered bugs were found and fixed in
the same round (both affecting `issues`, not this package directly — see `issues/README.md`
for the full record): `issue_images` used to persist a *resolved* URL forever, which silently
broke once URLs became time-limited; and a professional with a legitimate order on an issue
was never actually authorized to view that issue's photos at all (a gap explicitly deferred
since Milestone 2, confirmed never picked up through Milestone 6). Backend: 163/163 tests
pass. QA live-verified: professional/avatar/favorite images render; issue photos render for
both the owning customer and an authorized professional with a real order; unauthorized
access is genuinely rejected (tampered signature, tampered/expired timestamp, and missing
signature on a local-mode signed URL all correctly `401`); a booking draft's photos, which
used to break after the presigned-URL TTL elapsed, resume correctly via the new batch
endpoint (see "Responsibilities" above). Full design record:
`docs/architecture/backend-ms9-presigned-image-urls-design.md`. Not yet committed at the
time this doc was written — branch `frontend/MS9-gap-fixes`, pending the user's own git
operations.

**Production Roadmap MS1 — operator verification-document access (2026-08-22).** One method
added to `service.StorageService` (`getVerificationDocumentUrlForOperator`) plus one accessor
(`getPresignedUrlTtlSeconds`), described in full under "Operator read path — verification
documents" above. **No `StorageClient` change, no new endpoint in this package, no config
change, no migration, no new `ErrorCode`** — the new method reuses `presignUrl` and the
existing `pronto.storage.presigned-url-ttl-seconds`, and the operator-facing route it serves
lives in `professionals`. Live-verified as part of MS1: the operator gets a `200` with
`expiresInSeconds: 300`, a customer gets `403`, an unauthenticated caller gets `401`, the
browser opens the document in a new tab with **no presigned URL in the DOM**, and a log grep
after the flow found zero occurrences of the key, the prefix, or the signature/expiry
parameters (MS1 report, Validations 16, 21 and 31). **Not verified**: the `403` prefix-lock
branch has no unit test (Known Limitation 12). Branch
`production/ms1-professional-verification`, uncommitted at the time this doc was written.

## Production MS4 (2026-08-26) — `STORAGE_MODE=local` may not reach Production

`config.StorageModeStartupGuard` (new). `pronto.storage.mode` defaulted to `local` and nothing
checked it, so a deployment that forgot `STORAGE_MODE=s3` would write every issue photo **and every
professional verification document** to `./data/uploads` inside the running container, hand back
working signed URLs, report success on every screen — and lose all of it on the next deploy. Those
documents are the identity evidence marketplace eligibility is decided on, so this was silent,
permanent loss of the most sensitive data the platform holds.

Two checks with deliberately different scopes:

- **`mode=local` refused when production-like.** A durability/functionality decision, so `demo` and
  `test` may legitimately keep local-disk storage.
- **The placeholder `STORAGE_LOCAL_HMAC_SECRET` refused in every environment except `local`** —
  including `demo` and `test`. This is the `!isLocal()` rule `auth.security.JwtSecretStartupGuard`
  uses for the JWT secret, and for the same reason. In local mode that key is the *only*
  authorization on `GET /api/storage/images/**`, which `auth.config.SecurityConfig` leaves
  `permitAll` because a plain `<img src>` cannot carry a JWT (see the backend MS9 section above).
  With the checked-in placeholder, anyone who can read this repository can mint a valid signature
  for any image key — including verification documents. An empty or under-32-character override is
  refused on the same grounds.

Plus mode/credential consistency in **every** environment: `mode=s3` requires
`STORAGE_S3_BUCKET` and `STORAGE_S3_REGION`, because `client.S3StorageClient` constructs happily
with an empty bucket string and then fails every upload, download and presign at runtime.

**Deployment consequence.** The TEST/DEMO recipe in the repository `README.md` now requires
`STORAGE_LOCAL_HMAC_SECRET` alongside the `JWT_SECRET` it already required. That is a deliberate
break of a previously-working documented flow — see the MS4 report's Known Limitations.

**AWS credentials are unchanged and remain correct**: `S3StorageClient` uses
`DefaultCredentialsProvider`, never a hardcoded key. The MS4 report recommends an IAM task role
rather than long-lived static access keys in Production.

Tests: `storage/config/StorageModeStartupGuardTest`, plus
`common/config/ProductionStartupValidationTest`.
