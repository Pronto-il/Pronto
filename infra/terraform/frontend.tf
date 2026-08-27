# Frontend hosting: private S3 origin behind CloudFront with Origin Access Control.
#
# The bucket is never public. OAC signs CloudFront's requests to S3 with SigV4, and the bucket
# policy trusts exactly one distribution ARN -- so the objects are reachable through the CDN and
# through nothing else. (OAC, not the older Origin Access Identity: OAI predates SSE-KMS support and
# is on AWS's legacy track.)
#
# NOTE ON THE BLOCKED DOMAIN. No domain has been chosen for Pronto, and this milestone does not
# invent one. With var.domain_name empty, CloudFront serves on its own d111111abcdef8.cloudfront.net
# name -- with a valid Amazon certificate and real HTTPS. So the frontend is fully deployable and
# testable before DNS exists; only the custom hostname is blocked. The backend is the genuinely
# blocked half, because an ALB certificate must name a hostname somebody owns.

resource "aws_s3_bucket" "frontend" {
  bucket = "${var.project}-${var.environment_name}-frontend-${random_id.bucket_suffix.hex}"

  tags = { Name = "${var.project}-frontend" }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# No versioning here, unlike the uploads bucket. The contents are a build artifact that is fully
# reproducible from a git SHA by re-running the deploy workflow, so paying to keep every superseded
# copy of every hashed asset would buy nothing.
resource "aws_s3_bucket_lifecycle_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.project}-frontend-oac"
  description                       = "Signs CloudFront's requests to the private frontend bucket."
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# AWS-managed cache policies, referenced by data source rather than hand-rolled. Two different
# caching stories, because the two kinds of object have opposite requirements.
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.project} frontend"
  default_root_object = "index.html"
  price_class         = var.cloudfront_price_class

  # Gated on the CERTIFICATE, not just on domain_name. CloudFront rejects any alias its viewer
  # certificate does not cover, and the certificate below falls back to the *.cloudfront.net default
  # whenever acm_certificate_arn is empty -- which covers none of these names. Keying the aliases off
  # domain_name alone therefore produced an invalid distribution for the window between "a domain
  # exists" and "a certificate for it has been issued", which is exactly the window the staged
  # Route 53 -> delegation -> ACM rollout has to sit in.
  aliases = local.tls_enabled && var.domain_name != "" ? [var.domain_name, "www.${var.domain_name}"] : []

  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "frontend-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  # ---- default behaviour: index.html and anything not matched below ---------------------------
  #
  # Caching DISABLED at the edge, deliberately. index.html is the file that names which hashed asset
  # bundle is current, so a cached copy is precisely how a browser ends up requesting a bundle that
  # no longer exists -- a blank page after an otherwise successful deploy. It is a couple of
  # kilobytes and the origin is S3, so not caching it costs approximately nothing and removes an
  # entire class of deploy-day confusion.
  default_cache_behavior {
    target_origin_id       = "frontend-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_disabled.id
  }

  # ---- hashed assets: cache hard and forever ---------------------------------------------------
  #
  # Vite content-hashes every file it emits into assets/ (index-a1b2c3d4.js), so a given path's
  # bytes never change -- a new build produces a new path. That is what makes a one-year immutable
  # cache correct rather than reckless, and it is also why no deploy needs to invalidate anything
  # under assets/.
  ordered_cache_behavior {
    path_pattern           = "/assets/*"
    target_origin_id       = "frontend-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
  }

  # ---- SPA deep-link fallback ------------------------------------------------------------------
  #
  # frontend/src/app/router.tsx uses createBrowserRouter, so /orders/123 is a client-side route with
  # no corresponding S3 object. Without these two rules, opening that URL directly -- or reloading
  # it, or following a link into it -- returns an error page instead of the application.
  #
  # BOTH status codes are needed, and 403 is the one that is easy to miss: with OAC on a private
  # bucket, S3 answers a missing key with AccessDenied (403) rather than NoSuchKey (404), because
  # telling an unauthorized caller whether an object exists would itself leak information. Mapping
  # only 404 would leave every deep link broken.
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  restrictions {
    geo_restriction {
      # No geo restriction. Pronto's users are in Israel, but locking the CDN to one country would
      # break an Israeli user travelling, and a CDN is not an access-control layer.
      restriction_type = "none"
    }
  }

  viewer_certificate {
    # The SAME certificate the ALB listener uses (compute.tf). That is only correct because the
    # primary region is us-east-1, which is where CloudFront requires its certificate to live --
    # see var.acm_certificate_arn for the SANs it must carry and what changes if the region moves.
    #
    # Empty is the expected pre-domain state, not a degraded one: CloudFront's own certificate on
    # the *.cloudfront.net name is a real, valid, publicly-trusted certificate.
    cloudfront_default_certificate = !local.tls_enabled
    acm_certificate_arn            = local.tls_enabled ? local.certificate_arn : null
    ssl_support_method             = local.tls_enabled ? "sni-only" : null
    minimum_protocol_version       = local.tls_enabled ? "TLSv1.2_2021" : "TLSv1"
  }

  tags = { Name = "${var.project}-frontend" }
}

# The bucket policy that makes OAC work: one distribution, by ARN, and nothing else.
resource "aws_s3_bucket_policy" "frontend" {
  bucket     = aws_s3_bucket.frontend.id
  policy     = data.aws_iam_policy_document.frontend_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.frontend]
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid    = "AllowCloudFrontServicePrincipalReadOnly"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    # Without this condition the statement would let ANY CloudFront distribution in ANY AWS account
    # read the bucket, because the service principal is shared. The ARN condition is what scopes it
    # to this one.
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }

  statement {
    sid    = "DenyUnencryptedTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.frontend.arn,
      "${aws_s3_bucket.frontend.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}
