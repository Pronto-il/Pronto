import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader, Card, Badge, Button, EmptyState, Skeleton } from '../../shared/components';
import {
  getProfessionalReviewDetail,
  getCategoriesWithSubServices,
  approveProfessional,
  rejectProfessional,
  ApiError,
  GENERIC_ERROR_MESSAGE,
} from '../../shared/api';
import type { ProfessionalReviewDetail, CategoryWithSubServicesResponse } from '../../shared/api';
import { formatDateTimeLabel } from '../../shared/utils/formatDateTime';
import {
  describeDecision,
  describeVisibility,
  describeDecisionConflict,
  canApprove,
  canReject,
} from './approvalPresentation';
import { findCategoryNameHe, resolveSubServices } from './serviceCatalog';
import { VerificationDocumentAction } from './VerificationDocumentAction';
import { ApprovalDecisionModal } from './ApprovalDecisionModal';
import type { ApprovalDecision } from './ApprovalDecisionModal';
import styles from './ProfessionalReviewPage.module.css';

/**
 * `/admin/professionals/:professionalId` — one application, everything needed to decide on it,
 * and the two decisions (MS1, design `docs/architecture/ms1-professional-verification-design.md`
 * D-F).
 *
 * The screen is organised around the distinction MS1 exists to make (D4): **"what did we decide"**
 * and **"can customers reach this person"** are two separate cards, because they are two separate
 * facts and treating them as one is precisely the failure mode — an operator who reads a green
 * "אושר" and assumes the professional is now live, when their registration is still incomplete and
 * nothing changed for any customer. Both cards render backend-computed values (`approvalStatus`,
 * `bookable`, `onboardingComplete`); nothing here re-derives eligibility.
 *
 * **Working hours are not shown, and are not faked.** The backend exposes weekly working hours
 * only through `GET /api/availability/working-hours`, which is `PROFESSIONAL`-only and scoped to
 * the caller's own account — there is no operator-visible source. Rather than invent an endpoint
 * or imply the hours are fine, the registration-material card states plainly that this screen
 * cannot show them. See `features/admin/README.md` ("Known gap").
 *
 * Decision buttons are offered only for transitions the backend accepts
 * (`Professional#canApprove`/`#canReject`, mirrored in `approvalPresentation`). Notably, an
 * approved application cannot be rejected — withdrawing an approval is a suspension, which MS1
 * does not build — so the screen says so up front instead of letting an operator discover it as a
 * failed request. If the state moves under an open tab anyway, the resulting `409` is translated
 * into that same explanation and the detail is reloaded.
 */
