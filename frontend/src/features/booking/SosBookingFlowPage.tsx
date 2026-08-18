import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader, EMPTY_ADDRESS, Button } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { getSosProfessionalsForIssue, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderResponse, ProfessionalCard as ProfessionalCardData, ProfessionalSort } from '../../shared/api';
import { ProfessionalList, SOS_SORT_OPTIONS } from '../professionals';
import { useBookingDraft } from '../../shared/hooks';
import { AddressSelectionStep, type AddressMode } from './AddressSelectionStep';
import { SosBookingSummary } from './SosBookingSummary';
import styles from './SosBookingFlowPage.module.css';

type Step =
  | { name: 'address' }
  | { name: 'professionals' }
  | { name: 'confirm'; professional: ProfessionalCardData }
  | { name: 'success'; order: OrderResponse; professionalName: string };

const STEP_LABELS: Partial<Record<Step['name'], string>> = {
  address: 'שלב 1 מתוך 3',
  professionals: 'שלב 2 מתוך 3',
  confirm: 'שלב 3 מתוך 3',
};

const LISTING_ERROR_MESSAGES: Record<string, string> = {
  ISSUE_NOT_BOOKABLE: 'הבקשה הזו כבר בטיפול. אפשר לעקוב אחריה בדף ההזמנות שלך.',
  ISSUE_URGENCY_MISMATCH: 'הבקשה הזו אינה בקשה דחופה (SOS). יש להשתמש בתהליך ההזמנה הרגיל.',
};

/** `SOS_SORT_OPTIONS` only ever produces `RECOMMENDED`/`CHEAPEST` (§3.2/§3.3) — `sort`'s
 *  static type is the 3-value `ProfessionalSort`, but `FASTEST` is unreachable via this
 *  flow's chips, so narrowing to the draft's `sort` field is safe. */
function toDraftSort(sort: ProfessionalSort): 'RECOMMENDED' | 'CHEAPEST' {
  return sort === 'RECOMMENDED' ? 'RECOMMENDED' : 'CHEAPEST';
}

/**
 * `/issues/:issueId/sos-booking` — the SOS-booking step machine: service address →
 * available-now professional list → confirmation → success. Mirrors
 * `BookingFlowPage.tsx`'s step-machine pattern (one route, an internal step union, a
 * subtle step indicator, not a wizard UI) but with only 3 steps instead of 4 — SOS has no
 * slot-picking step, the order's `bookedStart` is set to `now()` server-side.
 *
 * `ISSUE_URGENCY_MISMATCH` on the listing call (reachable only via a manually-edited URL
 * for a non-SOS issue, the mirror-image of the defensive case `BookingFlowPage` already
 * handles) and `ISSUE_NOT_BOOKABLE` are mapped to honest Hebrew messages rather than shown
 * as a raw error.
 *
 * Booking-draft persistence (`ms3-ms4-corrections-design.md` §4): hydrates from an
 * in-progress draft on mount when `draft.issueId` matches this route's `issueId` and
 * `draft.urgencyType === 'SOS'`, writes through `updateDraft` on every step transition
 * (forward and backward), and `clearDraft()`s on order-creation success — the only trigger.
 */
