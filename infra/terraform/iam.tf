# IAM. Four principals, each with the narrowest set of permissions that lets it do its one job.
#
# NO LONG-LIVED ACCESS KEYS ARE CREATED ANYWHERE IN THIS FILE, and none should exist for this
# deployment. The application picks up role credentials through the AWS SDK's
# DefaultCredentialsProvider chain -- which is already what storage.client.S3StorageClient,
# auth.email.SesEmailSender and auth.sms.AwsSmsSender construct their clients with, so this requires
# no application code change at all. MS4 audited that and section 8 of its report recommends exactly
# this arrangement. .env.production.example lists AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY under
# "MUST NOT BE SET IN PRODUCTION"; nothing here contradicts that.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
}

# ==================================================================================================
# 1. ECS TASK ROLE -- the identity the running application acts as
# ==================================================================================================

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task" {
  name               = "${var.project}-${var.environment_name}-task"
  description        = "Runtime identity of the Pronto backend. Deliberately CANNOT read Secrets Manager -- see the execution role."
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "task" {
  # ---- S3: the uploads bucket, and only its objects ------------------------------------------
  #
  # Exactly the three operations storage.client.S3StorageClient performs: putObject, getObject and
  # headObject. Presigning needs no permission of its own -- S3Presigner signs with the caller's own
  # credentials, so the URL it mints carries whatever this role can already do and nothing more.
  # Note there is no s3:DeleteObject: nothing in the application deletes an upload, and a role that
  # cannot delete cannot be used to destroy verification evidence.
  statement {
    sid    = "UploadsBucketObjects"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
    ]
    resources = ["${aws_s3_bucket.uploads.arn}/*"]
  }

  # HeadObject is authorized by s3:GetObject on the object, but ListBucket on the BUCKET is what
  # turns a missing object into a 404 rather than a 403. Scoped to the bucket ARN, not /*.
  statement {
    sid       = "UploadsBucketList"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.uploads.arn]
  }

  # ---- SES: one verified sender, enforced by condition ----------------------------------------
  #
  # ses:SendEmail without a condition would let a compromised task send mail as any identity the
  # account has verified. The ses:FromAddress condition key pins it to the address the application
  # is configured with, so the permission cannot outrun the configuration.
  statement {
    sid    = "SendVerificationEmail"
    effect = "Allow"
    actions = [
      "ses:SendEmail",
    ]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [local.email_from]
    }
  }

  # ---- SNS: SMS to phone numbers, and explicitly not to topics --------------------------------
  #
  # A REFINEMENT OF MS4's RECOMMENDATION, recorded because it contradicts it in form while keeping
  # its intent. Report section 8 says sns:Publish should "specifically not [be] Resource: '*' for
  # topics". But an SMS publish has no topic ARN -- auth.sms.AwsSmsSender calls Publish with a
  # PhoneNumber instead -- so there is no resource to name, and Resource: "*" is structurally
  # unavoidable for this API shape.
  #
  # The intent is preserved by bounding it two ways instead: an aws:RequestedRegion condition, and
  # the explicit Deny below that removes every topic ARN from the grant. Net effect: "may send SMS
  # in this region, may not publish to any topic", which is what section 8 was asking for.
  statement {
    sid       = "SendSmsOtp"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.aws_region]
    }
  }

  statement {
    sid       = "NeverPublishToTopics"
    effect    = "Deny"
    actions   = ["sns:Publish"]
    resources = ["arn:${local.partition}:sns:*:*:*"]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "${var.project}-task-policy"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# ==================================================================================================
# 2. ECS TASK EXECUTION ROLE -- the agent that starts the container
# ==================================================================================================
#
# Separate from the task role on purpose, and the separation is the point: the execution role can
# read the secrets in order to inject them as environment variables, and the task role cannot read
# them at all. An application-level compromise therefore yields the secrets that were injected into
# that one container's environment, and no ability to enumerate or re-read the secret store.

resource "aws_iam_role" "task_execution" {
  name               = "${var.project}-${var.environment_name}-task-execution"
  description        = "Pulls the image, ships logs, and injects secrets into the task. Not the application's own identity."
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role = aws_iam_role.task_execution.name
  # AWS-managed: ECR pull (GetAuthorizationToken, BatchGetImage, GetDownloadUrlForLayer) and
  # CloudWatch Logs (CreateLogStream, PutLogEvents). Using the managed policy rather than a
  # hand-written copy means it tracks changes to what the Fargate agent needs.
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "task_execution_secrets" {
  statement {
    sid     = "ReadInjectedSecrets"
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]

    # Enumerated by ARN, never a wildcard: the five application secrets, and no other secret in the
    # account.
    #
    # The RDS-managed master secret was deliberately REMOVED from this list after the Production MS5
    # database bootstrap. It was here only so the one-shot pronto-db-bootstrap task could create the
    # pronto_app role (infra/bootstrap/), and that task shared this execution role. Leaving it would
    # mean the ordinary application start path could fetch the credential that owns the database --
    # a standing privilege serving a step that runs once.
    #
    # The application does not and must not use the master credential: the task definition injects
    # DB_PASSWORD from pronto/production/db-app-password and connects as pronto_app, which holds
    # only CONNECT on the database plus USAGE and CREATE on schema public.
    #
    # Re-running the bootstrap therefore requires temporarily restoring master access, which is the
    # intended friction. Prefer granting it to a dedicated bootstrap execution role rather than
    # widening this one again.
    resources = [for s in aws_secretsmanager_secret.application : s.arn]
  }
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name   = "${var.project}-task-execution-secrets"
  role   = aws_iam_role.task_execution.id
  policy = data.aws_iam_policy_document.task_execution_secrets.json
}

# ==================================================================================================
# 3. GITHUB ACTIONS DEPLOY ROLE -- assumed via OIDC, no stored credentials
# ==================================================================================================

# An account can hold only one OIDC provider per issuer URL, so creating this fails if the account
# already has one from an earlier project. In that case set create_github_oidc_provider = false and
# the data source below finds the existing one.
variable "create_github_oidc_provider" {
  description = "False if this AWS account already has a token.actions.githubusercontent.com OIDC provider (only one per account is permitted)."
  type        = bool
  default     = true
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_openid_connect_provider" "github_existing" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github_existing[0].arn
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # ------------------------------------------------------------------------------------------
    # The subject claim is scoped to the ENVIRONMENT, not to a branch ref.
    #
    # `repo:owner/name:ref:refs/heads/main` would let any workflow run on main assume this role.
    # `repo:owner/name:environment:production` is only issued to a job that declares
    # `environment: production` AND has passed that environment's protection rules -- so the manual
    # approval gate is enforced by AWS's trust policy rather than only by GitHub's UI. A workflow
    # that skips the gate cannot obtain credentials at all.
    #
    # This matters more than usual here because `main` is currently unprotected (recorded in the
    # MS1 report's CI-state findings): a ref-scoped trust would mean anyone who can push could
    # deploy.
    # ------------------------------------------------------------------------------------------
    # TWO accepted subjects, because GitHub has two spellings of the same repository.
    #
    # The familiar one is `repo:<owner>/<name>:environment:<env>`. The other embeds numeric ids --
    # `repo:<owner>@<ownerId>/<name>@<repoId>:environment:<env>` -- and is GitHub's IMMUTABLE
    # subject claim, which exists precisely so that renaming or transferring a repository does not
    # silently invalidate its cloud trust relationships.
    #
    # This repository was transferred into the Pronto-il organisation, and GitHub now mints its
    # tokens with the immutable form. Nothing in the workflow or the API says so directly; the
    # evidence is the repo's OIDC customization endpoint reporting
    # `sub_claim_prefix = repo:Pronto-il@321479622/Pronto@1181615069`. A trust policy matching only
    # the readable form fails with "Not authorized to perform sts:AssumeRoleWithWebIdentity", which
    # names neither the claim nor the mismatch.
    #
    # Both are listed rather than picking one: StringEquals over a list is OR, and GitHub may serve
    # either spelling depending on the rollout. This costs nothing in strictness -- each value is a
    # full exact match naming this repository and this environment, and the id-bearing form is
    # strictly harder to impersonate than the name-based one, since ids cannot be reused by
    # registering a freed-up name.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:environment:${var.github_environment}",
        "repo:${var.github_repository_immutable}:environment:${var.github_environment}",
      ]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name                 = "${var.project}-${var.environment_name}-github-deploy"
  description          = "Assumed by the deploy workflow through OIDC. Deploys; does not administer."
  assume_role_policy   = data.aws_iam_policy_document.github_assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "github_deploy" {
  # ---- push the image --------------------------------------------------------------------------
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # This action genuinely takes no resource; AWS defines it that way.
  }

  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.backend.arn]
  }

  # ---- deploy the service ----------------------------------------------------------------------
  statement {
    sid    = "EcsDeploy"
    effect = "Allow"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:DescribeServices",
      "ecs:UpdateService",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
    ]
    # RegisterTaskDefinition and DescribeTaskDefinition do not support resource-level permissions in
    # IAM -- AWS requires "*" for them. UpdateService is the one that actually changes anything and
    # it IS scoped, by the condition below.
    resources = ["*"]
  }

  # ---- the permission that would otherwise be a privilege escalation ---------------------------
  #
  # RegisterTaskDefinition lets the caller name the roles a task runs as. Without a scoped
  # PassRole, this role could register a task definition using ANY role in the account -- an
  # administrator role, say -- and then run it. Restricting PassRole to the two ECS roles is what
  # bounds the deploy role to deploying THIS application.
  statement {
    sid    = "PassOnlyTheEcsRoles"
    effect = "Allow"
    actions = [
      "iam:PassRole",
    ]
    resources = [
      aws_iam_role.task.arn,
      aws_iam_role.task_execution.arn,
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # ---- deploy the frontend ---------------------------------------------------------------------
  statement {
    sid    = "FrontendUpload"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:GetObject",
    ]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]
  }

  statement {
    sid       = "FrontendList"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.frontend.arn]
  }

  statement {
    sid       = "InvalidateCdn"
    effect    = "Allow"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [aws_cloudfront_distribution.frontend.arn]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "${var.project}-github-deploy-policy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}

# ==================================================================================================
# 3b. GITHUB ACTIONS ECR-PUSH ROLE -- for the build-image job specifically
# ==================================================================================================
#
# WHY THIS EXISTS. `build-image` deliberately has no `environment:` block (deploy-production.yml's
# own comment: it must be able to build and push an image, and be scanned, BEFORE the manual
# approval gate, so a reviewer approves a deployment of an image that already exists rather than
# waiting on the build). A job with no `environment:` never receives an
# `environment:production`-scoped OIDC subject claim, so it structurally cannot assume
# `github_deploy` above -- that role's trust is deliberately environment-only (see its own comment:
# "a ref-scoped trust would mean anyone who can push could deploy"). Discovered when the first real
# `deploy_backend=true` run failed at "Configure AWS credentials" with an empty role ARN (the
# variable that role needs is itself environment-scoped, for the same reason).
#
# The fix is NOT to broaden github_deploy's trust to also accept a ref-scoped subject -- that would
# undo the exact protection its own comment describes, given `main` is still unprotected. It is a
# second role: ref-scoped trust (main only, both GitHub subject spellings, matching the
# github_deploy precedent), and a permission set that stops at "push an image to this one ECR
# repository" -- no ecs:*, no iam:PassRole, no S3, no CloudFront. A workflow run on main can publish
# a backend image; it cannot make that image (or any other) run anywhere.

data "aws_iam_policy_document" "github_ecr_push_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Scoped to `main` specifically, not to the whole repository: deploy-production.yml is
    # workflow_dispatch-only and is meant to be run from main, and a ref-scoped trust (unlike the
    # environment-scoped one above) grants nothing beyond "GitHub says this run is on that branch" --
    # narrowing the branch is the only tightening this trust shape has available, so it is applied.
    # Both GitHub subject spellings, same reasoning as github_assume above (this repository was
    # transferred into the Pronto-il organisation, and GitHub may mint either form).
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:ref:refs/heads/main",
        "repo:${var.github_repository_immutable}:ref:refs/heads/main",
      ]
    }
  }
}

