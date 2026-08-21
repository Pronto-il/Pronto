package com.pronto.ai.service;

import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.ai.dto.LikelyIssue;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates the Professional Brief — the second AI responsibility, kept in its own service,
 * its own prompt and its own call.
 *
 * <p>Runs only after the routing category is final, so no tokens are spent preparing a
 * professional while it is still genuinely unclear which trade is going. It is invoked off
 * the customer's critical path (see {@code issues.service.IssueBriefService}): a brief that
 * fails to generate degrades the professional's preparation, it never blocks a booking.
 *
 * <p>Applies the content guarantees the prompt asks for but cannot enforce — the category
 * must be a real one, the hypothesis must carry evidence to be kept, and list sizes are
 * capped so a runaway "generic toolbox" answer cannot reach the professional's screen.
 */
@Service
public class ProfessionalBriefService {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalBriefService.class);

    /**
     * Caps on the advisory lists. Not arbitrary trimming for its own sake: a brief is read on
     * a phone before leaving, and an over-long list is the failure mode that makes the whole
     * section ignorable.
     */
    static final int MAX_LIST_ENTRIES = 6;
    static final int MAX_EVIDENCE_ENTRIES = 4;

    private final AiClassificationClient aiClassificationClient;
    private final ServiceCategoryCatalog catalog;
    private final IssueImageResolver imageResolver;

    public ProfessionalBriefService(AiClassificationClient aiClassificationClient,
                                     ServiceCategoryCatalog catalog,
                                     IssueImageResolver imageResolver) {
        this.aiClassificationClient = aiClassificationClient;
        this.catalog = catalog;
        this.imageResolver = imageResolver;
    }

    /**
     * @param description  the customer's original words, passed through untouched
     * @param imageKeys    the issue's photos; unreadable ones are skipped rather than fatal
     * @param categoryId   the final routing category — must resolve to a real row
     * @param urgencyLabel {@code STANDARD}/{@code SOS}, or {@code null}
     * @param exchanges    every clarification question and answer
     * @throws ApiException {@code AI_SERVICE_ERROR} when no usable brief could be produced
     */
    public ProfessionalBriefResponse generate(String description, List<String> imageKeys, Long categoryId,
                                               String urgencyLabel, List<ClarificationExchange> exchanges) {
        return generateFromResolved(description, imageResolver.resolveBestEffort(imageKeys), categoryId,
                urgencyLabel, exchanges);
    }

    /**
     * Same generation, for a caller that has already resolved the images — so an operation
     * making more than one AI call encodes each photo once rather than once per call. See
     * {@code ClassificationService.classifyResolved} for why reuse stops at the boundary of a
     * single server-side operation.
     */
    public ProfessionalBriefResponse generateFromResolved(String description, List<ImageAttachment> images,
                                                            Long categoryId, String urgencyLabel,
                                                            List<ClarificationExchange> exchanges) {

        ServiceCategory category = catalog.findById(categoryId)
                .orElseThrow(() -> new ApiException(ErrorCode.AI_SERVICE_ERROR,
                        "Cannot brief a professional for an unknown category."));

        List<ClarificationExchange> priorExchanges = exchanges == null ? List.of() : List.copyOf(exchanges);

        log.info("ai.brief.started category={} images={} answers={}",
                category.code(), images.size(), priorExchanges.size());

        ProfessionalBriefRequest request = new ProfessionalBriefRequest(description, images, category.code(),
                category.nameHe(), urgencyLabel, priorExchanges);

        ProfessionalBriefResponse brief;
        try {
            brief = aiClassificationClient.generateBrief(request);
        } catch (ApiException e) {
            log.warn("ai.brief.failed category={} code={}", category.code(), e.getCode());
            throw e;
        } catch (Exception e) {
            log.warn("ai.brief.failed category={} code=UNEXPECTED", category.code(), e);
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "Professional brief generation failed.");
        }

        ProfessionalBriefResponse sanitized = sanitize(brief, images.isEmpty());

        log.info("ai.brief.completed category={} hasHypothesis={} tools={} parts={} safetyNotes={}",
                category.code(), sanitized.likelyIssue() != null, sanitized.recommendedTools().size(),
                sanitized.recommendedParts().size(), sanitized.safetyNotes().size());

        return sanitized;
    }

    /**
     * Post-validation the schema cannot express:
     * <ul>
     *   <li>image observations are dropped outright when no photo was actually sent — the one
     *       place the model could most easily fabricate evidence;</li>
     *   <li>a hypothesis with no supporting evidence is dropped rather than shown, because an
     *       unexplained guess presented next to the customer's own report is worse than no
     *       hypothesis at all;</li>
     *   <li>lists are capped.</li>
     * </ul>
     */
    ProfessionalBriefResponse sanitize(ProfessionalBriefResponse brief, boolean noImagesWereSent) {
        List<String> imageObservations = noImagesWereSent ? List.of() : cap(brief.imageObservations(), MAX_LIST_ENTRIES);
        if (noImagesWereSent && !brief.imageObservations().isEmpty()) {
            log.warn("ai.brief.sanitize dropped={} reason=image-observations-without-images",
                    brief.imageObservations().size());
        }

        LikelyIssue likelyIssue = brief.likelyIssue();
        if (likelyIssue != null && likelyIssue.evidence().isEmpty()) {
            log.warn("ai.brief.sanitize dropped=likely-issue reason=no-supporting-evidence");
            likelyIssue = null;
        } else if (likelyIssue != null) {
            likelyIssue = new LikelyIssue(likelyIssue.description(), likelyIssue.confidence(),
                    cap(likelyIssue.evidence(), MAX_EVIDENCE_ENTRIES));
        }

        return new ProfessionalBriefResponse(
                brief.customerProblemSummary(),
                brief.clarificationSummary(),
                imageObservations,
                likelyIssue,
                cap(brief.possibleCauses(), MAX_LIST_ENTRIES),
                cap(brief.recommendedTools(), MAX_LIST_ENTRIES),
                cap(brief.recommendedParts(), MAX_LIST_ENTRIES),
                cap(brief.safetyNotes(), MAX_LIST_ENTRIES));
    }

    private List<String> cap(List<String> values, int limit) {
        return values.size() <= limit ? values : List.copyOf(values.subList(0, limit));
    }
}
