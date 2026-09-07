package com.pronto.ai.service;

import com.pronto.ai.Deadline;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * How many attachments are downloaded at once on the interactive path.
     *
     * <p>Downloads were sequential, so six photos cost six round trips end to end before the model
     * call had even started — the customer's whole budget could be spent before any classification
     * began. They are independent GETs against object storage, so the only reason to serialise
     * them was that nothing had said otherwise.
     *
     * <p>Bounded rather than unbounded: the cap is what keeps a burst of concurrent classifications
     * from turning into an unbounded fan-out against storage. Four covers the common case (the
     * uploader allows six, most customers attach one or two) in a single wave without making one
     * request capable of monopolising the pool.
     */
    private static final int MAX_CONCURRENT_DOWNLOADS = 4;

    private final StorageClient storageClient;
    /**
     * Shared, daemon, bounded. Deliberately not a per-call executor: creating and tearing down a
     * pool per classification would add thread churn to the exact path being optimised, and a
     * per-call pool has no global ceiling, so N concurrent requests would each get their own N
     * threads against storage.
     */
    private final ExecutorService downloadExecutor;

    public IssueImageResolver(StorageClient storageClient) {
        this.storageClient = storageClient;
        this.downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS, runnable -> {
            Thread thread = new Thread(runnable, "issue-image-download");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * All-or-nothing: a storage failure surfaces as {@code STORAGE_SERVICE_ERROR}. Used on
     * the interactive classification path, where silently classifying without a photo the
     * customer deliberately attached would be misleading.
     */
    public List<ImageAttachment> resolveRequired(List<String> imageKeys) {
        return resolveRequired(imageKeys, Deadline.unbounded());
    }

    /**
     * As above, bounded by {@code deadline} and downloading up to
     * {@value #MAX_CONCURRENT_DOWNLOADS} attachments concurrently.
     *
     * <p><b>Still all-or-nothing.</b> Concurrency changes how long this takes, never what it
     * returns: every requested key must resolve, and a failure is still a hard
     * {@code STORAGE_SERVICE_ERROR}. Dropping a slow photo to make the budget would be silently
     * classifying without evidence the customer deliberately attached, which is the one outcome
     * this method exists to prevent — so a deadline that expires here fails the request with
     * {@code AI_TIMEOUT} rather than proceeding on a subset.
     *
     * <p>Ownership validation is unaffected: it happens in {@code issues.service.IssuesService}
     * before any key reaches this class, and nothing here can widen the set of keys being read.
     */
    public List<ImageAttachment> resolveRequired(List<String> imageKeys, Deadline deadline) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return List.of();
        }
        deadline.requireBudget("image resolution");

        List<Future<ImageAttachment>> futures = new ArrayList<>(imageKeys.size());
        for (String key : imageKeys) {
            futures.add(downloadExecutor.submit(() -> toAttachment(key, storageClient.download(key))));
        }

        List<ImageAttachment> images = new ArrayList<>(imageKeys.size());
        try {
            for (int index = 0; index < futures.size(); index++) {
                // Each wait is capped by whatever is left of the shared budget, so the downloads
                // collectively cannot outlast it however many there are.
                long remaining = deadline.remainingMillis();
                if (remaining <= 0) {
                    throw new TimeoutException("image download budget exhausted");
                }
                images.add(futures.get(index).get(remaining, TimeUnit.MILLISECONDS));
            }
        } catch (TimeoutException e) {
            cancelAll(futures);
            log.warn("ai.image.resolve.timeout keys={} remainingMillis={}",
                    imageKeys.size(), deadline.remainingMillis());
            throw new ApiException(ErrorCode.AI_TIMEOUT,
                    "Attached images could not be read within the time budget.");
        } catch (InterruptedException e) {
            cancelAll(futures);
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to resolve an attached image.");
        } catch (ExecutionException e) {
            cancelAll(futures);
            log.warn("ai.image.resolve.failed reason={}",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to resolve an attached image.");
        }
        return images;
    }

    /**
     * Abandons the downloads still outstanding once the batch has already failed. Their results
     * can no longer be used, and leaving them running holds pool threads against storage for a
     * request that has ended.
     */
    private void cancelAll(List<Future<ImageAttachment>> futures) {
        futures.forEach(future -> future.cancel(true));
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
