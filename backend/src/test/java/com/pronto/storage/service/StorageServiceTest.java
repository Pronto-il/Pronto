package com.pronto.storage.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.storage.client.LocalHmacUrlSigner;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.PresignedImageUrlEntry;
import com.pronto.storage.dto.RetrievedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    private static final long TTL_SECONDS = 300;

    private StorageClient storageClient;
    private LocalHmacUrlSigner urlSigner;
    private StorageService storageService;
    private final AuthenticatedUser caller = new AuthenticatedUser(42L, "CUSTOMER");

    @BeforeEach
    void setUp() {
        storageClient = Mockito.mock(StorageClient.class);
        urlSigner = new LocalHmacUrlSigner("test-hmac-secret");
        storageService = new StorageService(storageClient, Optional.of(urlSigner), TTL_SECONDS);
    }

    // --- upload / uploadDocumentWithKey (unchanged by MS9) ---

    @Test
    void upload_rejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe",
                "application/octet-stream", "content".getBytes());

        assertThatThrownBy(() -> storageService.upload(caller, file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE));
    }

    @Test
    void upload_rejectsFileLargerThan8Mb() {
        byte[] tooLarge = new byte[(int) StorageService.MAX_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", tooLarge);

        assertThatThrownBy(() -> storageService.upload(caller, file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.IMAGE_TOO_LARGE));
    }

    @Test
    void upload_generatesKeyNamespacedByCallerIdAndDelegatesToStorageClient() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "bytes".getBytes());
        when(storageClient.upload(anyString(), Mockito.any(), Mockito.eq("image/jpeg")))
                .thenAnswer(invocation -> new StoredObject(
                        invocation.getArgument(0), "http://localhost:8080/x", "image/jpeg", 5));

        ImageUploadResponse response = storageService.upload(caller, file);

        assertThat(response.imageKey()).startsWith("customers/42/issues/temp/");
        assertThat(response.imageKey()).endsWith(".jpg");
        assertThat(response.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void uploadDocumentWithKey_rejectsMissingFile() {
        assertThatThrownBy(() -> storageService.uploadDocumentWithKey("verification-documents/42/x.pdf", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void uploadDocumentWithKey_rejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe",
                "application/octet-stream", "content".getBytes());

        assertThatThrownBy(() -> storageService.uploadDocumentWithKey("verification-documents/42/x.exe", file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
    }

    @Test
    void uploadDocumentWithKey_rejectsFileLargerThan8Mb() {
        byte[] tooLarge = new byte[(int) StorageService.MAX_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", tooLarge);

        assertThatThrownBy(() -> storageService.uploadDocumentWithKey("verification-documents/42/x.pdf", file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.IMAGE_TOO_LARGE));
    }

    @Test
    void uploadDocumentWithKey_acceptsPdfAndDelegatesToStorageClient() {
        MockMultipartFile file = new MockMultipartFile("file", "license.pdf", "application/pdf", "bytes".getBytes());
        String key = "verification-documents/42/uuid.pdf";
        when(storageClient.upload(eq(key), any(), eq("application/pdf")))
                .thenReturn(new StoredObject(key, "http://localhost:8080/x", "application/pdf", 5));

        StoredObject result = storageService.uploadDocumentWithKey(key, file);

        assertThat(result.key()).isEqualTo(key);
        assertThat(result.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void uploadDocumentWithKey_alsoAcceptsAPhotographedDocumentAsAnImage() {
        MockMultipartFile file = new MockMultipartFile("file", "license.jpg", "image/jpeg", "bytes".getBytes());
        String key = "verification-documents/42/uuid.jpg";
        when(storageClient.upload(eq(key), any(), eq("image/jpeg")))
                .thenReturn(new StoredObject(key, "http://localhost:8080/x", "image/jpeg", 5));

        StoredObject result = storageService.uploadDocumentWithKey(key, file);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
    }

    // --- getPresignedUrl (backend MS9 §2) ---

    @Test
    void getPresignedUrl_forbiddenWhenKeyOwnerDoesNotMatchCaller() {
        assertThatThrownBy(() -> storageService.getPresignedUrl(caller.id(), "customers/99/issues/temp/x.jpg"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void getPresignedUrl_succeedsForAKeyOwnedByTheCaller() {
        String key = "customers/42/issues/temp/photo.png";
        when(storageClient.presignUrl(eq(key), any())).thenReturn("http://localhost:8080/signed");

        String url = storageService.getPresignedUrl(caller.id(), key);

        assertThat(url).isEqualTo("http://localhost:8080/signed");
    }

    @Test
    void getPresignedUrl_authenticatedUserOverloadDelegatesToTheIdOverload() {
        String key = "customers/42/issues/temp/photo.png";
        when(storageClient.presignUrl(eq(key), any())).thenReturn("http://localhost:8080/signed");

        String url = storageService.getPresignedUrl(caller, key);

        assertThat(url).isEqualTo("http://localhost:8080/signed");
    }

    @Test
    void getPresignedUrl_publiclyReadableProfessionalKeyIsResolvableByAnyCaller() {
        String key = "professionals/7/profile/photo.jpg";
        when(storageClient.presignUrl(eq(key), any())).thenReturn("http://localhost:8080/signed");

        assertThat(storageService.getPresignedUrl(999L, key)).isEqualTo("http://localhost:8080/signed");
    }

    // --- getPresignedUrlAssumingCallerAuthorized (backend MS9 §2/§9.4.2's bypass) ---

    @Test
    void getPresignedUrlAssumingCallerAuthorized_bypassesTheOwnershipCheckEntirely() {
        // A key that would fail the general ownership check (owned by a different customer)
        // still resolves here -- this method trusts the caller (IssuesService#getById) to have
        // already established a broader authorization rule.
        String key = "customers/99/issues/temp/x.jpg";
        when(storageClient.presignUrl(eq(key), any())).thenReturn("http://localhost:8080/signed");

        String url = storageService.getPresignedUrlAssumingCallerAuthorized(key);

        assertThat(url).isEqualTo("http://localhost:8080/signed");
    }

    // --- getPresignedUrls batch (backend MS9 §12.2) ---

    @Test
    void getPresignedUrls_resolvesEveryOwnedKey() {
        when(storageClient.presignUrl(eq("customers/42/issues/temp/a.jpg"), any())).thenReturn("http://x/a");
        when(storageClient.presignUrl(eq("customers/42/issues/temp/b.jpg"), any())).thenReturn("http://x/b");

        List<PresignedImageUrlEntry> result = storageService.getPresignedUrls(caller.id(),
                List.of("customers/42/issues/temp/a.jpg", "customers/42/issues/temp/b.jpg"));

        assertThat(result).containsExactlyInAnyOrder(
                new PresignedImageUrlEntry("customers/42/issues/temp/a.jpg", "http://x/a"),
                new PresignedImageUrlEntry("customers/42/issues/temp/b.jpg", "http://x/b"));
    }

    @Test
    void getPresignedUrls_partialSuccess_skipsAnUnownedKeyWithoutFailingTheWholeBatch() {
        when(storageClient.presignUrl(eq("customers/42/issues/temp/a.jpg"), any())).thenReturn("http://x/a");

        List<PresignedImageUrlEntry> result = storageService.getPresignedUrls(caller.id(),
                List.of("customers/42/issues/temp/a.jpg", "customers/99/issues/temp/not-mine.jpg"));

        assertThat(result).containsExactly(new PresignedImageUrlEntry("customers/42/issues/temp/a.jpg", "http://x/a"));
    }

    @Test
    void getPresignedUrls_emptyResultWhenEveryKeyIsUnowned() {
        List<PresignedImageUrlEntry> result = storageService.getPresignedUrls(caller.id(),
                List.of("customers/99/issues/temp/not-mine.jpg"));

        assertThat(result).isEmpty();
    }

    @Test
    void getPresignedUrls_rejectsABatchLargerThanTheCap() {
        List<String> tooMany = IntStream.range(0, StorageService.MAX_BATCH_SIZE + 1)
                .mapToObj(i -> "customers/42/issues/temp/" + i + ".jpg")
                .toList();

        assertThatThrownBy(() -> storageService.getPresignedUrls(caller.id(), tooMany))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // --- retrieveBySignedUrl (backend MS9 §3/§4) ---

    @Test
    void retrieveBySignedUrl_succeedsForAValidUnexpiredSignature() {
        String key = "customers/42/issues/temp/photo.png";
        long expires = Instant.now().getEpochSecond() + 60;
        String signature = urlSigner.sign(key, expires);
        when(storageClient.download(key)).thenReturn(new byte[]{9, 9, 9});

        RetrievedImage image = storageService.retrieveBySignedUrl(key, expires, signature);

        assertThat(image.content()).containsExactly(9, 9, 9);
        assertThat(image.contentType()).isEqualTo("image/png");
    }

    @Test
    void retrieveBySignedUrl_rejectsATamperedSignatureWith401() {
        String key = "customers/42/issues/temp/photo.png";
        long expires = Instant.now().getEpochSecond() + 60;
        String tampered = "clearly-not-a-valid-signature";

        assertThatThrownBy(() -> storageService.retrieveBySignedUrl(key, expires, tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void retrieveBySignedUrl_rejectsAnExpiredSignatureWith401() {
        String key = "customers/42/issues/temp/photo.png";
        long expiredAt = Instant.now().getEpochSecond() - 60;
        String signature = urlSigner.sign(key, expiredAt);

        assertThatThrownBy(() -> storageService.retrieveBySignedUrl(key, expiredAt, signature))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void retrieveBySignedUrl_rejectsMissingParamsWith401() {
        String key = "customers/42/issues/temp/photo.png";

        assertThatThrownBy(() -> storageService.retrieveBySignedUrl(key, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void retrieveBySignedUrl_rejectsWith401WhenNoLocalHmacUrlSignerBeanExists() {
        // Mirrors s3 mode, where LocalHmacUrlSigner never exists as a bean -- this route is
        // categorically unreachable with a valid signature under s3 mode.
        StorageService s3ModeService = new StorageService(storageClient, Optional.empty(), TTL_SECONDS);
        String key = "customers/42/issues/temp/photo.png";
        long expires = Instant.now().getEpochSecond() + 60;

        assertThatThrownBy(() -> s3ModeService.retrieveBySignedUrl(key, expires, "any-signature"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }
}
