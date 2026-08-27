# Pronto — Production Deployment Runbook

> **Status: the infrastructure is DEFINED, not APPLIED.** Every AWS resource below exists only as
> Terraform. `terraform apply` has never been run against this configuration, no AWS resource has
> been created, no domain has been bought, no DNS record touched and no deployment performed.
> Anything in this document phrased as a present-tense fact about running infrastructure is a
> description of what applying it *will* produce.

Written for a developer who has not deployed this application before. Where a step cannot be
completed today, it says so and says why, rather than leaving a gap to be discovered halfway
through.

---

## 1. Architecture

```
                          Internet
        ┌─────────────────────┴──────────────────────┐
        ▼                                            ▼
┌───────────────┐                         ┌──────────────────────┐
│  CloudFront   │  HTTPS                  │  ALB (public subnets)│  HTTPS
│  + OAC        │                         │  :443 → :8080        │
└───────┬───────┘                         │  sg-alb              │
        │ SigV4                           └──────────┬───────────┘
        ▼                                            │ sg-alb ONLY
┌───────────────┐                                    ▼
│ S3 frontend   │                         ┌──────────────────────┐
│ private, BPA  │                         │ ECS Fargate          │
└───────────────┘                         │ desiredCount = 1     │
                                          │ PUBLIC subnet        │
════════════ VPC 10.0.0.0/16 ═════════════│ public IP (egress)   │
                                          │ sg-app               │
  public  10.0.0.0/24   (AZ-a) ← ALB      └────┬────────────┬────┘
  public  10.0.1.0/24   (AZ-b) ← ALB           │            │
  private 10.0.10.0/24  (AZ-a)                 │            │
  private 10.0.11.0/24  (AZ-b)                 │            │
                                    ┌──────────▼──┐   ┌─────▼─────────┐
   ┌──────────────────┐             │ RDS Postgres│   │ CloudWatch    │
   │ S3 Gateway       │             │ 16, Single- │   │ Logs + Alarms │
   │ Endpoint (free)  │             │ AZ, PRIVATE │   └───────────────┘
   └────────┬─────────┘             │ sg-db :5432 │
            ▼                       └─────────────┘
   S3 uploads bucket

   Internet Gateway ──► OpenAI · Google Maps · SES · SNS · ECR · Secrets Manager
                        (NO NAT GATEWAY — see §3)
```

Region **us-east-1** throughout (owner decision, MS5 Stage B review; it replaced eu-central-1).

One convenient consequence: CloudFront requires its ACM certificate in us-east-1 wherever the rest
of the stack lives, so a primary region of us-east-1 collapses what was a two-region, two-certificate
model into **one certificate serving both the ALB and CloudFront**. The us-east-1-aliased provider
that existed for the old model has been removed — it was never referenced by any resource.

What the region change does **not** carry with it: SES identities and sandbox status are per-region
and must be re-established in us-east-1, and `+972` A2P SMS support is no more verified here than it
was in eu-central-1.

| Layer | Choice |
|---|---|
| Frontend | React/Vite → private S3 → CloudFront + OAC |
| Backend | Spring Boot → Docker → ECR → ECS Fargate, 0.5 vCPU / 1024 MB |
| Ingress | Application Load Balancer |
| Database | RDS PostgreSQL 16, Single-AZ, private, encrypted |
| Secrets | AWS Secrets Manager, injected by the ECS execution role |
| CI/CD | GitHub Actions → OIDC → IAM role. No stored AWS keys. |
| IaC | Terraform, `infra/terraform/` |

---

## 2. Why the ECS service runs exactly one task

`desiredCount = 1` is a **correctness** constraint, not a cost decision, and
`infra/terraform/variables.tf` enforces it with a validation rule. The application holds four pieces
of per-JVM state that a second task would split or duplicate:

