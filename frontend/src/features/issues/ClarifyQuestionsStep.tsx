import { useState } from 'react';
import { Button } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { classifyIssue, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ClassifyIssueResponse, ClassifyQuestion } from '../../shared/api';
import styles from './ClarifyQuestionsStep.module.css';

export interface ClarifyQuestionsStepProps {
  description: string;
  photos: UploadedPhoto[];
  questions: ClassifyQuestion[];
  onClassified: (result: ClassifyIssueResponse) => void;
}

/**
 * The single allowed clarification round (api-contract-issues.md §2.1, "Clarification-
 * question extension") — resubmits the same description/images plus the chosen answers, and
 * always resolves to `status: "CLASSIFIED"`.
 */
export function ClarifyQuestionsStep({ description, photos, questions, onClassified }: ClarifyQuestionsStepProps) {
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  const allAnswered = questions.every((question) => Boolean(answers[question.id]));

  async function handleContinue() {
    setBannerError(null);
    setIsSubmitting(true);
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
      {questions.map((question) => (
        <fieldset key={question.id} className={styles.questionGroup}>
          <legend className={styles.question}>{question.question}</legend>
          <div className={styles.options}>
            {question.options.map((option) => (
              <button
                key={option}
                type="button"
                className={`${styles.option} ${answers[question.id] === option ? styles.optionSelected : ''}`}
                onClick={() => setAnswers((prev) => ({ ...prev, [question.id]: option }))}
              >
                {option}
              </button>
            ))}
          </div>
        </fieldset>
      ))}
      <Button onClick={handleContinue} loading={isSubmitting} disabled={!allAnswered} fullWidth>
        המשך
      </Button>
    </div>
  );
}
