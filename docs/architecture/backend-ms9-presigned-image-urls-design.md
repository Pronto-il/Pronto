# Backend MS9 — Presigned Image URLs Design

Status: proposed, planning-only. No application code is written here; `pronto-coding`
implements against this doc once `pronto-lead`/the user approves it. This is the
backend-focused counterpart to `docs/architecture/frontend-ms9-gap-fixes-design.md`
(same branch, same gap-fixes effort) — that doc's item 3 ("professional sees issue
photos") is frontend-complete but blocked on the backend bug this doc fixes. See also
`frontend/src/features/dashboard/README.md`'s "Open, unresolved issue" note, which is
where QA first recorded the `net::ERR_BLOCKED_BY_ORB` failure this doc resolves.

Labeling convention used throughout: **Confirmed** (directly observed in code/docs,
not inferred), **Decision** (this doc's proposed resolution), **Recommendation**
(a judgment call with reasoning, open to being overridden), **Open question** (needs
pronto-lead/user sign-off before coding starts).

---

## 0. Three problems, not one — read this first

Investigating the ORB bug surfaced two additional, previously-undocumented-as-fixed
problems that a naive "just add presigned URLs" implementation would not fix, and in
one case would make *worse* (silently break a currently-working case). All three must
be resolved together for "professional sees issue photos" to actually work:

1. **The ORB bug itself.** `<img src>` cannot carry an `Authorization` header, and
   `GET /api/storage/images/**` is JWT-gated. **Confirmed**, already root-caused in the
   launching brief and in `frontend/src/features/dashboard/README.md`'s note. Fixed by
   §2–§4 below (presigned/signed URLs, no header needed).

2. **`issue_images.image_url` persists a *resolved* URL at issue-creation time,
   forever.** `IssuesService.create` (backend/src/main/java/com/pronto/issues/service/IssuesService.java:113)
   calls `storageClient.resolveUrl(key)` once and saves the result into
   `issue_images.image_url`; `IssuesService.getById` (line 149-151) later reads
   `img.getImageUrl()` straight back out of the DB row — it never re-resolves. This was
   fine when `resolveUrl` returned a permanent, non-expiring backend-proxy URL. It is
   **not** fine once URLs are time-limited: a presigned URL saved into that column at
   creation time will have expired long before a professional later opens an incoming
   request (or a customer revisits their issue) and `getById` serves the same stale
   string back verbatim. **Confirmed, not speculative**: `data-model.md` §2.8 (line 288)
   *already flagged this exact tradeoff* back when the column was designed —
   *"storing the S3 object key instead of a full URL would be more flexible... kept as
   named/typed since PRD names it directly and this is a low-risk, easily-migrated-later
   choice"* — and `data-model.md` §2.7 (line 178) separately notes `professionals.profile_image_key`
   *already* does it the other way (stores a bare key, resolves at read time). "Later"
   is now. See §5.1.

3. **A professional viewing a customer's issue image was never authorized at the
   storage layer — this is a pre-existing, explicitly-deferred gap, not something this
   round introduces.** `ImageKeyUtils.belongsTo` matches a `customers/{callerId}/...`
   key's embedded owner id against the *viewing* caller's id. A professional's caller id
   is never the customer's id, so this always fails for a professional, regardless of
   whether they have a legitimate order on the issue. **Confirmed, with direct textual
   evidence, not inferred:**
   - `docs/architecture/api-contract-issues.md` lines 484-488, written at M2: *"this
     endpoint's ownership check only ever authorizes the uploading customer — no
     professional-viewing case is designed here... Milestone 3/4 (bookings) will need a
     professional assigned to an order to view that order's issue images; that
     authorization rule doesn't exist yet and is explicitly out of this milestone's
     scope to design."*
   - `backend/src/main/java/com/pronto/storage/README.md` lines 188-193, the
     Milestone-7 regression-verification note, states the *tested, expected-at-the-time*
     behavior as: *"a professional token also `403` on a customer's issue image."*
   - Nothing in M3/M4/M6/M7 built the deferred rule. It was never picked up.
   - Net effect: this was **invisible** until now because every `<img>`-tag request has
     been failing at the *missing-Authorization-header* stage (problem 1, a 401 before
     any per-key check ever runs) — the 403-for-a-legitimate-professional case was never
     actually exercised end-to-end through a real browser. Fixing problem 1 without
     fixing this would immediately expose problem 3: professionals would get a real,
     reachable `403` on every issue-image presigned-URL request, and the very feature
     this round exists to ship ("professional sees issue photos before accepting") would
     still not work — just with a different error than before. See §5.4 for the
     resolution.

---

## 1. `StorageClient` interface changes

**Decision**: remove `resolveUrl(String key)` from the interface entirely (not
deprecate-and-keep) and replace it with:

```java
public interface StorageClient {
    StoredObject upload(String key, byte[] content, String contentType);
    byte[] download(String key);
    boolean exists(String key);

    /**
     * Returns a URL from which {@code key} can be fetched directly — no Authorization
     * header, no cookie, nothing but the URL itself — valid for {@code expiry} from the
     * moment this method returns. Performs NO authorization of its own; the caller
     * (StorageService) is responsible for deciding whether the current caller should be
     * allowed to see this key BEFORE calling this method. Local mode: an HMAC-signed
     * query-string URL back to this backend's own GET /api/storage/images/**. S3 mode: a
     * real AWS S3 presigned GET URL, pointing directly at S3, never touching this
     * backend.
     */
    String presignUrl(String key, Duration expiry);
}
```

Reasoning for removing rather than deprecating: `resolveUrl` was deterministic and
non-expiring — every caller of it implicitly assumed "this URL works forever, safe to
store/reuse." That assumption is now false for every URL this system issues. Keeping
`resolveUrl` around (even marked deprecated) as a second, differently-behaved
URL-producing method on the same interface is exactly the kind of "per-feature one-off
mechanism" the user's brief explicitly ruled out — a future call site could reach for
the old (now-still-compiling, still-technically-working-in-local-mode-only) method out
of habit and silently reintroduce the ORB bug or a permanent-URL leak. One method, one
behavior, no legacy escape hatch.

Both `LocalDiskStorageClient.upload()` and `S3StorageClient.upload()` currently build
`StoredObject` via `resolveUrl(key)`; both change to call `this.presignUrl(key,
defaultExpiry)` instead (see §7 for why upload responses should carry a presigned URL
too, and where `defaultExpiry` comes from).

---

## 2. `StorageService` changes

```java
@Service
public class StorageService {

    private final StorageClient storageClient;
    private final Duration presignedUrlTtl; // @Value("${pronto.storage.presigned-url-ttl-seconds}")

    // upload(...) / uploadWithKey(...) / uploadDocumentWithKey(...) — unchanged signatures.

    /** General-purpose path. Reuses the exact isPubliclyReadable/belongsTo check the old
     *  retrieve() had — see private authorize() below. 403 FORBIDDEN if callerId may not
     *  view key. Use for every call site EXCEPT the one narrow exception in §5.4. */
    public String getPresignedUrl(Long callerId, String key) {
        authorize(callerId, key);
        return storageClient.presignUrl(key, presignedUrlTtl);
    }

    /** Convenience overload for call sites that already hold an AuthenticatedUser. */
    public String getPresignedUrl(AuthenticatedUser caller, String key) {
        return getPresignedUrl(caller.id(), key);
    }

    /**
     * Issues a presigned URL for {@code key} WITHOUT any ownership/visibility check of its
     * own. Callable ONLY by code that has already independently established, via an
     * equal-or-broader authorization rule, that the current caller may view {@code key}.
     * The sole approved caller today is issues.service.IssuesService#getById — see
     * docs/architecture/backend-ms9-presigned-image-urls-design.md §5.4 for the full
     * reasoning. Do not add a second caller without re-justifying this exemption to
     * pronto-lead; this method exists to avoid over-broadening the general-purpose
     * authorize() check below, not as a general escape hatch.
     */
    public String getPresignedUrlAssumingCallerAuthorized(String key) {
        return storageClient.presignUrl(key, presignedUrlTtl);
    }

    /** Verifies the local-mode HMAC signature + expiry, then streams the bytes. See §4.
     *  Unreachable in practice under s3 mode (see §4's SecurityConfig discussion). */
    public RetrievedImage retrieveBySignedUrl(String key, Long expiresEpochSeconds, String signature) { ... }

    private void authorize(Long callerId, String key) {
        if (!ImageKeyUtils.isPubliclyReadable(key) && !ImageKeyUtils.belongsTo(key, callerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You do not have access to this image.");
        }
    }
}
```

**The old `retrieve(AuthenticatedUser caller, String key)` method is retired**, not
kept alongside the new one — see §4 for why (its sole caller, the JWT-gated branch of
`StorageController.retrieve`, is removed; nothing else calls it after this migration —
confirmed by grep, `StorageController` is `StorageService.retrieve`'s only caller
today). Its authorization logic is not lost — it is exactly what `authorize()` above
reuses, unchanged (`isPubliclyReadable` first, `belongsTo` fallback, `403 FORBIDDEN`
never `404` on mismatch, same anti-enumeration property as before).

**Recommendation, not a hard mandate — flagging for pronto-lead/pronto-coding to
decide**: the old `retrieve()` also called `storageClient.exists(key)` before
downloading, to distinguish "doesn't exist" (`404`) from "not yours" (`403`).
`getPresignedUrl`/`getPresignedUrlAssumingCallerAuthorized` do not download anything —
they only mint a URL — so an eager `exists()` check here is a pure cost with no
byte-streaming benefit: for S3 mode, `S3Presigner.presignGetObject` is a local/offline
signing operation with no network call, so adding a `HeadObjectRequest` just to
pre-validate existence adds a real network round-trip to every image-URL resolution,
including the professional-listing / SOS-listing loops that already run per-card and
get re-invoked every 3-5s by polling (see §8). If the key genuinely doesn't exist
(should not happen in normal operation — keys are always server-generated before being
handed to a client), the browser's own subsequent GET against the presigned/signed URL
will 404 or fail naturally, surfacing as a broken `<img>` rather than an API-level
`404 NOT_FOUND`. Recommend skipping the eager `exists()` check for presign issuance;
keep it only if pronto-lead prefers stricter API-level correctness over the latency
win, in which case scope it to S3 mode's `HeadObjectRequest` cost specifically (local
mode's `Files.exists()` is a cheap disk stat, negligible either way).

**Existence check that must NOT be removed**: `IssuesService.validateImageKeys` — the
`imageKeys` ownership+existence check run against `/classify` and `POST /api/issues` —
is unrelated to presign issuance and is untouched by this design (still calls
`storageClient.exists(key)` directly, which remains on the interface unchanged).

---

## 3. Local-mode HMAC signature scheme

**Decision — exact scheme:**

- **Algorithm**: HMAC-SHA256.
- **Signed payload**: the UTF-8 string `key + "|" + expiresEpochSeconds`, where
  `expiresEpochSeconds` is `Instant.now().plus(expiry).getEpochSecond()` (a decimal
  string, no leading zeros/whitespace).
- **Encoding**: the raw HMAC digest is encoded as base64url without padding
  (`Base64.getUrlEncoder().withoutPadding()`), so it can sit directly in a query-string
  value with no additional percent-encoding of `+`/`/`/`=`.
- **Query parameters appended to the existing proxy URL**: `expires` (the epoch-seconds
  integer, plain decimal) and `sig` (the base64url HMAC). Full shape:
  `{publicBaseUrl}/api/storage/images/{key}?expires={epochSeconds}&sig={base64urlHmac}`.
- **Verification** (server-side, on every `GET`): reject (see below for exact status)
  if `expires`/`sig` are missing or unparseable; reject if
  `Instant.now().getEpochSecond() > expires`; recompute the HMAC over
  `key + "|" + expires` with the same secret and compare against `sig` using
  `MessageDigest.isEqual` (constant-time — never `String.equals`, to avoid a timing
  side-channel on the comparison). Any failure produces the same outcome regardless of
  *which* check failed (missing vs. expired vs. mismatched) — deliberately
  indistinguishable, same anti-enumeration spirit as the existing "always 403, never
  404, on an ownership mismatch" rule elsewhere in this package.
- **Failure status code**: **`401 UNAUTHORIZED`** (`ErrorCode.UNAUTHORIZED`, already
  defined), not `403 FORBIDDEN`. Reasoning: this signature+expiry pair *is* the
  authentication mechanism for this route in local mode (there is no JWT on this
  request at all) — a missing/invalid/expired signature is "you are not properly
  authenticated for this resource," the same category of failure as a missing/invalid
  JWT is everywhere else in the app, not "you're a known, authenticated identity who
  lacks permission" (which is what `403` means elsewhere in this codebase, e.g. the
  cross-customer ownership mismatch). `getById`-published presigned URLs never carry a
  JWT to compare a caller's identity against in the first place, so `403`'s "known
  identity, wrong permission" framing doesn't apply here.

