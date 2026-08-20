import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition, Transition, Variants } from 'framer-motion';
import { PageHeader } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { classifyIssue, getPresignedImageUrls, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type {
  ClassifyIssueResponse,
  IssueResponse,
  IssueUrgencyType,
  PresignedImageUrlsResponse,
} from '../../shared/api';
import { useBookingDraft } from '../../shared/hooks';
import type { BookingDraftPhoto } from '../../shared/hooks';
import { stepTransition } from '../../shared/motion/variants';
import { DescribeIssueStep } from './DescribeIssueStep';
import { ClarifyQuestionsStep } from './ClarifyQuestionsStep';
import { ReviewStep } from './ReviewStep';
import { AiAnalyzingOverlay } from './AiAnalyzingOverlay';
import styles from './NewIssuePage.module.css';

type Step =
  | { name: 'describe' }
  | { name: 'clarify'; classification: ClassifyIssueResponse }
  | { name: 'review'; classification: ClassifyIssueResponse };

const STEP_LABELS: Partial<Record<Step['name'], string>> = {
  describe: 'שלב 1 מתוך 3',
  clarify: 'שלב 2 מתוך 3',
  review: 'שלב 3 מתוך 3',
};

/** Feeds `PageHeader`'s `steps` progress-bar prop (design doc §7.1). Every step in this
 *  machine is an in-progress step now: a confirmed issue leaves this page immediately for the
 *  profession-matching screen, so there is no arrival step here to omit. */
const STEP_NUMBERS: Partial<Record<Step['name'], number>> = {
  describe: 1,
  clarify: 2,
  review: 3,
};

const ISSUE_DRAFT_STAGES = new Set(['ISSUE_DESCRIBE', 'ISSUE_CLARIFY', 'ISSUE_REVIEW']);

function toDraftPhotos(photos: UploadedPhoto[]): BookingDraftPhoto[] {
  return photos.map((photo) => ({ imageKey: photo.imageKey }));
}

/**
 * "יש לי תקלה" — the customer issue-report flow (README's "Home / New Issue" + "AI Review"
 * screens, Milestone 2). A single route holding a small step machine rather than one route
 * per step, since going "back" from review/clarify must preserve the description/photos
 * already entered — a subtle step indicator (DESIGN_SYSTEM.md §38), not a wizard UI.
 *
 * The only component in `features/issues` that touches `useBookingDraft()` (design doc §4.5)
 * — child step components stay draft-unaware, unchanged in their own prop contracts. Hydrates
 * from an in-progress issue-creation draft on mount (§4.4's resume table) and writes through
 * on step transitions; issue creation itself is explicitly NOT a clear-trigger — confirming
 * the issue transitions the draft *forward* to `ADDRESS_SELECTION` instead.
 */
export default function NewIssuePage() {
  const navigate = useNavigate();
  const { draft, updateDraft } = useBookingDraft();

  // Snapshotted once, at mount, via `useRef`'s "first call wins" semantics — deliberately NOT
  // re-derived from the live `draft` on every render, since this page's own later
  // `updateDraft` calls (a normal part of live step transitions) would otherwise make
  // `canHydrate` flip to `true` mid-session and incorrectly re-trigger the resume flow below.
  const initialDraft = useRef(draft).current;
  const canHydrate = initialDraft !== null && ISSUE_DRAFT_STAGES.has(initialDraft.stage);

  // Whether there's an in-progress *booking* draft (already past issue creation, i.e. has an
  // issueId) that starting a fresh issue here would silently overwrite/destroy (§4.5.1).
  const [hasConflictingDraft] = useState(() => Boolean(initialDraft && initialDraft.issueId !== undefined));
  const [warningDismissed, setWarningDismissed] = useState(false);

  const [description, setDescription] = useState(() => (canHydrate ? initialDraft!.description : ''));
  // Hydrated with `previewUrl: null` — every persisted draft photo only ever carries a bare
  // `imageKey` (backend MS9 §12.1: a resolved URL is never persisted), so nothing is
  // displayable yet until the resume effect below re-resolves fresh presigned URLs.
  const [photos, setPhotos] = useState<UploadedPhoto[]>(() =>
    canHydrate ? initialDraft!.photos.map((photo) => ({ imageKey: photo.imageKey, imageUrl: '', previewUrl: null })) : [],
  );
  const [urgencyType, setUrgencyType] = useState<IssueUrgencyType>(() =>
    canHydrate ? initialDraft!.urgencyType : 'STANDARD',
  );
  const [step, setStep] = useState<Step>({ name: 'describe' });
  const [isResuming, setIsResuming] = useState(() => canHydrate && initialDraft!.stage !== 'ISSUE_DESCRIBE');
  const [resumeError, setResumeError] = useState<string | null>(null);
  // Design doc §2.2/§2.5 — purely local, no persistence, no draft interaction.
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  /** `1` = advancing forward, `-1` = going back — drives `stepTransition`'s slide direction. */
  const [direction, setDirection] = useState(1);
  const shouldReduceMotion = useReducedMotion();
  /** Non-blocking notice for when the resume-time photo re-resolution (below) came back with
   *  fewer entries than requested — backend MS9 design §12.5, "graceful, not all-or-nothing"
   *  degradation. Reuses the existing `warningBanner` styling this file already uses twice. */
  const [photoWarning, setPhotoWarning] = useState<string | null>(null);
  const hasAttemptedResume = useRef(false);

  // Resume-time photo re-resolution (backend MS9 §12.4/§12.5): matches each still-present
  // draft photo (by imageKey) against the batch response, filling in imageUrl/previewUrl.
  // Any imageKey missing from the response is dropped from state entirely — the next
  // updateDraft call (already made on every step transition) then persists the narrowed
  // array, so the stale key self-heals rather than reappearing on a future resume.
  function applyResolvedPhotos(response: PresignedImageUrlsResponse) {
    const resolvedByKey = new Map(response.images.map((entry) => [entry.imageKey, entry.imageUrl]));
    setPhotos((prev) => {
      const next = prev
        .filter((photo) => resolvedByKey.has(photo.imageKey))
        .map((photo) => {
          const url = resolvedByKey.get(photo.imageKey)!;
          return { ...photo, imageUrl: url, previewUrl: url };
        });
      if (next.length < prev.length) {
        setPhotoWarning('חלק מהתמונות שהעלית בעבר לא נמצאו והוסרו מהבקשה');
      }
      return next;
    });
  }

  // Sub-case (a) only (§12.4): the batch call itself failed outright (not a partial
  // response) while resuming at ISSUE_DESCRIBE, which has no page-level resume gate to fall
  // back through. The still-unresolved (previewUrl === null) placeholders swap their spinner
  // for an inline error, reusing PhotoUploader's existing itemError treatment; the customer
  // can remove or re-upload them and continue.
  function markUnresolvedPhotosAsFailed() {
    setPhotos((prev) =>
      prev.map((photo) => (photo.previewUrl === null ? { ...photo, error: 'לא ניתן לטעון את התמונה' } : photo)),
    );
  }

  // Resume-hydration (§4.4/§12.4): for ISSUE_CLARIFY/ISSUE_REVIEW, the AI's raw response isn't
  // persisted, so it's cheaply re-derived here by re-calling classifyIssue with the persisted
  // description/photos/clarificationAnswers, then fed into the existing step-transition logic
  // — no new branching beyond the graceful QUESTIONS-result fallback already present in
  // handleClassified's own step-selection. The photo-presign batch call runs alongside it
  // (Promise.all), gated by the same existing full-page isResuming flag. For ISSUE_DESCRIBE
  // (no existing page-level gate), only the photo-presign call fires — DescribeIssueStep
  // renders immediately, with each photo showing the reused spinner placeholder until it
  // resolves. Runs once on mount only (see `initialDraft` above).
  useEffect(() => {
    if (hasAttemptedResume.current) {
      return;
    }
    hasAttemptedResume.current = true;
    if (!canHydrate || !initialDraft) {
      return;
    }
    const imageKeys = initialDraft.photos.map((photo) => photo.imageKey);

    if (initialDraft.stage === 'ISSUE_DESCRIBE') {
      if (imageKeys.length === 0) {
        return;
      }
      getPresignedImageUrls(imageKeys)
        .then(applyResolvedPhotos)
        .catch(markUnresolvedPhotosAsFailed);
      return;
    }

    (async () => {
      try {
        const [result] = await Promise.all([
          classifyIssue({
            description: initialDraft.description,
            imageKeys,
            clarificationAnswers: initialDraft.clarificationAnswers,
          }),
          getPresignedImageUrls(imageKeys).then(applyResolvedPhotos),
        ]);
        if (result.status === 'QUESTIONS') {
          setStep({ name: 'clarify', classification: result });
        } else {
          const classification =
            initialDraft.categoryId !== undefined ? { ...result, suggestedCategoryId: initialDraft.categoryId } : result;
          setStep({ name: 'review', classification });
        }
      } catch {
        setResumeError(GENERIC_ERROR_MESSAGE);
        setStep({ name: 'describe' });
      } finally {
        setIsResuming(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleClassified(result: ClassifyIssueResponse) {
    const nextStep: Step =
      result.status === 'QUESTIONS'
        ? { name: 'clarify', classification: result }
        : { name: 'review', classification: result };
    setDirection(1);
    setStep(nextStep);
    updateDraft({
      stage: nextStep.name === 'clarify' ? 'ISSUE_CLARIFY' : 'ISSUE_REVIEW',
      urgencyType,
      description,
      photos: toDraftPhotos(photos),
      ...(nextStep.name === 'review' ? { categoryId: result.suggestedCategoryId ?? undefined } : {}),
    });
  }

  function handleBack() {
    if (step.name === 'describe') {
      navigate('/');
    } else {
      setDirection(-1);
      setStep({ name: 'describe' });
      updateDraft({ stage: 'ISSUE_DESCRIBE', urgencyType, description, photos: toDraftPhotos(photos) });
    }
  }

  function handleConfirmed(issue: IssueResponse) {
    // Issue creation is explicitly NOT a clear-trigger (§4.5.1) — the draft moves forward into
    // the booking flow instead of being discarded.
    updateDraft({
      stage: 'ADDRESS_SELECTION',
      issueId: issue.id,
      categoryId: issue.categoryId,
      urgencyType: issue.urgencyType,
    });

    // Straight to the profession-matching screen — no intermediate "we'll find you someone"
    // step, and no second button between the customer and their results. `replace` so the back
    // button doesn't return to a review step for an issue that is already created. The
    // category/urgency ride along so the common path needs no extra fetch; the matching screen
    // re-derives them from the issue itself on refresh.
    navigate(`/issues/${issue.id}/matching`, {
      replace: true,
      state: { categoryId: issue.categoryId, urgencyType: issue.urgencyType },
    });
  }

  // Same neutralization pattern `RegistrationWizardShell.tsx`/`AiAnalyzingOverlay.tsx` already
  // apply — `stepTransition`'s `animate`/`exit` targets each carry their own embedded spring
  // `transition`, which wins over a component-level `transition` prop. Copied locally per
  // design doc §2.5/§9.4 rather than extracted into `shared/motion` this milestone.
  const stepVariants: Variants = useMemo(() => {
    if (!shouldReduceMotion) {
      return stepTransition;
    }
    const instant: Transition = { duration: 0 };
    const animate = stepTransition.animate as TargetAndTransition;
    const initial = stepTransition.initial as (custom: number) => TargetAndTransition;
    const exit = stepTransition.exit as (custom: number) => TargetAndTransition;
    return {
      initial: (custom: number) => ({ ...initial(custom), transition: instant }),
      animate: { ...animate, transition: instant },
      exit: (custom: number) => ({ ...exit(custom), transition: instant }),
    };
  }, [shouldReduceMotion]);

  const stepNumber = STEP_NUMBERS[step.name];

  return (
    <div className="focused-page">
      <PageHeader
        title="יש לי תקלה"
        description={STEP_LABELS[step.name]}
        onBack={handleBack}
        steps={stepNumber !== undefined ? { current: stepNumber, total: 3 } : undefined}
      />

      {hasConflictingDraft && !warningDismissed && step.name === 'describe' && (
        <div className={styles.warningBanner} role="alert">
          <p>יש לך בקשה פעילה בתהליך הזמנה — התחלת תקלה חדשה תבטל אותה.</p>
          <button type="button" className={styles.warningDismiss} onClick={() => setWarningDismissed(true)}>
            הבנתי
          </button>
        </div>
      )}

      {/* Unified with the in-session AiAnalyzingOverlay (design doc §2.4) — always mounted so
          its own internal AnimatePresence can play the exit transition when isResuming flips
          false, instead of the previous plain-text block disappearing instantly. */}
      <AiAnalyzingOverlay variant="inline" show={isResuming} />

      {!isResuming && resumeError && step.name === 'describe' && (
        <div className={styles.warningBanner} role="alert">
          <p>{resumeError}</p>
        </div>
      )}

      {!isResuming && photoWarning && (
        <div className={styles.warningBanner} role="alert">
          <p>{photoWarning}</p>
        </div>
      )}

      {!isResuming && (
        <div className={styles.stepViewport} aria-hidden={isAnalyzing}>
          <AnimatePresence mode="wait" custom={direction}>
            <motion.div
              key={step.name}
              custom={direction}
              variants={stepVariants}
              initial="initial"
              animate="animate"
              exit="exit"
            >
              {step.name === 'describe' && (
                <DescribeIssueStep
                  description={description}
                  onDescriptionChange={setDescription}
                  photos={photos}
                  onPhotosChange={setPhotos}
                  urgencyType={urgencyType}
                  onUrgencyChange={setUrgencyType}
                  onClassified={handleClassified}
                  onAnalyzingChange={setIsAnalyzing}
                />
              )}
              {step.name === 'clarify' && (
                <ClarifyQuestionsStep
                  description={description}
                  photos={photos}
                  questions={step.classification.questions}
                  onClassified={handleClassified}
                  onAnalyzingChange={setIsAnalyzing}
                />
              )}
              {step.name === 'review' && (
                <ReviewStep
                  classification={step.classification}
                  description={description}
                  photos={photos}
                  urgencyType={urgencyType}
                  onConfirmed={handleConfirmed}
                />
              )}
            </motion.div>
          </AnimatePresence>
          <AiAnalyzingOverlay variant="overlay" show={isAnalyzing} />
        </div>
      )}
    </div>
  );
}
