# Production MS4 — Production Security & Configuration

**Status: PASS (implementation), with 3 deployment prerequisites owned by MS5**
**Date: 2026-08-26**
**Branch: `main`, uncommitted — held for review**

---

## 1. Implementation summary

MS4's rule is one sentence: **Production must fail closed, not silently fall back to development
behaviour.**

The audit that opened this milestone found that Pronto already had a real, working fail-closed
startup architecture — four guards, all `@PostConstruct` so they run before the embedded web server
binds a port, all built on one fail-safe environment predicate. Email, SMS, Maps, demo data and the
OTP pepper were already covered. No secret was committed anywhere, and no AWS credential was
hardcoded anywhere.

What was missing was the providers and settings the earlier milestones had not reached. Concretely,
before this milestone a Production deployment could start successfully and serve traffic while:

- classifying every customer issue with an offline Hebrew keyword table (`AI_MODE` unguarded),
- writing every issue photo and every professional verification document to a container filesystem,
  signed with a placeholder HMAC key checked into this public repository (`STORAGE_MODE` unguarded),
- permitting exactly one browser origin — `http://localhost:5173`,
- connecting to its database as `pronto`/`pronto`,
- and trusting `X-Forwarded-For` from `0.0.0.0/0` if somebody wrote that, which passed the existing
  `TRUSTED_PROXIES` check and disables auth rate limiting entirely.

Separately, the **production frontend bundle** silently compiled in `http://localhost:8080` whenever
`VITE_API_BASE_URL` was unset at build time.

All of the above are now refused. Four new startup guards, two extended, one extracted shared
component, one build-time frontend guard, plus configuration documentation and two placeholder-only
env templates.

**Local and CI development is unchanged.** A fresh clone still runs with `docker compose up -d` and
`mvn spring-boot:run` and no environment variables at all — asserted by a test, and verified by
booting the application.

---

## 2. Files changed

### New — backend main

| File | What |
|---|---|
| `ai/config/AiModeStartupGuard.java` | Refuses `AI_MODE=mock` in production-like; requires `OPENAI_API_KEY`/`OPENAI_MODEL` when `mode=openai` (every environment); refuses unrecognized modes |
| `storage/config/StorageModeStartupGuard.java` | Refuses `STORAGE_MODE=local` in production-like; refuses the placeholder/empty/short `STORAGE_LOCAL_HMAC_SECRET` in every non-`local` environment; requires bucket + region when `mode=s3`; refuses unrecognized modes |
| `auth/config/CorsOriginStartupGuard.java` | Refuses empty / wildcard / `localhost` / non-HTTPS CORS origins in production-like |
| `common/config/DatabaseConfigStartupGuard.java` | Refuses the committed DB password, an empty password, a `localhost` DB host, schema-mutating `ddl-auto`, disabled Flyway and re-enabled Flyway `clean` — in production-like |
| `common/config/StartupConfigurationSummary.java` | One `INFO` line per startup naming environment + every provider mode. Modes only, never values |
| `auth/security/CidrBlock.java` | Extracted from `ClientIpResolver` so the resolver and the new proxy-range guard share exactly one CIDR parser |

### Modified — backend main

| File | What |
|---|---|
| `auth/security/JwtSecretStartupGuard.java` | Added empty-secret and `< 32` character checks; message never contains the value; `validate()` made public for the cross-package test |
| `auth/config/ProductionHardeningStartupGuard.java` | Added `TRUSTED_PROXIES` **value** validation — every block must lie inside private address space; validated whether or not `behind-proxy` is set |
| `auth/config/ProviderModeStartupGuard.java` | Added unrecognized-mode rejection for `EMAIL_MODE`/`SMS_MODE` with a message naming the variable |
| `auth/security/ClientIpResolver.java` | Now uses the shared `CidrBlock`; fixed `isIpLiteral` rejecting IPv6 literals that begin with a hex letter (`fc00::`, `fe80::1`) |
| `auth/email/LoggingEmailSender.java` | `sendOrderStatusEmail` no longer logs recipient + full body in a production-like environment |
| `ProntoApplication.java` | Removed the `System.out.println("WORKING DIR = …")` debug residue |
| `resources/application.yml` | Comments updated to state what is now enforced and by which guard — no property values changed |

