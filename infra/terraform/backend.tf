# Remote state.
#
# State for this configuration contains the RDS endpoint, every ARN, and the subnet CIDRs that
# become TRUSTED_PROXIES. It contains no secret VALUES — see secrets.tf for how that is arranged and
# why it is arranged that way — but it is still not something to keep on one laptop, because two
# people running apply against divergent local state is how infrastructure gets duplicated or
# orphaned.
#
# The bucket is created by infra/terraform/bootstrap/, which is a separate root module with local
# state precisely because it cannot store its state in a bucket it has not created yet.
#
# LOCKING: this uses S3 native lockfile locking (`use_lockfile`), not a DynamoDB table. Terraform
# 1.10+ supports it, it costs nothing, and it removes a resource that existed only to hold a lock.
# If the toolchain in use is older than 1.10, replace `use_lockfile` with a `dynamodb_table`
# argument and add the table to bootstrap/.
#
# Values are deliberately NOT hardcoded here: the bucket name embeds an AWS account id, which does
# not belong in source control. Initialise with a backend config file that is git-ignored:
#
#     terraform init -backend-config=backend.hcl
#
# where backend.hcl (never committed) contains:
#
#     bucket       = "pronto-tfstate-<account-id>"
#     key          = "production/terraform.tfstate"
#     region       = "us-east-1"
#     encrypt      = true
#     use_lockfile = true

terraform {
  backend "s3" {}
}
