import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import {
  Button,
  EMPTY_ADDRESS,
  PageHeader,
  isAddressComplete,
  toAddressValue,
  validateAddress,
  validateAddressTextOnly,
} from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { AddressSelectionStep } from '../booking';
import type { AddressMode } from '../booking';
import { useAuth, useBookingDraft, useHeaderBackAction } from '../../shared/hooks';
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
  // No issue id in the route any more. Deferred authentication moved issue creation to the
  // booking commit, so at this point in the journey there is usually no issue at all -- for a
  // guest because they have no account, and for a signed-in customer because nothing has been
  // committed. The draft is the state.
  //
  // `draft.issueId` is still read, and is still meaningful in exactly one case: a customer who
  // created an issue on an earlier pass and came back to it. Everything downstream treats it as
  // optional.
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
  const issueId = draft?.issueId;
  // The draft IS this journey now, rather than one of several drafts keyed by issue. There is
  // only ever one in localStorage, so "does it match?" reduces to "is there one?".
  const draftMatchesIssue = draft != null;
  // `draft?.address` alone was the condition here, and it is where the 400 came from: an
  // `AddressValue` full of empty strings is still an object, so a draft written by the back
  // button (which persists whatever was on screen, including nothing) resumed straight into the
  // wheel — and the wheel's whole job is to warm `GET /api/bookings/professionals`, which
  // requires city/street/houseNumber. `isAddressComplete` asks the question that was meant.
  // `addressUnconfirmed` is the stale-restore flag (`sanitizeRestoredDraft`): the address is
  // still here as prefill, but a returning guest has not said it is still where they want somebody
  // sent, so the question gets asked rather than assumed from the fields being non-empty.
  const [phase, setPhase] = useState<'address' | 'matching'>(
    draftMatchesIssue && isAddressComplete(draft?.address) && !draft?.addressUnconfirmed
      ? 'matching'
      : 'address',
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

  /**
   * The same check the booking flow's own address step applies — now by delegation rather than
   * by a third copy of three `if`s. The copies had already drifted: this one never learned the
   * "must have been selected from Google" rule, so an address typed here could reach the wheel
   * (and the listing prefetch behind it) unvalidated, only to be refused one screen later.
   *
   * Mode-aware for the same reason every other surface is: the customer's own saved home address
   * may predate address validation and is grandfathered by the backend, so demanding a
   * re-selection of it would stop a customer mid-flow over an address that works.
   */
  function handleAddressContinue() {
    const errors =
      addressMode === 'DEFAULT' ? validateAddressTextOnly(address) : validateAddress(address);
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
      // Confirmed for this visit — see `sanitizeRestoredDraft`.
      addressUnconfirmed: undefined,
      ...(resolved ? { urgencyType: resolved.urgencyType, categoryId: resolved.categoryId } : {}),
    });
    setPhase('matching');
  }

  // The category comes from the hand-off (the common path), then the draft, and only then from
  // the issue itself. That last source now applies to one case: a customer returning to an issue
  // they created on an earlier pass. A guest has no issue to fetch and does not need one -- the
  // draft carries the category the review step confirmed.
  useEffect(() => {
    if (resolved) {
      return;
    }
    if (draft?.categoryId !== undefined && draft.urgencyType !== undefined) {
      setResolved({ categoryId: draft.categoryId, urgencyType: draft.urgencyType });
      return;
    }
    if (issueId === undefined || !Number.isFinite(issueId)) {
      setLoadError(GENERIC_ERROR_MESSAGE);
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issueId, resolved, draft?.categoryId, draft?.urgencyType]);

  // Back lives in the app header on every screen of this flow (`AppLayout`). `null` while the
  // wheel is turning: that phase has no header and nothing to go back to — it auto-advances.
  useHeaderBackAction(phase === 'address' ? handleAddressBack : null);

  const isKnownCategory = resolved !== null && CATEGORIES.some((category) => category.id === resolved.categoryId);
  const bookingPath = resolved?.urgencyType === 'SOS' ? '/sos-booking' : '/booking';

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
    // The address guard is not redundant with `phase === 'matching'`: this effect is what turns
    // an address into a network request, so it re-states the precondition rather than trusting
    // that every path into the matching phase has already checked it. Without an address there
    // is nothing to warm — the wheel still spins and the booking flow's own address step takes
    // over on the other side.
    if (phase !== 'matching' || !resolved || !isKnownCategory || preloadRef.current
        || resolved.urgencyType === 'SOS' || !isAddressComplete(address)) {
      setIsListingReady(true);
      return;
    }
    // The address the customer just chose — default or one-off — is what the listing is warmed
    // with, so service-area relevance, distance and ETA are computed for the place the
    // professional actually has to reach.
    // Keyed on the issue when one exists, otherwise on the category the review step
    // confirmed -- the same subject the listing screen itself will use, so the warmed request is
    // the one it adopts rather than a near miss it has to redo.
    preloadRef.current = prefetchProfessionalListing(
      issueId !== undefined ? { issueId } : { categoryId: resolved.categoryId },
      address,
      RESUME_SORT,
    )
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
        />
        <AddressSelectionStep
          value={address}
          onChange={setAddress}
          mode={addressMode}
          onModeChange={setAddressMode}
          errors={addressErrors}
          onContinue={handleAddressContinue}
          offerSaveAsHome
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
