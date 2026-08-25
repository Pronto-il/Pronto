package com.pronto.auth.service;

/**
 * The one place an email address becomes canonical: lowercase, trimmed.
 *
 * <p>Production MS1. Before this, {@code AuthService} stored {@code request.email()} verbatim and
 * relied on {@code ux_users_email_lower} (a functional index on {@code lower(email)}) to stop
 * {@code Foo@x.com} and {@code foo@x.com} becoming two accounts. Uniqueness held, but the stored
 * value did not agree with the rule that enforced it, and the lookups
 * ({@code findByEmailIgnoreCase} → {@code upper(email) = upper(?)}) could not use that index at
 * all. {@code V48} canonicalizes the existing rows and swaps the index; this class is what keeps
 * every future write in line with it.
 *
 * <p><b>Deliberately not done: provider-specific canonicalization.</b> Stripping Gmail's ignored
 * dots or {@code +tags} would collapse addresses their owner may be using on purpose to keep two
 * separate accounts, and is simply wrong for the many providers that treat those characters
 * literally. Case and surrounding whitespace are the only things normalized, because those are the
 * only two the whole system has already been treating as insignificant.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /**
     * @return the canonical form of {@code email}, or {@code null} if {@code email} is {@code null}
     */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * Masks an address for display in an OTP challenge response ({@code d***@example.com}).
     *
     * <p>The client has to be able to tell the user <em>where</em> the code went, and it must be
     * able to do so without this API restating an address the caller may not actually own — a
     * password-reset request is answered identically for an address that exists and one that does
     * not, so the masked value is derived from what was submitted, not from any stored row.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + "***" + domain;
    }
}