### New — backend tests

`ai/config/AiModeStartupGuardTest`, `storage/config/StorageModeStartupGuardTest`,
`auth/config/CorsOriginStartupGuardTest`, `common/config/DatabaseConfigStartupGuardTest`,
`auth/security/JwtSecretStartupGuardTest` (the JWT guard had none),
`common/config/ProductionStartupValidationTest` (cross-package).

### Modified — backend tests

`auth/config/ProductionHardeningStartupGuardTest` — 14 new cases for proxy-range validation.

### Frontend

| File | What |
|---|---|
| `vite.config.ts` | New: fails `npm run build` in production mode when `VITE_API_BASE_URL` is unset, malformed, non-HTTPS, or a development host |
| `src/shared/api/httpClient.ts` | The `http://localhost:8080` fallback is now `import.meta.env.DEV`-only |

### Repository / CI / docs

| File | What |
|---|---|
| `.gitignore` (new, root) | `.env` and `.env.*` ignored, `*.example` explicitly un-ignored; root `data/`, `qa-tmp*/`, `.DS_Store` |
| `.env.example` (new) | Local-development template, placeholders only |
| `.env.production.example` (new) | Production template — required / optional / must-not-be-set, placeholders only |
| `.github/workflows/frontend-ci.yml` | Added the missing `npm test` step; supplied a placeholder `VITE_API_BASE_URL` to the build step |
| `README.md` | New "Configuration and environment variables" section; TEST/DEMO recipe now includes `STORAGE_LOCAL_HMAC_SECRET` |
| `backend/.../{ai,auth,common,storage}/README.md` | Per-package MS4 sections |
| `docs/production-roadmap/README.md` | Tracker corrected — MS2/MS3 were still listed `NOT STARTED` |
| `docs/production-roadmap/reports/prod-MS4-report.md` | This document |

---

## 3. Architecture and configuration decisions

### 3.1 No Spring profiles — `pronto.environment` stays

This codebase has never had a Spring profile: one `application.yml`, no `spring.profiles.active`
anywhere. Environment separation is carried by one property, `pronto.environment`
(`PRONTO_ENVIRONMENT`, default `local`), read through `common.config.ProntoEnvironment`.

**Kept deliberately, not by inertia.** The design has three properties a profile system would not
have improved on:

1. `isProductionLike()` is an **allow-list** — `local`/`demo`/`test` are non-production, and
   everything else including every typo and every value nobody has thought of yet is production. The
   failure mode of a misspelled `PRONTO_ENVIRONMENT` is "the guards are too strict", never "the
   guards silently switched off".
2. `DemoDataStartupGuard` cross-checks the claimed environment against `SELECT current_database()` —
   configuration verified against reality rather than against other configuration.
3. Introducing profiles now would create *two* environment concepts that can disagree, which is
   precisely the drift `ProntoEnvironment` was written to end.

### 3.2 Where each guard's scope line is drawn, and why they differ

Three different scopes are in use. This is intentional; each is a different kind of claim.

| Scope | Guards | Rationale |
|---|---|---|
| **production-like** (`!local`, `!demo`, `!test`) | AI mock, storage local, CORS, database, OTP pepper, proxy ranges, Email/SMS/Maps modes | Functionality and durability decisions. `demo` and `test` must be able to run offline transports — requiring real SES/SNS on a demo instance would either break it or start texting strangers whose numbers the synthetic dataset invented |
| **non-`local`** (includes `demo`, `test`) | JWT secret, storage URL-signing key | A publicly-known signing key is directly exploitable by anyone who can reach the instance, whatever that instance is called, and a demo instance is by definition semi-shared |
| **every environment** | mode/credential consistency (`AI_MODE=openai` needs a key; `STORAGE_MODE=s3` needs a bucket; `MAPS_MODE=google` needs a key) | These are not degraded modes. Every request to the provider is rejected, so the feature is *entirely* gone — better learned at boot than from a support ticket |

