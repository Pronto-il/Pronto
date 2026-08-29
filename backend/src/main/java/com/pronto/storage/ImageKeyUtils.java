package com.pronto.storage;

import com.pronto.common.security.UploadOwner;

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
 * <p><b>Guest uploads add one owner namespace, not a second ownership mechanism.</b>
 * {@code guests/{guestId}/issues/temp/{uuid}.{ext}} is the same template with a different owner
 * segment, and {@link #belongsTo(String, UploadOwner)} is the same "does the owner segment
 * embedded in the key match the caller we verified" question the numeric form already asked. The
 * guest segment is a UUID rather than a row id purely because a guest has no row — see
 * {@code auth.security.GuestSessionTokenService}, which is the only thing that mints one and the
 * only thing that can prove possession of one.
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

    /**
     * Guest-owned issue photos. The owner segment is restricted to a lowercase UUID at the regex
     * level, not merely by convention: it is the one owner segment that does not come from a
     * database id, so this pattern is what guarantees no {@code /} or {@code ..} can ever appear
     * in it and reach {@code LocalDiskStorageClient}'s path resolution or an S3 key.
     */
    private static final Pattern GUEST_OWNER_PATTERN =
            Pattern.compile("^guests/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/.*");

    private static final String GUEST_PREFIX = "guests/";

    /** The shared per-owner namespace an issue photo is uploaded into, before any issue exists. */
    private static final String ISSUE_TEMP_SEGMENT = "/issues/temp/";

    private ImageKeyUtils() {
    }

    /**
     * The key prefix a NEW upload from {@code owner} lands under —
     * {@code customers/{id}/issues/temp/} or {@code guests/{uuid}/issues/temp/}.
     *
     * <p>This is the single source of truth for the upload key template, extracted from
     * {@code StorageService#upload}'s former inline string concatenation so that the guest and
     * customer paths cannot drift apart. The template itself is byte-for-byte what it always was
     * for a customer.
     */
    public static String issueTempKeyPrefix(UploadOwner owner) {
        String ownerPrefix = owner.isCustomer() ? "customers/" : GUEST_PREFIX;
        return ownerPrefix + owner.preferredKeyOwnerSegment() + ISSUE_TEMP_SEGMENT;
    }

    /** {@code true} iff {@code key} is under the guest namespace at all (well-formed or not). */
    public static boolean isGuestKey(String key) {
        return key != null && key.startsWith(GUEST_PREFIX);
    }

    /** The {@code {guestId}} segment embedded in {@code guests/{guestId}/...}, if well-formed. */
    public static Optional<String> extractGuestOwnerId(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Matcher matcher = GUEST_OWNER_PATTERN.matcher(key);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** {@code true} iff {@code key} embeds exactly {@code guestId} as its owner segment. */
    public static boolean belongsToGuest(String key, String guestId) {
        return guestId != null && extractGuestOwnerId(key).map(guestId::equals).orElse(false);
    }

    /**
     * The one ownership predicate the shared upload/presign flow asks, for either kind of owner.
     *
     * <p>An owner holding both identities (a guest who registered mid-flow) matches a key in
     * either namespace — that, and nothing else, is what stops their own photos disappearing at
     * the moment they sign in. See {@link UploadOwner} for why that is deliberate rather than
     * lax: both identities were independently proved on this request, so accepting a key that
     * matches either is exactly as strict as accepting one that matches the single identity a
     * caller used to have.
     */
    public static boolean belongsTo(String key, UploadOwner owner) {
        if (owner == null) {
            return false;
        }
        return belongsTo(key, owner.customerId()) || belongsToGuest(key, owner.guestId());
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
