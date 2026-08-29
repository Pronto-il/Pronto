# ECR, ECS Fargate, and the Application Load Balancer in front of it.

# ==================================================================================================
# Derived configuration
# ==================================================================================================

locals {
  # ------------------------------------------------------------------------------------------
  # TRUSTED_PROXIES, GENERATED FROM THE ACTUAL VPC.
  #
  # This is the value MS1 and MS4 both deferred to this milestone ("Configure TRUSTED_PROXIES with
  # actual ALB subnet CIDRs"), and generating it is the entire point -- MS4's report says the real
  # values "must be read from the VPC, not guessed", and a hand-written value is a guess even when
  # it happens to be right.
  #
  # WHY THESE CIDRs. The ALB is deployed into the public subnets, so AWS gives it a network
  # interface in each one with a private address drawn from that subnet's range. When the ALB
  # forwards a request, THAT is the TCP source address the backend sees, because
  # server.forward-headers-strategy is left at NONE and so request.getRemoteAddr() is the raw peer.
  # auth.security.ClientIpResolver consults X-Forwarded-For only when the peer falls inside one of
  # these blocks.
  #
  # WHY SUBNET CIDRs RATHER THAN THE ALB's ADDRESSES. An ALB scales its nodes and replaces them
  # during maintenance, drawing new addresses from the same subnets each time. A list of /32s would
  # be correct until the first scaling event and then silently wrong -- at which point every user
  # would share one rate-limit bucket. The subnet range is the granularity that survives scaling.
  #
  # WHY IT IS SAFE THAT THIS IS WIDE. Per MS4's report section 3.3, the test is not "is this block
  # narrow" but "can a stranger's packet arrive with a source address inside it". These are RFC 1918
  # ranges inside a VPC, so an internet client -- whose source address is public -- can never match,
  # however wide the block. ProductionHardeningStartupGuard enforces exactly that property and would
  # refuse a public range here.
  #
  # WHAT DOES CHANGE IT. Adding an availability zone adds a subnet, and that subnet's CIDR must join
  # this list -- which happens automatically, because this reads the subnet resources rather than a
  # literal. Moving the ECS tasks into the private subnets later does NOT change it, because the ALB
  # does not move.
  # ------------------------------------------------------------------------------------------
  trusted_proxies = join(",", aws_subnet.public[*].cidr_block)

  # The origin the browser loads the frontend from. Also gates the STOMP WebSocket handshake --
  # realtime.config.WebSocketConfig reads the same pronto.cors.allowed-origins property -- so a
  # mismatch breaks SOS realtime as well as REST.
  cors_allowed_origins = coalesce(
    var.cors_allowed_origins != "" ? var.cors_allowed_origins : null,
    var.domain_name != "" ? "https://${var.domain_name}" : null,
    "https://${aws_cloudfront_distribution.frontend.domain_name}",
  )

  # SES sender identity. A placeholder on a reserved example domain until a real domain is verified;
  # ProviderModeStartupGuard requires a non-empty value whenever EMAIL_MODE=ses, and SES itself will
  # reject a send from an unverified identity -- so a wrong value fails loudly rather than silently.
  email_from = var.domain_name != "" ? "noreply@${var.domain_name}" : "noreply@pronto.example"
}

# ==================================================================================================
# Image registry
# ==================================================================================================

