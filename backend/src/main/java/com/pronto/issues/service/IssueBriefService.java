package com.pronto.issues.service;

import com.pronto.ai.config.AiAsyncConfig;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.ai.service.ClassificationService;
import com.pronto.ai.service.IssueImageResolver;
import com.pronto.ai.service.ProfessionalBriefService;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueBrief;
import com.pronto.issues.entity.IssueClarification;
import com.pronto.issues.entity.IssueClassification;
import com.pronto.issues.entity.IssueImage;
import com.pronto.issues.event.IssueCategoryChangedEvent;
import com.pronto.issues.event.IssueCreatedEvent;
import com.pronto.issues.repository.IssueBriefRepository;
import com.pronto.issues.repository.IssueClarificationRepository;
import com.pronto.issues.repository.IssueClassificationRepository;
import com.pronto.issues.repository.IssueImageRepository;
import com.pronto.issues.repository.IssueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Generates and stores the Professional Brief after an issue is created, off the customer's
 * critical path.
 *
 * <p>Triggered by {@link IssueCreatedEvent} <b>after commit</b> and on the
 * {@code aiTaskExecutor} pool: the issue is already durable before any model call starts, so
 * an OpenAI outage, timeout or malformed response can only ever downgrade the brief — never
 * the booking. Failures are recorded as {@code FAILED} rather than swallowed, so a
 * professional's screen can say something honest and an operator can see it happened.
 *
 * <p>Optionally also records what the AI independently routes this issue to
 * ({@code pronto.ai.record-final-classification}, <b>off by default</b>). That is a second
 * model call on every created issue, and paying it in production is not justified yet: the
 * labelled evaluation harness is what actually measures accuracy, and this only adds a
 * drift signal on top. The capability stays available for when that signal is wanted —
 * switch it on deliberately.
 *
 * <p>When it is on, both AI calls share one set of resolved images, so each photo is
 * downloaded and Base64-encoded once rather than once per call.
 */
@Service
public class IssueBriefService {

    private static final Logger log = LoggerFactory.getLogger(IssueBriefService.class);

    private final IssueRepository issueRepository;
    private final IssueImageRepository issueImageRepository;
    private final IssueClarificationRepository issueClarificationRepository;
    private final IssueClassificationRepository issueClassificationRepository;
    private final IssueBriefRepository issueBriefRepository;
    private final ProfessionalBriefService professionalBriefService;
    private final ClassificationService classificationService;
    private final IssueImageResolver imageResolver;
    private final boolean recordFinalClassification;

    public IssueBriefService(IssueRepository issueRepository,
                              IssueImageRepository issueImageRepository,
                              IssueClarificationRepository issueClarificationRepository,
                              IssueClassificationRepository issueClassificationRepository,
                              IssueBriefRepository issueBriefRepository,
                              ProfessionalBriefService professionalBriefService,
                              ClassificationService classificationService,
                              IssueImageResolver imageResolver,
                              @Value("${pronto.ai.record-final-classification:false}") boolean recordFinalClassification) {
        this.issueRepository = issueRepository;
        this.issueImageRepository = issueImageRepository;
        this.issueClarificationRepository = issueClarificationRepository;
        this.issueClassificationRepository = issueClassificationRepository;
        this.issueBriefRepository = issueBriefRepository;
        this.professionalBriefService = professionalBriefService;
        this.classificationService = classificationService;
        this.imageResolver = imageResolver;
        this.recordFinalClassification = recordFinalClassification;
    }

    /**
     * {@code REQUIRES_NEW} because the publishing transaction has already committed by the
     * time this runs — this method needs a transaction of its own to write into.
     */
    @Async(AiAsyncConfig.AI_TASK_EXECUTOR)
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIssueCreated(IssueCreatedEvent event) {
        generateFor(event.issueId());
    }

    /**
     * The customer re-routed their own issue to a different trade, so the brief has to be written
     * again: {@link com.pronto.ai.prompt.ProfessionalBriefPromptBuilder} states the confirmed
     * routing category outright and writes the whole brief for it, which makes a brief produced
     * for the old category actively misleading rather than merely stale.
     *
     * <p>Same after-commit, own-transaction, own-thread treatment as {@link #onIssueCreated}, and
     * {@code generateFor} reuses the existing {@code issue_briefs} row — so this overwrites the
     * brief in place rather than accumulating one per correction.
     */
    @Async(AiAsyncConfig.AI_TASK_EXECUTOR)
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIssueCategoryChanged(IssueCategoryChangedEvent event) {
        generateFor(event.issueId());
    }