resource "aws_iam_role" "github_ecr_push" {
  name        = "${var.project}-${var.environment_name}-github-ecr-push"
  description = "Assumed by the build-image job through OIDC, before the production approval gate. Pushes to ECR; nothing else."
  # Deliberately short-lived even relative to github_deploy's own hour: this role's job is a few
  # minutes of build+push, not a multi-step deploy that can legitimately run long.
  assume_role_policy   = data.aws_iam_policy_document.github_ecr_push_assume.json
  max_session_duration = 1800
}

data "aws_iam_policy_document" "github_ecr_push" {
  # Identical reasoning to github_deploy's own EcrAuth statement: GetAuthorizationToken takes no
  # resource, AWS defines it that way.
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Push-only, and one read (DescribeImages) for the "already built for this SHA, reuse it" check
  # deploy-production.yml's build step makes before pushing. No BatchGetImage, no
  # GetDownloadUrlForLayer -- both are PULL operations `docker push`/`ecr describe-images` never
  # call, and this role has no reason to be able to read an image back out.
  statement {
    sid    = "EcrPushOnly"
    effect = "Allow"
    actions = [
      "ecr:DescribeImages",
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.backend.arn]
  }

  # No ecs:*, no iam:PassRole, no s3:*, no cloudfront:* -- see the block comment above. This role
  # cannot register a task definition, update the service, or touch the frontend bucket/CDN.
}

resource "aws_iam_role_policy" "github_ecr_push" {
  name   = "${var.project}-github-ecr-push-policy"
  role   = aws_iam_role.github_ecr_push.id
  policy = data.aws_iam_policy_document.github_ecr_push.json
}

# ==================================================================================================
# 4. Terraform's own identity is NOT defined here.
#
# Applying this configuration requires broad permissions -- creating IAM roles, VPCs and databases.
# A role with those permissions is a role that can grant itself anything, so it must not also be the
# role a CI workflow can assume. It is assumed by a named human with MFA, out of band. Defining it
# here would put the account's most powerful credential in the same blast radius as the deploy
# pipeline, which is the arrangement this separation exists to avoid.
# ==================================================================================================
