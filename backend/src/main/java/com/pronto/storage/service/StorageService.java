package com.pronto.storage.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.ImageKeyUtils;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.RetrievedImage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST}/{@code GET /api/storage/images}, per
 * {@code docs/architecture/api-contract-issues.md} §2.3-2.4.
 */
@Service
public class StorageService {

    /** §2.3's recommended 8 MB cap. Spring's multipart limit (application.yml) is the
     *  first line of defense (rejects earlier, before bytes are even fully read into this
     *  service); this is a defense-in-depth re-check against the same number. */
    static final long MAX_SIZE_BYTES = 8L * 1024 * 1024;

    private final StorageClient storageClient;

    public StorageService(StorageClient storageClient) {
        this.storageClient = storageClient;
    }

    public ImageUploadResponse upload(AuthenticatedUser caller, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("file", "is required")));
        }

        ImageContentType type = ImageContentType.fromContentType(file.getContentType())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                        "Unsupported image content type: " + file.getContentType()));

        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(ErrorCode.IMAGE_TOO_LARGE, "File exceeds the maximum allowed size of 8 MB.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to read uploaded file.");
        }

        String key = "customers/" + caller.id() + "/issues/temp/" + UUID.randomUUID() + "." + type.extension();

        StoredObject stored;
        try {
            stored = storageClient.upload(key, content, type.contentType());
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to store uploaded image.");
        }

        return new ImageUploadResponse(stored.key(), stored.url(), stored.contentType(), stored.sizeBytes());
    }

    /**
     * §2.4 steps 2-4. Ownership mismatch (including an unparseable key) is always
     * {@code 403 FORBIDDEN} — never {@code 404} — so a caller can't distinguish "not yours"
     * from "doesn't exist" by probing (§2.4's explicit anti-enumeration requirement).
     */
    public RetrievedImage retrieve(AuthenticatedUser caller, String key) {
        boolean ownedByCaller = ImageKeyUtils.belongsTo(key, caller.id());
        if (!ownedByCaller) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You do not have access to this image.");
        }

        boolean exists;
        try {
            exists = storageClient.exists(key);
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to check image existence.");
        }
        if (!exists) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Image not found.");
        }

        byte[] content;
        try {
            content = storageClient.download(key);
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to read image.");
        }

        String contentType = ImageKeyUtils.extractExtension(key)
                .flatMap(ImageContentType::fromExtension)
                .map(ImageContentType::contentType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        return new RetrievedImage(content, contentType);
    }
}
