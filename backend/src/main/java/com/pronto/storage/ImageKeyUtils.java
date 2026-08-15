package com.pronto.storage;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code customers/{callerId}/issues/temp/{uuid}.{ext}} object key format (§2.3
 * step 3) — the sole ownership mechanism for uploaded images, since no DB row exists to
 * record it (§3.3). Used by {@code storage} itself (§2.4's retrieval endpoint) and by
 * {@code issues} (§2.1/§2.2's {@code imageKeys} ownership check) — kept here, not
 * duplicated, since the key format is this package's own convention.
 *
 * <p>Pure/stateless — no Spring dependency, trivially unit-testable.
 */
public final class ImageKeyUtils {

    /**
     * Matches both {@code customers/{callerId}/...} (issue images) and
     * {@code verification-documents/{callerId}/...} (Professional registration
     * verification documents, backend registration flow separation task §12) — the
     * latter is deliberately NOT under the public {@link #PUBLIC_PREFIX}: unlike a
     * profile image, a verification document is a private compliance artifact, so it
     * keeps the same strict per-caller ownership check as issue images, just keyed by
     * the uploading user's id rather than a professional row id (registration runs
     * before the caller has a JWT/{@code AuthenticatedUser} tied to a professional id).
     */
    private static final Pattern OWNER_PATTERN =
            Pattern.compile("^(?:customers|verification-documents)/(\\d+)/.*");

    /** Prefix for professional profile-image keys (see
     * {@code professionals.service.ProfessionalsService#uploadProfileImage}'s
     * {@code professionals/{professionalId}/profile/{uuid}.{ext}} key template). Unlike
     * {@code customers/}-prefixed issue images (private to their owner), profile images are
     * shown to any customer browsing listings — so they carry no per-caller ownership check,
     * just {@link #isPubliclyReadable}. */
    private static final String PUBLIC_PREFIX = "professionals/";

    private ImageKeyUtils() {
    }

    /**
     * {@code true} iff {@code key} is under the {@code professionals/} prefix — readable by any
     * authenticated caller (no ownership check), since profile images are meant to be visible
     * to any customer browsing listings, not just the owning professional. See
     * {@code storage.service.StorageService#retrieve}, which special-cases this before falling
     * back to {@link #belongsTo}'s strict ownership check for every other key format (in
     * particular {@code customers/}-prefixed issue images, whose behavior is unchanged).
     */
    public static boolean isPubliclyReadable(String key) {
        return key != null && key.startsWith(PUBLIC_PREFIX);
    }

    /** The {@code {callerId}} segment embedded in {@code customers/{callerId}/...}, if present and well-formed. */
    public static Optional<Long> extractOwnerId(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Matcher matcher = OWNER_PATTERN.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** {@code true} iff {@code key} embeds exactly {@code callerId} as its owner segment. */
    public static boolean belongsTo(String key, Long callerId) {
        return callerId != null && extractOwnerId(key).map(callerId::equals).orElse(false);
    }

    /** The file extension (lowercased, without the dot), if {@code key} has one past its last {@code /}. */
    public static Optional<String> extractExtension(String key) {
        if (key == null) {
            return Optional.empty();
        }
        int lastDot = key.lastIndexOf('.');
        int lastSlash = key.lastIndexOf('/');
        if (lastDot <= lastSlash || lastDot == key.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(key.substring(lastDot + 1).toLowerCase());
    }
}
