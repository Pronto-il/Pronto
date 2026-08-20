import { useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Circle, CheckCircle2 } from 'lucide-react';
import { Button } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { classifyIssue, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ClassifyIssueResponse, ClassifyQuestion } from '../../shared/api';
import { pageTransition } from '../../shared/motion/variants';
import styles from './ClarifyQuestionsStep.module.css';

export interface ClarifyQuestionsStepProps {
  description: string;
  photos: UploadedPhoto[];
  questions: ClassifyQuestion[];
  onClassified: (result: ClassifyIssueResponse) => void;
  /** Design doc §2.2/§4.3 — same minimal wiring as `DescribeIssueStepProps`, around this
   *  component's own `classifyIssue` call in `handleContinue`. */
  onAnalyzingChange: (isAnalyzing: boolean) => void;
}

/**
 * The single allowed clarification round (api-contract-issues.md §2.1, "Clarification-
 * question extension") — resubmits the same description/images plus the chosen answers, and
 * always resolves to `status: "CLASSIFIED"`.
 */
export function ClarifyQuestionsStep({
  description,
  photos,
  questions,
  onClassified,
  onAnalyzingChange,
}: ClarifyQuestionsStepProps) {
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);
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
    setIsSubmitting(true);
    onAnalyzingChange(true);
    try {
      const result = await classifyIssue({
        description,
        imageKeys: photos.map((photo) => photo.imageKey),
        clarificationAnswers: questions.map((question) => ({
          question: question.question,
          answer: answers[question.id],
        })),
      });
      onClassified(result);
    } catch {
      setBannerError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsSubmitting(false);
      onAnalyzingChange(false);
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