| Component | With two tasks |
|---|---|
| `auth.security.AuthRateLimitInterceptor` — counters in a `ConcurrentHashMap` | N tasks enforce N× the configured limit |
| `realtime.config.WebSocketConfig` — `enableSimpleBroker`, in-JVM | An SOS event published by task A never reaches a customer connected to task B. Degrades rather than breaks: the frontend polls REST as a fallback |
| `maps.cache.RouteCache` — in-process by design | Each task pays its own Google Maps calls |
| `notifications.scheduler.EmailDispatchJob` | **Sends every order email twice.** It selects `PENDING` rows with no claim or row lock before sending — a visible customer-facing defect, not a degradation |

This also dictates the deployment strategy (§8): stop-then-start rather than rolling, so there is
never a moment when two versions are running.

**Raising the count is post-1.0 work** and starts with fixing those four, in roughly that order of
severity. Do not raise it to "get zero-downtime deploys" — the downtime is a symptom, not the
problem.

---

## 3. Why there is no NAT Gateway, and how to add one later

The reference architecture puts the tasks in private subnets with a NAT gateway for egress. That is
roughly **$33/month before data processing** — a fixed cost, larger than the compute it serves,
incurred whether or not anybody uses the product. For a closed beta that was judged the wrong shape
of bill, so the tasks run in the **public** subnets with `assignPublicIp = true`, using the internet
gateway for outbound.

**What this costs, stated plainly.** In the private-subnet design there are two independent controls
between the internet and port 8080 — no route from the IGW, and the security group — and either
alone is sufficient. Here there is **one**: `aws_vpc_security_group_ingress_rule.app_from_alb` in
`infra/terraform/network.tf`. It admits port 8080 from the ALB's *security group id* (not a CIDR),
and no rule anywhere admits `0.0.0.0/0` to the application port. Read that rule's comment before
changing anything near it.

**One concrete consequence, and it is not cosmetic.** Without a NAT gateway there is no stable
egress IP: the task's public address changes every time the task is replaced, which is every deploy.
So the production **Google Maps API key cannot be IP-restricted** — carried-forward prerequisite #7
(MS4 §11.3) is only partially satisfiable today. Interim mitigation is API restriction (Geocoding +
Routes only) plus quotas and budget alerts; see §11.

**Adding NAT later** — the private subnets already exist, unused, for exactly this:

1. Add `aws_nat_gateway` + `aws_eip` in one of the public subnets.
2. Add a `0.0.0.0/0` route to `aws_route_table.private` pointing at it.
3. Change the ECS service's `network_configuration` to `subnets = aws_subnet.private[*].id` and
   `assign_public_ip = false`.
4. Restrict the Google Maps key to the NAT's Elastic IP.

Nothing is re-addressed and **`TRUSTED_PROXIES` does not change**, because the ALB does not move.

---

## 4. Prerequisites

| # | Requirement | Status |
|---|---|---|
| 1 | AWS account with billing enabled | Owner |
| 2 | Terraform ≥ 1.9 (1.10+ for S3 native state locking) | Local install |
| 3 | AWS CLI v2 authenticated via **IAM Identity Center profile `pronto-admin`**. Verify with `aws sts get-caller-identity --profile pronto-admin` — it must return an `assumed-role/AWSReservedSSO_...` ARN, **not** root | ✅ verified |
| 4 | Docker (for local image builds) | ✅ |
| 5 | A registered domain | **BLOCKED — none chosen** |
| 6 | SES out of sandbox **in us-east-1** — identities are per-region, so any prior eu-central-1 verification does not carry over | **BLOCKED** |
| 7 | AWS SMS production access + spend limit + Israeli origination identity | **BLOCKED** |
| 8 | Production Google Maps API key | **BLOCKED** |
| 9 | Confirmation that **us-east-1** supports `+972` A2P SMS | **BLOCKED — unverified.** Moving region neither solved nor worsened this |

Items 5–9 are AWS/provider account state that no repository change can satisfy. §11 has the detail.

