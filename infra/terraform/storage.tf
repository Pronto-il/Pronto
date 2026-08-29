# S3 bucket for application uploads -- issue photos and professional verification documents.
#
# Deliberately separate from the frontend bucket in frontend.tf. They have opposite access models
# (this one is reachable only by the backend's IAM role; that one is readable by a CloudFront
# distribution) and opposite lifecycles (this holds customer data indefinitely; that holds a build
# artifact replaced on every deploy). One bucket serving both would have to be permissive enough for
# the looser of the two.
#
# WHAT IS IN HERE MATTERS. Verification documents are the identity evidence marketplace eligibility
# is decided on (roadmap decision D4), and issue photos are customer property. Nothing in this file
# grants public access to any of it.

resource "random_id" "bucket_suffix" {
  # S3 bucket names are globally unique across every AWS account. A fixed name would collide with
  # any other account that guessed the same string, and the failure arrives mid-apply.
  byte_length = 4
}

resource "aws_s3_bucket" "uploads" {
  bucket = "${var.project}-${var.environment_name}-uploads-${random_id.bucket_suffix.hex}"

  tags = { Name = "${var.project}-uploads" }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  # All four. Together they mean no ACL and no bucket policy can make an object public, regardless
  # of what anyone writes later -- which is the point: this is a standing constraint rather than a
  # statement about the current policy.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  rule {
    apply_server_side_encryption_by_default {
      # SSE-S3, not SSE-KMS. storage.client.S3StorageClient's PutObjectRequest sets no encryption
      # header at all, so whatever the bucket default is, is what applies. SSE-KMS would encrypt
      # equally well and would additionally require kms:GenerateDataKey and kms:Decrypt on the task
      # role -- omit either and every upload fails at runtime rather than at deploy. SSE-S3 has no
      # per-request cost and no extra IAM surface, which is the right trade at this scale.
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  versioning_configuration {
    # On, deliberately, and this is the one place versioning is worth its cost. A verification
    # document overwritten by a key collision or deleted by a bug is not reconstructible -- the
    # professional would have to be asked to re-submit identity evidence, and any approval decision
    # already made on the old document becomes unauditable.
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  # Depends_on rather than a plain reference: S3 rejects a lifecycle configuration that expires
  # noncurrent versions on a bucket where versioning is not yet enabled, and Terraform has no way to
  # infer the ordering from the arguments alone.
  depends_on = [aws_s3_bucket_versioning.uploads]

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    # A failed multipart upload leaves parts that are billed and invisible in the console object
    # list. This is the standard hygiene rule and there is no reason not to have it.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-abandoned-guest-uploads"
    status = "Enabled"

    # Photos a visitor attached before they had an account, under guests/{sessionId}/. This prefix
    # is the ONE part of the bucket where age alone is a safe signal, and that is why the rule is
    # scoped to it rather than to .../issues/temp/ generally: a guest object is either promoted onto
    # a real customer's namespace at the booking commit -- issues.service.IssuesService copies it to
    # customers/{id}/ and deletes the original after commit -- or it belongs to a journey that was
    # abandoned. Nothing durable ever points at a guests/ key; issue_images never records one.
    #
    # Customer-namespace temp objects look similar and are NOT covered, deliberately: a
    # customers/{id}/issues/temp/ key is what issue_images stores for the life of the issue, so
    # expiring one by age would delete a live photo. That pre-existing orphan class (a signed-in
    # customer who abandons the New Issue flow) is a known, accepted gap recorded in
    # backend/src/main/java/com/pronto/storage/README.md, unchanged by this rule.
    #
    # Two days rather than one: the guest session token itself lives 24h
    # (pronto.auth.guest-session-ttl-seconds), and expiring objects on exactly that boundary would
    # race a visitor who returns to a paused draft at hour 23.
    filter {
      prefix = "guests/"
    }

    expiration {
      days = 2
    }

    # Versioning is on for this bucket, so the delete above only writes a delete marker. Without
    # this the "expired" objects stay billable as noncurrent versions for the 90 days below.
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  rule {
    id     = "expire-old-versions"
    status = "Enabled"

    filter {}

    # Versioning above protects against accidental overwrite; this bounds what that protection
    # costs. Ninety days is far longer than the hours or days it takes to notice a bad deploy, and
    # short enough that superseded copies do not accumulate for the life of the product.
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}

# TLS-only. Deny beats allow in IAM, so this cannot be undone by a later grant.
resource "aws_s3_bucket_policy" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  policy = data.aws_iam_policy_document.uploads_bucket.json

  # A bucket policy applied before the public-access block can be rejected, and more importantly the
  # block is the thing that must be true first.
  depends_on = [aws_s3_bucket_public_access_block.uploads]
}

data "aws_iam_policy_document" "uploads_bucket" {
  statement {
    sid    = "DenyUnencryptedTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.uploads.arn,
      "${aws_s3_bucket.uploads.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

# ==================================================================================================
# Deliberately absent: a CORS configuration.
#
# S3 CORS is only consulted for requests a BROWSER makes directly to S3. Pronto's browsers never do:
#
#   - Uploads are proxied through the backend as multipart form data
#     (storage.controller.StorageController takes a MultipartFile; application.yml caps it at 8 MB
#     per file / 20 MB per request), so the browser posts to the API, not to S3.
#   - Reads use presigned GET URLs rendered into plain <img src> tags. A plain image load is not a
#     CORS request -- and a repository-wide search finds no `crossOrigin` attribute anywhere in
#     frontend/src, which is the thing that would make it one.
#
# So a CORS policy here would grant browser access that nothing needs, on a bucket holding identity
# documents. If a future feature fetches an object with fetch()/XHR, add the narrowest possible rule
# then, with the frontend origin named explicitly.
# ==================================================================================================
