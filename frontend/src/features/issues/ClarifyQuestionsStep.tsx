import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Circle, CheckCircle2 } from 'lucide-react';
import { Button } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { ApiError, classifyIssue, CLASSIFY_TIMEOUT_CODE, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ClarificationAnswer, ClassifyIssueResponse, ClassifyQuestion } from '../../shared/api';
import { pageTransition } from '../../shared/motion/variants';
import styles from './ClarifyQuestionsStep.module.css';

/** Same rule as the describe step: a round that timed out decided nothing and says so. */
const CLARIFY_TIMEOUT_MESSAGE = 'לא הספקנו לנתח את התשובה בזמן. אפשר לנסות שוב.';

/** The client deadline and the backend's own — see `DescribeIssueStep`. */
const TIMEOUT_CODES = new Set([CLASSIFY_TIMEOUT_CODE, 'AI_TIMEOUT']);

export interface ClarifyQuestionsStepProps {
  description: string;
  photos: UploadedPhoto[];
  /**
   * The describe step's profession hint, carried into every clarification round.
   *
   * `/classify` is stateless and re-runs over the *complete* evidence each time, so a hint that
   * was sent on round one and dropped on round two would change the evidence between rounds —
   * the customer would appear to have withdrawn their choice by answering a question.
   */
  selectedCategoryId?: number;
  /** Normally exactly one — the backend asks the single highest-value question per round. The
   *  component still renders a list defensively rather than assuming a length. */
  questions: ClassifyQuestion[];
  /** Answers from earlier rounds. Resubmitted alongside the new ones so the backend always
   *  re-classifies against the complete conversation, never the latest answer alone. */
  previousAnswers: ClarificationAnswer[];
  onClassified: (result: ClassifyIssueResponse, answers: ClarificationAnswer[]) => void;
  /** Design doc §2.2/§4.3 — same minimal wiring as `DescribeIssueStepProps`, around this
   *  component's own `classifyIssue` call in `handleContinue`. */
  onAnalyzingChange: (isAnalyzing: boolean) => void;
}

/**
 * One clarification round. Pronto asks the highest-value question, re-classifies with the
 * answer, and may ask one more — so this step can legitimately be shown again with a different
 * question rather than always resolving straight to `CLASSIFIED`. The server-side question
 * budget is what ends the loop; this component neither counts nor caps rounds, it just carries
 * the growing conversation forward.
 */
export function ClarifyQuestionsStep({
  description,
  photos,
  selectedCategoryId,
  questions,
  previousAnswers,
  onClassified,
  onAnalyzingChange,
}: ClarifyQuestionsStepProps) {
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);
  // Identical guard to DescribeIssueStep's — see the comment there for why aborting and
  // sequence-checking are both needed rather than either alone.
  const submissionRef = useRef(0);
  const inFlightRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      submissionRef.current += 1;
      inFlightRef.current?.abort();
    };
  }, []);
  // Progressive reveal (design doc §4.2) — multi-question case only; a single question renders
  // immediately (today's existing behavior, just restyled).
  const [visibleCount, setVisibleCount] = useState(() => Math.min(1, questions.length));

  // Same pattern `RoleChooser.tsx` already uses for `pageTransition`'s per-item reuse: the
  // `animate` target itself must be overridden to neutralize the spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const itemAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  const allAnswered = questions.every((question) => Boolean(answers[question.id]));
  const isMultiQuestion = questions.length > 1;
  const visibleQuestions = isMultiQuestion ? questions.slice(0, visibleCount) : questions;

  function handleAnswer(question: ClassifyQuestion, option: string, index: number) {
    setAnswers((prev) => ({ ...prev, [question.id]: option }));
    if (isMultiQuestion && index === visibleCount - 1 && visibleCount < questions.length) {
      setVisibleCount((prev) => Math.min(prev + 1, questions.length));
    }
  }

  async function handleContinue() {
    setBannerError(null);
    const accumulated: ClarificationAnswer[] = [
      ...previousAnswers,
      ...questions.map((question) => ({ question: question.question, answer: answers[question.id] })),
    ];

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
          description,
          imageKeys: photos.map((photo) => photo.imageKey),
          ...(selectedCategoryId !== undefined ? { selectedCategoryId } : {}),
          clarificationAnswers: accumulated,
        },
        { signal: controller.signal },
      );
      if (!isCurrent()) {
        return;
      }
      onClassified(result, accumulated);
    } catch (error) {
      if (!isCurrent() || (error instanceof ApiError && error.code === 'ABORTED')) {
        return;
      }
      setBannerError(
        error instanceof ApiError && TIMEOUT_CODES.has(error.code)
          ? CLARIFY_TIMEOUT_MESSAGE
          : GENERIC_ERROR_MESSAGE,
      );
    } finally {
      if (isCurrent()) {
        setIsSubmitting(false);
        onAnalyzingChange(false);
      }
    }
  }

  return (
    <div className={styles.wrapper}>
      <p className={styles.intro}>כדי שנמצא את בעל המקצוע המתאים, יש לנו עוד שאלה קטנה.</p>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      <AnimatePresence initial={false}>
        {visibleQuestions.map((question, index) => (
          <motion.fieldset
            key={question.id}
            className={styles.questionGroup}
            variants={pageTransition}
            initial="initial"
            animate={itemAnimate}
            exit="exit"
          >
            <legend className={styles.question}>{question.question}</legend>
            <div className={styles.options} role="radiogroup" aria-label={question.question}>
              {question.options.map((option) => {
                const selected = answers[question.id] === option;
                return (
                  <button
                    key={option}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    className={`${styles.option} ${selected ? styles.optionSelected : ''}`}
                    onClick={() => handleAnswer(question, option, index)}
                  >
                    {selected ? (
                      <CheckCircle2 size={22} aria-hidden="true" className={styles.optionIcon} />
                    ) : (
                      <Circle size={22} aria-hidden="true" className={styles.optionIcon} />
                    )}
                    <span>{option}</span>
                  </button>
                );
              })}
            </div>
          </motion.fieldset>
        ))}
      </AnimatePresence>
      <Button onClick={handleContinue} loading={isSubmitting} disabled={!allAnswered} fullWidth>
        המשך
      </Button>
    </div>
  );
}
