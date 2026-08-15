package com.pronto.ai.service;

import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.client.ClarificationAnswer;
import com.pronto.ai.client.ClassificationResult;
import com.pronto.ai.client.ImageAttachment;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.ImageKeyUtils;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Orchestrates {@code POST /api/issues/classify} (§2.1 steps 4-5): resolves each image key
 * to bytes via {@code storage.StorageClient}, delegates to the configured
 * {@link AiClassificationClient} (mock or real OpenAI, per {@code pronto.ai.mode}), then
 * maps the result's {@code categoryCode} to a real {@code categories} row — applying the
 * {@code general_handyman} fallback (§2.1 step 5 / §4, a flagged recommendation, not a hard
 * requirement) if the AI's code doesn't match any seeded category.
 *
 * <p>Stateless: no DB write happens here or anywhere in this call path (§2.1's "no side
 * effects" rule) — {@link CategoryRepository} is read-only lookup.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);
    private static final String FALLBACK_CATEGORY_CODE = "general_handyman";

    private final AiClassificationClient aiClassificationClient;
    private final StorageClient storageClient;
    private final CategoryRepository categoryRepository;

    public ClassificationService(AiClassificationClient aiClassificationClient,
                                  StorageClient storageClient,
                                  CategoryRepository categoryRepository) {
        this.aiClassificationClient = aiClassificationClient;
        this.storageClient = storageClient;
        this.categoryRepository = categoryRepository;
    }

    public ClassificationSuggestion classify(String description, List<String> imageKeys) {
        List<ImageAttachment> images = resolveImages(imageKeys);
        ClassificationResult result = callClient(() -> aiClassificationClient.classify(description, images));
        return toSuggestion(result);
    }

    /**
     * Exactly one additional AI request after the customer answered the clarification
     * questions from a prior {@link #classify} call that returned
     * {@code ClassificationStatus.QUESTIONS} — see
     * {@code docs/architecture/api-contract-issues.md} §2.1's clarification-question
     * extension. {@code description}/{@code imageKeys} are the same original values passed
     * to {@link #classify}; there is no server-side session linking the two calls (mirrors
     * this endpoint's existing stateless design), so the caller round-trips them.
     */
    public ClassificationSuggestion classifyWithClarification(String description, List<String> imageKeys,
                                                                List<ClarificationAnswer> clarificationAnswers) {
        List<ImageAttachment> images = resolveImages(imageKeys);
        ClassificationResult result = callClient(() ->
                aiClassificationClient.classifyWithClarification(description, images, clarificationAnswers));
        return toSuggestion(result);
    }

    private ClassificationResult callClient(Supplier<ClassificationResult> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI classification client threw an unexpected exception.", e);
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "AI classification service failed to produce a result.");
        }
    }

    /**
     * A {@code QUESTIONS} result has no category to resolve — it's passed straight through.
     * A {@code CLASSIFIED} result gets the existing category-code-to-row mapping, with the
     * {@code general_handyman} fallback if the AI's code doesn't match any seeded category.
     */
    private ClassificationSuggestion toSuggestion(ClassificationResult result) {
        if (result.status() == ClassificationStatus.QUESTIONS) {
            return new ClassificationSuggestion(ClassificationStatus.QUESTIONS, null, null,
                    result.confidence(), result.explanation(), result.questions());
        }

        List<Category> categories = categoryRepository.findAll();
        Category matched = categories.stream()
                .filter(c -> c.getCode().equalsIgnoreCase(result.categoryCode()))
                .findFirst()
                .orElse(null);

        Double confidence = result.confidence();
        String explanation = result.explanation();

        if (matched == null) {
            log.warn("AI returned unrecognized category code '{}'; falling back to '{}'.",
                    result.categoryCode(), FALLBACK_CATEGORY_CODE);
            matched = categories.stream()
                    .filter(c -> c.getCode().equals(FALLBACK_CATEGORY_CODE))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Seeded category '" + FALLBACK_CATEGORY_CODE + "' is missing from the categories table."));
            confidence = null;
        }

        return new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, matched.getId(), matched.getCode(),
                confidence, explanation, List.of());
    }

    private List<ImageAttachment> resolveImages(List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return List.of();
        }
        List<ImageAttachment> images = new ArrayList<>();
        for (String key : imageKeys) {
            byte[] bytes;
            try {
                bytes = storageClient.download(key);
            } catch (StorageException e) {
                throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to resolve an attached image.");
            }
            String contentType = ImageKeyUtils.extractExtension(key)
                    .flatMap(ImageContentType::fromExtension)
                    .map(ImageContentType::contentType)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            images.add(new ImageAttachment(key, bytes, contentType));
        }
        return images;
    }
}