### 3.3 `TRUSTED_PROXIES` — containment in private space, not a prefix-width floor

The obvious rule ("refuse blocks wider than /16") is the wrong one. What matters is not whether a
block is narrow but **whether a stranger's packet can arrive with a source address inside it**.
`ClientIpResolver` consults `X-Forwarded-For` only when the TCP peer is inside a trusted block, so as
long as every block is private, an internet client — whose source address is public — can never
satisfy that test, however wide the block. `10.0.0.0/8` is therefore both wide and perfectly safe,
while a single public `/24` is not.

The rule implemented is: every configured block must lie entirely inside RFC 1918, loopback,
link-local, RFC 6598 CGNAT, or the IPv6 ULA/link-local/loopback ranges.

### 3.4 One CIDR parser, shared

`CidrBlock` was a private record inside `ClientIpResolver`. The new guard is a second consumer, and
the two must agree byte for byte on what a CIDR string means — **a guard with a subtly different
parser would be worse than no guard**, because it would approve a configuration whose real behaviour
it had never examined. Extraction also fixed a genuine defect: `isIpLiteral` decided whether hex
letters were legal by whether a colon had been seen *so far*, scanning left to right, so every IPv6
literal beginning with a hex digit (`fc00::`, `fe80::1`) was rejected. Fail-closed in the resolver —
such an address was simply never trusted — but wrong, and it is what made the private-range table
fail to build the first time.

### 3.5 The frontend guard fires at build time, because that is the only moment the value exists

Vite statically inlines `import.meta.env.VITE_API_BASE_URL`. An unset variable is therefore not a
runtime misconfiguration an operator can correct — it is compiled into the artifact. So the
frontend's equivalent of "refuse to start" has to be "refuse to build".

### 3.6 Guards report every failure at once

Each guard collects its failures into a list and throws once. A deployment fixing one variable per
restart is a bad afternoon, and there is no reason to hand out the problems one at a time.

### 3.7 No failure message ever contains a secret

Every message names the property and its environment variable, and describes the consequence. Tests
assert the absence of the value (`hasMessageNotContaining`) for the JWT secret, the OTP pepper, the
OpenAI key, the storage signing key and the database password. `DatabaseConfigStartupGuard`
additionally never echoes the assembled JDBC URL, only the extracted host — a JDBC URL is a place
credentials end up.

---

## 4. Required Production environment variables

The application refuses to start without these. Placeholder template: `.env.production.example`.

| Variable | Example | Enforced by |
|---|---|---|
| `PRONTO_ENVIRONMENT` | `production` | anything but `local`/`test`/`demo` enables every guard |
| `DB_HOST` | RDS endpoint | `DatabaseConfigStartupGuard` — `localhost` refused |
| `DB_PASSWORD` | from secret store | `DatabaseConfigStartupGuard` — `pronto` and empty refused |
| `JWT_SECRET` | ≥ 32 chars, generated | `JwtSecretStartupGuard` |
| `OTP_PEPPER` | ≥ 32 chars, **different** from `JWT_SECRET` | `ProductionHardeningStartupGuard` |
| `AI_MODE` | `openai` | `AiModeStartupGuard` |
| `OPENAI_API_KEY` | from secret store | `AiModeStartupGuard` |
| `EMAIL_MODE` | `ses` | `ProviderModeStartupGuard` |
| `EMAIL_FROM` | SES-verified identity | `ProviderModeStartupGuard` |
| `SMS_MODE` | `aws` | `ProviderModeStartupGuard` |
| `AWS_SMS_REGION` | `eu-central-1` | `ProviderModeStartupGuard` |
| `MAPS_MODE` | `google` | `ProviderModeStartupGuard` |
| `MAPS_API_KEY` | from secret store | `ProviderModeStartupGuard` |
| `STORAGE_MODE` | `s3` | `StorageModeStartupGuard` |
| `STORAGE_S3_BUCKET` | bucket name | `StorageModeStartupGuard` |
| `STORAGE_S3_REGION` | `eu-central-1` | `StorageModeStartupGuard` |
| `CORS_ALLOWED_ORIGINS` | `https://app.…` | `CorsOriginStartupGuard` |
| `TRUSTED_PROXIES` | private VPC subnet CIDRs | `ProductionHardeningStartupGuard` |
| `VITE_API_BASE_URL` | `https://api.…` (**build time**) | `vite.config.ts` |

