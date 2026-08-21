package com.pronto.ai.service;

import com.pronto.ai.dto.ImageAttachment;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.ImageKeyUtils;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves issue image keys to bytes for the AI calls. Shared by
 * {@link ClassificationService} and {@link ProfessionalBriefService} so the download,
 * content-type derivation and failure policy exist once.
 *
 * <p>Two different failure policies on purpose — see {@link #resolveRequired} versus
 * {@link #resolveBestEffort}: an image the customer is actively waiting on is worth failing
 * the request for, while a background brief should be written from whatever is readable
 * rather than not written at all.
 *
 * <p>Never logs image bytes; only keys and sizes.
 */
@Component
public class IssueImageResolver {

    private static final Logger log = LoggerFactory.getLogger(IssueImageResolver.class);

    private final StorageClient storageClient;

    public IssueImageResolver(StorageClient storageClient) {
        this.storageClient = storageClient;
    }

    /**
     * All-or-nothing: a storage failure surfaces as {@code STORAGE_SERVICE_ERROR}. Used on
     * the interactive classification path, where silently classifying without a photo the
     * customer deliberately attached would be misleading.
     */
    public List<ImageAttachment> resolveRequired(List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return List.of();
        }
        List<ImageAttachment> images = new ArrayList<>();
        for (String key : imageKeys) {
            try {
                images.add(toAttachment(key, storageClient.download(key)));
            } catch (StorageException e) {
                log.warn("ai.image.resolve.failed key={} reason={}", key, e.getMessage());
                throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to resolve an attached image.");
            }
        }
        return images;
    }

    /**
     * Skips unreadable keys and carries on. Used by the background Professional Brief job,
     * where a partial brief beats no brief.
     */
    public List<ImageAttachment> resolveBestEffort(List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return List.of();
        }
        List<ImageAttachment> images = new ArrayList<>();
        for (String key : imageKeys) {
            try {
                images.add(toAttachment(key, storageClient.download(key)));
            } catch (StorageException e) {
                log.warn("ai.image.resolve.skipped key={} reason={}", key, e.getMessage());
            }
        }
        return images;
    }

    private ImageAttachment toAttachment(String key, byte[] bytes) {
        String contentType = ImageKeyUtils.extractExtension(key)
                .flatMap(ImageContentType::fromExtension)
                .map(ImageContentType::contentType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ImageAttachment.of(key, bytes, contentType);
    }
}
