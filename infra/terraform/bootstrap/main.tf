# Terraform state backend — run this ONCE, before the main configuration.
#
# This is a separate root module with LOCAL state, and it has to be: the main configuration keeps
# its state in the bucket this module creates, and a configuration cannot store its state in a
# bucket it has not created yet. That circularity is the only reason this directory exists.
#
#     cd infra/terraform/bootstrap
#     terraform init
#     terraform apply
#     # then write the printed values into infra/terraform/backend.hcl (git-ignored)
#
# The local terraform.tfstate this produces describes one S3 bucket and contains no secret. Keep it
# — losing it means Terraform no longer knows it owns the bucket — but it is not the state that
# matters operationally.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "pronto"
      ManagedBy = "terraform"
      Purpose   = "terraform-state"
    }
  }
}

variable "aws_region" {
  description = "Must match the main configuration's region."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  type    = string
  default = "pronto"
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "state" {
  # The account id makes the name globally unique without a random suffix — which matters here
  # because unlike the application buckets, this name has to be written into a config file by hand,
  # so it should be derivable rather than looked up.
  bucket = "${var.project}-tfstate-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    # Destroying this orphans every resource the main configuration manages: Terraform would lose
    # all record of owning them, while they continue to exist and bill.
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    # The single most valuable setting in this file. A corrupted or truncated state file is
    # recoverable only from a previous version, and the moment you need one is the moment you
    # cannot create one.
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  # State contains every ARN, endpoint and subnet CIDR in the deployment. It contains no secret
  # value — see the main configuration's secrets.tf for how that is arranged — but it is still a
  # complete map of the infrastructure and is not for anyone else to read.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket     = aws_s3_bucket.state.id
  depends_on = [aws_s3_bucket_versioning.state]

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}

    # Ninety days of history is far more than any state-recovery scenario needs, and stops every
    # apply from accumulating a version forever.
    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# NO DYNAMODB LOCK TABLE. Terraform 1.10+ locks with an S3 lockfile (`use_lockfile = true` in the
# backend config), which needs no extra resource and costs nothing. The DynamoDB table that older
# guides create exists only to hold a lock and is now redundant. If the toolchain in use is older
# than 1.10, add a table here with a "LockID" string hash key and swap `use_lockfile` for
# `dynamodb_table` in the main configuration's backend.hcl.

output "backend_hcl" {
  description = "Write this into infra/terraform/backend.hcl (git-ignored), then run `terraform init -backend-config=backend.hcl` in the parent directory."
  value       = <<-EOT
    bucket       = "${aws_s3_bucket.state.bucket}"
    key          = "production/terraform.tfstate"
    region       = "${var.aws_region}"
    encrypt      = true
    use_lockfile = true
  EOT
}
