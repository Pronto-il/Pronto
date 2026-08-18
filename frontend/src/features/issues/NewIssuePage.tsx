import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { classifyIssue, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ClassifyIssueResponse, IssueResponse, IssueUrgencyType } from '../../shared/api';
import { useBookingDraft } from '../../shared/hooks';
import type { BookingDraftPhoto } from '../../shared/hooks';
import { DescribeIssueStep } from './DescribeIssueStep';
import { ClarifyQuestionsStep } from './ClarifyQuestionsStep';
import { ReviewStep } from './ReviewStep';
import { IssueSuccessStep } from './IssueSuccessStep';
import styles from './NewIssuePage.module.css';

type Step =
  | { name: 'describe' }
  | { name: 'clarify'; classification: ClassifyIssueResponse }
  | { name: 'review'; classification: ClassifyIssueResponse }
  | { name: 'success'; issue: IssueResponse };

const STEP_LABELS: Partial<Record<Step['name'], string>> = {
  describe: 'שלב 1 מתוך 3',
  clarify: 'שלב 2 מתוך 3',
  review: 'שלב 3 מתוך 3',
};

const ISSUE_DRAFT_STAGES = new Set(['ISSUE_DESCRIBE', 'ISSUE_CLARIFY', 'ISSUE_REVIEW']);

function toDraftPhotos(photos: UploadedPhoto[]): BookingDraftPhoto[] {
  return photos.map((photo) => ({ imageKey: photo.imageKey, imageUrl: photo.imageUrl }));
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
  const [photos, setPhotos] = useState<UploadedPhoto[]>(() =>
    canHydrate
      ? initialDraft!.photos.map((photo) => ({ imageKey: photo.imageKey, imageUrl: photo.imageUrl, previewUrl: photo.imageUrl }))
      : [],
  );
  const [urgencyType, setUrgencyType] = useState<IssueUrgencyType>(() =>
    canHydrate ? initialDraft!.urgencyType : 'STANDARD',
  );
  const [step, setStep] = useState<Step>({ name: 'describe' });
  const [isResuming, setIsResuming] = useState(() => canHydrate && initialDraft!.stage !== 'ISSUE_DESCRIBE');
  const [resumeError, setResumeError] = useState<string | null>(null);
  const hasAttemptedResume = useRef(false);

  // Resume-hydration for ISSUE_CLARIFY/ISSUE_REVIEW (§4.4): the AI's raw response isn't
  // persisted, so it's cheaply re-derived here by re-calling classifyIssue with the persisted
  // description/photos/clarificationAnswers, then fed into the existing step-transition logic
  // — no new branching beyond the graceful QUESTIONS-result fallback already present in
  // handleClassified's own step-selection. Runs once on mount only (see `initialDraft` above).
  useEffect(() => {
    if (hasAttemptedResume.current) {
      return;
    }
    hasAttemptedResume.current = true;
    if (!canHydrate || !initialDraft || initialDraft.stage === 'ISSUE_DESCRIBE') {
      return;
    }
    (async () => {
      try {
        const result = await classifyIssue({
          description: initialDraft.description,
          imageKeys: initialDraft.photos.map((photo) => photo.imageKey),
          clarificationAnswers: initialDraft.clarificationAnswers,
        });
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
    } else if (step.name !== 'success') {
      setStep({ name: 'describe' });
      updateDraft({ stage: 'ISSUE_DESCRIBE', urgencyType, description, photos: toDraftPhotos(photos) });
    }
  }

  function handleConfirmed(issue: IssueResponse) {
    setStep({ name: 'success', issue });
    // Issue creation is explicitly NOT a clear-trigger (§4.5.1) — the draft moves forward into
    // the booking flow instead of being discarded.
    updateDraft({
      stage: 'ADDRESS_SELECTION',
      issueId: issue.id,
      categoryId: issue.categoryId,
      urgencyType: issue.urgencyType,
    });
  }

  return (
    <div className="focused-page">
      <PageHeader title="יש לי תקלה" description={STEP_LABELS[step.name]} onBack={handleBack} />

      {hasConflictingDraft && !warningDismissed && step.name === 'describe' && (
        <div className={styles.warningBanner} role="alert">
          <p>יש לך בקשה פעילה בתהליך הזמנה — התחלת תקלה חדשה תבטל אותה.</p>
          <button type="button" className={styles.warningDismiss} onClick={() => setWarningDismissed(true)}>
            הבנתי
          </button>
        </div>
      )}

      {isResuming && (
        <div className={styles.resumingWrapper}>
          <p>טוענים את הבקשה שלכם…</p>
        </div>
      )}

      {!isResuming && resumeError && step.name === 'describe' && (
        <div className={styles.warningBanner} role="alert">
          <p>{resumeError}</p>
        </div>
      )}

      {!isResuming && step.name === 'describe' && (
        <DescribeIssueStep
          description={description}
          onDescriptionChange={setDescription}
          photos={photos}
          onPhotosChange={setPhotos}
          urgencyType={urgencyType}
          onUrgencyChange={setUrgencyType}
          onClassified={handleClassified}
        />
      )}
      {!isResuming && step.name === 'clarify' && (
        <ClarifyQuestionsStep
          description={description}
          photos={photos}
          questions={step.classification.questions}
          onClassified={handleClassified}
        />
      )}
      {!isResuming && step.name === 'review' && (
        <ReviewStep
          classification={step.classification}
          description={description}
          photos={photos}
          urgencyType={urgencyType}
          onConfirmed={handleConfirmed}
        />
      )}
      {step.name === 'success' && <IssueSuccessStep issueId={step.issue.id} urgencyType={step.issue.urgencyType} />}
    </div>
  );
}
