package com.pronto.storage;

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
        assertThat(ImageKeyUtils.belongsTo(key, null)).isFalse();
        assertThat(ImageKeyUtils.belongsTo("garbage", 42L)).isFalse();
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
}
