import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Button, EMPTY_ADDRESS, PageHeader, toAddressValue } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { AddressSelectionStep } from '../booking';
import type { AddressMode } from '../booking';
import { useAuth, useBookingDraft } from '../../shared/hooks';
import {
  CATEGORIES,
  getCategoryNameHe,
  getIssue,
  prefetchProfessionalListing,
  GENERIC_ERROR_MESSAGE,
} from '../../shared/api';
import type { IssueUrgencyType } from '../../shared/api';
import { listStagger, pageTransition } from '../../shared/motion/variants';
import { ProfessionRoulette } from './ProfessionRoulette';
import styles from './ProfessionMatchPage.module.css';

/** How long the landed profession is held on screen once the wheel stops, when the listing is
 *  already loaded. Raised from 900ms: at that length the result flashed past before it could be
 *  read, and the point of the animation is that the customer understands which profession was
 *  matched. Still no extra click — it leaves on its own. */
const SUCCESS_HOLD_MS = 1900;
/** Hard ceiling on waiting for the listing prefetch. Past this we navigate anyway and let the
 *  booking flow own the loading/error state — the brief's "do not keep the roulette spinning
 *  indefinitely", applied to the wait after the spin too. */
const MAX_PRELOAD_WAIT_MS = 6000;
/** The sort `BookingFlowPage`/`SosBookingFlowPage` resume with, mirrored here so the prefetch
 *  produces the exact request those screens are about to make. */
const RESUME_SORT = 'CHEAPEST';

/** Passed by `NewIssuePage` on hand-off, so the common path needs no extra request. Absent on
 *  refresh or a direct visit, where the issue is fetched instead — see the doc comment. */
export interface ProfessionMatchLocationState {
  categoryId?: number;
  urgencyType?: IssueUrgencyType;
}

interface ResolvedIssue {
  categoryId: number;
  urgencyType: IssueUrgencyType;
}

/**
 * `/issues/:issueId/matching` — everything between a finished issue and the professionals list,
 * in two phases: **service address, then the matching animation**. Replaces the old
 * `IssueSuccessStep` ("הבנתי. עכשיו נמצא לך מישהו" + a "בחירת בעל מקצוע" button), which made the
 * customer click again to see results they had already asked for.
 *
 * The address comes first because the wheel is a promise about *results*, and results depend on
 * where the professional has to travel to: the listing endpoint takes the service address and
 * derives service-area relevance, distance and ETA from it. Animating a match before knowing
 * the address would mean preloading against the wrong location, or not preloading at all.
 *
 * The address step is `features/booking`'s own `AddressSelectionStep`, imported rather than
 * reimplemented — the same default-vs-one-off chooser the booking flow uses, with the same
 * guarantee that picking a different address never writes back to the profile's saved default.
 *
 * The wheel is **not** choosing anything: the issue's own `categoryId` is the source of truth,
 * and the animation is a readout of a decision that was already made upstream in the AI
 * classification step.
 *
 * A route rather than a step inside `NewIssuePage`, so a refresh or a shared link re-derives
 * the same category from the issue itself instead of losing it with the component state.
 *
 * While the wheel turns, the professional listing for this issue is already being fetched
 * (`prefetchProfessionalListing`), and the booking flow this screen hands off to adopts that
 * in-flight request instead of issuing its own.
 */
