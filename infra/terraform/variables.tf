# Inputs. Every default here is either a real architectural decision (recorded in the deployment
# runbook) or a placeholder that is obviously a placeholder. No default is a real secret, a real
# domain, a real account id or a real ARN.

# ==================================================================================================
# Identity and region
# ==================================================================================================

variable "aws_region" {
  description = "Primary region for every resource in this configuration."
  type        = string
  # us-east-1 by owner decision (MS5 Stage B review). It replaces eu-central-1 throughout, and the
  # change is mirrored in application.yml's STORAGE_S3_REGION / EMAIL_SES_REGION / AWS_SMS_REGION
  # defaults, .env.production.example and the deploy workflow so the repository states one region.
  #
  # A convenient side effect: CloudFront's certificate must live in us-east-1 regardless of where
  # the rest of the stack is, so a primary region of us-east-1 collapses the previous two-region,
  # two-certificate model into one certificate serving both the ALB and CloudFront.
  #
  # NOT automatically true after the move, and tracked as open items: SES identities and sandbox
  # status are PER-REGION and must be re-established here, and Israeli (+972) A2P SMS support is
  # unverified in this region as in any other.
  default = "us-east-1"
}

variable "project" {
  description = "Name prefix for every resource. Short, lowercase, DNS-safe."
  type        = string
  default     = "pronto"
}

variable "environment_name" {
  description = <<-EOT
    Deployment environment name, used in resource names and tags. This is NOT the value of
    PRONTO_ENVIRONMENT -- that is set in compute.tf and is deliberately the literal "production",
    because common.config.ProntoEnvironment treats every value except local/test/demo as
    production-like and MS4's guards key off it.
  EOT
  type        = string
  default     = "production"
}

variable "github_repository" {
  description = "owner/repo, used for the GitHub Actions OIDC trust policy subject claim."
  type        = string
  default     = "yuvalharel/Pronto"
}

variable "github_environment" {
  description = <<-EOT
    GitHub Environment name that the deploy role trusts. The trust policy is scoped to
    `environment:<this>` rather than to a branch ref, which is what makes the environment's manual
    approval gate enforced by AWS rather than only by GitHub's UI: a workflow that has not passed
    the approval cannot obtain credentials at all.
  EOT
  type        = string
  default     = "production"
}

# ==================================================================================================
# Networking
# ==================================================================================================

variable "vpc_cidr" {
  description = "VPC CIDR. Must be inside RFC 1918 -- ProductionHardeningStartupGuard refuses a TRUSTED_PROXIES entry that is not."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && startswith(var.vpc_cidr, "10.")
    error_message = "vpc_cidr must be a valid CIDR inside 10.0.0.0/8, because its subnet CIDRs become TRUSTED_PROXIES and the application refuses any block outside private address space."
  }
}