---

## 5. Provision the infrastructure

### 5.1 State backend (once)

```bash
export AWS_PROFILE=pronto-admin
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN   # see the warning below

cd infra/terraform/bootstrap
terraform init
terraform apply            # creates the versioned, encrypted state bucket
terraform output backend_hcl > ../backend.hcl     # git-ignored
```

> **Unset the environment credentials first, every time.** The AWS SDK credential chain puts
> `AWS_ACCESS_KEY_ID` **above** `AWS_PROFILE`. A shell that still has the old root access keys
> exported will use them and ignore `pronto-admin` entirely — silently, with no warning, and
> `terraform apply` will happily create 84 resources as the account root. This was observed on the
> development machine during the Stage B review.

### 5.2 Main configuration

```bash
export AWS_PROFILE=pronto-admin
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN

cd infra/terraform
cp terraform.tfvars.example terraform.tfvars      # git-ignored; edit it
terraform init -backend-config=backend.hcl
terraform fmt -check -recursive
terraform validate
terraform plan -out=production.tfplan             # READ THIS BEFORE APPLYING
terraform apply production.tfplan
```

`terraform.tfvars` is where `aws_region` and `alarm_email` live. It is git-ignored on purpose: the
alarm address is a personal mailbox and does not belong in the repository, so the committed
`terraform.tfvars.example` keeps a placeholder.

Leave every domain/certificate variable empty on the first apply. The stack is deployable without
them — see §9.

### 5.3 Record the outputs

```bash
terraform output
```

`trusted_proxies` is the value MS1 and MS4 both deferred to this milestone. It is already wired into
the task definition; you need it for the §12 validation.

---

## 6. Secrets

Terraform creates the secret **containers** and deliberately no **versions**, so no secret value
ever enters Terraform state. Write them once:

```bash
P=pronto/production

aws secretsmanager put-secret-value --secret-id $P/jwt-secret     --secret-string "$(openssl rand -base64 48)"
aws secretsmanager put-secret-value --secret-id $P/otp-pepper     --secret-string "$(openssl rand -base64 48)"
aws secretsmanager put-secret-value --secret-id $P/openai-api-key --secret-string "sk-..."
aws secretsmanager put-secret-value --secret-id $P/maps-api-key   --secret-string "AIza..."
aws secretsmanager put-secret-value --secret-id $P/db-app-password --secret-string "$(openssl rand -base64 32)"
```

- `jwt-secret` and `otp-pepper` **must be different values**, each ≥ 32 characters. They have
  different blast radii and rotating one must not force rotating the other; `ProductionHardening‑
  StartupGuard` checks it.
- Until a version exists, the ECS task fails to start with a `ResourceNotFoundException` naming the
  secret. That is the correct failure: loud, specific, and before the port binds.

---

## 7. Database setup

RDS is created by Terraform, but two things must be done by hand.

**The RDS instance is in a private subnet with no internet route, so you cannot reach it from a
laptop.** Use an ECS exec session, a temporary bastion, or run the SQL from a one-off task. The
least machinery is ECS exec into the running backend task (requires `enableExecuteCommand`, which is
off by default — turn it on temporarily, then off).

### 7.1 Create the least-privilege application role

The application must **not** connect as the RDS master. Read the master password from the
RDS-managed secret, then:

```sql
CREATE ROLE pronto_app WITH LOGIN PASSWORD '<the db-app-password you wrote in §6>';
GRANT CONNECT ON DATABASE pronto TO pronto_app;
GRANT USAGE, CREATE ON SCHEMA public TO pronto_app;
```

`CREATE` on the schema is required — Flyway runs as this role and creates tables.

### 7.2 Migrations

**Flyway runs in-process at application startup**, before the port binds. There is no separate
migration step and no migration task, deliberately: with one task there is exactly one migrator, and
`ddl-auto: validate` then proves every JPA entity matches the migrated schema on the same boot.

