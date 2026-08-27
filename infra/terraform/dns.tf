# Route 53 -- authoritative DNS for the Pronto production domain.
#
# Production MS5, Stage 2. The domain prontohomeservice.com was purchased at an EXTERNAL registrar,
# not through Route 53 Domains, so creating this zone does not by itself make Route 53 authoritative.
# The registrar's name servers must be repointed at the four this zone publishes, by hand, once.
# Until that delegation is live:
#
#   * queries for the domain still go to the registrar's name servers
#   * records created here resolve for nobody
#   * ACM DNS validation cannot complete, because ACM resolves the validation record publicly
#
# That is why the certificate and every alias record live in a SECOND apply. Creating them before
# delegation is active would not fail cleanly -- aws_acm_certificate_validation would simply block
# until it timed out, leaving a PENDING_VALIDATION certificate behind.
#
# See docs/production-roadmap/deployment-runbook.md for the delegation step.

locals {
  # KNOWN AT PLAN TIME. Both operands are input variables, so this is safe in `count` and `for_each`.
  #
  # This distinction is the whole reason there are two locals instead of one. Terraform must be able
  # to evaluate count before it talks to any provider, so a count that reads
  # aws_acm_certificate_validation.main[0].certificate_arn -- unknown until apply -- fails with
  # "the count depends on resource attributes that cannot be determined until apply". Every
  # count/for_each keys off tls_enabled; only actual argument VALUES key off certificate_arn.
  tls_enabled = var.acm_certificate_arn != "" || var.domain_name != ""

  # Every hostname the certificate covers, in one place, derived only from configuration so it is
  # usable as a for_each key. The certificate's domain_name plus its SANs must equal this list.
  certificate_domains = var.domain_name == "" ? [] : [
    var.domain_name,
    "www.${var.domain_name}",
    "api.${var.domain_name}",
  ]

  # MAY ONLY BE KNOWN AFTER APPLY. Never use this in count or for_each.
  #
  # Prefers an externally-supplied certificate so the override still works, otherwise the one
  # Terraform creates and validates. Reading it from the _validation resource rather than from
  # aws_acm_certificate is deliberate: it is what orders the HTTPS listener and the CloudFront alias
  # behind "ACM has actually issued this", instead of merely "a certificate object exists".
  certificate_arn = var.acm_certificate_arn != "" ? var.acm_certificate_arn : (
    var.domain_name != "" ? aws_acm_certificate_validation.main[0].certificate_arn : ""
  )
}

resource "aws_route53_zone" "main" {
  # Counted on domain_name for the same reason every other domain-dependent resource is: the
  # configuration must remain applyable with no domain at all, which is the state it shipped in.
  count = var.domain_name == "" ? 0 : 1

  name    = var.domain_name
  comment = "Authoritative DNS for ${var.project} production. Registrar delegation is manual."

  tags = { Name = var.domain_name }
}

# ==================================================================================================
# TLS certificate
# ==================================================================================================
#
# ONE certificate in us-east-1 serving BOTH consumers, which works only because the whole stack is
# in us-east-1. CloudFront requires its certificate in us-east-1 regardless of where everything else
# lives; the ALB requires its certificate in its own region. Those two demands coincide here and
# would not if the primary region ever moved -- see providers.tf.
#
# Previously this ARN was supplied by hand through var.acm_certificate_arn, from a certificate
# requested outside Terraform. Terraform now owns the whole lifecycle, so nothing has to be copied
# between a console and a tfvars file, and renewal validation records cannot silently go missing.
# The variable survives as an override for a certificate this configuration does not manage.

resource "aws_acm_certificate" "main" {
  count = var.domain_name == "" || var.acm_certificate_arn != "" ? 0 : 1

  # Both derived from local.certificate_domains rather than written out again, so the names on the
  # certificate and the names the validation records are created for cannot drift apart. Element 0
  # is the apex; the rest are SANs:
  #   www.<apex>  -- CloudFront's second alias (frontend.tf serves both apex and www)
  #   api.<apex>  -- the ALB
  domain_name               = local.certificate_domains[0]
  subject_alternative_names = slice(local.certificate_domains, 1, length(local.certificate_domains))
  validation_method         = "DNS" # never EMAIL: it depends on a mailbox nobody reads and cannot renew unattended

  # The certificate must be replaced to change its names, and a listener cannot be left pointing at a
  # deleted one. Create the replacement first, re-point, then destroy.
  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${var.project}-${var.environment_name}" }
}