export default function ProfessionalReviewPage() {
  const { professionalId: professionalIdParam } = useParams<{ professionalId: string }>();
  const navigate = useNavigate();

  const professionalId = Number(professionalIdParam);
  const isValidId = Number.isInteger(professionalId) && professionalId > 0;

  const [detail, setDetail] = useState<ProfessionalReviewDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [catalog, setCatalog] = useState<CategoryWithSubServicesResponse[] | null>(null);

  const [pendingDecision, setPendingDecision] = useState<ApprovalDecision | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  /** Outcome banner above the cards. `warning` is the "someone else decided this first" case,
   *  which must not read like the confirmation of a decision this operator just made. */
  const [decisionNotice, setDecisionNotice] = useState<{
    text: string;
    tone: 'success' | 'warning';
  } | null>(null);

  useEffect(() => {
    let cancelled = false;
    // Best-effort, like the queue: without it sub-services show as ids-less placeholders, which is
    // a degraded review — not a reason to refuse to show the application at all.
    getCategoriesWithSubServices()
      .then((result) => {
        if (!cancelled) setCatalog(result);
      })
      .catch(() => {
        if (!cancelled) setCatalog(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isValidId) {
      setLoadError('הבקשה המבוקשת לא נמצאה.');
      return;
    }
    let cancelled = false;
    setDetail(null);
    setLoadError(null);
    getProfessionalReviewDetail(professionalId)
      .then((result) => {
        if (!cancelled) setDetail(result);
      })
      .catch((caught: unknown) => {
        if (cancelled) {
          return;
        }
        if (caught instanceof ApiError && caught.status === 404) {
          setLoadError('הבקשה המבוקשת לא נמצאה.');
        } else if (caught instanceof ApiError && caught.status === 403) {
          setLoadError('אין לך הרשאה לצפות בבקשה הזו.');
        } else {
          setLoadError(GENERIC_ERROR_MESSAGE);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [professionalId, isValidId, reloadKey]);

  const handleBack = useCallback(() => navigate('/admin/professionals'), [navigate]);

  async function handleConfirmDecision(reason?: string) {
    if (!detail || pendingDecision === null) {
      return;
    }
    const decision = pendingDecision;
    setDecisionError(null);
    setIsSubmitting(true);
    try {
      const updated =
        decision === 'approve'
          ? await approveProfessional(detail.professionalId)
          : await rejectProfessional(detail.professionalId, reason ?? '');
      setDetail(updated);
      setPendingDecision(null);
      setDecisionNotice({
        text: decision === 'approve' ? 'הבקשה אושרה.' : 'הבקשה נדחתה.',
        tone: 'success',
      });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'PROFESSIONAL_APPROVAL_INVALID_TRANSITION') {
        // Someone (or another tab) already decided. Drop the dialog and re-read the row **before**
        // wording the message: the status this component holds is by definition the stale one, and
        // explaining the conflict from it would produce the generic "something changed" line for
        // the very cases that have a specific, useful explanation (e.g. approve→reject).
        setPendingDecision(null);
        setLoadError(null);
        try {
          const fresh = await getProfessionalReviewDetail(detail.professionalId);
          setDetail(fresh);
          setDecisionNotice({
            text: describeDecisionConflict(decision, fresh.approvalStatus),
            tone: 'warning',
          });
        } catch {
          setDecisionNotice({
            text: describeDecisionConflict(decision, detail.approvalStatus),
            tone: 'warning',
          });
          setReloadKey((key) => key + 1);
        }
      } else if (caught instanceof ApiError && caught.code === 'VALIDATION_ERROR') {
        setDecisionError('יש לכתוב סיבה לדחייה (עד 500 תווים).');
      } else if (caught instanceof ApiError && caught.status === 403) {
        setDecisionError('אין לך הרשאה לבצע את הפעולה הזו.');
      } else {
        setDecisionError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  if (loadError) {
    return (
      <div className="focused-page">
        <PageHeader title="בדיקת בקשה" onBack={handleBack} backLabel="חזרה לרשימת הבקשות" />
        <EmptyState
          tone="error"
          title="לא ניתן להציג את הבקשה"
          description={loadError}
          action={
            <Button variant="secondary" onClick={() => navigate('/admin/professionals')}>
              חזרה לרשימת הבקשות
            </Button>
          }
        />
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="page-container">
        <PageHeader title="בדיקת בקשה" onBack={handleBack} backLabel="חזרה לרשימת הבקשות" />
        <div className={styles.sections} aria-busy="true">
          <Skeleton radius="var(--radius-lg)" style={{ height: 140 }} />
          <Skeleton radius="var(--radius-lg)" style={{ height: 200 }} />
        </div>
      </div>
    );
  }

  const decision = describeDecision(detail.approvalStatus);
  const visibility = describeVisibility(detail);
  const categoryNameHe = findCategoryNameHe(catalog, detail.categoryId);
  const subServices = resolveSubServices(catalog, detail.categoryId, detail.subServiceIds);
  const approvable = canApprove(detail.approvalStatus);
  const rejectable = canReject(detail.approvalStatus);

  // Says out loud which transitions this application still has, so "why is there no reject button"
  // is answered on the screen rather than by a failed request.
  const actionNote =
    detail.approvalStatus === 'APPROVED'
      ? 'הבקשה כבר אושרה. ביטול האישור אינו אפשרי בשלב הזה.'
      : detail.approvalStatus === 'REJECTED'
        ? 'אפשר לאשר בקשה שנדחתה, אם בעל המקצוע תיקן את מה שהיה חסר.'
        : approvable || rejectable
          ? 'ההחלטה נשמרת מיד ומשפיעה על מה שלקוחות רואים.'
          : 'אין פעולה שאפשר לבצע על הבקשה הזו.';

  return (
    <div className="page-container">
      <PageHeader title="בדיקת בקשה" onBack={handleBack} backLabel="חזרה לרשימת הבקשות" />

      {decisionNotice && (
        <p
          className={`${styles.notice} ${decisionNotice.tone === 'warning' ? styles.noticeWarning : ''}`}
          role="status"
        >
          {decisionNotice.text}
        </p>
      )}

      <div className={styles.sections}>
        <Card className={styles.card}>
          <div className={styles.identityRow}>
            <div>
              <h2 className={styles.name}>{detail.fullName}</h2>
              <p className={styles.email}>{detail.email}</p>
            </div>
            <Badge tone={decision.tone}>{decision.label}</Badge>
          </div>
          {/* No "ב־" prefix: `formatDateTimeLabel` yields "היום"/"מחר" for the recent cases, and
              "ב־היום" is not Hebrew. A label + value reads correctly for every value it returns. */}
          <p className={styles.submittedAt}>מועד הגשת הבקשה: {formatDateTimeLabel(detail.registeredAt)}</p>
        </Card>

        <Card className={styles.card}>
          <h3 className={styles.sectionTitle}>מצב מול לקוחות</h3>
          <div className={styles.visibilityRow}>
            <Badge tone={visibility.tone}>{visibility.label}</Badge>
          </div>
          <p className={styles.sectionText}>{visibility.explanation}</p>
        </Card>

        <Card className={styles.card}>
          <h3 className={styles.sectionTitle}>פרטי בעל המקצוע</h3>
          <dl className={styles.facts}>
            <div className={styles.fact}>
              <dt>תחום ראשי</dt>
              <dd>{categoryNameHe ?? 'שם התחום אינו זמין כרגע'}</dd>
            </div>
            <div className={styles.fact}>
              <dt>אזור שירות</dt>
              <dd>{detail.serviceArea}</dd>
            </div>
            <div className={styles.fact}>
              <dt>עיר</dt>
              <dd>{detail.city ?? 'לא צוינה'}</dd>
            </div>
            <div className={styles.fact}>
              <dt>מחיר ביקור בסיסי</dt>
              <dd>₪{detail.basePrice}</dd>
            </div>
          </dl>
          {detail.bio && (
            <div className={styles.bio}>
              <p className={styles.factLabel}>תיאור שכתב בעל המקצוע</p>
              <p className={styles.sectionText}>{detail.bio}</p>
            </div>
          )}
        </Card>

        <Card className={styles.card}>
          <h3 className={styles.sectionTitle}>הפרטים שנדרשים כדי לקבל עבודות</h3>

          <div className={styles.material}>
            <p className={styles.factLabel}>תחומי המשנה שנבחרו</p>
            {subServices.length === 0 ? (
              <p className={styles.warningText}>לא נבחרו תחומי משנה.</p>
            ) : (
              <>
                <div className={styles.chips}>
                  {subServices.map((subService) => (
                    <Badge
                      key={subService.id}
                      size="sm"
                      tone={subService.belongsToCategory ? 'neutral' : 'warning'}
                    >
                      {subService.nameHe ?? 'תחום שאינו קיים עוד ברשימת השירותים'}
                    </Badge>
                  ))}
                </div>
                {subServices.some((subService) => !subService.belongsToCategory) && (
                  <p className={styles.warningText}>
                    תחומים המסומנים בכתום אינם שייכים לתחום הראשי שנבחר, ולכן אינם נחשבים לצורך קבלת
                    עבודות.
                  </p>
                )}
              </>
            )}
          </div>

          <div className={styles.material}>
            <p className={styles.factLabel}>שעות עבודה שבועיות</p>
            <p className={styles.mutedText}>המסך הזה אינו מציג את שעות העבודה של בעל המקצוע.</p>
          </div>

          <div className={styles.material}>
            <p className={styles.factLabel}>מסמך אימות</p>
            <VerificationDocumentAction
              professionalId={detail.professionalId}
              hasVerificationDocument={detail.hasVerificationDocument}
            />
          </div>
        </Card>

        {(detail.approvalReviewedAt || detail.approvalRejectionReason) && (
          <Card className={styles.card}>
            <h3 className={styles.sectionTitle}>ההחלטה הקודמת</h3>
            {detail.approvalReviewedAt && (
              <p className={styles.sectionText}>
                מועד הבדיקה: {formatDateTimeLabel(detail.approvalReviewedAt)}
              </p>
            )}
            {detail.approvalRejectionReason && (
              <div className={styles.material}>
                <p className={styles.factLabel}>הסיבה שנרשמה לדחייה</p>
                <p className={styles.sectionText}>{detail.approvalRejectionReason}</p>
              </div>
            )}
          </Card>
        )}

        <div className={styles.actions}>
          {approvable && (
            <Button
              onClick={() => {
                setDecisionError(null);
                setDecisionNotice(null);
                setPendingDecision('approve');
              }}
            >
              אישור הבקשה
            </Button>
          )}
          {rejectable && (
            <Button
              variant="destructive"
              onClick={() => {
                setDecisionError(null);
                setDecisionNotice(null);
                setPendingDecision('reject');
              }}
            >
              דחיית הבקשה
            </Button>
          )}
          <p className={styles.mutedText}>{actionNote}</p>
        </div>
      </div>

      <ApprovalDecisionModal
        decision={pendingDecision}
        professionalName={detail.fullName}
        isSubmitting={isSubmitting}
        error={decisionError}
        onClose={() => {
          setPendingDecision(null);
          setDecisionError(null);
        }}
        onConfirm={handleConfirmDecision}
      />
    </div>
  );
}
