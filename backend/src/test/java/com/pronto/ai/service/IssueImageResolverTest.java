package com.pronto.ai.service;

import com.pronto.ai.Deadline;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Attachment resolution on the interactive path: concurrent, bounded, all-or-nothing.
 *
 * <p><b>What this changed and what it deliberately did not.</b> Downloads used to be sequential,
 * so six attached photos were six round trips end to end <i>before</i> the model call started —
 * a customer's whole latency budget could be spent before any classification began. They are
 * independent object-storage GETs, so the only thing making them serial was that nothing had said
 * otherwise.
 *
 * <p>Concurrency changes how long this takes and nothing about what it returns. The failure
 * policy is unchanged and is asserted here precisely because a "make it faster" change is exactly
 * where evidence quietly starts getting dropped: every requested key must still resolve, and a
 * slow photo is never silently discarded to make the budget.
 */
class IssueImageResolverTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

    private IssueImageResolver resolverFor(StorageClient storage) {
        return new IssueImageResolver(storage);
    }

    private static StorageClient storageReturning(byte[] bytes) {
        StorageClient storage = mock(StorageClient.class);
        when(storage.download(anyString())).thenReturn(bytes);
        return storage;
    }

    @Test
    void everyRequestedAttachmentIsReturnedInTheOrderItWasAskedFor() {
        // Order matters: the evidence prompt says "N image(s) are attached" and the model reads
        // them positionally, so a concurrent implementation that returned completion order would
        // silently reorder a customer's photos.
        StorageClient storage = mock(StorageClient.class);
        when(storage.download("a.jpg")).thenReturn(new byte[]{1});
        when(storage.download("b.jpg")).thenReturn(new byte[]{2});
        when(storage.download("c.jpg")).thenReturn(new byte[]{3});

        List<ImageAttachment> images = resolverFor(storage)
                .resolveRequired(List.of("a.jpg", "b.jpg", "c.jpg"), Deadline.inMillis(4_000));

        assertThat(images).extracting(ImageAttachment::key).containsExactly("a.jpg", "b.jpg", "c.jpg");
    }

    @Test
    void anEmptyOrNullKeyListCostsNothing() {
        StorageClient storage = mock(StorageClient.class);

        assertThat(resolverFor(storage).resolveRequired(List.of(), Deadline.inMillis(0))).isEmpty();
        assertThat(resolverFor(storage).resolveRequired(null, Deadline.inMillis(0))).isEmpty();
    }

    /**
     * Downloads really do overlap.
     *
     * <p>Asserted by observing concurrency directly rather than by timing: each download blocks
     * until it sees that another one has also started, so the call can only return at all if more
     * than one was in flight. A wall-clock assertion would be the flaky way to ask the same
     * question.
     */
    @Test
    void downloadsRunConcurrentlyRatherThanOneAfterAnother() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        StorageClient storage = mock(StorageClient.class);
        when(storage.download(anyString())).thenAnswer(invocation -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            Thread.sleep(120);
            concurrent.decrementAndGet();
            return JPEG;
        });

        resolverFor(storage).resolveRequired(List.of("a.jpg", "b.jpg", "c.jpg", "d.jpg"),
                Deadline.inMillis(10_000));

        assertThat(peak.get()).as("more than one download was in flight at once").isGreaterThan(1);
    }

    // ---- failure policy: unchanged, and deliberately strict --------------------------------------

    @Test
    void aStorageFailureFailsTheWholeRequestRatherThanClassifyingWithoutThePhoto() {
        StorageClient storage = mock(StorageClient.class);
        when(storage.download("ok.jpg")).thenReturn(JPEG);
        when(storage.download("broken.jpg")).thenThrow(new StorageException("gone", new RuntimeException("boom")));

        assertThatThrownBy(() -> resolverFor(storage)
                .resolveRequired(List.of("ok.jpg", "broken.jpg"), Deadline.inMillis(4_000)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.STORAGE_SERVICE_ERROR));
    }

    /**
     * A storage service that is merely SLOW, against a budget that runs out.
     *
     * <p>The tempting "optimisation" here is to classify on whichever photos arrived in time.
     * That would be classifying without evidence the customer deliberately attached, while
     * reporting success — so the budget expiring is a hard {@link ErrorCode#AI_TIMEOUT}, and
     * never a partial result.
     */
    @Test
    void aSlowStorageServiceTimesOutInsteadOfSilentlyDroppingAttachedEvidence() {
        StorageClient storage = mock(StorageClient.class);
        when(storage.download(anyString())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return JPEG;
        });

        assertThatThrownBy(() -> resolverFor(storage)
                .resolveRequired(List.of("slow.jpg"), Deadline.inMillis(300)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .as("not a partial success, and not a storage error — we ran out of time")
                        .isEqualTo(ErrorCode.AI_TIMEOUT));
    }

    @Test
    void anAlreadySpentBudgetIsRefusedBeforeAnyDownloadIsStarted() {
        StorageClient storage = mock(StorageClient.class);

        assertThatThrownBy(() -> resolverFor(storage)
                .resolveRequired(List.of("a.jpg"), Deadline.inMillis(0)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.AI_TIMEOUT));

        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    // ---- the background path keeps its old behaviour ---------------------------------------------

    @Test
    void bestEffortResolutionStillSkipsUnreadableKeysForTheBackgroundBrief() {
        StorageClient storage = mock(StorageClient.class);
        when(storage.download("ok.jpg")).thenReturn(JPEG);
        when(storage.download("broken.jpg")).thenThrow(new StorageException("gone", new RuntimeException("boom")));

        // A partial brief beats no brief — the opposite trade from the interactive path, and it
        // must survive the concurrency change untouched.
        assertThat(resolverFor(storage).resolveBestEffort(List.of("ok.jpg", "broken.jpg")))
                .extracting(ImageAttachment::key)
                .containsExactly("ok.jpg");
    }

    @Test
    void theUnboundedOverloadStillWorksForCallersWithNoBudget() {
        assertThat(resolverFor(storageReturning(JPEG)).resolveRequired(List.of("a.jpg")))
                .hasSize(1);
    }
}
