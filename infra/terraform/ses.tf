# SES sending identity for the production domain.
#
# The application sends every one-time password from noreply@<domain> (compute.tf's local.email_from
# -> EMAIL_FROM, with EMAIL_MODE=ses). SES refuses to send from an address whose domain it has not
# verified, so without this the entire authentication flow is inert: registration issues a code that
# is never dispatched, login the same, and AuthService turns that into OTP_DELIVERY_FAILED. With SMS
# production access also unapproved, that leaves no working verification channel at all.
#
# WHAT THIS DOES NOT DO. It does not lift the SES sandbox. A sandboxed account may send only to
# ADDRESSES IT HAS ALSO VERIFIED, so publishing these records makes noreply@<domain> a legitimate
# sender but does not make the platform able to mail the public. That needs a production-access
# request to AWS, which is a support ticket and a human review, not configuration. Until it is
# granted, only individually verified recipients receive mail -- which is enough to exercise the
# real login flow with a QA account and nothing more.
#
# EasyDKIM rather than BYODKIM: AWS generates and rotates the key pair, and the only thing this
# configuration has to own is publishing the three CNAMEs it asks for. Those records are what make
# the identity verify, and leaving them out is the usual reason an identity sits in "Pending"
# forever.

resource "aws_sesv2_email_identity" "domain" {
  count = var.domain_name == "" ? 0 : 1

  email_identity = var.domain_name

  dkim_signing_attributes {
    # RSA_2048_BIT over the 1024-bit option: the difference in signing cost is irrelevant at this
    # volume, and some receivers treat 1024-bit DKIM as weak.
    next_signing_key_length = "RSA_2048_BIT"
  }

  tags = { Name = "${var.project}-${var.environment_name}-ses" }
}

# The three CNAMEs EasyDKIM requires. Published into the zone this configuration already owns, for
# the same reason the ACM validation records are: a verification that depends on a record somebody
# has to remember to add by hand is a verification that silently expires.
resource "aws_route53_record" "ses_dkim" {
  for_each = var.domain_name == "" ? toset([]) : toset(["0", "1", "2"])

  zone_id = aws_route53_zone.main[0].zone_id
  name    = "${aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens[tonumber(each.key)]}._domainkey.${var.domain_name}"
  type    = "CNAME"
  records = ["${aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens[tonumber(each.key)]}.dkim.amazonses.com"]
  ttl     = 300

  # DKIM keys rotate, and a rotation reuses the record NAME with new content. Refusing to overwrite
  # would wedge the rotation and eventually break signing.
  allow_overwrite = true
}

# MAIL FROM is deliberately NOT configured.
#
# A custom MAIL FROM domain aligns SPF with the visible From address, which improves deliverability
# and is worth doing before real users receive mail. It also requires an MX record pointing at an SES
# feedback endpoint plus an SPF TXT record, and -- the part that matters here -- if those records are
# wrong SES falls back to amazonses.com or, on the strict setting, refuses to send. Adding it in the
# same change as the identity itself would make a failure ambiguous between two causes. It belongs
# with the production-access request, when there is a reason to care about inbox placement.