**Secret — config key**: `pronto.storage.local.hmac-secret`, env var
`STORAGE_LOCAL_HMAC_SECRET`, added to the existing `pronto.storage.local:` block in
`application.yml` (which today only has `base-dir`). **Confirmed gap**:
`application.yml` currently has zero `pronto.storage.*` keys wired for local dev beyond
what's already there (`mode`, `public-base-url`, `local.base-dir`, `bucket`, `region`)
— this is a genuinely new property, not a rename of something existing.

**Decision — a new, dedicated secret, not a reuse of `JWT_SECRET`**: reasoning —
- **Different blast radius / different threat model.** The JWT secret authenticates a
  user's entire session (every request they make, for up to 24h per
  `pronto.jwt.expiration-seconds: 86400`). This HMAC secret authorizes exactly one
  specific, narrow, short-lived (§6) grant: "read this one object key, until this one
  timestamp." Leaking the image-URL secret should never let an attacker forge a full
  session token, and rotating the image-URL secret (e.g. because a URL leaked somewhere
  visible) should never force every logged-in user to be signed out. Sharing the secret
  couples two mechanisms that should be independently rotatable.
- **Matches this codebase's own existing precedent** of one dedicated secret per
  concern — `JWT_SECRET` is already separate from `DB_PASSWORD`, which is separate from
  `OPENAI_API_KEY`. A shared "one secret for everything" model would be the outlier,
  not the norm, in this codebase.
- Default value follows the exact same "obviously-a-placeholder, loudly insecure"
  convention `pronto.jwt.secret`'s local-dev default already uses (e.g.
  `local-dev-only-insecure-hmac-secret-please-override-via-STORAGE_LOCAL_HMAC_SECRET-env-var-before-any-real-deployment`),
  so a stray copy-paste of the placeholder into a real deployment is visually obvious
  in a config diff.