### Intentionally optional

`AWS_SMS_SENDER_ID` (empty lets AWS pick an origination identity, which is correct in countries
that ignore or forbid alphanumeric sender IDs), `SERVER_PORT`, `JWT_EXPIRATION_SECONDS`,
`STORAGE_PRESIGNED_URL_TTL_SECONDS`, `OPENAI_MODEL` (defaults to `gpt-4o-mini`; required only in the
sense that it must not be blanked), `EMAIL_SES_REGION`, all `MAPS_*` tuning and cache values, all
`LOCATION_*`, all `SOS_*`, all `AI_*` routing thresholds, `PHONE_DEFAULT_REGION`,
`AI_RECORD_FINAL_CLASSIFICATION`, `BEHIND_PROXY` (defaults `true`, which is the Pronto 1.0 target
architecture).

### Must not be set in Production

`DEMO_DATA_MODE` (anything but `off` refuses to start), `STORAGE_LOCAL_HMAC_SECRET` (only meaningful
with `STORAGE_MODE=local`, which Production forbids), `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
(use an IAM role — §7).

---

## 5. Test/Dev vs Production differences

| | `local` | `test` / `demo` | Production-like |
|---|---|---|---|
| Env vars required | **none** | `JWT_SECRET`, `STORAGE_LOCAL_HMAC_SECRET` | all of §4 |
| AI | `mock` | `mock` allowed | `openai` required |
| Email / SMS | `log` | `log` allowed | `ses` / `aws` required |
| Maps | `fake` | `fake` allowed | `google` + key required |
| Storage | `local`, placeholder signing key | `local` allowed, **real signing key required** | `s3` + bucket + region required |
| CORS | `http://localhost:5173` | dev origins allowed | HTTPS, non-localhost, no wildcard |
| Database | `pronto`/`pronto` on `localhost:5433` | same | real host + real password |
| Demo dataset | permitted | permitted (`demo`/`test`) | refused |
| `TRUSTED_PROXIES` | may be empty | may be empty | required, and must be private ranges |
| Frontend build | dev server, localhost fallback | — | HTTPS non-localhost `VITE_API_BASE_URL` required |

**The one deliberate break of a previously-working documented flow:** the TEST/DEMO recipe now
requires `STORAGE_LOCAL_HMAC_SECRET`. See §9.

---

## 6. Startup guards — complete list after MS4

| Guard | Package | Added |
|---|---|---|
| `JwtSecretStartupGuard` | `auth.security` | M7, extended MS4 |
| `DemoDataStartupGuard` | `demo` | TEST/DEMO work |
| `ProviderModeStartupGuard` | `auth.config` | prod MS1, extended MS2 + MS4 |
| `ProductionHardeningStartupGuard` | `auth.config` | prod MS1, extended MS4 |
| `AiModeStartupGuard` | `ai.config` | **MS4** |
| `StorageModeStartupGuard` | `storage.config` | **MS4** |
| `CorsOriginStartupGuard` | `auth.config` | **MS4** |
| `DatabaseConfigStartupGuard` | `common.config` | **MS4** |

All use `@PostConstruct`, not `ApplicationRunner`. `ApplicationRunner`s execute during
`finishRefresh()`, which is *after* the embedded Tomcat is already accepting connections — a real,
if brief, window in which the application serves traffic it should never have served.
`@PostConstruct` runs during bean initialization, strictly before the port is bound.

---

## 7. TRUSTED_PROXIES deployment requirement

```
BEHIND_PROXY=true
TRUSTED_PROXIES=<private VPC subnet CIDRs of the ALB's network interfaces, comma-separated>
```

- Must be the **private subnet CIDRs** the ALB's interfaces live in. Startup refuses anything not
  inside private address space.
- **Never** the ALB's DNS name (refused: "not a CIDR block"), **never** AWS's published public
  ranges, **never** `0.0.0.0/0`.
