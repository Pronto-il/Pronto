package com.pronto.storage;

import com.pronto.common.security.UploadOwner;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageKeyUtilsTest {

    @Test
    void extractOwnerId_parsesWellFormedKey() {
        String key = "customers/42/issues/temp/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.jpg";
        assertThat(ImageKeyUtils.extractOwnerId(key)).contains(42L);
    }

    @Test
    void extractOwnerId_returnsEmptyForMalformedKey() {
        assertThat(ImageKeyUtils.extractOwnerId("not-a-valid-key")).isEmpty();
        assertThat(ImageKeyUtils.extractOwnerId("customers/abc/issues/temp/x.jpg")).isEmpty();
        assertThat(ImageKeyUtils.extractOwnerId(null)).isEmpty();
    }

    @Test
    void belongsTo_trueOnlyForMatchingOwner() {
        String key = "customers/42/issues/temp/uuid.png";
        assertThat(ImageKeyUtils.belongsTo(key, 42L)).isTrue();
        assertThat(ImageKeyUtils.belongsTo(key, 43L)).isFalse();
        // Cast needed since guest uploads added a belongsTo(String, UploadOwner) overload — a bare
        // `null` now matches both. The assertion is unchanged: no owner owns anything.
        assertThat(ImageKeyUtils.belongsTo(key, (Long) null)).isFalse();
        assertThat(ImageKeyUtils.belongsTo(key, (UploadOwner) null)).isFalse();
        assertThat(ImageKeyUtils.belongsTo("garbage", 42L)).isFalse();
    }

    // ---- the guest owner namespace ----

    @Test
    void issueTempKeyPrefix_isTheSameTemplateWithADifferentOwnerSegment() {
        assertThat(ImageKeyUtils.issueTempKeyPrefix(UploadOwner.customer(42L)))
                .isEqualTo("customers/42/issues/temp/");
        assertThat(ImageKeyUtils.issueTempKeyPrefix(UploadOwner.guest(GUEST_ID)))
                .isEqualTo("guests/" + GUEST_ID + "/issues/temp/");
        // Both identities present (a guest who registered mid-flow): new uploads go to the account,
        // because that ownership outlives the session.
        assertThat(ImageKeyUtils.issueTempKeyPrefix(new UploadOwner(42L, GUEST_ID)))
                .isEqualTo("customers/42/issues/temp/");
    }

    @Test
    void extractGuestOwnerId_requiresAWellFormedUuidSegment() {
        assertThat(ImageKeyUtils.extractGuestOwnerId(guestKey(GUEST_ID))).contains(GUEST_ID);
        // Not a UUID, so not an owner segment -- this is what stops a traversal or a partial match
        // ever being compared against a real guest id.
        assertThat(ImageKeyUtils.extractGuestOwnerId("guests/../customers/42/issues/temp/x.jpg")).isEmpty();
        assertThat(ImageKeyUtils.extractGuestOwnerId("guests/42/issues/temp/x.jpg")).isEmpty();
        assertThat(ImageKeyUtils.extractGuestOwnerId("guests/" + GUEST_ID + "x/issues/temp/x.jpg")).isEmpty();
        assertThat(ImageKeyUtils.extractGuestOwnerId("guests/" + GUEST_ID.toUpperCase() + "/issues/temp/x.jpg"))
                .isEmpty();
        assertThat(ImageKeyUtils.extractGuestOwnerId(null)).isEmpty();
    }

    @Test
    void belongsTo_matchesEitherProvedIdentityAndNothingElse() {
        String customerKey = "customers/42/issues/temp/x.jpg";
        String ownGuestKey = guestKey(GUEST_ID);
        String otherGuestKey = guestKey("9a8b7c6d-5e4f-4a3b-8c9d-0e1f2a3b4c5d");

        UploadOwner customer = UploadOwner.customer(42L);
        assertThat(ImageKeyUtils.belongsTo(customerKey, customer)).isTrue();
        assertThat(ImageKeyUtils.belongsTo(ownGuestKey, customer)).isFalse();

        UploadOwner guest = UploadOwner.guest(GUEST_ID);
        assertThat(ImageKeyUtils.belongsTo(ownGuestKey, guest)).isTrue();
        assertThat(ImageKeyUtils.belongsTo(customerKey, guest)).isFalse();
        assertThat(ImageKeyUtils.belongsTo(otherGuestKey, guest)).isFalse();

        UploadOwner transitioned = new UploadOwner(42L, GUEST_ID);
        assertThat(ImageKeyUtils.belongsTo(customerKey, transitioned)).isTrue();
        assertThat(ImageKeyUtils.belongsTo(ownGuestKey, transitioned)).isTrue();
        assertThat(ImageKeyUtils.belongsTo(otherGuestKey, transitioned)).isFalse();
    }

    @Test
    void guestKeysAreNotPubliclyReadable() {
        // The `professionals/` public-read exemption must not have grown a second member.
        assertThat(ImageKeyUtils.isPubliclyReadable(guestKey(GUEST_ID))).isFalse();
        assertThat(ImageKeyUtils.isGuestKey(guestKey(GUEST_ID))).isTrue();
        assertThat(ImageKeyUtils.isGuestKey("customers/42/issues/temp/x.jpg")).isFalse();
    }

    private static final String GUEST_ID = "2f1c9d8e-4b7a-4c3d-9e2f-1a2b3c4d5e6f";

    private static String guestKey(String guestId) {
        return "guests/" + guestId + "/issues/temp/x.jpg";
    }

    @Test
    void extractExtension_returnsLowercasedExtensionPastLastSlash() {
        assertThat(ImageKeyUtils.extractExtension("customers/1/issues/temp/uuid.JPG")).contains("jpg");
        assertThat(ImageKeyUtils.extractExtension("customers/1/issues/temp/uuid.webp")).contains("webp");
    }

    @Test
    void extractExtension_emptyWhenNoExtensionOrDotBelongsToADirectory() {
        assertThat(ImageKeyUtils.extractExtension("customers/1/issues/temp/uuidnoext")).isEmpty();
        // dot appears in a directory segment, not the final filename
        assertThat(ImageKeyUtils.extractExtension("customers/1.5/issues/temp/uuid")).isEmpty();
        Optional<String> trailingDot = ImageKeyUtils.extractExtension("customers/1/issues/temp/uuid.");
        assertThat(trailingDot).isEmpty();
    }

    @Test
    void isPubliclyReadable_trueOnlyForProfessionalsPrefix() {
        assertThat(ImageKeyUtils.isPubliclyReadable("professionals/7/profile/uuid.jpg")).isTrue();
        assertThat(ImageKeyUtils.isPubliclyReadable("customers/42/issues/temp/uuid.jpg")).isFalse();
        assertThat(ImageKeyUtils.isPubliclyReadable("garbage")).isFalse();
        assertThat(ImageKeyUtils.isPubliclyReadable(null)).isFalse();
    }

    @Test
    void verificationDocumentKey_ownershipResolvesByUploadingUserId() {
        // Backend registration flow separation task §12: a verification document is a
        // private compliance artifact, not a public profile image -- it must NOT match
        // isPubliclyReadable's professionals/ prefix, but still needs the same strict
        // per-caller ownership check issue images get.
        String key = "verification-documents/100/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.pdf";

        assertThat(ImageKeyUtils.isPubliclyReadable(key)).isFalse();
        assertThat(ImageKeyUtils.extractOwnerId(key)).contains(100L);
        assertThat(ImageKeyUtils.belongsTo(key, 100L)).isTrue();
        assertThat(ImageKeyUtils.belongsTo(key, 101L)).isFalse();
    }
}
