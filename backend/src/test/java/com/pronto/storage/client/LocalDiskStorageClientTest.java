package com.pronto.storage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskStorageClientTest {

    @TempDir
    Path tempDir;

    private static final long DEFAULT_TTL_SECONDS = 300;

    private final LocalHmacUrlSigner urlSigner = new LocalHmacUrlSigner("test-hmac-secret");

    private LocalDiskStorageClient client(Path baseDir) {
        return new LocalDiskStorageClient(baseDir.toString(), "http://localhost:8080", urlSigner, DEFAULT_TTL_SECONDS);
    }

    @Test
    void upload_thenDownload_roundTripsTheSameBytes() {
        LocalDiskStorageClient client = client(tempDir);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String key = "customers/42/issues/temp/some-uuid.jpg";

        StoredObject stored = client.upload(key, content, "image/jpeg");

        assertThat(stored.key()).isEqualTo(key);
        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(client.exists(key)).isTrue();
        assertThat(client.download(key)).isEqualTo(content);
    }

    @Test
    void upload_returnsAPresignedUrlPointingAtTheRetrievalEndpoint() {
        LocalDiskStorageClient client = client(tempDir);
        String key = "customers/42/issues/temp/some-uuid.jpg";

        StoredObject stored = client.upload(key, "x".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        assertThat(stored.url()).startsWith("http://localhost:8080/api/storage/images/" + key + "?expires=");
        assertThat(stored.url()).contains("&sig=");
    }

    @Test
    void exists_falseForNeverUploadedKey() {
        LocalDiskStorageClient client = client(tempDir);
        assertThat(client.exists("customers/1/issues/temp/never-uploaded.png")).isFalse();
    }

    @Test
    void download_throwsStorageExceptionForMissingKey() {
        LocalDiskStorageClient client = client(tempDir);
        assertThatThrownBy(() -> client.download("customers/1/issues/temp/missing.png"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void presignUrl_pointsAtTheLocalRetrievalEndpointWithAValidSignature() {
        LocalDiskStorageClient client = client(tempDir);
        String key = "customers/42/issues/temp/uuid.png";

        String url = client.presignUrl(key, Duration.ofSeconds(DEFAULT_TTL_SECONDS));

        assertThat(url).startsWith("http://localhost:8080/api/storage/images/" + key + "?expires=");
        String expiresParam = url.substring(url.indexOf("expires=") + "expires=".length(), url.indexOf("&sig="));
        String sigParam = url.substring(url.indexOf("&sig=") + "&sig=".length());
        assertThat(urlSigner.isValid(key, Long.parseLong(expiresParam), sigParam)).isTrue();
    }

    @Test
    void resolvePath_rejectsKeysThatEscapeTheBaseDirectory() {
        LocalDiskStorageClient client = client(tempDir);
        assertThatThrownBy(() -> client.download("../../etc/passwd"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void upload_preservesNestedDirectoryStructureFromTheKey() {
        LocalDiskStorageClient client = client(tempDir);
        String key = "customers/7/issues/temp/nested-uuid.webp";
        client.upload(key, "x".getBytes(StandardCharsets.UTF_8), "image/webp");

        assertThat(tempDir.resolve("customers/7/issues/temp/nested-uuid.webp")).exists();
    }
}
