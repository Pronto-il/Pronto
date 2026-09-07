import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Clock, Zap } from 'lucide-react';
import { Textarea, PhotoUploader, Button, Card, Select } from '../../shared/components';
import type { UploadedPhoto, SelectOption } from '../../shared/components';
import {
  classifyIssue,
  ApiError,
  CATEGORIES,
  CLASSIFY_TIMEOUT_CODE,
  GENERIC_ERROR_MESSAGE,
  ISSUE_DESCRIPTION_MIN_LENGTH,
  ISSUE_DESCRIPTION_MAX_LENGTH,
} from '../../shared/api';
import type { ClassifyIssueResponse, IssueUrgencyType } from '../../shared/api';
import styles from './DescribeIssueStep.module.css';

export interface DescribeIssueStepProps {
  description: string;
  onDescriptionChange: (value: string) => void;
  photos: UploadedPhoto[];
  onPhotosChange: (photos: UploadedPhoto[]) => void;
  urgencyType: IssueUrgencyType;
  onUrgencyChange: (value: IssueUrgencyType) => void;
  /**
   * The customer's optional profession hint. `undefined` means "let Pronto decide", which is
   * the default and remains fully supported — the picker narrows nothing by itself.
   */
  selectedCategoryId?: number;
  onSelectedCategoryChange: (categoryId: number | undefined) => void;
  onClassified: (result: ClassifyIssueResponse) => void;
  /** Design doc §2.2 — fires `true` immediately before `classifyIssue` starts, `false` in the
   *  existing `finally`, so `NewIssuePage` can show `AiAnalyzingOverlay` over this step without
   *  unmounting it. */
  onAnalyzingChange: (isAnalyzing: boolean) => void;
}

const CLASSIFY_ERROR_MESSAGES: Record<string, string> = {
  IMAGE_KEY_INVALID: 'אחת התמונות לא נטענה כראוי. יש להסיר אותה ולנסות שוב.',
  AI_SERVICE_ERROR: 'לא הצלחנו לעבד את התיאור כרגע. אפשר לנסות שוב בעוד רגע.',
};

/**
 * Shown when the classification ran out of time — see `CLASSIFY_TIMEOUT_CODE`.
 *
 * Deliberately says Pronto did not finish, not that anything was decided: a timeout is not a
 * classification, and the recovery on offer is to try again or to name the trade so the next
 * attempt has more to go on. Choosing a profession here is still only a hint that the next
 * classification reads — it is never a way around classification, which is why this copy asks
 * the customer to continue rather than promising the choice will be used as the answer.
 */
const CLASSIFY_TIMEOUT_MESSAGE =
  'לא הספקנו לנתח את התקלה בזמן. אפשר לנסות שוב, או לבחור את בעל המקצוע הדרוש ולהמשיך.';

/**
 * The two ways a classification can run out of time, shown identically because they mean the same
 * thing to the customer.
 *
 * `CLASSIFY_TIMEOUT` is this app's own 5-second deadline. `AI_TIMEOUT` is the backend's 4-second
 * one (`ErrorCode.AI_TIMEOUT`, a 504) and is the one that should normally fire, since the server
 * gives up first by design. Both mean Pronto does not know the answer yet — neither is a
 * classification, and neither may be turned into one.
 */
const TIMEOUT_CODES = new Set([CLASSIFY_TIMEOUT_CODE, 'AI_TIMEOUT']);

/**
 * The profession hint options.
 *
 * Built from the seven real, bookable `CATEGORIES` and labelled with `professionalNameHe` (the
 * practitioner — "אינסטלטור"), because the question is who the customer needs, not what the
 * field of work is called. Deliberately NOT the AI's 50-profession taxonomy: that is the
 * classification label space, and most of it names trades Pronto cannot dispatch — offering
 * them here would be advertising services that do not exist.
 */
const PROFESSION_OPTIONS: SelectOption[] = [
  { value: '', label: 'שהמערכת תזהה לפי התיאור' },
  ...CATEGORIES.map((category) => ({ value: String(category.id), label: category.professionalNameHe })),
];

/** Local, single-consumer "helpful examples" row (design doc §3.2) — shown only while
 *  `description` is empty; clicking one prefills a fuller example sentence via the existing
 *  `onDescriptionChange` prop (no new prop, no API/behavior change). */
const EXAMPLE_PROMPTS: { label: string; example: string }[] = [
  { label: 'נזילת מים', example: 'יש לי נזילת מים מתחת לכיור במטבח' },
  { label: 'תקלה בחשמל', example: 'יש לי תקלה בחשמל, הנתיך קופץ כל הזמן' },
  { label: 'מזגן לא מקרר', example: 'המזגן בסלון לא מקרר כמו שצריך' },
  { label: 'דלת או מנעול תקוע', example: 'הדלת נתקעת ולא ניתן לנעול אותה' },
];