    /**
     * Package-visible so it can be driven directly (tests, or a future retry path) without
     * going through the event/async machinery.
     */
    void generateFor(Long issueId) {
        Issue issue = issueRepository.findById(issueId).orElse(null);
        if (issue == null) {
            log.warn("issue.brief.skipped issueId={} reason=issue-not-found", issueId);
            return;
        }

        List<String> imageKeys = issueImageRepository.findByIssueId(issueId).stream()
                .map(IssueImage::getImageKey)
                .toList();
        List<ClarificationExchange> exchanges = issueClarificationRepository
                .findByIssueIdOrderByPositionAsc(issueId).stream()
                .map(row -> new ClarificationExchange(row.getQuestion(), row.getAnswer()))
                .toList();

        // Resolved once for this whole operation. Best-effort even for the classification pass
        // here (unlike the interactive path, which fails hard): this is background work, and a
        // photo that cannot be read is a reason for a thinner brief, not for no brief.
        List<ImageAttachment> images = imageResolver.resolveBestEffort(imageKeys);

        if (recordFinalClassification) {
            recordClassification(issue, images, exchanges);
        }

        generateBrief(issue, images, exchanges);
    }

    /**
     * A final routing pass with the clarification budget already spent, so it always commits
     * to an answer. Purely observational — it never changes {@code issues.category_id}, which
     * is the customer's confirmed choice.
     */
    private void recordClassification(Issue issue, List<ImageAttachment> images,
                                       List<ClarificationExchange> exchanges) {
        IssueClassification record = issueClassificationRepository.findById(issue.getId())
                .orElseGet(() -> new IssueClassification(issue.getId(), exchanges.size()));
        try {
            ClassificationSuggestion suggestion = classificationService.classifyResolved(
                    issue.getDescription(), images, issue.getCategoryId(), exchanges);

            if (suggestion.status() != ClassificationStatus.CLASSIFIED) {
                // Not reachable with a zero budget, but recorded rather than assumed away.
                log.warn("issue.classification.record issueId={} status={} unexpected=true",
                        issue.getId(), suggestion.status());
                return;
            }

            record.recordAiOutcome(suggestion.categoryCode(), suggestion.confidence(), suggestion.candidates(),
                    suggestion.ambiguityReason(), suggestion.lowConfidence(), suggestion.unresolved());
            issueClassificationRepository.save(record);

            boolean agreesWithCustomer = issue.getCategoryId().equals(suggestion.categoryId());
            log.info("issue.classification.recorded issueId={} aiCategory={} customerCategory={} agrees={} "
                            + "lowConfidence={} unresolved={} rounds={}",
                    issue.getId(), suggestion.categoryCode(), issue.getCategoryId(), agreesWithCustomer,
                    suggestion.lowConfidence(), suggestion.unresolved(), exchanges.size());
        } catch (Exception e) {
            // Telemetry must never take the brief down with it.
            log.warn("issue.classification.record.failed issueId={} reason={}", issue.getId(), e.getMessage());
        }
    }

    /**
     * Marks the brief {@code FAILED} and stops. **Deliberately not retried**: the customer's
     * booking is unaffected either way, a brief is preparation material rather than a
     * transactional obligation, and a retry loop around a paid model call is a cost and
     * complexity risk out of proportion to what is lost. The row records that an attempt was
     * made and failed, so this is visible rather than silent — and the professional still has
     * the description, photos, clarification answers and category, which is exactly what they
     * had before briefs existed.
     */
    private void generateBrief(Issue issue, List<ImageAttachment> images, List<ClarificationExchange> exchanges) {
        IssueBrief brief = issueBriefRepository.findById(issue.getId())
                .orElseGet(() -> new IssueBrief(issue.getId()));
        try {
            ProfessionalBriefResponse generated = professionalBriefService.generateFromResolved(
                    issue.getDescription(), images, issue.getCategoryId(),
                    issue.getUrgencyType() == null ? null : issue.getUrgencyType().name(), exchanges);

            brief.markReady(generated);
            issueBriefRepository.save(brief);
            log.info("issue.brief.ready issueId={} category={} answers={} images={}",
                    issue.getId(), issue.getCategoryId(), exchanges.size(), images.size());
        } catch (Exception e) {
            brief.markFailed();
            issueBriefRepository.save(brief);
            // Logged with the cause: this is terminal for the brief, so whatever detail exists
            // is the only record of why.
            log.warn("issue.brief.failed issueId={} retry=none", issue.getId(), e);
        }
    }
}
