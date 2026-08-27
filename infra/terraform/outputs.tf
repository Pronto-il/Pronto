# Outputs. Non-secret values only.
#
# Nothing here is marked `sensitive` because nothing here is sensitive: endpoints, ARNs, bucket
# names and subnet CIDRs are all operational facts, not credentials. Secret VALUES are never
# produced by this configuration at all (secrets.tf), so there is nothing to accidentally print.

# ==================================================================================================
# The output this milestone exists to produce
# ==================================================================================================

output "trusted_proxies" {
  description = <<-EOT
    The value of TRUSTED_PROXIES, read from the VPC rather than guessed.

    This closes the prerequisite MS1 and MS4 both deferred here ("Configure TRUSTED_PROXIES with
    actual ALB subnet CIDRs"). It is already wired into the ECS task definition, so nothing needs to
    copy it by hand; it is exposed because the deployment runbook's proxy-validation procedure
    checks the running application's startup log against it.

    See the locals block in compute.tf for why these particular CIDRs are the right ones, why subnet
    ranges rather than the ALB's own addresses, and what does and does not change the value.
  EOT
  value       = local.trusted_proxies
}

# ==================================================================================================
# Endpoints
# ==================================================================================================

output "alb_dns_name" {
  description = "Load balancer hostname. Becomes the target of the api.<domain> DNS record once a domain exists."
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "Hosted zone id for a Route 53 alias record pointing at the ALB."
  value       = aws_lb.main.zone_id
}

output "cloudfront_domain_name" {
  description = "The frontend's HTTPS origin. Serves on this name with a valid Amazon certificate even before a custom domain exists."
  value       = aws_cloudfront_distribution.frontend.domain_name
}

output "cloudfront_distribution_id" {
  description = "Needed by the deploy workflow to create a cache invalidation."
  value       = aws_cloudfront_distribution.frontend.id
}

output "rds_endpoint" {
  description = "RDS hostname. Not reachable from outside the VPC by design -- see the runbook for the bastion-free procedure for creating the application role."
  value       = aws_db_instance.main.address
}

output "rds_port" {
  value       = aws_db_instance.main.port
  description = "RDS port."
}

# ==================================================================================================
# Deployment targets
# ==================================================================================================

output "ecr_repository_url" {
  description = "Image registry. The deploy workflow pushes <this>:<git-sha>."
  value       = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster_name" {
  value       = aws_ecs_cluster.main.name
  description = "ECS cluster name, for `aws ecs update-service` and `aws ecs wait services-stable`."
}

output "ecs_service_name" {
  value       = aws_ecs_service.backend.name
  description = "ECS service name."
}

output "frontend_bucket" {
  description = "Target of `aws s3 sync frontend/dist/`."
  value       = aws_s3_bucket.frontend.bucket
}

output "uploads_bucket" {
  description = "Value of STORAGE_S3_BUCKET. Already wired into the task definition; exposed for verification."
  value       = aws_s3_bucket.uploads.bucket
}

output "github_deploy_role_arn" {
  description = <<-EOT
    Set this as the AWS_DEPLOY_ROLE_ARN variable on the GitHub `production` environment.

    An ARN is not a credential and is useless without the OIDC trust relationship, so it is a GitHub
    *variable* rather than a *secret*. No AWS access key exists for this deployment.
  EOT
  value       = aws_iam_role.github_deploy.arn
}

# ==================================================================================================
# Values the runbook's manual steps need
# ==================================================================================================

output "secret_arns" {
  description = "The five secret CONTAINERS. Terraform creates them empty; values are written once, out of band. Until then the ECS task fails to start naming the missing secret."
  value       = { for k, s in aws_secretsmanager_secret.application : k => s.arn }
}

output "rds_master_secret_arn" {
  description = "Secret RDS generated and owns for the master password. Read it to create the least-privilege application role; the application itself never uses this credential."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "backend_task_public_ip_note" {
  description = <<-EOT
    Where to find the egress address that the production Google Maps API key must be restricted to.

    In this NAT-free topology the task's own public IP is the egress address, and it CHANGES every
    time the task is replaced -- which is every deploy. So the Google key cannot be pinned to a
    stable IP today. This is a real consequence of deferring the NAT gateway, it is recorded as such
    in the runbook, and the interim mitigation is API restriction (Geocoding + Routes only) plus
    quotas and budget alerts rather than IP restriction.

    Read the current address with:
      aws ecs list-tasks --cluster <cluster> --service-name <service>
      aws ecs describe-tasks --cluster <cluster> --tasks <arn> \
        --query 'tasks[0].attachments[0].details'
  EOT
  value       = "See description -- the egress IP is not stable without a NAT gateway."
}

output "cors_allowed_origins" {
  description = "Value of CORS_ALLOWED_ORIGINS as deployed. Also gates the STOMP WebSocket handshake, so a mismatch breaks SOS realtime as well as REST."
  value       = local.cors_allowed_origins
}

output "vpc_id" {
  value       = aws_vpc.main.id
  description = "VPC id."
}

output "public_subnet_ids" {
  value       = aws_subnet.public[*].id
  description = "ALB and (in this beta topology) ECS task subnets."
}

output "private_subnet_ids" {
  description = "RDS subnets today; the landing ground for ECS when the NAT gateway is introduced post-beta."
  value       = aws_subnet.private[*].id
}
