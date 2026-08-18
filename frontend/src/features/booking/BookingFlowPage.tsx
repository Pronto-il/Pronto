import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader, EMPTY_ADDRESS, Button } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { getProfessionalsForIssue, getAvailableWindows, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type {
  AvailableWindow,
  OrderResponse,
  ProfessionalCard as ProfessionalCardData,
  ProfessionalSort,
} from '../../shared/api';
import { deriveStartTimeCandidates } from '../../shared/utils/availability';
import { ProfessionalList, STANDARD_SORT_OPTIONS } from '../professionals';
import { useBookingDraft } from '../../shared/hooks';
import { AddressSelectionStep, type AddressMode } from './AddressSelectionStep';
import { StartTimePicker } from './StartTimePicker';
import { BookingSummary } from './BookingSummary';
import styles from './BookingFlowPage.module.css';

type Step =
  | { name: 'address' }
  | { name: 'professionals' }
  | { name: 'slot'; professional: ProfessionalCardData }
  | { name: 'confirm'; professional: ProfessionalCardData; bookedStart: string }
  | { name: 'success'; order: OrderResponse; professionalName: string };

const STEP_LABELS: Partial<Record<Step['name'], string>> = {
  address: 'שלב 1 מתוך 4',
  professionals: 'שלב 2 מתוך 4',
  slot: 'שלב 3 מתוך 4',
  confirm: 'שלב 4 מתוך 4',
};

const LISTING_ERROR_MESSAGES: Record<string, string> = {
  ISSUE_NOT_BOOKABLE: 'הבקשה הזו כבר בטיפול. אפשר לעקוב אחריה בדף ההזמנות שלך.',
  ISSUE_URGENCY_MISMATCH: 'בקשות דחופות (SOS) עדיין לא נתמכות בתהליך ההזמנה הזה.',
};

/** `STANDARD_SORT_OPTIONS` only ever produces `RECOMMENDED`/`CHEAPEST` (§3.2/§3.3) — `sort`'s
 *  static type is the 3-value `ProfessionalSort`, but `FASTEST` is unreachable via this
 *  flow's chips, so narrowing to the draft's `sort` field is safe. */
function toDraftSort(sort: ProfessionalSort): 'RECOMMENDED' | 'CHEAPEST' {
  return sort === 'RECOMMENDED' ? 'RECOMMENDED' : 'CHEAPEST';
}

/**
 * `/issues/:issueId/booking` — the Standard-booking step machine: service address →
 * professional list → start-time picker → confirmation → success. Mirrors
 * `features/issues/NewIssuePage.tsx`'s pattern (one route, an internal step union, a
 * subtle step indicator per DESIGN_SYSTEM.md §38 — not a wizard UI) rather than one route
 * per step, so "back" can preserve state already entered.
 *
 * The service address is collected once, here, as the first step (§4 of this milestone's
 * brief) — reused unmodified for the professional-listing query params, thread through to
 * the start-time-picker step (which needs no address), and becomes the order's
 * `serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment` on creation.
 *
 * **As of the professional weekly availability calendar feature M6** (design §9.2.3/§7.6):
 * the 'slot' step (internal name kept for minimal diff — `STEP_LABELS`/`Step['name']` still
 * say `'slot'`) now consumes `GET .../available-windows?issueId=` +
 * `deriveStartTimeCandidates` instead of the retired `GET .../slots?issueId=`; the customer
 * picks a derived start time, not a pre-made `availability_slots` row.
 *
 * Booking-draft persistence (`ms3-ms4-corrections-design.md` §4): hydrates from an
 * in-progress draft on mount when `draft.issueId` matches this route's `issueId` and
 * `draft.urgencyType === 'STANDARD'`, writes through `updateDraft` on every step transition
 * (forward and backward), and `clearDraft()`s on order-creation success — the only trigger.
 */
export default function BookingFlowPage() {
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

  const [windows, setWindows] = useState<AvailableWindow[]>([]);
  const [defaultDurationMinutes, setDefaultDurationMinutes] = useState(60);
  const [selectedStart, setSelectedStart] = useState<string | null>(null);
  const [isLoadingSlots, setIsLoadingSlots] = useState(false);
  const [slotsError, setSlotsError] = useState<string | null>(null);
  /** Set by `handleTimeUnavailable` when order-creation 409s with `BOOKING_TIME_UNAVAILABLE`
   *  (raced by another customer booking the same window) — owned here, not by `BookingSummary`,
   *  because that component unmounts as part of the same transition that sends the customer
   *  back to the `slot` step, so a banner set on its own local state would never paint.
   *  Kept distinct from `slotsError` (a `getAvailableWindows` fetch failure) since
   *  `fetchWindows`'s `setSlotsError(null)` runs synchronously in the same tick as this is set. */
  const [timeUnavailableError, setTimeUnavailableError] = useState<string | null>(null);

  const [step, setStep] = useState<Step>({ name: 'address' });
  const hasAttemptedResume = useRef(false);

  const fetchProfessionals = useCallback(
    async (nextSort: ProfessionalSort, currentAddress: AddressValue) => {
      setIsLoadingProfessionals(true);
      setProfessionalsError(null);
      try {
        const result = await getProfessionalsForIssue(issueId, currentAddress, nextSort);
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

  // Resume-hydration (§4.4's table): only when the draft belongs to this exact issue/flow.
  // Every field the customer already chose (address, professionalId, bookedStart, sort) is
  // read straight from the draft, never re-asked; only the derived display objects
  // (professional card, available windows) are cheaply re-fetched. Falls back to an earlier
  // step (never a hard error) if the professional/start time turns out to no longer be valid
  // — the same fallback the live flow already uses via `fetchWindows`/re-fetching the listing.
  useEffect(() => {
    // Marked immediately (before the match check) so this only ever runs once, on mount —
    // otherwise this page's own subsequent `updateDraft` calls (which change `draft`'s
    // reference) would re-trigger the resume logic and clobber whatever step the customer
    // has since navigated to live.
    if (hasAttemptedResume.current) {
      return;
    }
    hasAttemptedResume.current = true;
    if (!draft || draft.issueId !== issueId || draft.urgencyType !== 'STANDARD') {
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
        const listing = await getProfessionalsForIssue(issueId, draft.address!, resumeSort);
        setProfessionals(listing.professionals);
        setCategoryId(listing.categoryId);

        if (draft.stage === 'PROFESSIONAL_SELECTION') {
          return;
        }

        const professional = listing.professionals.find((item) => item.professionalId === draft.professionalId);
        if (!professional) {
          // Professional no longer available — stays on 'professionals', the same fallback
          // `handleTimeUnavailable`/live selection already relies on.
          return;
        }

        setStep({ name: 'slot', professional });
        setIsLoadingSlots(true);
        setSelectedStart(null);
        const windowsResult = await getAvailableWindows(professional.professionalId, issueId);
        setWindows(windowsResult.windows);
        setDefaultDurationMinutes(windowsResult.defaultDurationMinutes);
        setIsLoadingSlots(false);

        if (draft.stage === 'BOOKING_CONFIRM' && draft.bookedStart !== undefined) {
          const candidates = deriveStartTimeCandidates(windowsResult.windows, windowsResult.defaultDurationMinutes);
          if (candidates.includes(draft.bookedStart)) {
            setSelectedStart(draft.bookedStart);
            setStep({ name: 'confirm', professional, bookedStart: draft.bookedStart });
          }
          // else: stays on 'slot', matching handleTimeUnavailable's fallback.
        }
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
      urgencyType: 'STANDARD',
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

  async function fetchWindows(professional: ProfessionalCardData) {
    setIsLoadingSlots(true);
    setSlotsError(null);
    setSelectedStart(null);
    try {
      const result = await getAvailableWindows(professional.professionalId, issueId);
      setWindows(result.windows);
      setDefaultDurationMinutes(result.defaultDurationMinutes);
    } catch {
      setSlotsError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsLoadingSlots(false);
    }
  }

  function handleSelectProfessional(professional: ProfessionalCardData) {
    setStep({ name: 'slot', professional });
    setTimeUnavailableError(null);
    void fetchWindows(professional);
    updateDraft({ stage: 'SLOT_SELECTION', professionalId: professional.professionalId, bookedStart: undefined });
  }

  function handleSlotContinue() {
    if (step.name !== 'slot' || !selectedStart) {
      return;
    }
    setStep({ name: 'confirm', professional: step.professional, bookedStart: selectedStart });
    setTimeUnavailableError(null);
    updateDraft({ stage: 'BOOKING_CONFIRM', bookedStart: selectedStart });
  }

  /** Order creation 409'd with `BOOKING_TIME_UNAVAILABLE` — another customer booked the same
   *  window first. `message` (rendered on the `slot` step below, once `StartTimePicker` is
   *  back on screen) is passed in by `BookingSummary` rather than looked up here, since it
   *  already owns the `ORDER_ERROR_MESSAGES` copy for that error code. */
  function handleTimeUnavailable(message: string) {
    if (step.name !== 'confirm') {
      return;
    }
    const { professional } = step;
    setStep({ name: 'slot', professional });
    setTimeUnavailableError(message);
    void fetchWindows(professional);
    updateDraft({ stage: 'SLOT_SELECTION', bookedStart: undefined });
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
    } else if (step.name === 'slot') {
      setStep({ name: 'professionals' });
      setTimeUnavailableError(null);
      updateDraft({ stage: 'PROFESSIONAL_SELECTION', professionalId: undefined });
    } else if (step.name === 'confirm') {
      setStep({ name: 'slot', professional: step.professional });
      setTimeUnavailableError(null);
      updateDraft({ stage: 'SLOT_SELECTION', bookedStart: undefined });
    }
  }

  return (
    <div className="focused-page">
      <PageHeader
        title="בחירת בעל מקצוע"
        description={STEP_LABELS[step.name]}
        onBack={step.name === 'success' ? undefined : handleBack}
      />

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
          {isLoadingProfessionals && <p className={styles.transitionText}>מחפשים בעלי מקצוע זמינים באזור שלך…</p>}
          {professionalsError ? (
            <div className={styles.banner} role="alert">
              <p>{professionalsError}</p>
            </div>
          ) : (
            <ProfessionalList
              professionals={professionals}
              sort={sort}
              sortOptions={STANDARD_SORT_OPTIONS}
              onSortChange={handleSortChange}
              onSelect={handleSelectProfessional}
              isLoading={isLoadingProfessionals}
              viewProfileContext={{ issueId, urgencyType: 'STANDARD' }}
            />
          )}
        </div>
      )}

      {step.name === 'slot' && (
        <div className={styles.step}>
          {timeUnavailableError && (
            <div className={styles.banner} role="alert">
              <p>{timeUnavailableError}</p>
            </div>
          )}
          {slotsError ? (
            <div className={styles.banner} role="alert">
              <p>{slotsError}</p>
            </div>
          ) : (
            <>
              <StartTimePicker
                windows={windows}
                defaultDurationMinutes={defaultDurationMinutes}
                selectedStart={selectedStart}
                onSelect={(value) => {
                  setSelectedStart(value);
                  setTimeUnavailableError(null);
                }}
                isLoading={isLoadingSlots}
              />
              {windows.length > 0 && (
                <Button onClick={handleSlotContinue} disabled={!selectedStart} fullWidth>
                  המשך
                </Button>
              )}
            </>
          )}
        </div>
      )}

      {step.name === 'confirm' && categoryId !== null && (
        <BookingSummary
          issueId={issueId}
          categoryId={categoryId}
          professional={step.professional}
          bookedStart={step.bookedStart}
          defaultDurationMinutes={defaultDurationMinutes}
          address={address}
          onConfirmed={handleConfirmed}
          onTimeUnavailable={handleTimeUnavailable}
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
