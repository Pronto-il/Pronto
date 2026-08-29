package com.pronto.storage.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.common.security.UploadOwner;
import com.pronto.storage.client.LocalHmacUrlSigner;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.PresignedImageUrlEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>The central claim of guest image upload: there is one upload policy, not two.</b>
 *
 * <p>Every rule that decides whether an upload is accepted is asserted here <em>as a parameterised
 * test over both kinds of owner</em>, rather than as an authenticated case plus a separate guest
 * case that happens to use the same numbers today. Written this way deliberately: a guest-specific
 * constant added later would not merely fail a test, it would have nowhere to be added without one
 * of these parameters disagreeing with the other.
 *
 * <p>Ownership itself — who may read or claim which key — is asserted below the parity block, since
 * that is the one thing that genuinely does differ between the two owners.
 */
class GuestUploadPolicyParityTest {

    private static final long TTL_SECONDS = 300;
    private static final String GUEST_ID = "2f1c9d8e-4b7a-4c3d-9e2f-1a2b3c4d5e6f";
    private static final String OTHER_GUEST_ID = "9a8b7c6d-5e4f-4a3b-8c9d-0e1f2a3b4c5d";

    private static final UploadOwner CUSTOMER = UploadOwner.customer(42L);
    private static final UploadOwner GUEST = UploadOwner.guest(GUEST_ID);

