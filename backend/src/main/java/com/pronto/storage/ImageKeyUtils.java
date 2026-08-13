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

    private static final Pattern OWNER_PATTERN = Pattern.compile("^customers/(\\d+)/.*");

    private ImageKeyUtils() {
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
