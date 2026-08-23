import { useEffect, useState } from 'react';
import { Modal, Button, Textarea } from '../../shared/components';
import { REJECTION_REASON_MAX_LENGTH } from '../../shared/api';
import styles from './ApprovalDecisionModal.module.css';

export type ApprovalDecision = 'approve' | 'reject';

export interface ApprovalDecisionModalProps {
  decision: ApprovalDecision | null;
  professionalName: string;
  isSubmitting: boolean;
  /** Banner text from a failed attempt — kept on the modal so the operator can correct and retry
   *  without losing the reason they typed. */
  error: string | null;
  onClose: () => void;
  /** `reason` is supplied for a rejection and omitted for an approval. */
  onConfirm: (reason?: string) => void;
}

/**
 * The confirmation step in front of both decisions. Neither is undoable from this surface: an
 * approval makes a stranger reachable by customers, and a rejection is a decision about a named
 * person that MS1 provides no way to withdraw except by approving them later. Firing either from
 * a single mis-click on a list row would be the wrong shape entirely, so both go through here.
 *
 * One component for both, switched by `decision`, because they are the same interaction with one
 * extra required field. The rejection reason is required and length-capped to mirror
 * `RejectProfessionalRequest`'s `@NotBlank @Size(max = 500)` — the backend refuses a blank one, and
 * a reason nobody recorded is a decision nobody can account for later. Client validation here is
 * for fast feedback only; the backend re-checks it.
 *
 * `mobilePresentation="dialog"` rather than the default bottom sheet: this is a desktop operator
 * tool, and a confirmation with a destructive action reads better as a centred dialog at every
 * width than as a sheet that shares its gesture vocabulary with dismissal.
 */
export function ApprovalDecisionModal({
  decision,
  professionalName,
  isSubmitting,
  error,
  onClose,
  onConfirm,
}: ApprovalDecisionModalProps) {
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);

  // A fresh reason per opening — a draft left over from a previous, abandoned rejection must never
  // be submitted against a different professional.
  useEffect(() => {
    if (decision === null) {
      setReason('');
      setReasonError(null);
    }
  }, [decision]);

  function handleConfirm() {
    if (decision === 'reject') {
      const trimmed = reason.trim();
      if (!trimmed) {
        setReasonError('יש לכתוב סיבה לדחייה.');
        return;
      }
      if (trimmed.length > REJECTION_REASON_MAX_LENGTH) {
        setReasonError(`הסיבה ארוכה מדי (עד ${REJECTION_REASON_MAX_LENGTH} תווים).`);
        return;
      }
      setReasonError(null);
      onConfirm(trimmed);
      return;
    }
    onConfirm();
  }

  const isReject = decision === 'reject';

  return (
    <Modal
      isOpen={decision !== null}
      onClose={onClose}
      title={isReject ? 'דחיית הבקשה' : 'אישור הבקשה'}
      size={isReject ? 'normal' : 'small'}
      mobilePresentation="dialog"
      footer={
        <div className={styles.footer}>
          <Button variant="ghost" onClick={onClose} disabled={isSubmitting}>
            ביטול
          </Button>
          <Button
            variant={isReject ? 'destructive' : 'primary'}
            onClick={handleConfirm}
            loading={isSubmitting}
          >
            {isReject ? 'דחיית הבקשה' : 'אישור הבקשה'}
          </Button>
        </div>
      }
    >
      <div className={styles.body}>
        <p className={styles.question}>
          {isReject
            ? `לדחות את הבקשה של ${professionalName}?`
            : `לאשר את הבקשה של ${professionalName}?`}
        </p>
        <p className={styles.consequence}>
          {isReject
            ? 'בעל המקצוע לא יוצג ללקוחות. הסיבה שתיכתב כאן נשמרת עם ההחלטה.'
            : 'אחרי האישור בעל המקצוע יוכל להופיע בחיפוש של לקוחות ולקבל עבודות, בתנאי שהשלים את פרטי ההרשמה שלו.'}
        </p>

        {isReject && (
          <Textarea
            label="סיבת הדחייה"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            error={reasonError ?? undefined}
            hint={`עד ${REJECTION_REASON_MAX_LENGTH} תווים.`}
            maxLength={REJECTION_REASON_MAX_LENGTH}
            required
          />
        )}

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
      </div>
    </Modal>
  );
}