    private StorageClient storageClient;
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageClient = Mockito.mock(StorageClient.class);
        storageService = new StorageService(storageClient, Optional.of(new LocalHmacUrlSigner("test-hmac-secret")),
                TTL_SECONDS);
    }

    /** The two owners every rule below is asserted against, together. */
    static Stream<UploadOwner> bothOwners() {
        return Stream.of(CUSTOMER, GUEST);
    }

    // ---- 3, 4, 5. Identical validation for both owners ----

    @ParameterizedTest(name = "{0} — unsupported content type is rejected identically")
    @MethodSource("bothOwners")
    void unsupportedContentTypeIsRejectedIdentically(UploadOwner owner) {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe",
                "application/octet-stream", "content".getBytes());

        assertThatThrownBy(() -> storageService.upload(owner, file))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.UNSUPPORTED_IMAGE_TYPE));
        verify(storageClient, never()).upload(anyString(), any(), anyString());
    }

    @ParameterizedTest(name = "{0} — a file over the 8 MB cap is rejected identically")
    @MethodSource("bothOwners")
    void oversizedFileIsRejectedIdentically(UploadOwner owner) {
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                new byte[(int) StorageService.MAX_SIZE_BYTES + 1]);

        assertThatThrownBy(() -> storageService.upload(owner, file))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.IMAGE_TOO_LARGE));
        verify(storageClient, never()).upload(anyString(), any(), anyString());
    }

    @ParameterizedTest(name = "{0} — a file exactly at the cap is accepted identically")
    @MethodSource("bothOwners")
    void aFileExactlyAtTheCapIsAcceptedIdentically(UploadOwner owner) {
        // The boundary itself, so that "same limit" means the same number and not merely the same
        // direction.
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                new byte[(int) StorageService.MAX_SIZE_BYTES]);
        stubEchoingUpload();

        assertThat(storageService.upload(owner, file).sizeBytes()).isEqualTo(StorageService.MAX_SIZE_BYTES);
    }

    @ParameterizedTest(name = "{0} — a missing file is rejected identically")
    @MethodSource("bothOwners")
    void aMissingFileIsRejectedIdentically(UploadOwner owner) {
        assertThatThrownBy(() -> storageService.upload(owner, null))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.VALIDATION_ERROR));
    }

    @ParameterizedTest(name = "{0} — the accepted content types are exactly the same three")
    @MethodSource("bothOwners")
    void theAllowListIsTheSameThreeTypes(UploadOwner owner) {
        stubEchoingUpload();

        assertThat(uploadExtension(owner, "image/jpeg")).isEqualTo("jpg");
        assertThat(uploadExtension(owner, "image/png")).isEqualTo("png");
        assertThat(uploadExtension(owner, "image/webp")).isEqualTo("webp");
        assertThatThrownBy(() -> storageService.upload(owner,
                new MockMultipartFile("file", "x.gif", "image/gif", "bytes".getBytes())))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.UNSUPPORTED_IMAGE_TYPE));
    }

    @ParameterizedTest(name = "{0} — the batch presign cap is the same number")
    @MethodSource("bothOwners")
    void theBatchPresignCapIsTheSameNumber(UploadOwner owner) {
        List<String> tooMany = IntStream.range(0, StorageService.MAX_BATCH_SIZE + 1)
                .mapToObj(i -> keyFor(owner, i + ".jpg"))
                .toList();

        assertThatThrownBy(() -> storageService.getPresignedUrls(owner, tooMany))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.VALIDATION_ERROR));
    }

    @ParameterizedTest(name = "{0} — the key template and presigned-URL TTL are the same")
    @MethodSource("bothOwners")
    void theKeyTemplateAndTtlAreTheSame(UploadOwner owner) {
        stubEchoingUpload();

        ImageUploadResponse response = storageService.upload(owner,
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", "bytes".getBytes()));

        // Same shape, same /issues/temp/ segment, same uuid.ext filename -- only the owner segment
        // differs, which is the entire intended difference.
        String expectedPrefix = owner.isCustomer() ? "customers/42/issues/temp/" : "guests/" + GUEST_ID + "/issues/temp/";
        assertThat(response.imageKey()).startsWith(expectedPrefix).endsWith(".jpg");
        assertThat(response.imageKey().substring(expectedPrefix.length())).hasSize("uuid.jpg".length() + 32);
        assertThat(storageService.getPresignedUrlTtlSeconds()).isEqualTo(TTL_SECONDS);
    }

    // ---- 1, 2. A guest can obtain an authorized upload ----

    @Test
    void aGuestUploadsThroughTheSameCodePathAndGetsAUsableUrlBack() {
        when(storageClient.upload(anyString(), any(), eq("image/jpeg")))
                .thenAnswer(i -> new StoredObject(i.getArgument(0), "http://localhost:8080/presigned", "image/jpeg", 5));

        ImageUploadResponse response = storageService.upload(GUEST,
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", "bytes".getBytes()));

        assertThat(response.imageKey()).startsWith("guests/" + GUEST_ID + "/issues/temp/");
        // The thumbnail works immediately, with no separate presign call -- exactly as it does for a
        // customer, because it is the same StoredObject#url the same StorageClient minted.
        assertThat(response.imageUrl()).isEqualTo("http://localhost:8080/presigned");
    }

    // ---- 7, 8. Cross-owner isolation ----

    @Test
    void guestACannotPresignGuestBsImage() {
        assertThatThrownBy(() -> storageService.getPresignedUrl(GUEST, guestKey(OTHER_GUEST_ID, "b.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
    }

    @Test
    void anAuthenticatedCustomerCannotPresignAGuestsImageWithoutThatGuestsSession() {
        // Holding an account proves nothing about a guest namespace. This is the "authenticated
        // user A must not attach guest B's images" case at the storage layer.
        assertThatThrownBy(() -> storageService.getPresignedUrl(CUSTOMER, guestKey(GUEST_ID, "a.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
    }

    @Test
    void aGuestCannotPresignACustomersImage() {
        assertThatThrownBy(() -> storageService.getPresignedUrl(GUEST, "customers/42/issues/temp/a.jpg"))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
    }

    @Test
    void aForgedGuestKeyThatIsNotAUuidIsRefused() {
        // The owner segment is regex-pinned to a UUID, so a key cannot smuggle a traversal or a
        // partial match into the namespace comparison.
        assertThatThrownBy(() -> storageService.getPresignedUrl(GUEST, "guests/" + GUEST_ID + "x/issues/temp/a.jpg"))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> storageService.getPresignedUrl(GUEST, "guests/../customers/42/issues/temp/a.jpg"))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
    }

    // ---- 10. The auth transition ----

    @Test
    void aGuestWhoSignsInStillOwnsBothNamespaces() {
        UploadOwner transitioned = new UploadOwner(42L, GUEST_ID);
        when(storageClient.presignUrl(anyString(), any())).thenReturn("http://x/signed");

        // The photos uploaded before registering are still readable...
        assertThat(storageService.getPresignedUrl(transitioned, guestKey(GUEST_ID, "before.jpg")))
                .isEqualTo("http://x/signed");
        // ...and so is anything uploaded after.
        assertThat(storageService.getPresignedUrl(transitioned, "customers/42/issues/temp/after.jpg"))
                .isEqualTo("http://x/signed");
        // Without becoming a skeleton key for anyone else's.
        assertThatThrownBy(() -> storageService.getPresignedUrl(transitioned, guestKey(OTHER_GUEST_ID, "b.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
    }

    // ---- 9. Surviving navigation: the batch re-resolve a resumed guest draft depends on ----

    @Test
    void aGuestsPausedDraftReResolvesItsOwnKeysAndSkipsForeignOnes() {
        when(storageClient.presignUrl(eq(guestKey(GUEST_ID, "a.jpg")), any())).thenReturn("http://x/a");

        List<PresignedImageUrlEntry> result = storageService.getPresignedUrls(GUEST,
                List.of(guestKey(GUEST_ID, "a.jpg"), guestKey(OTHER_GUEST_ID, "b.jpg")));

        assertThat(result).containsExactly(new PresignedImageUrlEntry(guestKey(GUEST_ID, "a.jpg"), "http://x/a"));
    }

    // ---- 11. Promotion at the booking commit ----

    @Test
    void promotionCopiesOntoTheCustomerNamespacePreservingTheFilename() {
        String guestKey = guestKey(GUEST_ID, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg");

        String promoted = storageService.promoteGuestImage(new UploadOwner(42L, GUEST_ID), guestKey, 42L);

        assertThat(promoted).isEqualTo("customers/42/issues/temp/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg");
        verify(storageClient).copy(guestKey, promoted);
        // The original is NOT deleted here -- that happens after the transaction commits, so a
        // rolled-back booking does not destroy the customer's photos.
        verify(storageClient, never()).delete(anyString());
    }

    @Test
    void promotionIsIdempotentAcrossARetriedCommit() {
        String guestKey = guestKey(GUEST_ID, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg");
        UploadOwner owner = new UploadOwner(42L, GUEST_ID);

        assertThat(storageService.promoteGuestImage(owner, guestKey, 42L))
                .isEqualTo(storageService.promoteGuestImage(owner, guestKey, 42L));
    }

    @Test
    void promotionRefusesAKeyTheCallerDoesNotOwn() {
        // 12, at the storage layer: the ownership check is re-run here even though IssuesService
        // already ran it, because this is the operation that writes into another id's namespace.
        assertThatThrownBy(() -> storageService.promoteGuestImage(
                new UploadOwner(42L, GUEST_ID), guestKey(OTHER_GUEST_ID, "b.jpg"), 42L))
                .isInstanceOf(ApiException.class)
                .satisfies(sameCode(ErrorCode.FORBIDDEN));
        verify(storageClient, never()).copy(anyString(), anyString());
    }

    // ---- 14. The authenticated path is untouched ----

    @Test
    void theAuthenticatedUserOverloadStillProducesTheOriginalCustomerKey() {
        stubEchoingUpload();

        ImageUploadResponse response = storageService.upload(new AuthenticatedUser(42L, "CUSTOMER"),
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", "bytes".getBytes()));

        assertThat(response.imageKey()).startsWith("customers/42/issues/temp/").endsWith(".jpg");
    }

    // ---- helpers ----

    private static Consumer<Throwable> sameCode(ErrorCode expected) {
        return e -> assertThat(((ApiException) e).getCode()).isEqualTo(expected);
    }

    private static String guestKey(String guestId, String fileName) {
        return "guests/" + guestId + "/issues/temp/" + fileName;
    }

    private static String keyFor(UploadOwner owner, String fileName) {
        return owner.isCustomer() ? "customers/42/issues/temp/" + fileName : guestKey(GUEST_ID, fileName);
    }

    private void stubEchoingUpload() {
        when(storageClient.upload(anyString(), any(), anyString()))
                .thenAnswer(i -> new StoredObject(i.getArgument(0), "http://x/signed", i.getArgument(2),
                        ((byte[]) i.getArgument(1)).length));
    }

    private String uploadExtension(UploadOwner owner, String contentType) {
        String key = storageService.upload(owner,
                new MockMultipartFile("file", "photo", contentType, "bytes".getBytes())).imageKey();
        return key.substring(key.lastIndexOf('.') + 1);
    }
}