On the first deploy, **V1 … V52 apply as one batch** against the empty database. Rehearse this
against a throwaway RDS instance first and time it — "it should be seconds" is not evidence.

**Migrations are forward-only.** There are no down scripts and there will not be. Two consequences:

- **Code rollback** (redeploy the previous image) is safe **only if the previous code tolerates the
  newer schema.**
- **Schema rollback does not exist.** Undoing a migration means a PITR restore, which loses every
  write since the restore point.

Therefore: **expand/contract discipline.** Migrations are additive. A column is never dropped in the
same release that stops writing it. A `NOT NULL` is added only in a release *after* the one that
backfills it. This is the rule that makes "roll back the image" actually work.

`backend/tools/check-migrations-append-only.sh` runs in CI and fails the build if an already-applied
migration is edited or deleted.

---

## 8. Deploying

Run the **Deploy Production** workflow (`workflow_dispatch`). There is no automatic deploy on push —
see §8.1.

```
verify ─► build-image ─► [MANUAL APPROVAL] ─► deploy-backend ─► health check
                                          └─► deploy-frontend ─► invalidate ─► smoke
```

`verify` runs the full backend suite against a real PostgreSQL, the frontend lint/test/build, the
migration-immutability check, and the production-shaped startup smoke
(`backend/tools/production-config-smoke.sh`).

**Expect roughly 60–120 seconds of downtime per backend deploy.** The service runs
`deployment_minimum_healthy_percent = 0`, so ECS stops the single task before starting its
replacement. That is deliberate (§2): one Flyway migrator, one scheduler set, never two code
versions against one schema.

### 8.1 Why deployment is manual

`main` is **not a protected branch** — the MS1 report measured it through the GitHub API and found
`"protected": false` with required status checks off. Auto-deploying an unprotected branch means an
unreviewed or red commit reaches customers. Fix in this order:

1. Enable branch protection on `main`, requiring the `Backend CI` and `Frontend CI` checks.
2. Run manual deploys through the beta.
3. Then revisit automation.

The `production` GitHub Environment's approval gate is enforced twice: by GitHub, and by the AWS
trust policy, which only issues credentials to the `environment:production` OIDC subject claim.

### 8.2 GitHub configuration

On the `production` environment, set these **variables** (not secrets — none is sensitive):

| Variable | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `terraform output github_deploy_role_arn` |
| `FRONTEND_BUCKET` | `terraform output frontend_bucket` |
| `CLOUDFRONT_DISTRIBUTION_ID` | `terraform output cloudfront_distribution_id` |
| `PRODUCTION_API_URL` | `https://api.<domain>` — **BLOCKED** until a domain exists |
| `PRODUCTION_APP_URL` | `https://<domain>` or the CloudFront domain |

**No `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` is created or stored anywhere.**

---

## 9. DNS and TLS — BLOCKED

No domain has been chosen and this milestone does not invent one. The stack is deployable without
it, asymmetrically:

- **Frontend: fully working.** CloudFront serves on its own `*.cloudfront.net` name with a valid
  Amazon certificate — real HTTPS, real SPA deep links.
- **Backend: genuinely blocked.** An ALB certificate must name a hostname somebody owns. With no
  certificate there is no HTTPS listener, and port 80 returns a fixed `503`.
  `enable_plaintext_http_listener = true` forwards port 80 in plaintext instead — for validating
  infrastructure only, against an environment with no real user data, because every JWT sent to it
  is exposed.

Once a domain exists:

1. Register it; create a Route 53 hosted zone.
2. Request **one** certificate in **us-east-1** covering the apex, `www` and `api` hostnames as
   SANs. One, not two: CloudFront requires us-east-1 regardless of the primary region, and the ALB
   is now in us-east-1 too, so a single certificate satisfies both.
3. Set `domain_name` and `acm_certificate_arn` in `terraform.tfvars`; re-apply. There is no second
   certificate variable — `acm_certificate_arn_us_east_1` was removed when the primary region became
   us-east-1.
