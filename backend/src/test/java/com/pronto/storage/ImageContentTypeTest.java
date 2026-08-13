package com.pronto.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageContentTypeTest {

    @Test
    void fromContentType_matchesAllThreeAcceptedTypes() {
        assertThat(ImageContentType.fromContentType("image/jpeg")).contains(ImageContentType.JPEG);
        assertThat(ImageContentType.fromContentType("image/png")).contains(ImageContentType.PNG);
        assertThat(ImageContentType.fromContentType("image/webp")).contains(ImageContentType.WEBP);
    }

    @Test
    void fromContentType_rejectsUnsupportedOrNullType() {
        assertThat(ImageContentType.fromContentType("image/gif")).isEmpty();
        assertThat(ImageContentType.fromContentType("application/pdf")).isEmpty();
        assertThat(ImageContentType.fromContentType(null)).isEmpty();
    }

    @Test
    void extensionRoundTripsBackToTheSameContentType() {
        for (ImageContentType type : ImageContentType.values()) {
            assertThat(ImageContentType.fromExtension(type.extension())).contains(type);
        }
    }
}
