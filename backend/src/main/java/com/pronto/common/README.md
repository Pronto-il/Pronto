# `common`

## Purpose

Shared exceptions, error-response envelope, DTOs, and the cross-request security
principal type — cross-cutting infrastructure used by more than one domain package.

## Responsibilities

- The standard error-response envelope (`docs/architecture/api-contract.md` §1), used by
  every endpoint across every milestone, not just `auth`/`users`.
- A global `@RestControllerAdvice` (`exception.GlobalExceptionHandler`) that converts
  `ApiException`s, Bean Validation failures, and unreadable JSON bodies into that envelope,
  plus a catch-all for anything unexpected (never leaks a stack trace to the client).
- `security.AuthenticatedUser` — the `Authentication` principal type set by `auth`'s JWT
  filter and read by any controller via `@AuthenticationPrincipal`. Lives here (not in
  `auth`) specifically so `users` (and any future package with an authenticated endpoint)
  doesn't have to depend on `auth` just to read "who is the current caller" — `role` is
  stored as a plain `String` rather than `users`' `UserRole` enum for the same reason: this
  package must never depend on a domain package.
- `security.RoleGuard` (Milestone 2) — a one-method role-restriction check
  (`requireRole(principal, requiredRole)`, throws `ApiException(FORBIDDEN, ...)` on
  mismatch), used by `issues`/`storage` since every endpoint in those packages requires
  `role = CUSTOMER`. Deliberately **not** `@PreAuthorize`/method security — see
  `storage/README.md`'s "Role enforcement" section for the full rationale (in short:
  declarative method security would need `auth.config.SecurityConfig` changes, which were
  out of bounds for the task that added it). Takes a plain `String` role, same reasoning as
  `AuthenticatedUser.role` above.
- `security.RoleRequiredInterceptor` (Milestone 2 bug fix) — a generic, reusable
  `HandlerInterceptor` that calls `RoleGuard.requireRole` from `preHandle`, i.e. *before*
  Spring resolves `@Valid`/`@RequestParam` argument binding for the matched controller
  method. Fixes an ordering bug where `RoleGuard.requireRole` called from inside a
  controller method body ran *after* argument resolution, so a wrong-role request with an
  also-malformed body incorrectly returned `400 VALIDATION_ERROR` instead of
  `403 FORBIDDEN`. Takes the required role as a constructor argument and has no built-in
  knowledge of which routes it applies to — each domain package registers its own instance
  for its own routes (`issues.config.IssuesWebConfig`, `storage.config.StorageWebConfig`),
  keeping this class generic and keeping route-to-role knowledge out of `common`.
- No business logic of its own — stays thin and infrastructural.

## Key classes