4. Create records: `api.<domain>` → ALB alias, `<domain>` → CloudFront alias.
5. Set `enable_plaintext_http_listener = false`.
6. Update `PRODUCTION_API_URL` / `PRODUCTION_APP_URL` and **rebuild the frontend** — Vite inlines
   `VITE_API_BASE_URL` at build time, so changing it requires a new bundle.

---

## 10. `TRUSTED_PROXIES`

This is the highest-consequence value in the deployment, and the prerequisite MS1 and MS4 both
deferred here.

**It is generated, never typed.** `infra/terraform/compute.tf` derives it from the ALB's actual
subnet resources and `outputs.tf` exposes it. MS4's report requires the real values be "read from
the VPC, not guessed", and a hand-written value is a guess even when it happens to be right.

**Why the public subnet CIDRs.** The ALB has a network interface in each public subnet with a
private address from that subnet's range. When it forwards a request, that is the TCP source address
the backend sees — because `server.forward-headers-strategy` is left at Spring Boot's default of
`NONE`, so `request.getRemoteAddr()` is the raw peer. `ClientIpResolver` consults `X-Forwarded-For`
only when that peer is inside a configured block.

**Why subnet ranges and not the ALB's addresses.** An ALB scales its nodes and replaces them,
drawing new addresses from the same subnets. A list of `/32`s is correct until the first scaling
event and then silently wrong — at which point every user shares one rate-limit bucket.

**Why it is safe that the range is wide.** Per MS4 §3.3, the test is not "is this block narrow" but
"can a stranger's packet arrive with a source address inside it". These are RFC 1918 ranges inside a
VPC; an internet client's source address is public and can never match.
`ProductionHardeningStartupGuard` enforces exactly that and refuses a public range.

**What changes it:** adding an availability zone (handled automatically — the output reads the
subnet resources). **What does not:** ALB scaling, task count, or moving ECS to the private subnets.

### 10.1 Never set `server.forward-headers-strategy`

Setting it to `native` or `framework` installs Tomcat's `RemoteIpValve`, which **rewrites**
`getRemoteAddr()` from the very header `ClientIpResolver` is trying to decide whether to trust. The
peer would become the client's public address, which is in no private block, so the resolver takes
its untrusted-peer branch and returns that unverified attacker-supplied value for every request —
while every startup guard still passes. Rate limiting becomes spoofable and nothing says so.
`application.yml` records this next to the property.

---

## 11. External providers

| Provider | Configuration | Status |
|---|---|---|
| **OpenAI** | `OPENAI_API_KEY` from Secrets Manager; `gpt-4o-mini`; 10 s timeout; 3 attempts with exponential backoff + jitter, honouring `Retry-After` on 429 (MS5 fixed the MS3 "no backoff" gap). Set a hard spend limit on the OpenAI account — AWS budgets cannot see it. Keep `AI_RECORD_FINAL_CLASSIFICATION=false`; enabling it doubles model calls per issue. | Key needed |
| **Google Maps** | New key in its own GCP project — **not** the MS2 validation key, which lives in local IDE config. Restrict to **Geocoding API + Routes API**. **IP restriction is not possible without a NAT gateway** (§3), so compensate with per-API daily quotas and a GCP budget alert. `MAPS_GEOCODE_CACHE_MAX_AGE_DAYS=30` is an **unverified legal placeholder** — confirm Google's current retention terms before launch. | **BLOCKED** |
| **SES** | Region **us-east-1**. Identities and sandbox status are PER-REGION, so nothing verified in eu-central-1 carries over — the production identity must be created and verified in us-east-1. Use a **domain** identity with DKIM, plus SPF and DMARC. Must be **out of the sandbox**. Subscribe an SNS topic to Bounce and Complaint notifications — the application has **no bounce handling**; `EmailDispatchJob` marks `SENT` on a successful API call, which is not delivery. | **BLOCKED** |
| **SMS** | `SnsClient.publish` to a phone number. Account is **still in the SMS sandbox** — only pre-verified destinations receive codes. Needs production access, a spend-limit review, and an Israeli origination identity (Israel does not support unregistered alphanumeric sender IDs). Leave `AWS_SMS_SENDER_ID` empty so AWS selects from the pool. **Confirm us-east-1 supports `+972` A2P delivery.** The region change does not address this: it was unverified in eu-central-1 and is unverified in us-east-1, and no evidence has been gathered either way. | **BLOCKED** |

