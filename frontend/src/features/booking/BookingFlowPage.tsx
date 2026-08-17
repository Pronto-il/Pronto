import { useCallback, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader, AddressFormFields, EMPTY_ADDRESS, Button } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { getProfessionalsForIssue, getProfessionalSlots, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type {
  AvailabilitySlotItem,
  OrderResponse,
  ProfessionalCard as ProfessionalCardData,
  ProfessionalSort,
} from '../../shared/api';
import { ProfessionalList } from '../professionals';
import { SlotPicker } from './SlotPicker';
import { BookingSummary } from './BookingSummary';
import styles from './BookingFlowPage.module.css';

type Step =
  | { name: 'address' }
  | { name: 'professionals' }
  | { name: 'slot'; professional: ProfessionalCardData }
  | { name: 'confirm'; professional: ProfessionalCardData; slot: AvailabilitySlotItem }
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

/**
 * `/issues/:issueId/booking` — the Standard-booking step machine: service address →
 * professional list → slot picker → confirmation → success. Mirrors
 * `features/issues/NewIssuePage.tsx`'s pattern (one route, an internal step union, a
 * subtle step indicator per DESIGN_SYSTEM.md §38 — not a wizard UI) rather than one route
 * per step, so "back" can preserve state already entered.
 *
 * The service address is collected once, here, as the first step (§4 of this milestone's
 * brief) — reused unmodified for the professional-listing query params, thread through to
 * the slot-picker step (which needs no address), and becomes the order's
 * `serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment` on creation.
 */
export default function BookingFlowPage() {
  const navigate = useNavigate();
  const { issueId: issueIdParam } = useParams<{ issueId: string }>();
  const issueId = Number(issueIdParam);

  const [address, setAddress] = useState<AddressValue>(EMPTY_ADDRESS);
  const [addressErrors, setAddressErrors] = useState<Partial<Record<keyof AddressValue, string>>>({});

  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [professionals, setProfessionals] = useState<ProfessionalCardData[]>([]);
  const [sort, setSort] = useState<ProfessionalSort>('CHEAPEST');
  const [isLoadingProfessionals, setIsLoadingProfessionals] = useState(false);
  const [professionalsError, setProfessionalsError] = useState<string | null>(null);

  const [slots, setSlots] = useState<AvailabilitySlotItem[]>([]);
  const [selectedSlot, setSelectedSlot] = useState<AvailabilitySlotItem | null>(null);
  const [isLoadingSlots, setIsLoadingSlots] = useState(false);
  const [slotsError, setSlotsError] = useState<string | null>(null);

  const [step, setStep] = useState<Step>({ name: 'address' });

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
  }

  function handleSortChange(nextSort: ProfessionalSort) {
    setSort(nextSort);
    void fetchProfessionals(nextSort, address);
  }

  async function fetchSlots(professional: ProfessionalCardData) {
    setIsLoadingSlots(true);
    setSlotsError(null);
    setSelectedSlot(null);
    try {
      const result = await getProfessionalSlots(professional.professionalId, issueId);
      setSlots(result.slots);
    } catch {
      setSlotsError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsLoadingSlots(false);
    }
  }

  function handleSelectProfessional(professional: ProfessionalCardData) {
    setStep({ name: 'slot', professional });
    void fetchSlots(professional);
  }

  function handleSlotContinue() {
    if (step.name !== 'slot' || !selectedSlot) {
      return;
    }
    setStep({ name: 'confirm', professional: step.professional, slot: selectedSlot });
  }

  function handleSlotUnavailable() {
    if (step.name !== 'confirm') {
      return;
    }
    const { professional } = step;
    setStep({ name: 'slot', professional });
    void fetchSlots(professional);
  }

  function handleConfirmed(order: OrderResponse) {
    if (step.name !== 'confirm') {
      return;
    }
    setStep({ name: 'success', order, professionalName: step.professional.fullName });
  }

  function handleBack() {
    if (step.name === 'address') {
      navigate('/');
    } else if (step.name === 'professionals') {
      setStep({ name: 'address' });
    } else if (step.name === 'slot') {
      setStep({ name: 'professionals' });
    } else if (step.name === 'confirm') {
      setStep({ name: 'slot', professional: step.professional });
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
          <AddressFormFields value={address} onChange={setAddress} errors={addressErrors} />
          <Button onClick={handleAddressContinue} fullWidth>
            המשך
          </Button>
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
              onSortChange={handleSortChange}
              onSelect={handleSelectProfessional}
              isLoading={isLoadingProfessionals}
            />
          )}
        </div>
      )}

      {step.name === 'slot' && (
        <div className={styles.step}>
          {slotsError ? (
            <div className={styles.banner} role="alert">
              <p>{slotsError}</p>
            </div>
          ) : (
            <>
              <SlotPicker
                slots={slots}
                selectedSlotId={selectedSlot?.slotId ?? null}
                onSelect={setSelectedSlot}
                isLoading={isLoadingSlots}
              />
              {slots.length > 0 && (
                <Button onClick={handleSlotContinue} disabled={!selectedSlot} fullWidth>
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
          slot={step.slot}
          address={address}
          onConfirmed={handleConfirmed}
          onSlotUnavailable={handleSlotUnavailable}
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