export default function SosBookingFlowPage() {
  const navigate = useNavigate();
  const { issueId: issueIdParam } = useParams<{ issueId: string }>();
  const issueId = Number(issueIdParam);
  const { draft, updateDraft, clearDraft } = useBookingDraft();

  const [address, setAddress] = useState<AddressValue>(EMPTY_ADDRESS);
  const [addressMode, setAddressMode] = useState<AddressMode>('CUSTOM');
  const [addressErrors, setAddressErrors] = useState<Partial<Record<keyof AddressValue, string>>>({});

  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [professionals, setProfessionals] = useState<ProfessionalCardData[]>([]);
  const [sort, setSort] = useState<ProfessionalSort>('CHEAPEST');
  const [isLoadingProfessionals, setIsLoadingProfessionals] = useState(false);
  const [professionalsError, setProfessionalsError] = useState<string | null>(null);

  const [step, setStep] = useState<Step>({ name: 'address' });
  const hasAttemptedResume = useRef(false);

  const fetchProfessionals = useCallback(
    async (nextSort: ProfessionalSort, currentAddress: AddressValue) => {
      setIsLoadingProfessionals(true);
      setProfessionalsError(null);
      try {
        const result = await getSosProfessionalsForIssue(issueId, currentAddress, nextSort);
        setProfessionals(result.professionals);
        setCategoryId(result.categoryId);
      } catch (error) {
        if (error instanceof ApiError && LISTING_ERROR_MESSAGES[error.code]) {
          setProfessionalsError(LISTING_ERROR_MESSAGES[error.code]);
        } else {
          setProfessionalsError(GENERIC_ERROR_MESSAGE);
        }
      } finally {
        setIsLoadingProfessionals(false);
      }
    },
    [issueId],
  );

  // Resume-hydration (§4.4's table). SOS has no slot-selection stage, so BOOKING_CONFIRM only
  // needs to reconstruct the professional card, not slots. Falls back to 'professionals' (the
  // same fallback `handleProfessionalUnavailable` already uses) if the professional is no
  // longer in the re-fetched list.
  useEffect(() => {
    // Marked immediately (before the match check) so this only ever runs once, on mount —
    // otherwise this page's own subsequent `updateDraft` calls (which change `draft`'s
    // reference) would re-trigger the resume logic and clobber whatever step the customer
    // has since navigated to live.
    if (hasAttemptedResume.current) {
      return;
    }
    hasAttemptedResume.current = true;
    if (!draft || draft.issueId !== issueId || draft.urgencyType !== 'SOS') {
      return;
    }

    if (draft.address) {
      setAddress(draft.address);
    }
    if (draft.addressMode) {
      setAddressMode(draft.addressMode);
    }

    if (draft.stage === 'ADDRESS_SELECTION' || !draft.address) {
      setStep({ name: 'address' });
      return;
    }

    const resumeSort = draft.sort ?? 'CHEAPEST';
    setSort(resumeSort);

    (async () => {
      setStep({ name: 'professionals' });
      setIsLoadingProfessionals(true);
      setProfessionalsError(null);
      try {
        const listing = await getSosProfessionalsForIssue(issueId, draft.address!, resumeSort);
        setProfessionals(listing.professionals);
        setCategoryId(listing.categoryId);

        if (draft.stage === 'PROFESSIONAL_SELECTION') {
          return;
        }

        const professional = listing.professionals.find((item) => item.professionalId === draft.professionalId);
        if (!professional) {
          // Professional no longer SOS-available — stays on 'professionals', the same
          // fallback `handleProfessionalUnavailable` already relies on.
          return;
        }
        setStep({ name: 'confirm', professional });
      } catch (error) {
        if (error instanceof ApiError && LISTING_ERROR_MESSAGES[error.code]) {
          setProfessionalsError(LISTING_ERROR_MESSAGES[error.code]);
        } else {
          setProfessionalsError(GENERIC_ERROR_MESSAGE);
        }
      } finally {
        setIsLoadingProfessionals(false);
      }
    })();
    // Intentionally run once on mount only — see comment above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function validateAddress(): boolean {
    const errors: Partial<Record<keyof AddressValue, string>> = {};
    if (!address.city.trim()) errors.city = 'יש להזין עיר.';
    if (!address.street.trim()) errors.street = 'יש להזין רחוב.';
    if (!address.houseNumber.trim()) errors.houseNumber = 'יש להזין מספר בית.';
    setAddressErrors(errors);
    return Object.keys(errors).length === 0;
  }

  function handleAddressContinue() {
    if (!validateAddress()) {
      return;
    }
    setStep({ name: 'professionals' });
    void fetchProfessionals(sort, address);
    updateDraft({
      stage: 'PROFESSIONAL_SELECTION',
      urgencyType: 'SOS',
      issueId,
      addressMode,
      address,
      sort: toDraftSort(sort),
    });
  }

  function handleSortChange(nextSort: ProfessionalSort) {
    setSort(nextSort);
    void fetchProfessionals(nextSort, address);
    updateDraft({ sort: toDraftSort(nextSort) });
  }

  function handleSelectProfessional(professional: ProfessionalCardData) {
    setStep({ name: 'confirm', professional });
    updateDraft({ stage: 'BOOKING_CONFIRM', professionalId: professional.professionalId });
  }

  function handleProfessionalUnavailable() {
    if (step.name !== 'confirm') {
      return;
    }
    setStep({ name: 'professionals' });
    void fetchProfessionals(sort, address);
    updateDraft({ stage: 'PROFESSIONAL_SELECTION', professionalId: undefined });
  }

  function handleConfirmed(order: OrderResponse) {
    if (step.name !== 'confirm') {
      return;
    }
    setStep({ name: 'success', order, professionalName: step.professional.fullName });
    clearDraft();
  }

  function handleBack() {
    if (step.name === 'address') {
      navigate('/');
    } else if (step.name === 'professionals') {
      setStep({ name: 'address' });
      updateDraft({ stage: 'ADDRESS_SELECTION' });
    } else if (step.name === 'confirm') {
      setStep({ name: 'professionals' });
      updateDraft({ stage: 'PROFESSIONAL_SELECTION', professionalId: undefined });
    }
  }

  return (
    <div className="focused-page">
      <PageHeader
        title="בקשת SOS"
        description={STEP_LABELS[step.name]}
        onBack={step.name === 'success' ? undefined : handleBack}
      />

      {step.name !== 'success' && (
        <div className={styles.sosBanner}>
          <p className={styles.sosBannerTitle}>SOS פעיל</p>
          <p className={styles.sosBannerText}>נעדיף בעלי מקצוע שיכולים להגיע אליך במהירות.</p>
        </div>
      )}

      {step.name === 'address' && (
        <div className={styles.step}>
          <AddressSelectionStep
            value={address}
            onChange={setAddress}
            mode={addressMode}
            onModeChange={setAddressMode}
            errors={addressErrors}
            onContinue={handleAddressContinue}
          />
        </div>
      )}

      {step.name === 'professionals' && (
        <div className={styles.step}>
          {isLoadingProfessionals && <p className={styles.transitionText}>מחפשים בעלי מקצוע זמינים לעבודות דחופות…</p>}
          {professionalsError ? (
            <div className={styles.banner} role="alert">
              <p>{professionalsError}</p>
            </div>
          ) : (
            <ProfessionalList
              professionals={professionals}
              sort={sort}
              sortOptions={SOS_SORT_OPTIONS}
              onSortChange={handleSortChange}
              onSelect={handleSelectProfessional}
              isLoading={isLoadingProfessionals}
              viewProfileContext={{ issueId, urgencyType: 'SOS' }}
            />
          )}
        </div>
      )}

      {step.name === 'confirm' && categoryId !== null && (
        <SosBookingSummary
          issueId={issueId}
          categoryId={categoryId}
          professional={step.professional}
          address={address}
          onConfirmed={handleConfirmed}
          onProfessionalUnavailable={handleProfessionalUnavailable}
        />
      )}

      {step.name === 'success' && (
        <div className={styles.successWrapper}>
          <span className={styles.successCheck} aria-hidden="true">
            ✓
          </span>
          <h2 className={styles.successTitle}>ההזמנה נשלחה</h2>
          <p className={styles.successText}>הבקשה נשלחה ל{step.professionalName}. ממתינים לאישור בעל המקצוע.</p>
          <div className={styles.successActions}>
            <Button onClick={() => navigate(`/orders/${step.order.id}`)} fullWidth>
              צפייה בהזמנה
            </Button>
            <Button variant="secondary" onClick={() => navigate('/')} fullWidth>
              חזרה לדף הבית
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