- Empty + `BEHIND_PROXY=true` is refused: behind a balancer every request appears to come from the
  balancer, so all users share one rate-limit bucket and registration becomes a platform-wide cap of
  10 requests per 10 minutes.
- A deployment genuinely reached directly sets `BEHIND_PROXY=false`, which states that decision
  explicitly rather than leaving it inferred from an empty string.

**Additional infrastructure requirement, not enforceable in code:** the ALB must be the only ingress
path. A task or instance whose security group accepts traffic from anywhere else lets a direct
connection reach the application with a forged header from an address that may itself be inside the
trusted range.

**The real CIDR values do not exist yet.** They are created by MS5 and must be read from the VPC, not
guessed. Nothing in this milestone invented one.

---

## 8. AWS IAM credential recommendation

**Current state (audited, unchanged by MS4):** every AWS client resolves credentials through the
default provider chain — `S3StorageClient` (both `S3Client` and `S3Presigner`), `SesEmailSender`,
`AwsSmsSender`. **No hardcoded access key, no `StaticCredentialsProvider`, and no pinned local
profile exists anywhere in the codebase.**

**Recommended for Production:** IAM **role-based** credentials via the container/instance metadata
leg of that same chain — an ECS task role (or IRSA on EKS, or an EC2 instance profile). **No code
change is required.** Long-lived `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` should not be set.

Least-privilege policy, scoped to the three actual uses:

- `s3:PutObject`, `s3:GetObject`, `s3:HeadObject` on `arn:aws:s3:::<bucket>/*` only. Presigning needs
  no additional permission — it signs with the caller's own credentials.
- `ses:SendEmail`, constrained by the `ses:FromAddress` condition key to the verified identity.
- `sns:Publish` for SMS, constrained by region — and specifically **not** `Resource: "*"` for topics.

Application secrets (`JWT_SECRET`, `OTP_PEPPER`, `OPENAI_API_KEY`, `MAPS_API_KEY`, `DB_PASSWORD`) in
Secrets Manager or SSM Parameter Store (SecureString), injected as environment variables by the task
definition — never baked into an image, never in source control.

Per the milestone's boundaries: **no credential was rotated, deleted, created or inspected in any
live AWS account.**

---

## 9. Known limitations

1. **The TEST/DEMO recipe gained a required variable.** `STORAGE_LOCAL_HMAC_SECRET` is now mandatory
   for any non-`local` environment running `STORAGE_MODE=local`. The `README.md` recipe is updated.
   This is a deliberate break: the alternative is a semi-shared demo instance whose image-URL signing
   key is published in this repository.

2. **Guard failures can be preceded by a database connection failure.** The guards are
   `@PostConstruct`, and Spring may instantiate Flyway/JPA infrastructure beans first. If the
   database is *also* unreachable, the reported error is the database one and the guard messages
   never appear. The security property is unaffected — the application refuses to start either way,
   and the port is never bound — but the diagnostic ordering is not controllable without moving to an
   `EnvironmentPostProcessor`, which would be a larger architectural change than the benefit
   justifies. Verified empirically (§10.3).

3. **`DatabaseConfigStartupGuard` has no escape hatch for a localhost production database.** Pronto
   1.0's target is RDS over the VPC, so `localhost` in production means `DB_HOST` was never set. An
   override would be one more thing that can be set by accident. If a single-host deployment ever
   becomes a real requirement, this is the line to revisit.

4. **CORS requires HTTPS in production-like environments**, including any environment named
   `staging`. A plaintext staging deployment will not start. This is the intended reading of
   "production must fail closed", and the fix is TLS, not a flag.

5. **Google Geocoding sends its API key as a URL query parameter** (`GoogleGeocodingProvider`), where
   the Routes API uses a header. That is Google's required form for Geocoding, and Pronto's own code
   never logs the URL. Residual exposure is in provider-side and any intermediate-proxy access logs;
   the mitigation is API-key restriction at Google, which is a deployment task (§11).

6. **The JWT is held in `localStorage`** (`AuthProvider.tsx`), so any XSS yields a token. This is a
   pre-existing architectural choice, out of MS4's scope, and recorded here because the audit
   surfaced it.