resource "aws_ecr_repository" "backend" {
  name = "${var.project}-backend"

  # IMMUTABLE, so a tag always means the same bits. Images are tagged with the git SHA that built
  # them, and rollback is "deploy the previous tag" -- which is only a guarantee if a tag cannot be
  # repointed at different content. The cost is that re-running a build for a SHA already pushed
  # fails; the deploy workflow handles that by checking for the tag and skipping the build.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = { Name = "${var.project}-backend" }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  # Ten is enough to roll back through several releases and few enough that storage never becomes a
  # line item worth reading.
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 10 most recent images; expire the rest."
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# ==================================================================================================
# Load balancer
# ==================================================================================================

resource "aws_lb" "main" {
  name               = "${var.project}-alb"
  load_balancer_type = "application"
  internal           = false
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]

  enable_deletion_protection = var.alb_deletion_protection

  # ------------------------------------------------------------------------------------------
  # The two attributes that keep ClientIpResolver's X-Forwarded-For handling sound.
  #
  # xff_header_processing_mode = "append" is the AWS default and is set here explicitly so it is a
  # decision on the record rather than an inherited one. It means the ALB APPENDS the real client
  # address to whatever X-Forwarded-For arrived, rather than preserving the client's version or
  # removing the header.
  #
  # That interacts precisely with how ClientIpResolver reads the chain: it walks RIGHT TO LEFT and
  # takes the right-most hop that is not itself a trusted proxy. So a client that sends
  # "X-Forwarded-For: <victim>" produces "<victim>, <real client>" at the backend, and the resolver
  # returns <real client>. The forged prefix cannot displace the real address.
  #
  # SETTING THIS TO "preserve" WOULD BREAK THAT. The header would then be whatever the client sent,
  # with no appended truth, and the right-most entry would be attacker-chosen. Rate limiting would
  # become spoofable, and no startup guard would notice, because the application's configuration
  # would still be correct -- the load balancer's would not.
  #
  # drop_invalid_header_fields discards malformed headers at the balancer instead of passing them
  # through for the application's parser to interpret.
  # ------------------------------------------------------------------------------------------
  xff_header_processing_mode = "append"
  drop_invalid_header_fields = true

  # Longer than the backend's own graceful-shutdown budget so the ALB is never the component that
  # severs an in-flight request during a deploy.
  idle_timeout = 60

  tags = { Name = "${var.project}-alb" }
}