| Class | Role |
|---|---|
| `exception.ErrorCode` | The stable machine-readable taxonomy (`VALIDATION_ERROR`, `DUPLICATE_EMAIL`, ... `INTERNAL_ERROR`, plus Milestone 2's `FORBIDDEN`/`IMAGE_KEY_INVALID`/`UNSUPPORTED_IMAGE_TYPE`/`IMAGE_TOO_LARGE`/`AI_SERVICE_ERROR`/`STORAGE_SERVICE_ERROR`, plus Milestone 7's `RATE_LIMITED`, `429`), each with its HTTP status. |
| `exception.ApiException` | Thrown by service-layer code for any expected, business-meaningful failure; carries an `ErrorCode` + message + optional `details`. |
| `exception.GlobalExceptionHandler` | `@RestControllerAdvice`, builds the envelope for every exception type above, plus (Milestone 2) `MaxUploadSizeExceededException` → `413 IMAGE_TOO_LARGE` and `MissingServletRequestPartException` → `400 VALIDATION_ERROR` (both from `storage`'s multipart upload endpoint). |
| `dto.ErrorResponse` / `dto.ErrorBody` | The envelope shape (`timestamp`, `path`, `error{code,message,details}`). |
| `dto.FieldError` | One entry in a `VALIDATION_ERROR`'s `details` array. |
| `dto.LockedDetails` | The `details` payload for `ACCOUNT_LOCKED` (`lockedUntil`, `retryAfterSeconds`). |
| `dto.RateLimitDetails` | (Milestone 7) The `details` payload for `RATE_LIMITED` (`retryAfterSeconds`) — thrown by `auth.security.AuthRateLimitInterceptor`, same value also set as the response's `Retry-After` header. |
| `security.AuthenticatedUser` | `record(Long id, String role)` — the JWT-derived principal. |
| `security.UploadOwner` | (Guest image upload, 2026-08-29) `record(Long customerId, String guestId)` — who an uploaded image belongs to, the one context the shared upload/presign flow is parameterised by. **Both fields may be set at once**, and that is the point: a guest who registers mid-flow owns keys in both namespaces, and collapsing that to "the account wins" would make their own photos unreadable at the moment they did what we asked. Neither field is ever client-asserted — `customerId` comes from a verified JWT, `guestId` from a signed guest-session token (`auth.security.GuestSessionTokenService`). Lives here for the same reason `AuthenticatedUser` does: a principal type produced by `auth`, consumed by `storage`/`issues`, depending on no domain package itself. |
| `security.RoleGuard` | (Milestone 2) `requireRole(principal, requiredRole)` — see "Responsibilities" above. |
| `security.RoleRequiredInterceptor` | (Milestone 2 bug fix) `HandlerInterceptor` wrapping `RoleGuard.requireRole`, registered per-route by `issues`/`storage` — see "Responsibilities" above. **Guest image upload (2026-08-29) added an opt-in `allowAnonymous` constructor**: "if you are a signed-in user you must be `requiredRole`; being nobody is somebody else's question." Used by exactly one registration (`storage`'s `POST /api/storage/images`, which now has two legitimate kinds of caller — a `CUSTOMER` JWT or a guest-session token — and only the first is a role question). It opens nothing: a request waved through here still has to satisfy `auth.security.UploadOwnerResolver#requireIdentified` in the handler. Every other registration uses the pre-existing constructors and is unchanged. |

## Interactions with other packages

- Available to be depended on by every other `com.pronto.*` package. Must never depend on
  a domain package itself (enforced by design choices above, e.g. `AuthenticatedUser.role`
  being a `String`, not an enum from `users`).
- `auth`'s `JwtAuthenticationFilter`/`JsonAuthenticationEntryPoint` construct/read
  `AuthenticatedUser` and throw/format using `ApiException`/`ErrorCode`.
- Every controller across every package is expected to let `ApiException`s propagate up to
  `GlobalExceptionHandler` rather than building its own error responses.
- `issues`/`storage` (Milestone 2) each register a `RoleRequiredInterceptor(role =
  "CUSTOMER")` (via their own `config.*WebConfig` `WebMvcConfigurer`) for their route
  prefixes, which calls `RoleGuard.requireRole` before their controller methods' argument
  resolution runs.

## Data model

No tables owned by this package.

## Status

Populated in **Milestone 1** with the error-envelope/exception-handling infrastructure
`api-contract.md` §1 calls for, plus the shared `AuthenticatedUser` principal type. The
Milestone 0 health-endpoint note (Actuator auto-configuration, no supporting class needed)
still applies unchanged.

Extended in **Milestone 2** (per `api-contract-issues.md` §1) with the 6 new error codes,
2 new `GlobalExceptionHandler` entries (multipart-upload failures), and `RoleGuard` for
`issues`/`storage`'s `role = CUSTOMER` restriction.

Extended again post-Milestone-2, fixing a QA-reported ordering bug: `RoleGuard.requireRole`
was originally called from inside `issues`/`storage` controller method bodies, which ran
*after* Spring's `@Valid`/`@RequestParam` argument resolution, so a wrong-role request with
an also-malformed body incorrectly returned `400 VALIDATION_ERROR` instead of
`403 FORBIDDEN`. Added `security.RoleRequiredInterceptor` (a `HandlerInterceptor`, calls
`RoleGuard.requireRole` from `preHandle`, which always runs before argument resolution) to
fix it, without touching `auth.config.SecurityConfig`.

Extended in **Milestone 7** (`docs/architecture/hardening-plan.md` §5.2) with `RATE_LIMITED`
(`429`) and `dto.RateLimitDetails`, consumed by `auth.security.AuthRateLimitInterceptor` —
the same `ApiException`/`GlobalExceptionHandler` envelope mechanism as every other error,
no new pattern introduced.

Extended in **Production Roadmap MS1** (2026-08-22) with exactly one `ErrorCode`,
`PROFESSIONAL_APPROVAL_INVALID_TRANSITION` (`409`) — thrown by
`professionals.service.ProfessionalApprovalService` when an approval decision is not legal from
the professional's current status; its own code rather than a generic `VALIDATION_ERROR` for
the same reason every other `*_INVALID_STATE` code here exists (nothing about the request was
malformed, the world moved). No other change to this package: MS1's `ADMIN` route gate reuses
the existing `security.RoleRequiredInterceptor` unmodified, registered by
`professionals.config.ProfessionalsWebConfig` — the route-to-role knowledge stays out of
`common`, as designed.

## Production MS4 (2026-08-26) — environment/configuration guards

Two new classes, both in `config`, both cross-cutting in exactly the way this package exists for.

### `config.DatabaseConfigStartupGuard`

Refuses to start a production-like environment with the committed local-development database
password (`pronto`, published in this repository and in `docker-compose.yml`), an empty password, a
`localhost`/`127.0.0.1` database host, a schema-mutating `spring.jpa.hibernate.ddl-auto`
(`create`/`create-drop`/`update` — Flyway owns the schema), `spring.flyway.enabled=false`, or
`spring.flyway.clean-disabled=false`.

`application.yml` deliberately mirrors `docker-compose.yml` variable for variable so a fresh clone
runs with no environment set. The cost of that convenience was that the only thing standing between
a public password and a production database was "no real database would accept it" — a property of
the database, not of this application, and one that stops holding the moment somebody provisions RDS
with the same convenience credentials. **Never logs the password or the assembled JDBC URL** (a
place credentials end up); the failure message names the property, the environment variable and the
host only, the same rule `demo.DemoDataStartupGuard` follows.

### `config.StartupConfigurationSummary`

One `INFO` line at every startup naming the environment and the mode actually in force for each
external dependency. The guards answer "may this configuration run"; this answers what an operator
asks next — "what is this instance really wired to?" — without a shell on the box.

```text
pronto.startup.configuration environment=local productionLike=false ai=mock email=log sms=log
  storage=local maps=fake demoData=off behindProxy=true trustedProxyRanges=0 corsOrigins=1
```

**Modes, never values.** No secret, no key, no bucket name, no origin, no connection string — a
startup banner is exactly the kind of thing that gets pasted into a ticket. Uses
`ApplicationReadyEvent` rather than `@PostConstruct`, breaking with every guard in the codebase on
purpose: a guard runs before the port binds because refusing to serve traffic is the point, whereas
a report is only true once everything it describes has successfully initialized.

### `config.ProntoEnvironment` — unchanged, now with seven consumers

MS4 added four more guards on top of MS1/MS2's three. The allow-list rule is untouched:
`local`/`demo`/`test` are non-production, **everything else including every typo is production**.

Tests: `common/config/DatabaseConfigStartupGuardTest`, and `common/config/ProductionStartupValidationTest`
— which runs *every* startup guard in the codebase against one candidate configuration. That test
exists because a set of individually correct guards can still be collectively unsatisfiable, which
no per-guard unit test can detect, and because it doubles as the executable specification of the
Production variable set documented in `docs/production-roadmap/reports/prod-MS4-report.md`.

---

## Production MS5 — the first Spring-context test

### `config.HealthProbeIntegrationTest`

**The first `@SpringBootTest` in this repository.** MS0 recorded the absence of any Spring-context or
database test as a gap and Playbook **D3** assigns building the permanent integration harness to MS5;
`ProfessionalEligibilityTest`'s Javadoc names the same gap and the same owner. This is the first
piece of it.

The probe endpoints are the right place to start, because they are the contract between this
application and the infrastructure MS5 provisions, and because both of their failure modes live in
the gap between `application.yml` and `SecurityConfig` where no unit test can see them:

- without `management.endpoint.health.probes.enabled`, Spring Boot 3.3 registers the liveness and
  readiness groups only when it detects Kubernetes — on ECS both paths answer **404**;
- without widening `SecurityConfig`'s matcher to `/actuator/health/**`, both answer **401**.

Either way the ALB drains the service to zero. Verified non-vacuous: narrowing the matcher back
fails 4 of the 7 cases.

It also asserts the composition that justifies having two probes at all — readiness includes `db`
(a task that cannot reach RDS should not receive traffic), liveness excludes it (ECS **restarts** on
liveness failure, so including the database would turn a transient RDS blip into a crash loop, since
Flyway also needs the database at startup) — and that the widening did not over-open anything.

Gated on PostgreSQL being reachable with the same `@EnabledIf` pattern `MigrationIntegrationTest`
uses, so a machine without a database still builds green. CI supplies one.

### Production-shaped startup smoke — outside the test suite, deliberately

`backend/tools/production-config-smoke.sh` closes MS4's Known Limitation 7. It boots the **packaged
jar** with a production-shaped environment and asserts that a valid configuration starts and that
breaking any one required variable refuses *before the port binds*.

`ProductionStartupValidationTest` proves the guards are individually correct and jointly satisfiable.
It cannot prove the assembled context reaches them, that a bean graph with `AI_MODE=openai` and
`STORAGE_MODE=s3` can be constructed at all, or that the artifact CI ships behaves like the classes
CI tested — and the first run found a real defect of exactly that shape (see `ai/README.md`).

It also turns MS4's **Known Limitation 2** from prose into a measured property: with
`DB_PASSWORD=pronto` Flyway's connection attempt fails first, and with an under-32-character
`JWT_SECRET` jjwt's own key check fails first, so in those two cases the message names the component
rather than the variable. Both still refuse, and neither ever binds a port. Asserted rather than
skipped, so if the guards ever move to an `EnvironmentPostProcessor` these cases will fail and say so.