**Recommendation, not built this round**: `auth.security.JwtSecretStartupGuard`
already refuses to start when `pronto.environment != local` and the JWT secret is
still its placeholder. The same class of guard should probably eventually cover this
new secret too, when `pronto.storage.mode == local` *and* `pronto.environment !=
local` (i.e., "someone is running local-disk storage, HMAC-signed-URL mode, outside of
local dev — don't let them do it with the placeholder secret"). Not designing the
guard's exact implementation here since it's a small, mechanical follow-up matching an
existing pattern, not a new decision — flagging it so it isn't silently forgotten.

**Where the signing/verification logic should live**: **Recommendation**: a new small
component, `storage.client.LocalHmacUrlSigner` (name illustrative, pronto-coding's
call), `@Component` with the same
`@ConditionalOnProperty(prefix = "pronto.storage", name = "mode", havingValue =
"local", matchIfMissing = true)` guard `LocalDiskStorageClient` already uses — i.e. it
only exists as a bean at all when storage mode is local. Exposes `sign(key,
expiresEpochSeconds)` (used by `LocalDiskStorageClient.presignUrl`) and `isValid(key,
expiresEpochSeconds, signature)` (used by `StorageService.retrieveBySignedUrl`).
Reasoning for a dedicated class rather than inlining into `LocalDiskStorageClient`
directly: `StorageService`/`StorageController` need to call the *verification* half,
and they depend on `StorageClient` (the interface), not on
`LocalDiskStorageClient` (the concrete local implementation) — injecting
`Optional<LocalHmacUrlSigner>` into `StorageService` keeps the "local-mode-only, may
not exist as a bean" fact explicit and typed, rather than downcasting `StorageClient`
to check if it happens to be the local implementation. **This also cleanly answers the
"what if someone hits this route while running s3 mode" edge case**: `Optional` is
empty (bean doesn't exist under `mode=s3`), so `retrieveBySignedUrl` immediately
rejects with `401 UNAUTHORIZED` regardless of what's in the query string — there is
categorically no way to authenticate a request to this backend's own proxy route under
S3 mode, which is correct (S3-mode image URLs never point at this backend at all, see
§4).

---

## 4. `SecurityConfig` change and the permitAll/still-rejects split

**Decision:**

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health", "/api/auth/**").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/storage/images/**").permitAll()
        .anyRequest().authenticated())
```

Scoped explicitly to `HttpMethod.GET` (not just the path pattern) — deliberately, so
there is no ambiguity about whether this also exempts `POST /api/storage/images`
(upload) from authentication. Upload stays fully JWT-gated and `CUSTOMER`-role-gated
exactly as today (`SecurityConfig`'s `.anyRequest().authenticated()` catch-all still
covers it, plus `StorageWebConfig`'s existing `RoleRequiredInterceptor` for the role
check) — this change touches retrieval only.

**Why `permitAll()` at the Spring Security layer does not mean "anyone can read any
local-disk file by key":** `permitAll()` only means "Spring Security's filter chain
will not itself 401/403 this request before it reaches the controller" — it says
nothing about what the controller does next. `StorageController.retrieve` /
`StorageService.retrieveBySignedUrl` become the sole gate for this route once
`SecurityConfig` steps aside, and they reject (`401 UNAUTHORIZED`, per §3) any request
whose `expires`/`sig` query parameters are missing, malformed, expired, or don't match
the HMAC recomputed server-side — i.e., authorization has *moved*, from the Spring
Security filter layer to the controller/service layer, not disappeared. A caller who
guesses/enumerates a key with no valid `sig` gets exactly the same `401` a caller with
no JWT gets on every other route today; the only way to get a `200` is to hold a
signature this backend itself issued (via `LocalHmacUrlSigner.sign`, only ever called
from an authorized `getPresignedUrl`/`getPresignedUrlAssumingCallerAuthorized` call).

**Decision: retire the JWT-gated branch of `retrieve()` entirely — do not keep it
alongside the signature-gated path.** Investigated per the brief's explicit ask
("check for any legitimate remaining direct callers of the old proxy GET without a
presigned URL"): grepped the entire frontend for any direct authenticated `fetch`/GET
of `/api/storage/images/**` — **confirmed zero exist**. `frontend/src/shared/api/storage.ts`
only ever `POST`s (upload); every consumer of a retrieved image today is (or, after
this fix, will be) a plain `<img src>` pointed at a presigned/signed URL, never a
manual authenticated fetch. `StorageController.retrieve` is `StorageService`'s only
caller of the old `retrieve(AuthenticatedUser, String)` method (confirmed by grep).
Once this migration lands, in **local mode** the route is reachable only via a
signature-gated request (no JWT ever attached, since it's an `<img src>`); in **s3
mode** the route is never hit *at all* by a legitimate client (S3 presigned URLs point
straight at AWS, bypassing this backend entirely — `SecurityConfig` never even sees
that request). There is no remaining scenario where a JWT-bearing direct `GET` to this
route is a legitimate, needed code path — keeping the old branch "just in case" would
be dead code with no test coverage reflecting real usage, and a second, redundant
authorization mechanism on the same route is itself a maintenance/security-review
burden. `@AuthenticationPrincipal AuthenticatedUser principal` is dropped from
`StorageController.retrieve`'s signature entirely; it becomes
`retrieve(HttpServletRequest request, @RequestParam(required = false) Long expires,
@RequestParam(required = false) String sig)`.

**Flag for pronto-coding**: `StorageServiceTest` (mentioned in `storage/README.md`'s
"Status" section as already covering "ownership-mismatch/not-found retrieval branches"
against the old `retrieve()`) will need real rework, not just a rename, since the
method it tests is being removed and replaced by two differently-shaped methods
(`getPresignedUrl`/`getPresignedUrlAssumingCallerAuthorized` plus
`retrieveBySignedUrl`). Not attempting to design the test plan here — pronto-coding's
and pronto-qa's job — but flagging so it isn't discovered as a surprise mid-implementation.

**Flag for pronto-documentation**: `storage/config/StorageWebConfig.java`'s javadoc
(lines 18-31, quoted in part in §0 above) and large portions of
`backend/src/main/java/com/pronto/storage/README.md`'s "Role enforcement" section
describe the *old* "GET is either-role, JWT-required, per-key-authorized-in-service"
story in detail — both need a real rewrite once this lands, not a patch, since the
underlying mechanism (permitAll + signature) is genuinely different, not just
relabeled.

---

## 5. S3-mode presigning approach

**Confirmed approach, per the brief**: AWS SDK v2's `S3Presigner` +
`GetObjectPresignRequest`. Concretely, in `S3StorageClient`:

```java
private final S3Presigner presigner; // built once in the constructor, alongside s3Client

@Override
public String presignUrl(String key, Duration expiry) {
    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(expiry)
            .getObjectRequest(getObjectRequest)
            .build();
    return presigner.presignGetObject(presignRequest).url().toString();
}
```

`S3Presigner` is built once in the constructor (mirrors how `s3Client` itself is
already a field, not rebuilt per call) using the same
`Region`/`DefaultCredentialsProvider` the existing `s3Client` build already uses.
**Recommendation**: `S3Presigner` implements `SdkAutoCloseable`; add a
`@PreDestroy`-annotated `close()` method (or have `S3StorageClient` implement
`DisposableBean`) to release it cleanly on application shutdown — minor resource
hygiene, not a blocking design decision, flagged so it isn't missed.

**New Maven dependency, confirmed needed**: verified locally
(`~/.m2/repository/software/amazon/awssdk/`) that `s3-presigner` is not currently
downloaded — `pom.xml` declares the AWS SDK v2 BOM (`2.28.20`, imported) and the `s3`
artifact, but `S3Presigner` lives in the separate `s3-presigner` artifact. Add to
`backend/pom.xml`, right after the existing `s3` dependency, no explicit version
(managed by the already-imported BOM, exactly like `s3` itself):

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3-presigner</artifactId>
</dependency>
```

**`S3StorageClient`'s javadoc — explicit, deliberate reversal, not a silent
contradiction.** The class's current javadoc (lines 23-30) states: *"every image fetch
is backend-proxied, never a direct-to-S3 redirect or a pre-signed URL (a deliberate
decision, not a placeholder pending one)."* **This decision is being reversed by
explicit, direct user instruction in this round** (private bucket stays private;
presigned URLs replace backend-proxying for both storage modes). This is not an
accidental contradiction of prior recorded history — pronto-coding must update this
javadoc when implementing, to state plainly that backend-proxying was reversed in
MS9 in favor of presigned URLs, and why (the ORB bug — a plain `<img src>` cannot
carry the Authorization header the old proxy route required), cross-referencing this
doc. The same reversal applies to the analogous prose in
`LocalDiskStorageClient`'s javadoc, `storage/README.md`, and `data-model.md` §2.7's
`profile_image_key` note (all currently describe "resolved to a public URL"/
"backend-proxied" — flag all for pronto-documentation, not fixed here).

---

## 6. Presigned URL expiry — decision and reasoning

**Decision: 300 seconds (5 minutes)**, via a new config property
`pronto.storage.presigned-url-ttl-seconds` (env var
`STORAGE_PRESIGNED_URL_TTL_SECONDS`, default `300`), added to the existing
`pronto.storage:` block. Used both by `LocalHmacUrlSigner`/local mode's `expires` param
and as the `Duration` passed to S3 mode's `GetObjectPresignRequest.signatureDuration`
— one config value, one source of truth, read independently by whichever
`StorageClient` implementation is active plus by `StorageService` itself (see §1 for
why `StorageClient.upload()` also needs its own copy of this value).

**Reasoning (not a rubber-stamped default):**
- **Long enough to comfortably cover a realistic single page-view without a broken
  image mid-session.** The concrete cases this needs to survive: a customer filling out
  a multi-step "New Issue" or booking flow with already-uploaded photos visible on
  screen the whole time (`DescribeIssueStep`/`ReviewStep`); a professional reading an
  incoming request card, including genuinely reading/deciding, before accepting or
  rejecting; a customer or professional browsing a listing page. None of these are
  designed to be fast — a broken/missing thumbnail appearing mid-decision (e.g. a
  professional evaluating whether to accept a job) is a real, user-visible UX
  regression, arguably worse than the marginal security cost of a slightly longer
  window. 5 minutes is comfortably longer than any single realistic dwell on one of
  these screens without a full page reload.
- **Short enough to meaningfully limit exposure if a URL leaks.** Presigned URLs are
  bearer credentials in themselves — they can end up in browser history, in a
  `Referer` header sent to some other origin if a page ever links out from a view that
  has one embedded, in a screenshot/screen-recording's page source, or in this
  backend's own access logs (local mode) / S3 access logs (S3 mode). 5 minutes bounds
  that exposure to single-digit minutes, not hours — deliberately much shorter than
  this app's own JWT lifetime (`pronto.jwt.expiration-seconds: 86400`, 24h): an image
  isn't as sensitive as a full session token, but there's no reason for an image URL to
  outlive a single page interaction by orders of magnitude the way a session should.
- **Compatible with, and generous relative to, this app's own short-polling cadence.**
  `usePolling` re-fetches every 3-5s (per this project's confirmed real-time-transport
  decision). 5 minutes is ~60-100x a single poll interval — there is no scenario where
  the app's *own* re-fetch cadence would ever race a presigned URL's expiry (the next
  poll tick always arrives, and mints a fresh URL, long before the current one could
  expire). This headroom is what makes §8's polling/URL-churn tradeoff purely a
  bandwidth-waste problem, not a correctness problem — a stale-but-not-yet-expired URL
  from 4 seconds ago is still perfectly valid, it's just redundant.
- **A round, easily-tuned number.** Backed by config, not hardcoded, so it can be
  adjusted post-launch without a code change if real usage patterns suggest otherwise
  (e.g. if QA finds professionals routinely leaving an incoming-request tab open for
  longer than 5 minutes before deciding).

---

## 7. Upload responses — should they carry a presigned URL too?

**Decision: yes** — `ImageUploadResponse`/`ProfileImageUploadResponse`'s `imageUrl`
field should be a presigned URL (via `StoredObject.url()`, itself now built from
`presignUrl`, per §1), not a raw/dead proxy URL. Reasoning:

- **Consistency is the user's explicit, non-negotiable requirement**: "one consistent
  mechanism reused... no per-feature one-off mechanisms." An upload response whose
  `imageUrl` used the *old* mechanism while every read path uses the *new* one would be
  exactly that kind of one-off — and it would silently work by coincidence in local
  mode today (still hits the same permitAll route) while being subtly wrong in intent.
- **Authorization reasoning for "you just uploaded it, it's your key" is straightforward
  and doesn't need re-deriving**: the upload endpoint (`POST /api/storage/images`) is
  already `CUSTOMER`-role-gated and builds the key itself from the caller's own id
  (`customers/{caller.id()}/issues/temp/{uuid}.ext}` — the caller cannot supply an
  arbitrary key). The caller who just uploaded a key is, by construction, always its
  owner. No additional authorization check is needed before presigning it in the
  upload response — this is different in kind from the general `getPresignedUrl` path
  (which must check an arbitrary caller against an arbitrary key), so `upload()`/
  `uploadWithKey()` can safely presign unconditionally, without routing through
  `StorageService.authorize()`.
- **Important corollary this decision surfaces, general beyond just this response**:
  a presigned URL returned from an upload response is valid for exactly
  §6's TTL (5 minutes) from the moment of upload — **it must never be persisted
  anywhere for later reuse** (not in a DB column, not in browser `localStorage`, not in
  React state that outlives the current page). This is precisely the same mistake as
  problem 2 in §0, just at a different layer. **Confirmed, concretely reachable, not
  hypothetical**: `frontend/src/shared/hooks/bookingDraftContext.ts`
  (`BookingDraftPhoto.imageUrl`, lines 18-24) explicitly persists the upload response's
  `imageUrl` to `localStorage` as a "**Durable** backend URL... NOT the ephemeral
  `URL.createObjectURL(file)` blob preview, which does not survive a full page reload"
  — and `frontend/src/features/issues/NewIssuePage.tsx` line 64 concretely *renders*
  that persisted value (`previewUrl: photo.imageUrl`) when resuming a draft. Once
  upload responses carry a presigned URL, that comment's claim of "durable" becomes
  false, and resuming a booking draft more than 5 minutes after the original photo
  upload will show a broken thumbnail. **Not fixed in this round** (out of this doc's
  backend-only scope, and the brief explicitly says not to expand scope) — flagged
  here in the strongest terms because it's the single most concretely-reachable
  instance of the general "never persist a resolved URL, always persist the key and
  re-resolve on render" principle this whole migration establishes. Recommend
  `pronto-lead` scope a small frontend follow-up (re-resolve `imageKey` → a fresh
  presigned URL via a to-be-added authenticated endpoint, or accept the current
  behavior as a known gap) rather than silently letting this regress.

---

## 8. Polling / URL-churn — known follow-up, not fixed this round

**Flagged, not addressed in this round, per explicit scope instruction.** Several
pages poll every 3-5s via `usePolling` (e.g. `IncomingRequestsPage`, professional
listing pages that re-fetch on an interval). Each poll tick re-runs the same
listing/detail endpoint, which — under this design — calls `getPresignedUrl`/
`presignUrl` again for the same underlying key, producing a **different** URL each
time (different `sig`/expiry in local mode, a different AWS signature in S3 mode)
even though the underlying image bytes haven't changed. The browser has no way to know
two different URL strings point at the same resource, so it re-fetches the full image
on every poll tick instead of reusing its cache — correct (nothing breaks), but
bandwidth-wasteful, and worse the more images are on screen (e.g. a professional
listing page with many cards). Not fixing this now — noting it as a real, known
follow-up. Possible future directions (not designed here, just named for whoever picks
this up later): decouple "is there new data" from "re-render every image" (e.g. only
regenerate a presigned URL when the underlying key actually changes, keep a
short in-memory cache of `key → (url, mintedAt)` on the frontend or backend valid for
some fraction of §6's TTL), or a longer TTL specifically for frequently-polled listing
endpoints traded off against §6's leak-exposure reasoning.

---

## 9. Migration plan — five call sites, plus the two newly-surfaced fixes

### 9.1 `BookingsService.enrichAndSort` (Standard + SOS listing, `professionals/` keys)

`backend/src/main/java/com/pronto/bookings/service/BookingsService.java:620`. Public
key (`professionals/` prefix) — every authenticated caller may view it, no per-caller
distinction needed. **Caller-in-scope check, per the brief's explicit ask**: `listProfessionals`/
`listSosProfessionals` (the only two callers of `enrichAndSort`, lines 111 and 218)
take `Long callerId`, not a full `AuthenticatedUser` — **confirmed, flagged**: this
call site does *not* have an `AuthenticatedUser` in scope, only the raw id. Change:
inject `StorageService` instead of `StorageClient`; thread `callerId` (already a method
parameter) through into `enrichAndSort`; replace
`storageClient.resolveUrl(card.profileImageUrl())` with
`storageService.getPresignedUrl(callerId, card.profileImageUrl())`.

### 9.2 `FavoritesService.toSummary` (`professionals/` keys)

`backend/src/main/java/com/pronto/favorites/service/FavoritesService.java:114`. Public
key, same reasoning. **Caller-in-scope**: `listFavorites(AuthenticatedUser caller)`
(the sole caller of `toSummary`) already has a full `AuthenticatedUser` — confirmed.
Change: inject `StorageService` instead of `StorageClient`; thread `caller` (or just
`caller.id()`) into `toSummary`; replace `storageClient.resolveUrl(...)` with
`storageService.getPresignedUrl(caller.id(), professional.getProfileImageKey())`.

### 9.3 `ProfessionalsService.toResponse` + `uploadProfileImage` (`professionals/` keys)

`backend/src/main/java/com/pronto/professionals/service/ProfessionalsService.java:151`
(`toResponse`, called from `getMyProfile`/`updateMyProfile`/`getProfile` — all three
already have a full `AuthenticatedUser caller` in scope, confirmed) and the
upload-response construction at line 134 (`uploadProfileImage`, also has `caller`).
This class already injects both `StorageClient` and `StorageService` — drop the
`StorageClient` field/constructor param, keep only `StorageService` (it already has
it). `toResponse` needs `caller` threaded into it (currently takes
`(Professional professional, User user, Boolean favorited)` — add `AuthenticatedUser
caller` or just `Long callerId`, pronto-coding's call on exact shape). Replace
`storageClient.resolveUrl(...)` with `storageService.getPresignedUrl(caller.id(),
professional.getProfileImageKey())`. `uploadProfileImage`'s response already comes from
`stored.url()` (via `StoredObject`) — per §7, this becomes presigned automatically once
`StorageClient.upload()` itself is changed to presign; no separate change needed at
this specific call site beyond the field/constructor cleanup above.

### 9.4 `IssuesService` — the one call site needing real redesign, not just a swap

`backend/src/main/java/com/pronto/issues/service/IssuesService.java`. Two distinct
sub-changes, corresponding to problems 2 and 3 in §0:

**9.4.1 — `issue_images` stores a key, not a URL, from now on (fixes problem 2).**

- New Flyway migration, `V24__rename_issue_images_image_url_to_image_key.sql` (next
  free version number, confirmed — highest existing is `V23`):
  ```sql
  ALTER TABLE issue_images RENAME COLUMN image_url TO image_key;
  ```
- **Flag, not silently glossed over**: existing rows in this column (any already-seeded
  local/QA test data) currently hold the *old resolved URL string*, not a bare key — a
  column rename alone leaves those specific rows semantically wrong (a URL sitting in a
  now-"key"-named column). Since this is pre-launch MVP data (no production data
  exists), **recommend** QA/dev environments simply reseed/recreate their test issues
  after this migration lands, rather than writing a one-off data-backfill `UPDATE` to
  strip the URL prefix back down to a bare key (fragile — the strip logic would differ
  between what local mode's old URL shape vs. s3 mode's old URL shape looked like, for
  zero real benefit on data nobody depends on keeping). Flagging explicitly so
  pronto-coding/pronto-qa don't discover "existing seeded issue photos look broken"
  mid-QA and assume it's a new bug.
- `IssueImage` entity (`backend/src/main/java/com/pronto/issues/entity/IssueImage.java`):
  rename the `imageUrl` field/column mapping/getter to `imageKey`/`getImageKey()`;
  update its javadoc to state it's the raw storage key, resolved to a presigned URL at
  read time, never persisted resolved — this now matches
  `professionals.profile_image_key`'s existing, already-correct pattern exactly (see
  §0 problem 2's `data-model.md` §2.7 citation) rather than being the odd one out.
- `IssuesService.create` (line 108-118): stop calling `storageClient.resolveUrl(key)`
  at write time entirely — build `IssueImage(issue.getId(), key)` directly (the raw
  key, unchanged from what was uploaded). Resolve the presigned URL only when building
  the *response* (`toResponse`, still within the same request, so the TTL is
  irrelevant here) via `storageService.getPresignedUrl(callerId, img.getImageKey())`
  (customer viewing their own image they just uploaded — passes the normal `belongsTo`
  check, no exemption needed here).
- `IssuesService.getById` (line 149-151): same — replace
  `img.getImageUrl()` (a straight DB-column read) with a fresh
  `storageService.<...>(img.getImageKey())` call, resolved every time this endpoint is
  hit (which is correct and necessary now — see 9.4.2 for which of the two
  `StorageService` methods to call here).
- `IssueImageResponse`/`IssueResponse`/`IssueDetailResponse` DTO shapes are unchanged
  (still `String imageUrl` in the response — only where/when that string gets computed
  changes, from "once, at write time" to "every time, at read time").

**9.4.2 — `getById`'s professional-viewing case needs the pre-authorized bypass, not
the general `getPresignedUrl` check (fixes problem 3).**

As established in §0, `ImageKeyUtils.belongsTo(key, callerId)` will always fail for a
professional caller against a customer-owned `customers/...` key, regardless of
whether that professional has a legitimate order on the issue. `getById` (lines
127-160) already independently computes exactly the right, broader authorization rule
*before* it ever touches an image — its existing role-based branch (lines 132-145)
already establishes: customer callers must own the issue; professional callers must
have an existing order on the issue (`orderRepository.existsByIssueIdAndProfessionalId`);
any other caller/role is rejected outright. By the time the method reaches the
`images` list-building loop (line 149), the caller's right to view *this issue and
everything attached to it* has already been fully and correctly established — this is
a strict superset of "may view every image this issue owns."

**Decision**: `getById`'s image-URL-resolution loop calls
`storageService.getPresignedUrlAssumingCallerAuthorized(img.getImageKey())` (§2's
bypass method), not the general `getPresignedUrl(callerId, key)` — precisely because
re-running the general per-image ownership check here would be *wrong*, not just
redundant: it would reject the exact professional caller `getById`'s own role check
just approved. `IssuesService.create`'s loop (9.4.1 above) keeps using the general,
checked `getPresignedUrl` — it's CUSTOMER-only and every key in it is guaranteed to
already belong to the caller (`validateImageKeys` enforced that before any image was
ever persisted), so there's no mismatch case to bypass there; using the checked path
there is simpler to reason about and doesn't need to justify an exemption.

**Rejected alternative, for the record**: making `ImageKeyUtils`/`StorageService`'s
authorization itself DB-aware (inject `OrderRepository` so the general check can
recognize "professional with an order on the issue that owns this key"). Rejected
because: (a) it breaks `ImageKeyUtils`'s deliberate, explicitly-stated design purity
("Pure/stateless — no Spring dependency, trivially unit-testable" per its own javadoc)
for a need only one call site actually has; (b) the `customers/{callerId}/issues/temp/{uuid}`
key format doesn't even encode an `issueId`, so this check would first need to look up
the owning `issue_images` row by key to learn which issue it belongs to — meaning
`storage` would need a new dependency on `issues`' repository just to make this work,
a real package-boundary violation this codebase has otherwise avoided (`storage` today
has zero DB dependencies). The narrow, `IssuesService`-local bypass in §2 achieves the
same outcome with strictly less coupling.

**Open question, flagged for explicit pronto-lead/user sign-off before coding**: this
is a genuine, newly-surfaced (well, newly-*actionable* — it was explicitly deferred at
M2, see §0) change to the app's authorization model for issue images, even though it's
narrowly scoped to one call site. Given the security-sensitivity of any code path that
deliberately skips a standard check, recommend a second pair of eyes on §2's
`getPresignedUrlAssumingCallerAuthorized` naming/placement and §9.4.2's reasoning
specifically, before pronto-coding implements it.

### 9.5 `StorageClient.upload()` (both implementations) — see §1 and §7

`LocalDiskStorageClient.upload()`/`S3StorageClient.upload()` both currently build
`StoredObject` via `resolveUrl(key)`; both change to `presignUrl(key,
presignedUrlTtl)` (each implementation reads
`pronto.storage.presigned-url-ttl-seconds` via its own `@Value`, per §1's reasoning
for why this isn't centralized into a single shared reader). No authorization check
needed here — see §7's "you just uploaded it, it's your key" reasoning.

---

## 10. Config summary — new/changed properties

Added to the existing `pronto.storage:` block in `backend/src/main/resources/application.yml`:

```yaml
pronto:
  storage:
    presigned-url-ttl-seconds: ${STORAGE_PRESIGNED_URL_TTL_SECONDS:300}
    local:
      base-dir: ${STORAGE_LOCAL_BASE_DIR:./data/uploads}
      hmac-secret: ${STORAGE_LOCAL_HMAC_SECRET:local-dev-only-insecure-hmac-secret-please-override-via-STORAGE_LOCAL_HMAC_SECRET-env-var-before-any-real-deployment}
```

`pronto.storage.public-base-url` is unchanged in shape/meaning (still the base URL
this backend is reachable at) but its usage narrows: local mode still builds
`{publicBaseUrl}/api/storage/images/{key}?expires=...&sig=...` from it (same route,
now with query params); S3 mode no longer uses it at all for `resolveUrl`/`presignUrl`
(S3-presigned URLs come entirely from `S3Presigner`, pointing at the S3 endpoint
directly) — **flag for pronto-documentation**: `public-base-url`'s doc comment in
`application.yml` (lines 73-79) currently says it's "used by BOTH storage modes,"
which becomes false for S3 mode; needs updating.

---

## 11. Open questions / risks for pronto-lead and the user

1. **§9.4.2's authorization-model change (professional viewing a customer's issue
   image) needs explicit sign-off**, not just an implicit nod via this doc's approval.
   It's narrowly scoped and, in this doc's judgment, correct and necessary — but it is
   a real loosening of what was previously the app's only image-authorization rule,
   and deserves deliberate agreement rather than being absorbed as a side-effect of
   "fix the presigned URLs."
2. **§9.4.1's data migration is a rename with no backfill** — acceptable for
   pre-launch/no-production-data, but confirm no environment currently treated as
   "must not lose data" has seeded `issue_images` rows that matter.
3. **The startup-guard follow-up for the HMAC secret** (§3) is recommended but not
   designed in detail here — worth a short follow-up ticket rather than being
   forgotten entirely, given it mirrors an existing, already-built pattern
   (`JwtSecretStartupGuard`).
4. **The `exists()`-before-presign question in §2** is left as a recommendation, not a
   mandate — pronto-lead should make the final call on whether API-level `404`
   correctness or the latency/cost win matters more here.
5. **§7's `bookingDraftContext` staleness** is flagged as a known, concretely-reachable
   gap but explicitly out of this round's scope (backend-only design doc, and the
   brief says not to expand scope) — needs its own tracked follow-up so it isn't lost.
6. **§8's polling URL-churn** is, per the brief, explicitly not being fixed this round
   — restated here only to make sure it's tracked, not re-litigated.

---

## Summary of files this design touches (for pronto-coding)

- `backend/src/main/java/com/pronto/storage/client/StorageClient.java` — remove
  `resolveUrl`, add `presignUrl(String, Duration)`.
- `backend/src/main/java/com/pronto/storage/client/LocalDiskStorageClient.java` —
  `presignUrl` impl (delegates to new `LocalHmacUrlSigner`); `upload()` uses
  `presignUrl` instead of `resolveUrl`.
- `backend/src/main/java/com/pronto/storage/client/S3StorageClient.java` —
  `presignUrl` impl via `S3Presigner`/`GetObjectPresignRequest`; new `S3Presigner`
  field; javadoc reversal per §5; `upload()` uses `presignUrl`.
- `backend/src/main/java/com/pronto/storage/client/LocalHmacUrlSigner.java` (new) —
  HMAC sign/verify, local-mode-conditional bean.
- `backend/src/main/java/com/pronto/storage/service/StorageService.java` — remove
  `retrieve(AuthenticatedUser, String)`; add `getPresignedUrl` (two overloads),
  `getPresignedUrlAssumingCallerAuthorized`, `retrieveBySignedUrl`.
- `backend/src/main/java/com/pronto/storage/controller/StorageController.java` — drop
  `@AuthenticationPrincipal`, add `expires`/`sig` `@RequestParam`s, call
  `retrieveBySignedUrl`.
- `backend/src/main/java/com/pronto/auth/config/SecurityConfig.java` — add the
  `HttpMethod.GET`-scoped `permitAll()` matcher for `/api/storage/images/**`.
- `backend/src/main/java/com/pronto/bookings/service/BookingsService.java`,
  `favorites/service/FavoritesService.java`,
  `professionals/service/ProfessionalsService.java` — swap `StorageClient` for
  `StorageService`, thread caller id/`AuthenticatedUser` through, per §9.1-9.3.
- `backend/src/main/java/com/pronto/issues/service/IssuesService.java` — per §9.4:
  stop persisting resolved URLs, resolve at read time in both `create` and `getById`,
  use the pre-authorized bypass in `getById` only.
- `backend/src/main/java/com/pronto/issues/entity/IssueImage.java` — `imageUrl` →
  `imageKey` rename.
- `backend/src/main/resources/db/migration/V24__rename_issue_images_image_url_to_image_key.sql` (new).
- `backend/pom.xml` — add `software.amazon.awssdk:s3-presigner`.
- `backend/src/main/resources/application.yml` — new `presigned-url-ttl-seconds`,
  `local.hmac-secret` properties.
- Doc updates flagged for `pronto-documentation` (not written here): `storage/README.md`,
  `storage/config/StorageWebConfig.java`'s javadoc, `issues/README.md`,
  `data-model.md` §2.7/§2.8, `application.yml`'s `public-base-url` comment.

No frontend code changes are expected for existing `<img src>` consumers (per the
brief's own investigation, confirmed no custom header injection anywhere) — but per the
brief, this claim should be spot-checked live by pronto-coding/pronto-qa once the
backend change lands, not assumed correct by construction. `bookingDraftContext.ts`'s
persisted `imageUrl` (§7) should be specifically checked for whether it renders a
broken image after the TTL window, even though fixing it is out of scope this round.

---

## 12. Addendum — booking-draft photo TTL fix (§7's gap, now in scope)

**Status: approved, in scope for this round.** §7 flagged, and §11 item 5 recorded as a
deferred follow-up, that `bookingDraftContext.ts` persists a resolved `imageUrl` to
`localStorage` and `NewIssuePage.tsx` renders it verbatim on resume — which breaks once
that URL's TTL (§6, 300s) elapses. Per explicit user instruction, this is now fixed in
this same round, before `pronto-coding` touches any of the files below. This section is
self-contained but builds directly on §1-§11 above (same `authorize()`/`getPresignedUrl`
machinery, same TTL, same "never persist a resolved URL, persist the key and re-resolve
at render time" principle already established for `issue_images` in §9.4.1).

### 12.1 `BookingDraftPhoto` — stop persisting `imageUrl`, keep only `imageKey`

**Decision**: `imageUrl` is dropped from what's persisted. `frontend/src/shared/hooks/bookingDraftContext.ts`:

```typescript
export interface BookingDraftPhoto {
  /** Raw storage key only — a resolved URL is never persisted here. Presigned URLs expire
   *  (backend MS9 design §6, 300s TTL) long before a paused draft is likely to be resumed,
   *  so a URL saved at upload time would already be dead by the time `NewIssuePage`
   *  rehydrates it. Instead, `NewIssuePage`'s resume flow re-resolves every photo's
   *  `imageKey` into a fresh presigned URL via a single batch call
   *  (`shared/api/storage.ts`'s `getPresignedImageUrls`) immediately on mount — see
   *  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §12. This interface
   *  previously also carried a `imageUrl` field described as "durable" — that was true only
   *  while `POST /api/storage/images`'s response returned a non-expiring proxy URL; it
   *  stopped being true the moment upload responses became presigned (§7) and is corrected
   *  here, not left contradicting the new behavior. */
  imageKey: string;
}
```

No draft-schema `version` bump is needed for this change: existing persisted drafts in a
user's `localStorage` already have both `imageKey` and `imageUrl` written side by side
(per the brief's own grounding) — new code simply stops reading/writing the now-unused
`imageUrl` field going forward; an old draft's stray `imageUrl` property is harmlessly
ignored (never destructured), not a parse failure. This is a narrowing, not a
shape-breaking change, so the existing "unreadable/mismatched-version draft is discarded"
mechanism doesn't need to fire for it.

`toDraftPhotos` in `frontend/src/features/issues/NewIssuePage.tsx` (line 29-31) changes
from `photos.map((photo) => ({ imageKey: photo.imageKey, imageUrl: photo.imageUrl }))` to
`photos.map((photo) => ({ imageKey: photo.imageKey }))`. `PhotoUploader.tsx`'s
`UploadedPhoto` interface (and `uploadImage`'s response type) are **unaffected** — they
keep their own `imageUrl` field exactly as-is; that field's job is unchanged
(immediate, same-page-load, post-upload display, well within the TTL, per §7's original
reasoning) — only the *booking-draft-persisted* copy of it is removed. Do not conflate
the two: `UploadedPhoto.imageUrl` (ephemeral, in-memory, same session) stays;
`BookingDraftPhoto.imageUrl` (persisted, cross-reload) goes.

### 12.2 New backend endpoint — batch key-to-presigned-URL lookup

**Decision**: a new endpoint, `POST /api/storage/images/presigned-urls`.

- **Request**: `{ "imageKeys": string[] }`.
- **Response**: `{ "images": [{ "imageKey": string, "imageUrl": string }, ...] }` —
  **may contain fewer entries than requested keys** (§12.5 explains when and why; the
  response is never padded with nulls/placeholders for a dropped key, it's simply
  absent).
- **Auth**: any authenticated caller — no new role gate. Mirrors the existing single-key
  `GET /api/storage/images/**` route's own "either-role at the route level, per-key
  authorization inside the service" stance (see `StorageController`'s existing javadoc).
  Per-key ownership is what actually matters, and that's enforced by reusing the
  **existing, unchanged** `StorageService.authorize()`/`ImageKeyUtils.belongsTo` check
  from §2 — no new authorization rule and no exemption like §9.4.2's bypass is needed
  here. A `customers/{callerId}/issues/temp/{uuid}.ext` key already passes `belongsTo`
  cleanly for the exact customer who uploaded it, which is by construction the only
  caller who will ever legitimately hold that key in a draft (drafts are `ownerId`-scoped
  and discarded on logout/account-switch, per `bookingDraftContext.ts`'s existing
  cross-account guard).
- **Batch size cap**: 20 (comfortably above `PhotoUploader`'s own `maxCount` default of
  6, generous headroom, still small enough that no pagination/streaming concern exists).
  A request exceeding this is rejected outright with `ErrorCode.VALIDATION_ERROR`
  (`400`, matching this codebase's existing convention for input-size validation
  failures) — this is a batch-shape guard, not a per-key authorization decision, so it's
  checked before any per-key work happens.
- **No eager `exists()` check**, for the same reasoning §2 already established for the
  single-key path: presigning is a local/offline signing operation (S3 mode) or a cheap
  disk stat (local mode) with no meaningful cost either way; adding an existence check
  here would be a new, inconsistent policy relative to every other presign-issuing call
  site in this design. If a key genuinely doesn't exist, the browser's own subsequent
  `<img>` GET against the returned URL fails naturally — the same accepted failure mode
  used everywhere else in this design, not a new one invented for this endpoint.
- **New DTOs** (`storage.dto` package, following existing naming): `record
  PresignedImageUrlsRequest(List<String> imageKeys)`; `record
  PresignedImageUrlEntry(String imageKey, String imageUrl)`; `record
  PresignedImageUrlsResponse(List<PresignedImageUrlEntry> images)`.
- **`StorageController`**: new
  `@PostMapping("/images/presigned-urls")` method, `@AuthenticationPrincipal
  AuthenticatedUser principal`, `@RequestBody PresignedImageUrlsRequest request`,
  delegates to `storageService.getPresignedUrls(principal.id(), request.imageKeys())`.
- **`StorageService`**: new method, alongside the existing `getPresignedUrl`/
  `getPresignedUrlAssumingCallerAuthorized`:
  ```java
  public List<PresignedImageUrlEntry> getPresignedUrls(Long callerId, List<String> keys) {
      if (keys.size() > MAX_BATCH_SIZE) {
          throw new ApiException(ErrorCode.VALIDATION_ERROR, "Too many image keys requested.");
      }
      List<PresignedImageUrlEntry> result = new ArrayList<>();
      for (String key : keys) {
          try {
              authorize(callerId, key); // reused unchanged from §2
              result.add(new PresignedImageUrlEntry(key, storageClient.presignUrl(key, presignedUrlTtl)));
          } catch (ApiException ex) {
              // Ownership mismatch on a key inside this caller's OWN draft should never
              // legitimately happen (see auth reasoning above) — this branch exists purely
              // as defense-in-depth against a corrupted/tampered localStorage draft. Skip,
              // don't fail the whole batch — see §12.5.
          }
      }
      return result;
  }
  ```
  A per-key `authorize()` failure is caught and that key is simply omitted from the
  result — it never turns into an overall `403`/`500` for the batch. This is the
  concrete mechanism behind §12.5's "graceful, not all-or-nothing" decision.

### 12.3 Frontend API surface — `shared/api/storage.ts`

```typescript
export interface PresignedImageUrlEntry {
  imageKey: string;
  imageUrl: string;
}

export interface PresignedImageUrlsResponse {
  images: PresignedImageUrlEntry[];
}

/**
 * `POST /api/storage/images/presigned-urls` — batch re-resolves already-known image keys
 * into fresh presigned URLs (each valid for the standard TTL, see backend MS9 design
 * §6/§12). Used exclusively by `NewIssuePage`'s draft-resume flow: a paused draft only
 * ever persists `imageKey`s (never a URL, see §12.1), so this is how a resumed draft's
 * photos become displayable again. May return fewer entries than `imageKeys.length`
 * requested — see §12.5.
 */
export function getPresignedImageUrls(imageKeys: string[]): Promise<PresignedImageUrlsResponse> {
  if (imageKeys.length === 0) {
    return Promise.resolve({ images: [] }); // avoid a pointless round trip on a photo-less draft
  }
  return httpClient.post<PresignedImageUrlsResponse>('/api/storage/images/presigned-urls', { imageKeys });
}
```

### 12.4 `NewIssuePage.tsx` resume flow — exact change

**`PhotoUploader`'s `UploadedPhoto.previewUrl` widens from `string` to `string | null`.**
`null` is a deliberate, distinct sentinel for "not yet re-resolved" (not `''`, to avoid
ambiguity with a legitimately-empty string). `PhotoUploader`'s render loop for its
`photos` prop (not the internal `pending` array, which is unaffected) gets one new
branch: when `photo.previewUrl === null`, render the **same** `uploadingOverlay`/
`spinner` markup it already renders for its own `pending` uploads, instead of `<img
src={photo.previewUrl}>` — literally the existing CSS classes/JSX shape reused for a
second "not ready yet" case, not a new visual pattern invented for this fix (matches the
brief's own instruction to follow this codebase's existing loading-state precedent
rather than inventing one).

**Line 64's direct reuse of `photo.imageUrl` is removed.** During hydration, `photos`
state is now seeded with `previewUrl: null` for every draft photo (`imageKey`/`imageUrl`
both set to the same placeholder-free shape:
`{ imageKey: photo.imageKey, imageUrl: '', previewUrl: null }` — `imageUrl` is filled in
alongside `previewUrl` once the batch call resolves, per below; it is unused while
`previewUrl` is `null`).

Two resume sub-cases, matching the two hydration paths already present in this file:

- **(a) `initialDraft.stage === 'ISSUE_DESCRIBE'`** (today: no full-page `isResuming`
  gate, `DescribeIssueStep` renders immediately). The presign-batch call
  (`getPresignedImageUrls(initialDraft.photos.map(p => p.imageKey))`) fires at mount time
  (a sibling effect to, or folded into, the existing resume `useEffect` — exact wiring is
  pronto-coding's call, both are guarded by the same "run once" `hasAttemptedResume`-style
  ref). Photos render with the reused spinner placeholder for the short round-trip this
  call takes; the rest of the describe step (description field, urgency toggle, submit
  flow) is fully interactive the whole time — this fix does not introduce a new
  page-level loading gate for the already-fast `ISSUE_DESCRIBE` resume case.
- **(b) `initialDraft.stage` is `ISSUE_CLARIFY` or `ISSUE_REVIEW`** (today: already gated
  by the existing full-page `isResuming` flag / "טוענים את הבקשה שלכם…" message). The
  presign-batch call is folded into the **same** resume effect that already calls
  `classifyIssue`, run concurrently with it (`Promise.all([classifyIssue(...),
  getPresignedImageUrls(...)])`), and `isResuming` only flips to `false` once **both**
  resolve. Since `ClarifyQuestionsStep`/`ReviewStep` don't render until `isResuming` is
  `false`, no separate per-thumbnail placeholder handling is needed for these two stages
  — the existing full-page loading message already covers the wait.

**On successful (full or partial) resolution**: `setPhotos` maps each still-present
`initialDraft.photos` entry to its resolved URL, matched by `imageKey` against
`response.images`, setting both `imageUrl` and `previewUrl` to the resolved value. Any
`imageKey` not present in the response is dropped from the resulting `photos` array
entirely (§12.5).

**If the batch call itself fails outright** (not a partial response — a full network/5xx
failure): for sub-case (b), reuse the **existing** `resumeError`/`GENERIC_ERROR_MESSAGE`
banner path already in this file (falls back to the `'describe'` step) — this is already
exactly what happens today when `classifyIssue` itself fails, and `Promise.all` naturally
routes a `getPresignedImageUrls` rejection into the same `catch` block, so no new error
UI is needed for case (b). For sub-case (a) (no existing page-level fallback to reuse):
the affected photos keep their placeholder slot but swap the spinner for a small inline
error state reusing `PhotoUploader`'s own existing `itemError` treatment (currently used
for a failed live upload, styled/positioned identically, with new copy since "ההעלאה
נכשלה" — "the upload failed" — is upload-specific wording that doesn't fit a
re-resolution failure; e.g. "לא ניתן לטעון את התמונה" — "could not load the photo").
The customer can manually remove those specific broken thumbnails (the existing remove
button already present on every thumbnail) and continue the flow, or re-upload
replacements — a single batch-presign failure never blocks the rest of the describe step.

### 12.5 Partial-response / missing-key handling (item 4)

**Decision — graceful, proportionate degradation, not a hard failure.** This path is
expected to essentially never fire (no expiry-based deletion of temp keys exists in this
design; the only realistic trigger would be a corrupted/hand-edited `localStorage`
entry), so it is designed to degrade cleanly rather than being heavily engineered:

- The backend (§12.2) never fails the whole batch because of one bad key — a missing
  authorization or a since-deleted key simply isn't in `response.images`.
- The frontend, whenever `response.images.length < requestedKeys.length`:
  - Drops the missing photo(s) from the in-memory `photos` state (they never render as
    persistent broken thumbnails, since the removal is at the state level, not just a
    style change).
  - Lets the draft **self-heal**: the next `updateDraft` call this page already makes on
    any step transition (forward or backward — this file calls it on essentially every
    transition already) persists the narrowed `photos` array, so the stale key doesn't
    keep reappearing on a future resume.
  - Shows a single non-blocking inline notice reusing this file's **existing**
    `warningBanner` styling (already used twice in this exact file — the conflicting-draft
    warning and `resumeError`) with copy such as "חלק מהתמונות שהעלית בעבר לא נמצאו
    והוסרו מהבקשה" ("Some of your previously-uploaded photos could not be found and were
    removed from the request"). **Deliberately not a toast** — this codebase has no toast
    component anywhere (confirmed by grep across `frontend/src`); every existing
    transient/error notice here is an inline `role="alert"` banner, so this reuses that
    established convention instead of introducing a new UI primitive for a rare edge
    case.
- The whole resume flow does **not** error out / abort just because one photo among
  several is missing — only a total request failure (§12.4's "batch call fails outright"
  case) triggers the stronger `resumeError`/fallback-to-describe-step path.

### 12.6 Documentation flags for `pronto-documentation` — confirmed, not fixed here

- **`frontend/src/shared/components/README.md`**, "MS3/MS4 product-corrections pass"
  paragraph: currently states `PhotoUploader`'s `imageUrl` "does [survive a reload]" and
  that this was "a supporting fix for... booking-draft persistence." Both claims are now
  false as stated — `imageUrl` was never wrong to add to `UploadedPhoto` (it's still
  needed for same-page-load display, §12.1), but it no longer does the
  cross-reload-survival job this paragraph credits it with; that job now belongs to
  `BookingDraftPhoto.imageKey` plus the new batch-resolve call (§12.2-§12.4). Needs a
  correction, not a deletion.
- **`frontend/src/shared/hooks/README.md`**, the `bookingDraftContext.ts` bullet:
  currently describes `BookingDraftPhoto` as holding `imageKey`/`imageUrl` and doesn't
  mention any resume-time re-resolution step. Needs updating to reflect §12.1's dropped
  field and to add a description of the new resume-time batch-presign call this section
  introduces (likely also touching the `useBookingDraft`/`BookingDraftProvider`
  paragraphs' framing of what "resuming a draft" involves, since it's no longer a pure
  localStorage read with no network call).
- Both confirmed as needing a flag — explicitly noted here so it isn't missed, per this
  doc's own labeling convention.

### 12.7 Files this addendum touches (for `pronto-coding`)

- `backend/src/main/java/com/pronto/storage/dto/PresignedImageUrlsRequest.java` (new),
  `PresignedImageUrlEntry.java` (new), `PresignedImageUrlsResponse.java` (new).
- `backend/src/main/java/com/pronto/storage/controller/StorageController.java` — new
  `POST /images/presigned-urls` handler.
- `backend/src/main/java/com/pronto/storage/service/StorageService.java` — new
  `getPresignedUrls(Long, List<String>)` method, per §12.2.
- `frontend/src/shared/api/storage.ts` — new `getPresignedImageUrls`,
  `PresignedImageUrlEntry`/`PresignedImageUrlsResponse` types.
- `frontend/src/shared/hooks/bookingDraftContext.ts` — `BookingDraftPhoto` narrows to
  `{ imageKey: string }`, javadoc corrected per §12.1.
- `frontend/src/features/issues/NewIssuePage.tsx` — `toDraftPhotos`, the hydration
  `useState` initializer, and the resume `useEffect` all change per §12.1/§12.4.
- `frontend/src/shared/components/PhotoUploader.tsx` — `UploadedPhoto.previewUrl` widens
  to `string | null`; render loop over the `photos` prop gains the `null`-placeholder
  branch (reusing existing spinner/`itemError` markup), per §12.4.
- Doc updates flagged for `pronto-documentation` (not written here): §12.6's two READMEs.

**§11 item 5 is now resolved by this section**, not merely re-flagged — the "known,
concretely-reachable gap" it described is fixed by §12.1-§12.5 above, not deferred
further.