export default function ProfessionMatchPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { issueId: issueIdParam } = useParams<{ issueId: string }>();
  const issueId = Number(issueIdParam);
  const { user } = useAuth();
  const { draft, updateDraft } = useBookingDraft();
  const shouldReduceMotion = useReducedMotion();

  const handedOver = (location.state as ProfessionMatchLocationState | null) ?? null;
  const [resolved, setResolved] = useState<ResolvedIssue | null>(
    handedOver?.categoryId !== undefined && handedOver.urgencyType !== undefined
      ? { categoryId: handedOver.categoryId, urgencyType: handedOver.urgencyType }
      : null,
  );
  const [loadError, setLoadError] = useState<string | null>(null);
  const [hasSettled, setHasSettled] = useState(false);

  const defaultAddress: AddressValue | null = user?.defaultAddress ? toAddressValue(user.defaultAddress) : null;

  /**
   * Address first, wheel second. Restored from the booking draft on mount, so a refresh mid-spin
   * — or a customer who navigates away and comes back — never re-asks for an address they have
   * already given. The draft is the same one the booking flow reads, so there is one stored
   * answer, not two.
   */
  const draftMatchesIssue = draft?.issueId === issueId;
  const [phase, setPhase] = useState<'address' | 'matching'>(
    draftMatchesIssue && draft?.address ? 'matching' : 'address',
  );
  const [address, setAddress] = useState<AddressValue>(() => {
    if (draftMatchesIssue && draft?.address) {
      return draft.address;
    }
    return defaultAddress ?? EMPTY_ADDRESS;
  });
  const [addressMode, setAddressMode] = useState<AddressMode>(() => {
    if (draftMatchesIssue && draft?.addressMode) {
      return draft.addressMode;
    }
    return defaultAddress ? 'DEFAULT' : 'CUSTOM';
  });
  const [addressErrors, setAddressErrors] = useState<Partial<Record<keyof AddressValue, string>>>({});

  /**
   * Back from the address step returns to the **AI classification** screen it was reached from,
   * not to the home page (which is what this button used to do — the customer's only way back to
   * the classification was to restart the whole issue flow, losing the description and photos).
   *
   * `NewIssuePage` navigated here with `replace: true`, so there is no history entry to pop; the
   * route back is the booking draft, rewound one stage. Everything the classification screen
   * needs is already in it — description, photos, clarification answers and the confirmed
   * category — so `NewIssuePage` re-hydrates straight onto its review step. `issueId` is
   * deliberately left in place: the issue already exists, and keeping it lets that screen
   * continue with the same issue when the category comes back unchanged instead of creating a
   * duplicate (`ReviewStep`'s `existingIssue`). The chosen address stays in the draft too, so a
   * customer who just wanted to re-check the classification isn't asked for it twice.
   */
  function handleAddressBack() {
    updateDraft({
      stage: 'ISSUE_REVIEW',
      ...(resolved ? { urgencyType: resolved.urgencyType, categoryId: resolved.categoryId } : {}),
      addressMode,
      address,
    });
    navigate('/issues/new');
  }

  /** Same required-field check the booking flow's own address step applies. */
  function handleAddressContinue() {
    const errors: Partial<Record<keyof AddressValue, string>> = {};
    if (!address.city.trim()) errors.city = 'יש להזין עיר.';
    if (!address.street.trim()) errors.street = 'יש להזין רחוב.';
    if (!address.houseNumber.trim()) errors.houseNumber = 'יש להזין מספר בית.';
    setAddressErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }
    // Persisted before the wheel starts, so a refresh resumes into the animation with this
    // address rather than back at the question. Never touches the profile's saved default —
    // `AddressSelectionStep` has no endpoint that could, and neither does this screen.
    updateDraft({
      stage: 'ADDRESS_SELECTION',
      issueId,
      addressMode,
      address,
      ...(resolved ? { urgencyType: resolved.urgencyType, categoryId: resolved.categoryId } : {}),
    });
    setPhase('matching');
  }

  // The issue is authoritative. Only fetched when the hand-off didn't carry the category —
  // i.e. on refresh, remount or a direct visit — so the common path costs no extra request.
  useEffect(() => {
    if (resolved || !Number.isFinite(issueId)) {
      return;
    }
    let cancelled = false;
    getIssue(issueId)
      .then((issue) => {
        if (!cancelled) {
          setResolved({ categoryId: issue.categoryId, urgencyType: issue.urgencyType });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoadError(GENERIC_ERROR_MESSAGE);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [issueId, resolved]);

  const isKnownCategory = resolved !== null && CATEGORIES.some((category) => category.id === resolved.categoryId);
  const bookingPath =
    resolved?.urgencyType === 'SOS' ? `/issues/${issueId}/sos-booking` : `/issues/${issueId}/booking`;

  /**
   * Warms the exact request the booking flow is about to make. Resolves either way — a failed
   * prefetch is not this screen's error to show, the listing screen owns that.
   */
  const preloadRef = useRef<Promise<unknown> | null>(null);
  const [isListingReady, setIsListingReady] = useState(false);
  useEffect(() => {
    // Skipped for an SOS issue: Pronto SOS dispatches on the customer's behalf and never
    // renders this listing, so warming it would be a wasted request against an endpoint the
    // SOS route does not use.
    if (phase !== 'matching' || !resolved || !isKnownCategory || preloadRef.current
        || resolved.urgencyType === 'SOS') {
      setIsListingReady(true);
      return;
    }
    // The address the customer just chose — default or one-off — is what the listing is warmed
    // with, so service-area relevance, distance and ETA are computed for the place the
    // professional actually has to reach.
    preloadRef.current = prefetchProfessionalListing(issueId, address, RESUME_SORT)
      .catch(() => undefined)
      // Ready either way — a failed prefetch shouldn't hold the customer on this screen; the
      // listing screen owns that error.
      .finally(() => setIsListingReady(true));
  }, [phase, resolved, isKnownCategory, address, issueId]);

  /**
   * Hands off to the booking flow. Guarded by a ref so React StrictMode's double-invoked
   * effects — or a settle callback that fires twice across a remount — can only navigate once.
   */
  const hasNavigatedRef = useRef(false);
  const goToProfessionals = useCallback(() => {
    if (hasNavigatedRef.current || !resolved) {
      return;
    }
    hasNavigatedRef.current = true;

    // Moving the existing booking draft forward is what makes the listing the *first* thing the
    // customer sees: `BookingFlowPage`/`SosBookingFlowPage` already resume onto their
    // professionals step for a `PROFESSIONAL_SELECTION` draft that carries an address. The
    // address travels in that same draft, so the flow never re-asks — its own address step stays
    // one back-button away for a customer who wants to change it.
    updateDraft({
      stage: 'PROFESSIONAL_SELECTION',
      issueId,
      urgencyType: resolved.urgencyType,
      categoryId: resolved.categoryId,
      addressMode,
      address,
      sort: RESUME_SORT,
    });

    navigate(bookingPath, { replace: true });
  }, [resolved, address, addressMode, issueId, updateDraft, navigate, bookingPath]);

  /** The wheel has stopped. Hold the result briefly, then leave as soon as the listing is
   *  ready — or once `MAX_PRELOAD_WAIT_MS` is up, whichever comes first. */
  const handleSettled = useCallback(() => {
    setHasSettled(true);
  }, []);

  useEffect(() => {
    if (!hasSettled) {
      return;
    }
    let cancelled = false;
    const hold = new Promise((resolve) => setTimeout(resolve, SUCCESS_HOLD_MS));
    const cap = new Promise((resolve) => setTimeout(resolve, MAX_PRELOAD_WAIT_MS));

    void Promise.race([
      Promise.all([hold, preloadRef.current ?? Promise.resolve()]),
      cap,
    ]).then(() => {
      if (!cancelled) {
        goToProfessionals();
      }
    });

    return () => {
      cancelled = true;
    };
  }, [hasSettled, goToProfessionals]);

  const containerAnimate = shouldReduceMotion
    ? { transition: { staggerChildren: 0, delayChildren: 0 } }
    : 'animate';
  const itemAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  // Unresolvable issue, or a category this app doesn't know: fall back to the plain hand-off
  // rather than animating a lie. Same banner treatment every other screen in this flow uses.
  if (loadError || (resolved && !isKnownCategory)) {
    return (
      <div className="focused-page">
        <PageHeader title="מחפשים בעל מקצוע" />
        <div className={styles.banner} role="alert">
          <p>{loadError ?? 'לא זיהינו את סוג התקלה, אבל אפשר להמשיך ולבחור בעל מקצוע.'}</p>
        </div>
        <Button onClick={() => navigate(bookingPath, { replace: true })} fullWidth>
          המשך לבחירת בעל מקצוע
        </Button>
      </div>
    );
  }

  if (phase === 'address') {
    return (
      <div className="focused-page">
        <PageHeader
          title="לאן שנגיע?"
          description="נשתמש בכתובת הזו כדי למצוא בעלי מקצוע קרובים אליך."
          onBack={handleAddressBack}
        />
        <AddressSelectionStep
          value={address}
          onChange={setAddress}
          mode={addressMode}
          onModeChange={setAddressMode}
          errors={addressErrors}
          onContinue={handleAddressContinue}
        />
      </div>
    );
  }

  return (
    <div className={`focused-page ${styles.page}`}>
      <motion.div
        className={styles.content}
        variants={listStagger}
        initial="initial"
        animate={containerAnimate}
      >
        <motion.p className={styles.eyebrow} variants={pageTransition} animate={itemAnimate}>
          {hasSettled ? 'מצאנו את בעלי המקצוע המתאימים!' : 'רגע, מתאימים לך בעל מקצוע…'}
        </motion.p>

        {resolved ? (
          <ProfessionRoulette
            categories={CATEGORIES}
            targetCategoryId={resolved.categoryId}
            onSettled={handleSettled}
          />
        ) : (
          <div className={styles.wheelPlaceholder} aria-hidden="true" />
        )}

        <motion.div className={styles.caption} variants={pageTransition} animate={itemAnimate}>
          {resolved && (
            <p className={styles.profession} aria-live="polite">
              {getCategoryNameHe(resolved.categoryId)}
            </p>
          )}
          {/* Only when the wheel has stopped *and* the list genuinely isn't ready yet. When the
              prefetch beat the animation — the normal case — this never appears at all and the
              screen goes straight from success to results. */}
          {hasSettled && !isListingReady && (
            <p className={styles.pending}>
              <span className={styles.dots} aria-hidden="true" />
              טוענים את בעלי המקצוע הזמינים
            </p>
          )}
        </motion.div>
      </motion.div>
    </div>
  );
}
