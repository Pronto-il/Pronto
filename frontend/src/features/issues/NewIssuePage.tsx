import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import type { ClassifyIssueResponse, IssueUrgencyType } from '../../shared/api';
import { DescribeIssueStep } from './DescribeIssueStep';
import { ClarifyQuestionsStep } from './ClarifyQuestionsStep';
import { ReviewStep } from './ReviewStep';
import { IssueSuccessStep } from './IssueSuccessStep';

type Step =
  | { name: 'describe' }
  | { name: 'clarify'; classification: ClassifyIssueResponse }
  | { name: 'review'; classification: ClassifyIssueResponse }
  | { name: 'success' };

const STEP_LABELS: Partial<Record<Step['name'], string>> = {
  describe: 'שלב 1 מתוך 3',
  clarify: 'שלב 2 מתוך 3',
  review: 'שלב 3 מתוך 3',
};

/**
 * "יש לי תקלה" — the customer issue-report flow (README's "Home / New Issue" + "AI Review"
 * screens, Milestone 2). A single route holding a small step machine rather than one route
 * per step, since going "back" from review/clarify must preserve the description/photos
 * already entered — a subtle step indicator (DESIGN_SYSTEM.md §38), not a wizard UI.
 */
export default function NewIssuePage() {
  const navigate = useNavigate();
  const [description, setDescription] = useState('');
  const [photos, setPhotos] = useState<UploadedPhoto[]>([]);
  const [urgencyType, setUrgencyType] = useState<IssueUrgencyType>('STANDARD');
  const [step, setStep] = useState<Step>({ name: 'describe' });

  function handleClassified(result: ClassifyIssueResponse) {
    setStep(
      result.status === 'QUESTIONS'
        ? { name: 'clarify', classification: result }
        : { name: 'review', classification: result },
    );
  }

  function handleBack() {
    if (step.name === 'describe') {
      navigate('/');
    } else if (step.name !== 'success') {
      setStep({ name: 'describe' });
    }
  }

  return (
    <div className="focused-page">
      <PageHeader title="יש לי תקלה" description={STEP_LABELS[step.name]} onBack={handleBack} />
      {step.name === 'describe' && (
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
      {step.name === 'clarify' && (
        <ClarifyQuestionsStep
          description={description}
          photos={photos}
          questions={step.classification.questions}
          onClassified={handleClassified}
        />
      )}
      {step.name === 'review' && (
        <ReviewStep
          classification={step.classification}
          description={description}
          photos={photos}
          urgencyType={urgencyType}
          onConfirmed={() => setStep({ name: 'success' })}
        />
      )}
      {step.name === 'success' && <IssueSuccessStep />}
    </div>
  );
}
