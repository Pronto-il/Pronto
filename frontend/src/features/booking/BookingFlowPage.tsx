import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition, Transition, Variants } from 'framer-motion';
import { Info } from 'lucide-react';
import { PageHeader, EMPTY_ADDRESS, Button, Mascot } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { getProfessionalsForIssue, getAvailableWindows, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type {
  AvailableWindow,
  OrderResponse,
  ProfessionalCard as ProfessionalCardData,
  ProfessionalSort,
} from '../../shared/api';
import { deriveStartTimeCandidates } from '../../shared/utils/availability';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import { ProfessionalList, STANDARD_SORT_OPTIONS } from '../professionals';
import { useBookingDraft } from '../../shared/hooks';
import { stepTransition } from '../../shared/motion/variants';
import { AddressSelectionStep, type AddressMode } from './AddressSelectionStep';
import { StartTimePicker } from './StartTimePicker';
import { BookingSummary } from './BookingSummary';
import { BookingSuccessStep } from './BookingSuccessStep';
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

/** Feeds `PageHeader`'s `steps` progress-bar prop (design doc §3.A5) — omitted for
 *  `'success'`, mirroring `NewIssuePage.tsx`'s `STEP_NUMBERS`/`STEP_LABELS` pairing. */
const STEP_NUMBERS: Partial<Record<Step['name'], number>> = {
  address: 1,
  professionals: 2,
  slot: 3,
  confirm: 4,
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
  /** Set by `handleTimeUnavailable` when the chosen start time can no longer be booked —
   *  either order-creation 409'd with `BOOKING_TIME_UNAVAILABLE` (raced by another customer)
   *  or the time slipped into the past (MS4 final corrections, item 1). Owned here, not by
   *  `BookingSummary`, because that component unmounts as part of the same transition that
   *  sends the customer back to the `slot` step, so a message set on its own local state would
   *  never paint. Rendered as a `notice`, not an error banner: nothing went wrong, the
   *  customer is simply being handed a refreshed list. Kept distinct from `slotsError` (a
   *  `getAvailableWindows` fetch failure) since `fetchWindows`'s `setSlotsError(null)` runs
   *  synchronously in the same tick as this is set. */
  const [timeUnavailableError, setTimeUnavailableError] = useState<string | null>(null);

  const [step, setStep] = useState<Step>({ name: 'address' });
  const hasAttemptedResume = useRef(false);
  /** `1` = advancing forward, `-1` = going back — drives `stepTransition`'s slide direction
   *  (design doc §3.A1), mirroring `NewIssuePage.tsx`'s `direction` state exactly. */
  const [direction, setDirection] = useState(1);
  const shouldReduceMotion = useReducedMotion();

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
          // Same `notBeforeMs` filter `StartTimePicker` applies, so a draft resumed after the
          // saved start time has passed falls back to the picker instead of restoring a
          // confirm step the server would reject (MS4 final corrections, item 1).
          const candidates = deriveStartTimeCandidates(windowsResult.windows, windowsResult.defaultDurationMinutes, {
            notBeforeMs: Date.now(),
          });
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
    setDirection(1);
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
    setDirection(1);
    setStep({ name: 'slot', professional });
    setTimeUnavailableError(null);
    void fetchWindows(professional);
    updateDraft({ stage: 'SLOT_SELECTION', professionalId: professional.professionalId, bookedStart: undefined });
  }

  function handleSlotContinue() {
    if (step.name !== 'slot' || !selectedStart) {
      return;
    }
    // Belt-and-braces against the same staleness `BookingSummary` guards at submit time: the
    // chip may have expired between the last `StartTimePicker` clock tick and this click.
    if (new Date(selectedStart).getTime() <= Date.now()) {
      handleSelectedStartExpired();
      return;
    }
    setDirection(1);
    setStep({ name: 'confirm', professional: step.professional, bookedStart: selectedStart });
    setTimeUnavailableError(null);
    updateDraft({ stage: 'BOOKING_CONFIRM', bookedStart: selectedStart });
  }

  /** `StartTimePicker`'s live clock retired the selected chip while the customer was still on
   *  the `slot` step (MS4 final corrections, item 1). No re-fetch here: the picker re-derives
   *  its own chips from the windows already in state, so the list on screen is current — only
   *  the now-dangling selection needs clearing, plus an explanation for why the chip vanished. */
  function handleSelectedStartExpired() {
    setSelectedStart(null);
    setTimeUnavailableError('הזמן שבחרת כבר עבר. אפשר לבחור מועד אחר מהרשימה המעודכנת.');
  }

  /** The chosen start time can no longer be booked: order creation either 409'd with
   *  `BOOKING_TIME_UNAVAILABLE` (another customer took the same window first) or was rejected
   *  as non-future. Either way the customer goes back to a freshly-fetched picker. `message`
   *  (rendered on the `slot` step below, once `StartTimePicker` is back on screen) is passed in
   *  by `BookingSummary` rather than looked up here, since it already owns the copy for both
   *  cases and they read differently. */
  function handleTimeUnavailable(message: string) {
    if (step.name !== 'confirm') {
      return;
    }
    const { professional } = step;
    setDirection(-1);
    setStep({ name: 'slot', professional });
    setTimeUnavailableError(message);
    void fetchWindows(professional);
    updateDraft({ stage: 'SLOT_SELECTION', bookedStart: undefined });
  }

  function handleConfirmed(order: OrderResponse) {
    if (step.name !== 'confirm') {
      return;
    }
    setDirection(1);
    setStep({ name: 'success', order, professionalName: step.professional.fullName });
    clearDraft();
  }

  function handleBack() {
    if (step.name === 'address') {
      navigate('/');
    } else if (step.name === 'professionals') {
      setDirection(-1);
      setStep({ name: 'address' });
      updateDraft({ stage: 'ADDRESS_SELECTION' });
    } else if (step.name === 'slot') {
      setDirection(-1);
      setStep({ name: 'professionals' });
      setTimeUnavailableError(null);
      updateDraft({ stage: 'PROFESSIONAL_SELECTION', professionalId: undefined });
    } else if (step.name === 'confirm') {
      setDirection(-1);
      setStep({ name: 'slot', professional: step.professional });
      setTimeUnavailableError(null);
      updateDraft({ stage: 'SLOT_SELECTION', bookedStart: undefined });
    }
  }

  // Same neutralization pattern `NewIssuePage.tsx`/`AiAnalyzingOverlay.tsx` already apply —
  // `stepTransition`'s `animate`/`exit` targets each carry their own embedded spring
  // `transition`, which wins over a component-level `transition` prop. Copied locally per
  // that established pattern rather than extracted into `shared/motion` (design doc §3.A1).
  const stepVariants: Variants = useMemo(() => {
    if (!shouldReduceMotion) {
      return stepTransition;
    }
    const instant: Transition = { duration: 0 };
    const animate = stepTransition.animate as TargetAndTransition;
    const initial = stepTransition.initial as (custom: number) => TargetAndTransition;
    const exit = stepTransition.exit as (custom: number) => TargetAndTransition;
    return {
      initial: (custom: number) => ({ ...initial(custom), transition: instant }),
      animate: { ...animate, transition: instant },
      exit: (custom: number) => ({ ...exit(custom), transition: instant }),
    };
  }, [shouldReduceMotion]);

  return (
    <div className="focused-page">
      <PageHeader
        title="בחירת בעל מקצוע"
        description={STEP_LABELS[step.name]}
        onBack={step.name === 'success' ? undefined : handleBack}
        steps={STEP_NUMBERS[step.name] !== undefined ? { current: STEP_NUMBERS[step.name]!, total: 4 } : undefined}
      />

      <div className={styles.stepViewport}>
        <AnimatePresence mode="wait" custom={direction}>
          <motion.div key={step.name} custom={direction} variants={stepVariants} initial="initial" animate="animate" exit="exit">
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
                {isLoadingProfessionals && (
                  <div className={styles.searchingState}>
                    <Mascot state="searching" loop size="lg" />
                    <p className={styles.transitionText}>מחפשים בעלי מקצוע זמינים באזור שלך…</p>
                  </div>
                )}
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
                    categoryId={categoryId ?? undefined}
                    isLoading={isLoadingProfessionals}
                    viewProfileContext={{ issueId, urgencyType: 'STANDARD' }}
                  />
                )}
              </div>
            )}

            {step.name === 'slot' && (
              <div className={styles.step}>
                {timeUnavailableError && (
                  <div className={styles.notice} role="status">
                    <Info size={18} aria-hidden="true" className={styles.noticeIcon} />
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
                      onSelectedExpired={handleSelectedStartExpired}
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

            {/* Flow-specific copy (MS4 final corrections, item 2) — the shared component stays
                copy-agnostic; a Standard booking's whole point is the scheduled slot, so the
                confirmed date/time is repeated back here. `SosBookingFlowPage` passes its own. */}
            {step.name === 'success' && (
              <BookingSuccessStep
                title="ההזמנה נשלחה"
                body={`הבקשה נשלחה ל${step.professionalName} ל${formatDateLabel(step.order.bookedStart)} בשעה ${formatTimeLabel(step.order.bookedStart)}. נעדכן אותך ברגע שההזמנה תאושר.`}
                orderId={step.order.id}
              />
            )}
          </motion.div>
        </AnimatePresence>
      </div>
    </div>
  );
}