---

## 12. Validation after the first deploy

Nothing below can be satisfied by reading configuration. Run it and keep the output.

### 12.1 Basics
1. `curl https://api.<domain>/actuator/health/readiness` → `200`
2. All 52 migrations applied: `SELECT count(*) FROM flyway_schema_history WHERE success;`
3. Startup log shows `productionLike=true ai=openai email=ses sms=aws storage=s3 maps=google`
4. Frontend loads over HTTPS; a deep link such as `/orders` resolves (SPA fallback)
5. CORS: a real browser request from the frontend origin succeeds; the SOS WebSocket handshake
   connects (it reads the same allow-list)

### 12.2 Guards still reject unsafe configuration
Deliberately break one variable in the task definition, deploy, confirm the task fails to start with
a message naming it, then revert. Do this **in the real environment** — the CI smoke test proves the
artifact behaves; this proves the deployed configuration path does.

### 12.3 `TRUSTED_PROXIES` — the acceptance criteria

From a machine **outside** the VPC:

1. **Startup assertion.** CloudWatch Logs contains
   `Client IP resolution: X-Forwarded-For honoured for peers within [10.0.0.0/24, 10.0.1.0/24]`.
   The alternative line, `direct peer address only`, means the variable never reached the container.
2. **Per-client limiting.** 11 rapid `POST /api/auth/login` from one public IP → the 11th returns
   `429`. The log shows `pronto.ratelimit.refused client=<your IP>`.
3. **Clients are isolated.** Immediately repeat from a *different* public IP → **not** `429`. If it
   is, every client shares one bucket and the value is wrong.
4. **Forged headers are ignored.** From a fresh IP, send 11 requests each carrying
   `X-Forwarded-For: 203.0.113.<random>` → the 11th still `429`, and the log shows your **real**
   address, not the forged one. If it shows the forged one, **do not launch**.
5. **Victim-targeting is impossible.** From IP A send requests naming IP B; then call normally from
   IP B → B is not limited.
6. **Direct ingress is impossible.** `aws ec2 describe-security-groups` shows no `0.0.0.0/0` rule on
   port 8080. Keep the output as evidence.

**Steps 2–5 are the acceptance criteria. None may be marked PASS on configuration review.**

### 12.4 Providers
Real OpenAI classification, real Google geocoding + routing, real S3 upload and presigned retrieval,
real SES delivery to a real inbox, SMS to whatever the sandbox permits — each end to end, each
recorded.

### 12.5 Operations
Rollback rehearsed (§13). RDS PITR restore rehearsed. One alarm deliberately triggered and the email
received — an unconfirmed SNS subscription looks identical to a working one.

---

## 13. Rollback

**Backend** — the workflow prints the previous task definition ARN before deploying:

```bash
aws ecs update-service --cluster pronto-cluster --service pronto-backend \
  --task-definition <previous-arn>
aws ecs wait services-stable --cluster pronto-cluster --services pronto-backend
```

Safe only if the previous code tolerates the current schema — see §7.2.

**Frontend** — re-run the deploy workflow from the previous commit. Images are tagged by git SHA and
ECR keeps the last 10, so the artifact is still there.

**Database** — PITR restore to a new instance, repoint `DB_HOST`, restart the service. **RTO ≈ 30
minutes, RPO ≈ 5 minutes.** This loses every write since the restore point; it is the last resort,
not a rollback step.