resource "aws_lb_target_group" "backend" {
  name        = "${var.project}-backend-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # awsvpc networking registers the task ENI's address, not an instance id.

  health_check {
    # READINESS, not liveness and not the aggregate. The readiness group includes the `db`
    # indicator (application.yml), so a task that cannot reach RDS is taken out of the target group
    # rather than left to answer 500s. The liveness group deliberately excludes the database and is
    # used by the ECS container health check instead -- see the task definition below for why the
    # two must not be the same endpoint.
    path                = "/actuator/health/readiness"
    matcher             = "200"
    protocol            = "HTTP"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # The AWS default is 300 seconds. With a single task and a stop-then-start deployment, that would
  # add five minutes of nothing-happening to every deploy while the balancer waited to deregister a
  # task that has already shut down gracefully.
  deregistration_delay = 30

  lifecycle {
    create_before_destroy = true
  }
}

# ---- listeners ---------------------------------------------------------------------------------
#
# Three mutually exclusive shapes, chosen by whether a certificate exists:
#
#   certificate set        -> 443 HTTPS forwards to the backend; 80 redirects to 443.
#   no certificate, flag off (DEFAULT)
#                          -> 80 returns a fixed 503. Nothing is served in plaintext.
#   no certificate, flag on -> 80 forwards in plaintext. Explicit opt-in, for validating
#                             infrastructure before a domain exists. See the variable's warning.

resource "aws_lb_listener" "https" {
  count = local.tls_enabled ? 1 : 0

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = local.certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # Nested conditionals in a dynamic block would be unreadable; three explicit branches are not.
  dynamic "default_action" {
    for_each = local.tls_enabled ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = !local.tls_enabled && var.enable_plaintext_http_listener ? [1] : []
    content {
      # PLAINTEXT. Every JWT sent through this listener is exposed on the wire. Reachable only
      # because an operator explicitly set enable_plaintext_http_listener.
      type             = "forward"
      target_group_arn = aws_lb_target_group.backend.arn
    }
  }

  dynamic "default_action" {
    for_each = !local.tls_enabled && !var.enable_plaintext_http_listener ? [1] : []
    content {
      type = "fixed-response"
      fixed_response {
        content_type = "text/plain"
        status_code  = "503"
        message_body = "TLS is not configured for this environment. See infra/terraform/variables.tf: acm_certificate_arn."
      }
    }
  }
}

# ---- target group association shim -------------------------------------------------------------
#
# ECS CreateService rejects a load-balanced service whose target group is not attached to a load
# balancer:
#
#   InvalidParameterException: The target group with targetGroupArn ... does not have an associated
#   load balancer.
#
# ELBv2 attaches a target group to a load balancer only when some listener action FORWARDS to it.
# Creating the listener is not enough. In the default posture above -- no certificate, no plaintext
# opt-in -- the only default_action is a fixed-response 503, which forwards nowhere, so
# aws_lb_target_group.backend.load_balancer_arns stays empty and the ECS service cannot be created.
# The 503 posture and the service's load_balancer block are otherwise mutually exclusive, which
# means the stack could never fully apply in its own default configuration.
#
# This rule resolves that by giving ELBv2 a forward action to attach on, while serving nothing.
#
# The condition is a source_ip of 192.0.2.0/24 -- TEST-NET-1, reserved by RFC 5737 for documentation
# and not routed on the public internet. Two properties make it unreachable rather than merely
# obscure:
#
#   * ALB evaluates source_ip against the TCP source address of the connection, NOT against
#     X-Forwarded-For (that would be an http-header condition). A client cannot present a source
#     address it does not own and still complete a handshake, so the condition cannot be forged.
#   * No client can legitimately hold a TEST-NET-1 address on the internet in the first place.
#
# So every real request continues to fall through to the default 503. Nothing is served in
# plaintext; the rule exists purely so that ELBv2 records the association.
#
# This exists ONLY in the no-certificate, no-plaintext case, mirroring the fixed-response branch's
# for_each exactly. Once acm_certificate_arn is set, aws_lb_listener.https forwards to the target
# group for real, the association is genuine, and this shim disappears on the next apply.
resource "aws_lb_listener_rule" "backend_tg_association" {
  count = !local.tls_enabled && !var.enable_plaintext_http_listener ? 1 : 0

  listener_arn = aws_lb_listener.http.arn

  # Lowest usable priority: this must never shadow a real rule added later.
  priority = 50000

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  condition {
    source_ip {
      values = ["192.0.2.0/24"]
    }
  }
}

# ==================================================================================================
# ECS
# ==================================================================================================

resource "aws_ecs_cluster" "main" {
  name = "${var.project}-cluster"

  setting {
    # Container Insights bills per custom metric and per ingested log. The free service-level
    # CPU/memory metrics ECS publishes are sufficient for a one-task beta, and observability.tf
    # alarms on those. Enable this when there is enough traffic for the detail to mean something.
    name  = "containerInsights"
    value = "disabled"
  }

  tags = { Name = "${var.project}-cluster" }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${var.project}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory

  execution_role_arn = aws_iam_role.task_execution.arn
  task_role_arn      = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    # X86_64 rather than ARM64. Fargate Graviton is roughly 20% cheaper, but the deploy workflow
    # builds on GitHub's x86 runners, so ARM would mean cross-building under QEMU (slow and a new
    # class of failure) or introducing ARM runners. Recorded in the runbook as a cost option to take
    # once the pipeline is otherwise settled.
    cpu_architecture = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "backend"
    image     = "${aws_ecr_repository.backend.repository_url}:bootstrap"
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    # ---- non-secret configuration -------------------------------------------------------------
    # Everything the application needs that is not a credential. Values, not references, so the
    # deployed configuration is readable in the console without decrypting anything.
    environment = [
      # Anything other than local/test/demo makes common.config.ProntoEnvironment report
      # productionLike and turns on all eight startup guards. Set explicitly rather than relying on
      # "an unrecognised value is treated as production" -- correct, but not something to depend on.
      { name = "PRONTO_ENVIRONMENT", value = "production" },

      { name = "DB_HOST", value = aws_db_instance.main.address },
      { name = "DB_PORT", value = tostring(aws_db_instance.main.port) },
      { name = "DB_NAME", value = var.db_name },
      { name = "DB_USER", value = var.db_app_username },
      # The client half of the TLS pair; the server half is rds.force_ssl in database.tf. Neither
      # alone prevents a silent downgrade.
      { name = "DB_URL_PARAMS", value = "?sslmode=require" },

      { name = "AI_MODE", value = "openai" },
      # Read by pronto.ai.openai.model. Deliberately a plain environment variable and nothing more:
      # no model name appears anywhere in Java, so changing it is this one line and a redeploy.
      #
      # gpt-4.1-mini, raised from gpt-4o-mini for issue classification. The routing prompt asks the
      # model to name the trade a customer needs BEFORE consulting Pronto's catalogue, and to
      # decline to map it when Pronto covers no such trade -- an instruction that only pays off if
      # the model reliably follows a multi-step, negative constraint ("do not pick the nearest
      # entry") rather than defaulting to the helpful-looking answer.
      #
      # Measure before and after with the labelled harness rather than trusting the version number:
      #   PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-… OPENAI_MODEL=gpt-4.1-mini \
      #     mvn test -Dtest=OpenAiClassificationEvaluationRunnerTest
      # Reverting is this line; nothing else in the codebase names a model.
      { name = "OPENAI_MODEL", value = "gpt-4.1-mini" },

      { name = "EMAIL_MODE", value = "ses" },
      { name = "EMAIL_FROM", value = local.email_from },
      { name = "EMAIL_SES_REGION", value = var.aws_region },

      { name = "SMS_MODE", value = "aws" },
      { name = "AWS_SMS_REGION", value = var.aws_region },

      # TEMPORARY (Production MS5). AWS End User Messaging production SMS access is not approved for
      # this account, so no verification code can reach an Israeli handset. With the requirement on,
      # every new account would register, log in, and then be refused at the first real action --
      # an unreachable rule that reads as a bug rather than a policy.
      #
      # This does NOT weaken email verification, which stays mandatory, and it writes nothing to the
      # database: accounts created while it is false keep phone_verified = false, so flipping it
      # back asks exactly the right people to prove exactly the right thing. Set to "true" once AWS
      # approves production SMS -- confirm delivery works BEFORE flipping it, or the trap returns.
      #
      # Hardcoded "false" rather than tostring(var.sms_verification_required): this is the same SMS
      # sandbox condition as AUTH_OTP_REQUIRED below, and the two are being disabled together as one
      # deliberate operator decision, not derived from a variable whose default (true) still applies
      # anywhere else it might be read from. Re-enable by restoring tostring(var.sms_verification_required)
      # once AWS approves production SMS -- see the note above.
      { name = "SMS_VERIFICATION_REQUIRED", value = "false" },

      # TEMPORARY, pre-user stage (see auth.config.AuthOtpPolicy's own Javadoc). AWS End User
      # Messaging is still SMS-sandboxed with an exhausted monthly spend quota, so a login OTP
      # cannot reliably be delivered -- an undeliverable second factor is not security, it is a
      # locked door with the key on the wrong side. Password verification, account lockout, login
      # rate limiting, JWT issuance/validation and every route guard are all unchanged; this removes
      # exactly one step from POST /api/auth/login. (It used to be true to add "and the
      # email-verified requirement" here; EMAIL_VERIFICATION_REQUIRED below now relaxes that
      # separately, so this line no longer claims it.)
      # Re-enable with AUTH_OTP_REQUIRED=true (or remove this line, since application.yml's own
      # default is already "true") once SMS delivery is confirmed working again.
      { name = "AUTH_OTP_REQUIRED", value = "false" },

      # TEMPORARY, closed beta (see auth.config.VerificationPolicy's own Javadoc). AWS SES is still
      # in the SANDBOX and Production Access has not been approved, so SES rejects every recipient
      # that has not itself been individually verified in the console. With the requirement on, a
      # real user registers, the account is created, and the request then fails with
      # OTP_DELIVERY_FAILED -- and every subsequent login re-issues the same undeliverable code.
      # Nobody outside the console allowlist can get into the product at all.
      #
      # This writes nothing to the database: accounts created while it is false keep
      # email_verified = false, exactly like the SMS half above, so flipping it back asks exactly
      # the right people to prove exactly the right thing with no migration.
      #
      # IT IS A REAL RELAXATION: while this is false an account's email address is unproved, and a
      # typo'd or someone else's address reaches the marketplace. Accepted deliberately so the
      # closed beta can happen at all.
      #
      # Hardcoded "false" rather than a variable, for the same reason as SMS_VERIFICATION_REQUIRED:
      # this is one deliberate operator decision tied to a specific provider-sandbox condition, not
      # a setting anything else should derive. DELETE THIS LINE the moment SES Production Access is
      # approved -- application.yml's own default is already "true", so removal is the whole
      # reversal.
      { name = "EMAIL_VERIFICATION_REQUIRED", value = "false" },

      # THE MASTER SWITCH, set explicitly rather than left to its default (see
      # auth.config.OtpVerificationPolicy). It does not replace the three flags above, it GATES
      # them: every policy bean reports `master AND own-flag`, so with this false the platform
      # issues no one-time password on any path regardless of what the other three say.
      #
      # Set here even though the outcome is already "OTP off" via those three, because a reader
      # answering "does Production verify anybody?" should find one line that says so rather than
      # having to know that three separate flags happen to all be false and that a fourth,
      # unset variable defaults to true and ANDs over them. The default is `true`, so leaving it
      # out means the file's most consequential auth setting is the one it does not mention.
      #
      # Reversing the beta is now ONE variable: OTP_VERIFICATION_ENABLED=true restores whatever the
      # three flags above say, which is what makes deleting them individually safe later.
      # OtpVerificationPolicy logs the resolved state at WARN on every boot, and accepts only the
      # exact strings "true"/"false" -- anything else refuses to start rather than guessing.
      { name = "OTP_VERIFICATION_ENABLED", value = "false" },

      { name = "MAPS_MODE", value = "google" },

      { name = "STORAGE_MODE", value = "s3" },
      { name = "STORAGE_S3_BUCKET", value = aws_s3_bucket.uploads.bucket },
      { name = "STORAGE_S3_REGION", value = var.aws_region },

      { name = "CORS_ALLOWED_ORIGINS", value = local.cors_allowed_origins },

      # Generated from the VPC -- see the locals block at the top of this file.
      { name = "BEHIND_PROXY", value = "true" },
      { name = "TRUSTED_PROXIES", value = local.trusted_proxies },

      { name = "SERVER_PORT", value = "8080" },
    ]

    # ---- secrets ------------------------------------------------------------------------------
    # Resolved by the EXECUTION role at task start and injected as environment variables. The values
    # never appear in the task definition, in Terraform state, or in the console.
    secrets = [
      { name = "DB_PASSWORD", valueFrom = aws_secretsmanager_secret.application["db_password"].arn },
      { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.application["jwt_secret"].arn },
      { name = "OTP_PEPPER", valueFrom = aws_secretsmanager_secret.application["otp_pepper"].arn },
      { name = "OPENAI_API_KEY", valueFrom = aws_secretsmanager_secret.application["openai_api_key"].arn },
      { name = "MAPS_API_KEY", valueFrom = aws_secretsmanager_secret.application["maps_api_key"].arn },
    ]

    # ---- container health check ---------------------------------------------------------------
    # LIVENESS, deliberately not readiness and not the aggregate. This check restarts the container
    # when it fails, so it must answer "is this process broken beyond recovery" and nothing else.
    #
    # Pointing it at readiness -- which includes the database -- would mean a sixty-second RDS
    # failover kills a perfectly healthy JVM, and because Flyway runs at startup and also needs the
    # database, the replacement would fail to boot too. A recoverable dependency blip would become
    # an unrecoverable crash loop. The ALB's health check is the one that watches the database, and
    # it merely stops sending traffic.
    #
    # curl is installed in backend/Dockerfile for exactly this command.
    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health/liveness || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 120 # Flyway plus Spring context startup; failures before this do not count.
    }

    # Longer than spring.lifecycle.timeout-per-shutdown-phase (20s in application.yml), so Spring's
    # graceful shutdown always finishes before ECS escalates to SIGKILL. Equal values would be a
    # race whose loser is an in-flight request.
    stopTimeout = 30

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.backend.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "backend"
      }
    }
  }])

  tags = { Name = "${var.project}-backend" }

  # Old revisions stay ACTIVE. Changing a task definition replaces it, and the provider's default is
  # to deregister the one it replaces -- but the SERVICE may be running that revision, because
  # aws_ecs_service ignores task_definition so CI can own the deployed one. Deregistering a revision
  # a running service points at does not stop the running task; it stops ECS being able to start a
  # REPLACEMENT for it, which turns the next task recycle into an outage. skip_destroy makes the old
  # revision merely superseded.
  skip_destroy = true

  # ------------------------------------------------------------------------------------------
  # OWNERSHIP. Terraform owns this task definition's SHAPE -- environment, secrets, sizing, health
  # check, logging. CI owns only which IMAGE is deployed, by registering its own revision and
  # pointing the service at it.
  #
  # `ignore_changes = [container_definitions]` used to be here so a terraform apply could not revert
  # CI's image to the placeholder above. It worked, and it also froze every environment variable in
  # the same blob: container_definitions is ONE attribute holding both the image (CI's) and the env
  # (Terraform's), so ignoring it to protect the former discarded the latter. Combined with the
  # deploy workflow copying the LIVE task definition and changing only the image, neither side could
  # ever change an environment variable again -- each deferring to the other. CORS_ALLOWED_ORIGINS
  # sat at a stale CloudFront origin through an entire domain migration because of it.
  #
  # The fix is to remove the ignore and change where CI reads from: it now derives its revision from
  # the FAMILY's latest (which Terraform has just updated) rather than from the service's current
  # one. Terraform's env changes therefore reach production on the next deploy, and Terraform still
  # never reverts CI's image, because it does not touch the service's task_definition at all.
  #
  # The placeholder image below is expected to be stale in state and that is harmless: no apply
  # points the service at it.
  # ------------------------------------------------------------------------------------------
}

