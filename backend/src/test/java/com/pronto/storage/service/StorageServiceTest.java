package com.pronto.storage.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.RetrievedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    private StorageClient storageClient;
    private StorageService storageService;
    private final AuthenticatedUser caller = new AuthenticatedUser(42L, "CUSTOMER");

    @BeforeEach
    void setUp() {
        storageClient = Mockito.mock(StorageClient.class);
        storageService = new StorageService(storageClient);
    }

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
    void retrieve_forbiddenWhenKeyOwnerDoesNotMatchCaller() {
        assertThatThrownBy(() -> storageService.retrieve(caller, "customers/99/issues/temp/x.jpg"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void retrieve_notFoundWhenKeyDoesNotExistInStorage() {
        when(storageClient.exists("customers/42/issues/temp/missing.jpg")).thenReturn(false);

        assertThatThrownBy(() -> storageService.retrieve(caller, "customers/42/issues/temp/missing.jpg"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void retrieve_returnsBytesAndInferredContentTypeWhenOwnedAndExisting() {
        String key = "customers/42/issues/temp/photo.png";
        when(storageClient.exists(key)).thenReturn(true);
        when(storageClient.download(key)).thenReturn(new byte[]{9, 9, 9});

        RetrievedImage image = storageService.retrieve(caller, key);

        assertThat(image.content()).containsExactly(9, 9, 9);
        assertThat(image.contentType()).isEqualTo("image/png");
    }
}
