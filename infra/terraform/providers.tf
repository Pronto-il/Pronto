# Pronto production infrastructure — provider and version pinning.
#
# Production MS5. Everything under infra/terraform/ describes the Pronto 1.0 / closed-beta
# environment described in docs/production-roadmap/deployment-runbook.md. Nothing here has been
# applied: `terraform apply` is a Stage B action requiring explicit owner approval.

terraform {
  # 1.9 is the floor for the `removed` block and for input-variable validation referencing other
  # values, neither of which is used yet. The real reason for a floor is that `terraform fmt` and
  # provider-schema behaviour drift between majors and a repository this small benefits from one
  # answer to "which Terraform".
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 5.x is the line that has `xff_header_processing_mode` on aws_lb and the
      # `aws_vpc_security_group_ingress_rule` / `_egress_rule` resources this configuration uses
      # instead of inline rule blocks. Pinned to the major so a provider release cannot silently
      # change a plan.
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Every resource in this configuration carries these. The one that earns its keep is
  # ManagedBy=terraform: it is how somebody looking at an unfamiliar console distinguishes a
  # resource that will be reverted by the next apply from one that was created by hand.
  default_tags {
    tags = {
      Project     = "pronto"
      Environment = var.environment_name
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    }
  }
}

# ==================================================================================================
# There is deliberately NO second, us-east-1-aliased provider.
#
# CloudFront requires its ACM certificate in us-east-1 — an AWS constraint, not a preference. While
# the primary region was eu-central-1 that meant a two-region certificate model, and an aliased
# provider was declared here for it. Two things were true about that alias: it was never actually
# referenced by any resource (certificates are supplied as ARNs, not created here), so it was dead
# code; and as of the move to us-east-1 it would be a second provider for the region this
# configuration already targets.
#
# One certificate in us-east-1, carrying SANs for the apex, www and api hostnames, now satisfies
# both the ALB and CloudFront. See var.acm_certificate_arn.
#
# If the primary region ever moves away from us-east-1, this becomes two certificates again and the
# aliased provider comes back — CloudFront's requirement does not move with it.
# ==================================================================================================