resource "aws_ecs_service" "backend" {
  name            = "${var.project}-backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  launch_type     = "FARGATE"

  # ONE. See variables.tf's backend_desired_count for the four pieces of per-JVM state that make
  # this a correctness constraint rather than a cost decision -- the sharpest being that two tasks
  # send every order email twice.
  desired_count = var.backend_desired_count

  # ---- stop-then-start, not rolling ---------------------------------------------------------
  #
  # 0/100 means ECS stops the running task before starting its replacement. That is the opposite of
  # the usual advice and it is correct here: at any moment there is at most one task, so there is
  # exactly one Flyway migrator, exactly one set of scheduled jobs, and never two code versions
  # against one schema.
  #
  # The cost is roughly 60-120 seconds of downtime per deploy (task stop, image pull, Flyway, Spring
  # startup, two healthy health checks). That is an accepted trade for a closed beta. Zero-downtime
  # deployment is blocked on making the application multi-instance safe, not on this setting.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets         = aws_subnet.public[*].id
    security_groups = [aws_security_group.app.id]

    # PUBLIC IP, FOR OUTBOUND ONLY. This is the NAT-gateway-avoidance decision documented at the top
    # of network.tf: the task needs to reach OpenAI, Google Maps, SES, SNS, ECR and Secrets Manager,
    # and a public IP on the internet gateway does that for nothing where a NAT gateway costs ~$33
    # per month.
    #
    # It does NOT make the backend reachable from the internet. aws_vpc_security_group_ingress_rule
    # .app_from_alb admits port 8080 from the ALB's security group and from nothing else, and there
    # is no rule anywhere admitting 0.0.0.0/0 to this task. That rule is load-bearing here in a way
    # it would not be in a private subnet -- read its comment before changing it.
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }

  # Flyway runs 52 migrations against an empty database on the first deploy, before the port binds.
  # Without a grace period the ALB would start failing health checks against a task that has not
  # finished migrating and ECS would kill it, which on an empty database is an unbootable loop.
  health_check_grace_period_seconds = 120

  # ECS CreateService requires that aws_lb_target_group.backend already be ATTACHED to a load
  # balancer -- and ELBv2 attaches a target group only when some listener action forwards to it.
  # Listener existence alone is NOT sufficient, which is what an earlier version of this comment got
  # wrong and what made the first production apply fail with:
  #
  #   InvalidParameterException: The target group with targetGroupArn ... does not have an
  #   associated load balancer.
  #
  # Each of the three listener postures attaches the target group somewhere different, so all three
  # are listed. Counted resources contribute nothing when their count is 0, so this is correct in
  # every posture rather than only the current one:
  #
  #   certificate set        -> aws_lb_listener.https forwards.
  #   no cert, plaintext on  -> aws_lb_listener.http forwards.
  #   no cert, plaintext off -> aws_lb_listener_rule.backend_tg_association forwards (see its
  #                             comment -- the http listener itself only returns a 503).
  depends_on = [
    aws_lb_listener.http,
    aws_lb_listener.https,
    aws_lb_listener_rule.backend_tg_association,
  ]

  lifecycle {
    # CI owns the deployed revision; Terraform owns the shape of the service. Without this, an
    # unrelated `terraform apply` would roll the service back to whatever revision Terraform last
    # recorded -- silently redeploying an old build.
    ignore_changes = [task_definition]
  }

  tags = { Name = "${var.project}-backend" }
}