/**
 * Step 1 of the New Issue flow — description + optional photos + urgency. Owns the
 * `POST /api/issues/classify` call (api-contract-issues.md §2.1); nothing is persisted here
 * (§3.4 — the first DB write only happens on final confirm in `ReviewStep`).
 */
export function DescribeIssueStep({
  description,
  onDescriptionChange,
  photos,
  onPhotosChange,
  urgencyType,
  onUrgencyChange,
  selectedCategoryId,
  onSelectedCategoryChange,
  onClassified,
  onAnalyzingChange,
}: DescribeIssueStepProps) {
  const [descriptionError, setDescriptionError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [photosUploading, setPhotosUploading] = useState(false);
  const descriptionRef = useRef<HTMLTextAreaElement>(null);

  /**
   * Guards against a stale classification landing after the app has moved on.
   *
   * Two separate mechanisms, because they solve two different halves of the same problem.
   * `inFlightRef` aborts the previous request so a superseded call stops occupying the network
   * and the backend stops being waited on. `submissionRef` is the correctness half: an abort is
   * not instantaneous, and a response that was already parsed when the newer submission started
   * would otherwise still resolve and drive `onClassified` — advancing the flow on an answer to
   * a question the customer has since changed. Comparing the sequence number at the moment the
   * promise settles is what makes that impossible.
   */
  const submissionRef = useRef(0);
  const inFlightRef = useRef<AbortController | null>(null);

  // Leaving the step (back navigation, or the parent swapping in another step) must not leave a
  // request outstanding. Nothing is rendered from it any more, so continuing to hold the socket
  // open is pure waste.
  useEffect(() => {
    return () => {
      submissionRef.current += 1;
      inFlightRef.current?.abort();
    };
  }, []);

  // The field opens at roughly three lines so the urgency choice below it is reachable on a
  // phone without scrolling past an empty box, and grows with the text instead — up to the
  // `max-height` in CSS, after which it scrolls internally. Runs on hydration from a draft too,
  // so a resumed description arrives already sized.
  useLayoutEffect(() => {
    const element = descriptionRef.current;
    if (!element) {
      return;
    }
    // Read before collapsing: `scrollHeight` excludes borders, which the border-box `height`
    // set below does include, so the difference has to be added back or every pass shrinks.
    const borders = element.offsetHeight - element.clientHeight;
    element.style.height = 'auto';
    element.style.height = `${element.scrollHeight + borders}px`;
  }, [description]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void runClassification();
  }

  async function runClassification() {
    setBannerError(null);

    const trimmed = description.trim();
    if (trimmed.length < ISSUE_DESCRIPTION_MIN_LENGTH || trimmed.length > ISSUE_DESCRIPTION_MAX_LENGTH) {
      setDescriptionError(
        `יש לתאר את התקלה באורך של ${ISSUE_DESCRIPTION_MIN_LENGTH} עד ${ISSUE_DESCRIPTION_MAX_LENGTH} תווים.`,
      );
      return;
    }
    setDescriptionError(undefined);

    // Supersede whatever is already running. A double-tapped "המשך" is the ordinary case; the
    // damaging one is the second tap resolving first and then being overwritten by the first
    // tap's older answer.
    inFlightRef.current?.abort();
    const controller = new AbortController();
    inFlightRef.current = controller;
    const submission = ++submissionRef.current;
    const isCurrent = () => submissionRef.current === submission;

    setIsSubmitting(true);
    onAnalyzingChange(true);
    try {
      const result = await classifyIssue(
        {
          description: trimmed,
          imageKeys: photos.map((photo) => photo.imageKey),
          // Omitted entirely rather than sent as null when the customer did not choose — the
          // field is optional in the contract, and "absent" is what the backend already reads
          // as "no hint".
          ...(selectedCategoryId !== undefined ? { selectedCategoryId } : {}),
        },
        { signal: controller.signal },
      );
      if (!isCurrent()) {
        return;
      }
      onClassified(result);
    } catch (error) {
      // A superseded or unmounted call is not a failure anyone should be told about.
      if (!isCurrent() || (error instanceof ApiError && error.code === 'ABORTED')) {
        return;
      }
      if (error instanceof ApiError && TIMEOUT_CODES.has(error.code)) {
        setBannerError(CLASSIFY_TIMEOUT_MESSAGE);
      } else if (error instanceof ApiError && CLASSIFY_ERROR_MESSAGES[error.code]) {
        setBannerError(CLASSIFY_ERROR_MESSAGES[error.code]);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      // Guarded so a late-arriving loser cannot clear the spinner belonging to the submission
      // that replaced it — which would leave the customer looking at an idle form while a
      // classification they are still waiting for is genuinely in flight.
      if (isCurrent()) {
        setIsSubmitting(false);
        onAnalyzingChange(false);
      }
    }
  }

  return (
    <div>
      <Card className={styles.card}>
        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          {bannerError && (
            <div className={styles.banner} role="alert">
              <p>{bannerError}</p>
              {bannerError === CLASSIFY_TIMEOUT_MESSAGE && (
                <button
                  type="button"
                  className={styles.bannerRetry}
                  onClick={() => void runClassification()}
                  disabled={isSubmitting}
                >
                  נסו שוב
                </button>
              )}
            </div>
          )}
          {/* The page header already says "יש לי תקלה", so this field's own label is the only
              question on the screen — the guidance that used to sit under the box now sits
              under the label, where it can still be read before anything is typed. */}
          <Textarea
            ref={descriptionRef}
            className={styles.problemField}
            label="מה הבעיה?"
            placeholder="לדוגמה: יש נזילת מים מתחת לכיור במטבח"
            value={description}
            onChange={(event) => onDescriptionChange(event.target.value)}
            error={descriptionError}
            helperText="תאר בכמה מילים את התקלה - המערכת תמצא את בעלי המקצוע המתאימים"
            maxLength={ISSUE_DESCRIPTION_MAX_LENGTH}
            required
          />
          {description.length === 0 && (
            <div className={styles.examplesRow}>
              {EXAMPLE_PROMPTS.map((prompt) => (
                <button
                  key={prompt.label}
                  type="button"
                  className={styles.exampleChip}
                  onClick={() => onDescriptionChange(prompt.example)}
                >
                  {prompt.label}
                </button>
              ))}
            </div>
          )}
          {/* Placed between the examples and the photos deliberately: it reads as the natural
              follow-up to "what is the problem", and it is a hint the classifier gets to use, so
              it belongs with the evidence rather than beside the urgency choice at the bottom.
              A compact select rather than a grid of chips — seven permanently-expanded cards
              would push the urgency choice and the submit button off a phone screen, for a field
              most customers should be able to skip without reading. */}
          <Select
            className={styles.professionField}
            label="איזה בעל מקצוע דרוש לך?"
            options={PROFESSION_OPTIONS}
            value={selectedCategoryId === undefined ? '' : String(selectedCategoryId)}
            onChange={(event) =>
              onSelectedCategoryChange(event.target.value === '' ? undefined : Number(event.target.value))
            }
            hint="לא חובה - אפשר להשאיר לנו לזהות לפי התיאור"
          />
          <PhotoUploader
            label="אפשר להוסיף תמונה?"
            photos={photos}
            onChange={onPhotosChange}
            onUploadingChange={setPhotosUploading}
            hint="לא חובה, עד 6 תמונות"
          />
          <div className={styles.urgencySection}>
            <h3 className={styles.urgencyHeading}>באיזו דחיפות מדובר?</h3>
            <div className={styles.urgencyRow}>
              <button
                type="button"
                className={`${styles.urgencyCard} ${urgencyType === 'STANDARD' ? styles.urgencyCardActive : ''}`}
                onClick={() => onUrgencyChange('STANDARD')}
              >
                <Clock size={22} aria-hidden="true" className={styles.urgencyIcon} />
                <span className={styles.urgencyTitle}>רגיל</span>
                <span className={styles.urgencySubcopy}>מתאים לרוב התקלות, בוחרים זמן שנוח לכם.</span>
              </button>
              <button
                type="button"
                className={`${styles.urgencyCard} ${styles.sosCard} ${
                  urgencyType === 'SOS' ? styles.sosCardActive : ''
                }`}
                onClick={() => onUrgencyChange('SOS')}
              >
                <Zap size={22} aria-hidden="true" className={styles.urgencyIcon} />
                <span className={styles.urgencyTitle}>דחוף? אנחנו על זה.</span>
                <span className={styles.urgencySubcopy}>
                  נחפש עבורך בעל מקצוע זמין שיוכל להגיע בהקדם.
                </span>
                {/* Pricing clarification, deliberately its own quieter line rather than a tail on
                    the description — it is the one thing on this card the customer is agreeing to
                    pay for. Selection behavior is untouched. */}
                <span className={styles.urgencyNote}>שירות SOS כולל תוספת עבור קריאה דחופה.</span>
              </button>
            </div>
          </div>
          <Button type="submit" loading={isSubmitting} disabled={photosUploading} fullWidth>
            המשך
          </Button>
        </form>
      </Card>
    </div>
  );
}