# One CNAME per name on the certificate.
#
# for_each is keyed on the HOSTNAMES FROM CONFIG, not on anything read back off the certificate.
# The obvious formulation -- iterating domain_validation_options and keying on a field of it -- is
# what the provider documentation shows, and it fails here:
#
#   Error: Invalid for_each argument
#   The "for_each" map includes keys derived from resource attributes that cannot be determined
#   until apply
#
# Terraform must know the full set of instance keys before it can build a plan, and every field of
# domain_validation_options is unknown until ACM has actually issued the request. Keys therefore
# come from local.certificate_domains, which is pure configuration; only the record VALUES are
# resolved at apply time, which Terraform permits.
resource "aws_route53_record" "cert_validation" {
  for_each = var.domain_name == "" || var.acm_certificate_arn != "" ? toset([]) : toset(local.certificate_domains)

  zone_id = aws_route53_zone.main[0].zone_id

  # `one()` rather than `[0]`: it asserts that exactly one validation option matches this hostname
  # and fails loudly otherwise, instead of silently picking the first of an unexpected set.
  name = one([
    for o in aws_acm_certificate.main[0].domain_validation_options : o.resource_record_name
    if o.domain_name == each.key
  ])
  type = one([
    for o in aws_acm_certificate.main[0].domain_validation_options : o.resource_record_type
    if o.domain_name == each.key
  ])
  records = [one([
    for o in aws_acm_certificate.main[0].domain_validation_options : o.resource_record_value
    if o.domain_name == each.key
  ])]

  ttl             = 60
  allow_overwrite = true # renewal reuses the same record name; refusing to overwrite would wedge it
}

# Blocks until ACM reports ISSUED. This is the gate that everything else hangs off: the HTTPS
# listener and the CloudFront alias both resolve their ARN through local.certificate_arn, which
# reads this resource, so Terraform cannot attach a certificate that is still PENDING_VALIDATION.
#
# It also depends on public DNS actually resolving -- ACM queries the record from the internet, not
# from the hosted zone directly -- which is why the registrar delegation has to be live first.
resource "aws_acm_certificate_validation" "main" {
  count = var.domain_name == "" || var.acm_certificate_arn != "" ? 0 : 1

  certificate_arn         = aws_acm_certificate.main[0].arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]

  timeouts {
    create = "15m"
  }
}

# ==================================================================================================
# Records
# ==================================================================================================
#
# All three are ALIAS records, not CNAMEs and not A records with literal addresses:
#
#   * an ALB's addresses change without notice, and CloudFront has no single address at all
#   * a CNAME is illegal at the apex (RFC 1034 -- the apex must hold the zone's SOA and NS)
#   * an alias resolves at query time inside Route 53 and costs nothing per query
#
# evaluate_target_health is false everywhere. There is no second target to fail over to with one
# task and one ALB, so health-evaluated DNS would only add a way for records to disappear.

resource "aws_route53_record" "api" {
  count = var.domain_name == "" ? 0 : 1

  zone_id = aws_route53_zone.main[0].zone_id
  name    = "api.${var.domain_name}"
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "apex" {
  count = var.domain_name == "" ? 0 : 1

  zone_id = aws_route53_zone.main[0].zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name    = aws_cloudfront_distribution.frontend.domain_name
    zone_id = aws_cloudfront_distribution.frontend.hosted_zone_id
    # Z2FDTNDATAQYW2 is CloudFront's fixed hosted zone id, but reading it off the resource means
    # nothing to update if AWS ever changes it.
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "www" {
  count = var.domain_name == "" ? 0 : 1

  zone_id = aws_route53_zone.main[0].zone_id
  name    = "www.${var.domain_name}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}