7. **Backend CI still has no production-configuration smoke run.** The guard tests are unit tests;
   there is no CI job that boots the application with a production-shaped environment. MS5 owns the
   deployment pipeline where that belongs.

---

## 10. Validation evidence

### 10.1 Automated

| Suite | Command | Result |
|---|---|---|
| Backend, full | `mvn -o -B test` | **1401 run, 0 failures, 0 errors, 56 skipped — BUILD SUCCESS** |
| Backend, MS4 guards only | `mvn -o -B test -Dtest='AiModeStartupGuardTest,StorageModeStartupGuardTest,CorsOriginStartupGuardTest,DatabaseConfigStartupGuardTest,JwtSecretStartupGuardTest,ProductionHardeningStartupGuardTest,ProviderModeStartupGuardTest,ClientIpResolverTest'` | **170 run, 0 failures** |
| Backend, cross-guard | `mvn -o -B test -Dtest=ProductionStartupValidationTest` | **21 run, 0 failures** |
| Frontend tests | `npm test` | **6 files, 55 tests passed** |
| Frontend typecheck | `npx tsc -b` | clean |
| Frontend lint | `npm run lint` | pass (3 pre-existing `only-export-components` warnings, unrelated to MS4) |
| Frontend production build | `VITE_API_BASE_URL=https://api.pronto.example npm run build` | **built successfully** |

New backend tests added by MS4: **~120 cases** across six classes.

`ProductionStartupValidationTest` deserves a note. It runs **every** startup guard in the codebase
against one candidate configuration, then breaks exactly one variable per case. It exists because a
set of individually correct guards can still be collectively unsatisfiable — two of them demanding
contradictory things about the same variable would pass every per-guard unit test and make Production
unbootable — and because it doubles as the executable specification of §4. It also asserts the two
cases MS4 must not break: the zero-configuration `local` environment, and the documented `demo` one.

### 10.2 Frontend build guard — manual, all four cases

| `VITE_API_BASE_URL` | Result |
|---|---|
| unset | ✅ refused — "is not set." |
| `api.example.com` | ✅ refused — "not a valid absolute URL" |
| `http://api.example.com` | ✅ refused — "which is not HTTPS. Every JWT this app holds travels over that origin." |
| `https://localhost:8443` | ✅ refused — "a development origin." |
| `https://api.pronto.example` | ✅ built in 933 ms |

A defect in the guard was found and fixed by this exercise: the HTTPS check originally threw from
inside the URL-parsing `try`, so its own failure was swallowed by the `catch` meant to report a
malformed URL and every failure was reported as "not a valid absolute URL".

### 10.3 Backend startup — manual, against a live local PostgreSQL

Each step adds the variable the previous step demanded. `PRONTO_ENVIRONMENT=production` throughout.

| Step | Configuration | Result |
|---|---|---|
| 1 | production, nothing else set | ✅ refused — `pronto.ai.mode=mock (AI_MODE)` |
| 2 | + `AI_MODE=openai`, `OPENAI_API_KEY` | ✅ refused — `CORS_ALLOWED_ORIGINS` contains `http://localhost:5173` |
| 3 | + all providers real (`ses`/`aws`/`google`/`s3` + creds), real CORS origin | ✅ refused — `TRUSTED_PROXIES` empty while `behind-proxy` true |
| 4 | + `TRUSTED_PROXIES=0.0.0.0/0` | ✅ refused — "not inside private address space… lets it evade the auth rate limiter entirely" |
| 5 | + `TRUSTED_PROXIES=10.0.0.0/16` | ✅ refused — **both** DB failures at once: committed `DB_PASSWORD` and `localhost` `DB_HOST` |

Step 5 is the endpoint that matters: the chain terminates at the only guard that *cannot* be
satisfied on a developer machine, which proves every other guard is jointly satisfiable with
real-shaped values.

Regression check — `local`, zero configuration:

```text
INFO c.p.demo.DemoDataStartupGuard : pronto.startup.environment environment=local database=pronto demoDataMode=OFF
INFO o.s.b.w.e.tomcat.TomcatWebServer : Tomcat started on port 8080 (http)
INFO c.p.c.c.StartupConfigurationSummary : pronto.startup.configuration environment=local
     productionLike=false ai=mock email=log sms=log storage=local maps=fake demoData=off
     behindProxy=true trustedProxyRanges=0 corsOrigins=1
```

### 10.4 Secrets scan

- Pattern scan over all tracked files for `AKIA…`, `ASIA…`, `sk-…`, `AIza…` and PEM private keys:
  **zero matches.**
- `git log --all --diff-filter=A` for any added `.env` / secret / credential file: **none has ever
  been committed.**
- `backend/qa-tmp/*.env` (live QA JWTs) confirmed git-ignored via `git check-ignore`; never tracked;
  untouched by this milestone.
- The three checked-in placeholder secrets (`pronto.jwt.secret`, `pronto.otp.pepper`,
  `pronto.storage.local.hmac-secret`) are all loudly self-describing and all now startup-refused
  outside `local`.

---

## 11. Remaining deployment prerequisites (MS5)

None of these is an open MS4 defect; none can be satisfied by a code change.

1. **Real `TRUSTED_PROXIES` values** — the ALB's private subnet CIDRs, read from the VPC.
2. **IAM task role** with the least-privilege policy in §8, replacing any static access keys.
3. **Google Maps API key restriction** — restrict to the Geocoding + Routes APIs and to the
   deployment's egress IPs. Mitigates §9.5.
4. Carried forward from MS1 and still open: **exit the AWS SMS sandbox**, review the SMS spend limit,
   and validate a previously unverified `+972` destination after sandbox exit.
5. **Secrets Manager / SSM entries** created and wired into the task definition.
6. Confirm Google Maps Platform's current geocoding-retention terms against live documentation
   before launch (carried from MS2; `MAPS_GEOCODE_CACHE_MAX_AGE_DAYS` encodes a 30-day assumption).

---

## 12. Remaining risks

| Risk | Severity | Status |
|---|---|---|
| ALB not the only ingress path → forged `X-Forwarded-For` from inside the trusted range | HIGH | Infrastructure; MS5. Not enforceable in application code |
| JWT in `localStorage` → XSS yields a session token | MEDIUM | Pre-existing; out of MS4 scope |
| Google Geocoding key in URL query strings reaches provider/proxy access logs | MEDIUM | Mitigated by key restriction (§11.3) |
| Guard diagnostics masked by a simultaneous database outage | LOW | §9.2 — fail-closed behaviour is unaffected |
| Unknown-unknown provider added later without a matching guard | MEDIUM | Mitigated by the pattern being uniform and by `ProductionStartupValidationTest` being the single place a new guard must be registered |
| No CI job boots a production-shaped configuration | LOW | MS5 owns the deployment pipeline |

---

## 13. Gate

**MS4: PASS (implementation).**

Every Definition-of-Done item proposed in the Phase 1 audit is met:

- ✅ Production fails closed on all sixteen configuration classes the brief named, mapped to the
  actual architecture (nothing was implemented for a concept that does not exist here).
- ✅ Every failure names the property and its environment variable, and prints no sensitive value —
  asserted by test.
- ✅ All guards run before the web server binds a port.
- ✅ `local`, `test` and CI startup paths unchanged; zero-config local development still works,
  asserted by test and verified by booting.
- ✅ A structurally valid production configuration passes every guard in a unit test with no external
  network call.
- ✅ Frontend production build fails without a valid `VITE_API_BASE_URL`; no localhost fallback
  survives into a production bundle.
- ✅ No secret, real credential or real infrastructure value added to source, YAML, tests, examples or
  docs.
- ✅ Backend tests, frontend tests, typecheck, lint and production build all pass.
- ✅ Documentation written; per-package READMEs updated per the standing rule.
- ✅ Nothing committed, pushed or merged. MS5 not started.

**This is the implementation gate, not a claim that Pronto is deployable.** The six prerequisites in
§11 remain, and they are what stands between "unsafe configuration cannot silently reach Production"
— which is now true — and "Production exists".