variable "public_subnet_cidrs" {
  description = <<-EOT
    Subnets for the ALB and -- in this early-beta topology -- the ECS tasks as well. Two, in two
    AZs, because an ALB requires at least two.

    THESE CIDRs BECOME TRUSTED_PROXIES (see outputs.tf). The ALB's network interfaces draw their
    private addresses from these ranges, and those addresses are what the backend sees as the TCP
    peer. Read the value from the `trusted_proxies` output; never hand-write it.
  EOT
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "private_subnet_cidrs" {
  description = <<-EOT
    Subnets with no route to the internet. Today they hold only RDS.

    They are deliberately created now even though ECS does not use them yet: they are the landing
    ground for the post-beta move of ECS off public IPs. When that happens the change is (1) add a
    NAT gateway and a default route on the private route table, (2) point the ECS service's
    network_configuration at these subnets with assign_public_ip = false. No re-addressing, no new
    subnets, and TRUSTED_PROXIES does not change because the ALB stays where it is.
  EOT
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "alb_deletion_protection" {
  description = "Keep true in a real environment. Set false only to tear the beta down deliberately."
  type        = bool
  default     = true
}

variable "enable_plaintext_http_listener" {
  description = <<-EOT
    DANGEROUS, and false by default. When no ACM certificate is configured there is no HTTPS
    listener, and this decides what port 80 does:

      false (default) -- port 80 returns a fixed 503. The API is unreachable until TLS exists,
                         which is the correct posture for a service whose every request carries a
                         JWT.
      true            -- port 80 forwards to the backend in plaintext. Only for validating the
                         infrastructure before a domain has been chosen, and only against an
                         environment with no real user data. Every token sent to it is exposed.

    Ignored entirely once acm_certificate_arn is set: TLS then terminates on 443 and port 80 becomes
    a redirect.
  EOT
  type        = bool
  default     = false
}

# ==================================================================================================
# DNS / TLS -- BLOCKED until a domain is chosen. Everything below is safe to leave empty.
# ==================================================================================================

variable "domain_name" {
  description = <<-EOT
    Apex domain, e.g. "pronto.example". EMPTY BY DEFAULT AND THAT IS THE EXPECTED STATE: no domain
    has been chosen for Pronto, and this milestone does not invent one.

    Empty means: no Route 53 records, no custom ALB hostname, and CloudFront serves on its own
    *.cloudfront.net domain -- which is already HTTPS with an Amazon certificate, so the frontend is
    fully testable before a domain exists. The backend is the half that is genuinely blocked,
    because its certificate must name a hostname somebody owns.
  EOT
  type        = string
  default     = ""
}

variable "acm_certificate_arn" {
  description = <<-EOT
    ONE certificate, in us-east-1, serving BOTH the ALB's HTTPS listener and CloudFront.

    This used to be two variables and two certificates. CloudFront requires its certificate in
    us-east-1 wherever the rest of the stack lives, so while the primary region was eu-central-1
    there had to be one certificate per region. With the primary region now us-east-1 both consumers
    want a certificate in the same region, and one can serve both -- provided it carries SANs for
    every hostname in use:

        pronto.example          (CloudFront, apex)
        www.pronto.example      (CloudFront, if the www alias is served)
        api.pronto.example      (ALB)

    Request it with `aws acm request-certificate --domain-name <apex>
    --subject-alternative-names www.<apex> api.<apex> --validation-method DNS --region us-east-1`.

    Empty is the expected state until a domain exists: no ALB HTTPS listener is created, and
    CloudFront falls back to its own *.cloudfront.net certificate, which is a real publicly-trusted
    certificate. See enable_plaintext_http_listener for what port 80 does meanwhile.

    IF THE PRIMARY REGION EVER MOVES off us-east-1, this splits back into two variables and two
    certificates, and providers.tf needs its us-east-1 alias back -- CloudFront's requirement does
    not move with the rest of the stack.
  EOT
  type        = string
  default     = ""
}

variable "cors_allowed_origins" {
  description = <<-EOT
    Value of CORS_ALLOWED_ORIGINS. Must be the exact origin the browser loads the frontend from.

    Empty means "use the CloudFront distribution domain", which is what makes the stack coherent
    before a domain exists. Note this list also gates the STOMP WebSocket handshake
    (realtime.config.WebSocketConfig reads the same property), so a mismatch breaks SOS realtime as
    well as REST -- and CorsOriginStartupGuard refuses any non-HTTPS or localhost entry in
    production, so the application will not start with a wrong value rather than misbehaving.
  EOT
  type        = string
  default     = ""
}

variable "api_base_url" {
  description = <<-EOT
    Value of VITE_API_BASE_URL, compiled into the frontend bundle at build time. Empty means "derive
    from domain_name", and when that is empty too the frontend build is BLOCKED -- vite.config.ts
    refuses to build a production bundle without a valid HTTPS non-localhost origin, by design.
  EOT
  type        = string
  default     = ""
}

# ==================================================================================================
# Compute sizing
# ==================================================================================================

variable "backend_cpu" {
  description = "Fargate CPU units. 512 = 0.5 vCPU."
  type        = number
  default     = 512
}

variable "backend_memory" {
  description = "Fargate memory (MiB). backend/Dockerfile's MaxRAMPercentage=65 is sized against this exact figure; changing one without the other is how a task gets OOM-killed."
  type        = number
  default     = 1024
}

variable "backend_desired_count" {
  description = <<-EOT
    MUST BE 1 FOR PRONTO 1.0, and this is a correctness constraint rather than a cost one.

    The application holds per-JVM state that a second task would silently duplicate or split:
      - auth.security.AuthRateLimitInterceptor keeps its counters in a ConcurrentHashMap, so N tasks
        enforce N times the configured limit;
      - realtime.config.WebSocketConfig uses enableSimpleBroker, an in-JVM broker, so an SOS event
        published by one task never reaches a customer connected to the other (REST polling covers
        this, so it degrades rather than breaks);
      - maps.cache.RouteCache is in-process, so each task pays its own provider calls;
      - notifications.scheduler.EmailDispatchJob selects PENDING notifications with no claim or row
        lock before sending, so TWO TASKS SEND EVERY ORDER EMAIL TWICE. That one is a visible
        customer-facing defect, not a degradation.

    Raising this is post-1.0 work that starts with making those four multi-instance safe.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.backend_desired_count == 1
    error_message = "backend_desired_count must be 1 until the single-instance constraints in this variable's description are fixed. Raising it duplicates every order email."
  }
}

# ==================================================================================================
# Database
# ==================================================================================================

variable "db_instance_class" {
  description = "RDS instance class. t4g (Graviton) is the cheapest burstable line."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Initial storage (GiB)."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage" {
  description = "Storage autoscaling ceiling (GiB). Set well above the initial size: a full disk is the most common self-inflicted RDS outage and autoscaling costs nothing until it fires."
  type        = number
  default     = 100
}

variable "db_backup_retention_days" {
  description = "Automated backup retention. Any value above 0 also enables point-in-time recovery. Backup storage up to the size of the database is free."
  type        = number
  default     = 7
}

variable "db_multi_az" {
  description = <<-EOT
    False for the closed beta, deliberately. Multi-AZ roughly doubles the RDS bill to buy automatic
    AZ failover; a beta's cost of a ~30-minute manual restore is well below that. This is a recorded
    deferral to revisit at public launch, not an oversight -- see the deployment runbook's DR
    section for the RTO/RPO it implies.
  EOT
  type        = bool
  default     = false
}

variable "db_master_username" {
  description = "RDS master user. The APPLICATION does not use this -- it connects as db_app_username, which is created manually (runbook). The master exists to create that role and for break-glass."
  type        = string
  default     = "pronto_master"
}

variable "db_app_username" {
  description = "Least-privilege role the application connects as. Created manually against the running instance; Terraform cannot reach a database in a private subnet."
  type        = string
  default     = "pronto_app"
}

variable "db_name" {
  description = "Initial database name."
  type        = string
  default     = "pronto"
}

# ==================================================================================================
# Frontend delivery
# ==================================================================================================

variable "cloudfront_price_class" {
  description = <<-EOT
    PriceClass_200, not the cheaper PriceClass_100. Pronto's users are in Israel, and PriceClass_100
    covers only North America and Europe -- Israeli traffic would be served from Frankfurt. The Tel
    Aviv edge is in PriceClass_200. Saving a few dollars a month by adding a sea crossing to every
    asset request for the entire user base is the wrong trade.
  EOT
  type        = string
  default     = "PriceClass_200"
}

# ==================================================================================================
# Observability
# ==================================================================================================

variable "log_retention_days" {
  description = "CloudWatch Logs retention. The AWS default is 'never expire', which is a slow, silent cost leak. Also bounds how long resolved client IP addresses are held (see AuthRateLimitInterceptor)."
  type        = number
  default     = 30
}

variable "alarm_email" {
  description = "Address subscribed to the alarm SNS topic. Empty creates the topic with no subscription -- alarms then fire into a void, so set it. AWS sends a confirmation email that must be clicked."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  description = "AWS Budgets monthly threshold. Alerts at 50/80/100 percent of this. Does not cap spend -- AWS has no such control -- it only tells you."
  type        = number
  default     = 200
}

variable "sms_verification_required" {
  description = <<-EOT
    Must an account prove its PHONE NUMBER as well as its email address before it can use the
    marketplace? Sets SMS_VERIFICATION_REQUIRED, read by auth.config.VerificationPolicy.

    true  -- the intended rule, and the default. Both channels. A customer cannot create an issue,
             booking or SOS request until the phone is proved, and a professional is not
             discoverable at all. This platform sends a professional to a stranger's home.

    false -- TEMPORARY. AWS End User Messaging production SMS access is not approved for this
             account, so no code can reach an Israeli handset and the rule is unsatisfiable by
             anyone. Email verification stays mandatory either way, and nothing is written to the
             database, so setting this back to true needs no migration.

    Turn it back on only AFTER confirming SMS actually delivers -- re-enabling an unsatisfiable
    requirement recreates the failure this exists to avoid.
  EOT
  type        = bool
  default     = true
}

variable "github_repository_immutable" {
  description = <<-EOT
    The same repository as github_repository, in GitHub's IMMUTABLE OIDC form:
    `<owner>@<ownerId>/<name>@<repoId>`.

    GitHub mints OIDC subject claims with numeric ids so that renaming or transferring a repository
    does not break its cloud trust relationships. Read it from
    `gh api repos/<owner>/<repo>/actions/oidc/customization/sub` -> `sub_claim_prefix`, which
    reports the exact prefix tokens will carry.

    Both spellings are trusted (see iam.tf), because which one GitHub serves depends on the rollout
    and on whether the repository has been transferred. Trusting both is not a widening: each is an
    exact match naming this repository and this environment.
  EOT
  type        = string
  default     = "Pronto-il@321479622/Pronto@1181615069"
}