**Infrastructure** — `terraform apply` from the last known-good commit.

---

## 14. Backups and disaster recovery

| Asset | Protection | Restore |
|---|---|---|
| RDS | 7-day automated backups, PITR, deletion protection, storage autoscaling | PITR to a new instance (RTO ~30 min, RPO ~5 min) |
| S3 uploads | Versioning on; noncurrent versions expire at 90 days | Restore the prior object version |
| S3 frontend | None — a reproducible build artifact | Re-run the deploy workflow |
| Secrets | 7-day recovery window | Recover, or regenerate. **Regenerating `JWT_SECRET` invalidates every session; regenerating `OTP_PEPPER` invalidates every outstanding OTP.** Both survivable — but discover it here, not during an incident |
| Infrastructure | Versioned S3 state bucket | `terraform apply` from a known-good commit |
| Images | ECR keeps the last 10, tagged by git SHA | Redeploy a previous tag |

**Single-AZ is a deliberate beta deferral.** Multi-AZ roughly doubles the RDS bill for automatic
failover; a beta's cost of a ~30-minute manual restore is below that. Revisit at public launch.

---

## 15. Monitoring

CloudWatch only — no Datadog, New Relic or APM; Sentry deferred. Log retention **30 days**, which
also bounds how long resolved client IP addresses are held.

Alarms (`infra/terraform/observability.tf`) → SNS → email. Metric filters key on log events the
application already writes; **renaming one silently zeroes an alarm**:

| Signal | Event key |
|---|---|
| Application errors | `ERROR` |
| AI exhaustion | `openai.request.exhausted` |
| OTP delivery failure | `OTP_DELIVERY_FAILED` (MS1 asked for this by name) |
| Rate-limit spikes | `pronto.ratelimit.refused` |
| Geocoding rejections | `maps.geocode.rejected` (MS2 §17.5) |
| Startup refusal | `Refusing to start` |

Plus ALB unhealthy-target / 5xx, ECS running-task count, and RDS CPU / storage / memory /
connections. A monthly AWS budget alerts at 50/80/100%.

---

## 16. Known blockers and deferred work

**BLOCKED — cannot be closed from this repository**

1. Domain registration, DNS, both ACM certificates
2. SES production access and domain verification **in us-east-1** (per-region; prior work elsewhere does not transfer)
3. AWS SMS sandbox exit, spend limit, Israeli origination identity
4. Confirmation that us-east-1 supports `+972` A2P SMS
5. Production Google Maps key, quotas, GCP budget
6. Google geocoding retention terms (`MAPS_GEOCODE_CACHE_MAX_AGE_DAYS`)
7. Rotating the root-account AWS keys in local IntelliJ config (MS1 §Q)

**Deferred by decision, recorded rather than forgotten**

| Item | Why | Revisit |
|---|---|---|
| Multi-instance safety (rate limiter, broker, schedulers, duplicate emails) | Real engineering, not configuration | Before scaling past one task |
| NAT gateway + private ECS | ~$33/month at beta traffic | At meaningful traffic — also unblocks Maps IP restriction |
| Multi-AZ RDS | Roughly doubles the RDS bill | Public launch |
| `sslmode=verify-full` | Needs the RDS CA bundle in the image | Post-1.0 hardening |
| Structured JSON logging | Spring Boot 3.3 predates built-in support; the app already emits stable greppable keys | If log volume makes filters unwieldy |
| Fargate ARM64 (~20% cheaper) | GitHub runners are x86; cross-building adds a failure mode | Once the pipeline is settled |
| Container Insights | Bills per custom metric | When traffic makes the detail meaningful |
| SES bounce/complaint automation | Application has no suppression handling | Before open signup |
| JWT in `localStorage` (XSS → token) | Pre-existing architectural choice | Owner decision |
