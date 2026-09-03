import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Siren } from 'lucide-react';
import {
  Button,
  Card,
  EMPTY_ADDRESS,
  PageHeader,
  Skeleton,
  toAddressValue,
  toServicePlaceFields,
  validateAddress,
  validateAddressTextOnly,
} from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { AddressSelectionStep } from '../booking';
import type { AddressMode } from '../booking';
import { useAuth, useBookingDraft } from '../../shared/hooks';
import {
  ApiError,
  GENERIC_ERROR_MESSAGE,
  createIssue,
  createSosRequest,
  getCategoryNameHe,
  getIssue,
  getMySosRequests,
  isSosTerminalStatus,
} from '../../shared/api';
import type { IssueDetailResponse } from '../../shared/api';
import { SOS_ERROR_MESSAGES } from './sosUiState';
import ProntoSosScreen from './ProntoSosScreen';
import styles from './ProntoSosEntryPage.module.css';

/**
 * Reaching the SOS commit with no issue and not enough draft to create one.
 *
 * A local sentinel rather than an {@link ApiError}, because no request was made — this is the
 * client refusing to send something the server would rightly reject. It is the guard that replaces
 * the old behaviour of posting `"issueId": null`: an invalid lifecycle state now stops here, with
 * a way forward, instead of becoming a `VALIDATION_ERROR` the customer cannot act on.
 */
class MissingIssueDraftError extends Error {
  constructor() {
    super('SOS activation reached with no issue and no draft to create one from.');
    this.name = 'MissingIssueDraftError';
  }
}

function toUserMessage(error: unknown): string {
  if (error instanceof MissingIssueDraftError) {
    return 'לא מצאנו את פרטי התקלה. צריך לתאר אותה שוב כדי שנוכל לחפש בעל מקצוע.';
  }
  if (error instanceof ApiError && SOS_ERROR_MESSAGES[error.code]) {
    return SOS_ERROR_MESSAGES[error.code];
  }
  return GENERIC_ERROR_MESSAGE;
}

/**
 * `/sos-booking` — the customer's way into Pronto SOS.
 *
 * ## The Production bug this page had, and why it only hit signed-in customers
 *
 * This page used to read `issueId` from `useParams()`, because its route used to be
 * `/issues/:issueId/sos-booking`. Deferred authentication flattened it to `/sos-booking` — during
 * matching there is no issue yet, so there is no id to put in the URL — but this page was not
 * updated with it. `useParams().issueId` became `undefined`, `Number(undefined)` is `NaN`, and
 * `JSON.stringify` serialises `NaN` as `null`, so every activation posted `"issueId": null` and
 * the backend answered exactly:
 *
 * ```
 * VALIDATION_ERROR  issueId: must not be null
 * ```
 *
 * It presented as authenticated-only because a guest is redirected to login by the boundary in
 * {@link activate} *before* the request is ever built — so only a signed-in customer got far
 * enough to send the malformed body.
 *
 * ## The one rule this page is built around
 *
 * The customer has already described the problem. They do not describe it again — not to activate
 * SOS, and not to retry after a failed attempt. The issue carries the category, the description,
 * the photos and the AI analysis, and an SOS request is *an attempt to find someone for it*, not
 * a second copy of it. So there is no path from here back to issue creation, ever.
 *
 * **That rule is unchanged; what changed is *when* the issue starts existing.** SOS still requires
 * a real, persisted, caller-owned issue — `SosService.create` loads it, checks ownership, requires
 * `urgencyType = SOS` and `status = OPEN`, reads its `categoryId` for matching, and the
 * one-active-attempt-per-issue invariant (`ux_sos_requests_active_issue`) is keyed on its id.
 * Under deferred authentication that issue is created at the *commit*, which for SOS is this
 * screen, exactly as `features/booking/BookingSummary` creates it at the Standard commit. So this
 * page now creates the issue when the draft has not already produced one, immediately before
 * activating — see {@link resolveIssueId}.
 *
 * ## What it does
 *
 * 1. Resolves the issue for this attempt: an id the draft already carries (a returning customer, a
 *    retry, or a Standard-flow issue being escalated), otherwise none yet. When it has one it also
 *    looks for an SOS attempt already in flight on it (`GET /api/sos/requests/me`). That lookup is
 *    what makes a refresh, a returning customer and a second tab all land back on the live screen
 *    instead of trying to activate a second time — the backend permits only one active attempt per
 *    issue, enforced by a unique index.
 * 2. Resolves the service address without asking again where it can: the booking draft the
 *    matching screen already wrote, then the profile's saved default. It only renders the address
 *    step when there is genuinely nothing to use (a direct visit with no default on file).
 * 3. **Activates immediately**, and hands the whole live experience to `ProntoSosScreen`.
 *
 * ## Why there is no "הפעלת SOS" button any more (MS3)
 *
 * There used to be a confirmation card here: the customer picked SOS, pressed Continue, and then
 * had to press a second button to actually start the search. Two presses for one decision, the
 * second of which asked "are you sure?" about something the customer had already chosen twice —
 * while a pipe was leaking. Choosing SOS and continuing *is* the intent, so arriving here with a
 * usable address starts the search, once (`activationAttemptedRef`), and the customer lands on
 * the live screen with the scan already running. The address step still appears when there is
 * genuinely nothing to send, and cancelling is always one tap away on the live screen.
 *
 * This replaces the no-API placeholder that held this route while the Pronto SOS backend was
 * being built.
 */
export default function ProntoSosEntryPage() {
  const navigate = useNavigate();
  const { user, token } = useAuth();
  const { draft, updateDraft } = useBookingDraft();
  /**
   * Still read, and still only a fallback. The current route carries no id, so this is `undefined`
   * in normal use — it is kept because `NotificationBell` and `OrderTrackingPage` still link to the
   * legacy `/issues/:issueId/sos-booking` shape, so that an id supplied that way keeps working if
   * those links are ever repaired. What it must never again do is reach the request body unchecked:
   * everything below goes through `issueId`, which is `null` rather than `NaN` when absent.
   */
  const { issueId: issueIdParam } = useParams<{ issueId: string }>();
  const routeIssueId = Number(issueIdParam);

  /**
   * The issue this attempt is for, or `null` when it does not exist yet.
   *
   * `null`, never `NaN` — that distinction is the whole bug. A number that is not a number
   * survives every truthiness check and only reveals itself as `null` after `JSON.stringify`, at
   * the server, as a validation error nobody can trace back to a missing route segment.
   */
  const [issueId, setIssueId] = useState<number | null>(() => {
    if (Number.isFinite(routeIssueId) && routeIssueId > 0) {
      return routeIssueId;
    }
    return draft?.issueId ?? null;
  });

  const [issue, setIssue] = useState<IssueDetailResponse | null>(null);
  const [sosRequestId, setSosRequestId] = useState<number | null>(null);
  const [isResolving, setIsResolving] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [activationError, setActivationError] = useState<string | null>(null);
  /**
   * Whether the failure was "there is nothing to activate" rather than "activation was refused".
   * The two need different offers: retrying a missing draft can only fail again in exactly the
   * same way, so that case gets a route back to describing the problem instead of a dead button.
   */
  const [needsIssueDescription, setNeedsIssueDescription] = useState(false);
  const [isActivating, setIsActivating] = useState(false);

  const defaultAddress: AddressValue | null = user?.defaultAddress ? toAddressValue(user.defaultAddress) : null;
  /**
   * The address the matching screen already wrote. Previously gated on `draft.issueId === issueId`,
   * which under deferred authentication is `undefined === NaN` — false — for every customer who
   * came through the normal flow, so the address they had just entered was discarded and the page
   * fell back to the profile default (or asked again). Now it simply takes the SOS draft's address:
   * there is exactly one draft, this page only runs for `urgencyType === 'SOS'`, and when the draft
   * does carry an issue id it is the same one `issueId` was initialised from.
   */
  const draftAddress = draft?.urgencyType === 'SOS' ? (draft.address ?? null) : null;

  const [address, setAddress] = useState<AddressValue>(() => draftAddress ?? defaultAddress ?? EMPTY_ADDRESS);
  const [addressMode, setAddressMode] = useState<AddressMode>(() =>
    draftAddress || !defaultAddress ? 'CUSTOM' : 'DEFAULT',
  );
  const [addressErrors, setAddressErrors] = useState<Partial<Record<keyof AddressValue, string>>>({});
  /** Set by the "change address" action. The step is otherwise shown only when nothing usable
   *  was inherited from the draft or the profile — SOS should not re-ask a question already
   *  answered a screen ago. */
  const [isEditingAddress, setIsEditingAddress] = useState(false);

  /**
   * Issue context plus any attempt already running, in one pass.
   *
   * `allSettled`, not `all`, and that matters: the two answers have different weights. Finding an
   * attempt already in flight is what stops a refresh from trying to activate a second one, so it
   * must survive a failed issue fetch — the issue only supplies the category and description shown
   * on the activation card, and the server validates the issue at activation regardless. Only
   * losing *both* is a real error.
   */
  useEffect(() => {
    // No issue yet — the normal deferred-authentication path. There is nothing to fetch and, by
    // definition, no attempt in flight for an issue that does not exist. Previously this branch
    // set a generic load error (because `NaN` is not finite) while the activation effect below
    // went ahead and posted a null id anyway.
    if (issueId === null) {
      setIsResolving(false);
      return;
    }
    let cancelled = false;
    const currentIssueId = issueId;

    async function resolve() {
      const [issueResult, mineResult] = await Promise.allSettled([getIssue(currentIssueId), getMySosRequests()]);
      if (cancelled) {
        return;
      }
      if (issueResult.status === 'fulfilled') {
        setIssue(issueResult.value);
      }
      if (mineResult.status === 'fulfilled') {
        const active = mineResult.value.requests.find(
          (candidate) => candidate.issueId === currentIssueId && !isSosTerminalStatus(candidate.status),
        );
        if (active) {
          setSosRequestId(active.id);
        }
      }
      if (issueResult.status === 'rejected' && mineResult.status === 'rejected') {
        setLoadError(toUserMessage(issueResult.reason));
      }
      setIsResolving(false);
    }

    void resolve();
    return () => {
      cancelled = true;
    };
  }, [issueId]);

  /**
   * Activation, and retry — the same call, deliberately. The backend allows a second, third or
   * fourth attempt on the same issue once the previous one has finished, which is exactly what
   * "נסה שוב" needs and why retry does not go anywhere near issue creation.
   *
   * A `SOS_REQUEST_ALREADY_EXISTS` here means an attempt is live that this screen did not know
   * about (a second tab, or a double tap that raced the unique index). Attaching to it is a
   * better answer than reporting an error for something the customer wanted anyway.
   */
  /**
   * The issue this SOS attempt is for — reused if it already exists, created exactly once if not.
   *
   * <b>Idempotency, which is the whole reason this is not inlined.</b> The created id is written to
   * `issueId` state *and* to the draft before anything else can fail, so the three ways this screen
   * re-enters activation all reuse it instead of describing the problem a second time:
   *
   *  - `SOS_REQUEST_ALREADY_EXISTS` recovery, which re-reads `/api/sos/requests/me`
   *  - the live screen's "נסה שוב" after a terminal attempt ({@link handleRetry})
   *  - the error card's "ניסיון נוסף" after a failed activation
   *
   * A refresh reuses it too, because the draft survives the reload — which is also what makes the
   * backend's one-active-attempt-per-issue index do its job rather than being sidestepped by a
   * fresh issue per retry.
   *
   * <b>What it refuses.</b> An SOS commit needs a described problem: a confirmed category and a
   * description. Without them there is nothing to create and nothing for a professional to answer,
   * so this throws rather than inventing a placeholder issue or letting a malformed request reach
   * the server. {@link activate}'s catch turns that into the "start again" card.
   */
  /**
   * Whether this screen has enough to produce an issue — an id it already holds, or a draft
   * carrying a confirmed category and a description.
   *
   * Consulted in two places, and both matter: the auto-activation effect below (so no request is
   * ever attempted from an invalid lifecycle state) and the render (so the customer is told what is
   * missing instead of being asked for an address that leads nowhere). {@link resolveIssueId}
   * enforces the same rule at the commit itself, which is the one that must hold even if a future
   * caller reaches activation by another path.
   */
  const canCommit =
    issueId !== null || Boolean(draft && draft.categoryId !== undefined && draft.description.trim());

  const resolveIssueId = useCallback(async (): Promise<number> => {
    if (issueId !== null) {
      return issueId;
    }
    if (!draft || draft.categoryId === undefined || !draft.description.trim()) {
      throw new MissingIssueDraftError();
    }
    const created = await createIssue({
      categoryId: draft.categoryId,
      description: draft.description,
      urgencyType: 'SOS',
      // Ownership is the server's call, not this screen's: `IssuesService.validateImageKeys`
      // re-checks every key against the caller's own namespace (and, for a guest who registered
      // mid-flow, against the guest-session token `httpClient` still attaches), then promotes the
      // guest-owned ones onto this account. Passing the draft's keys through unmodified is exactly
      // what the Standard commit does.
      imageKeys: draft.photos.map((photo) => photo.imageKey),
      clarificationAnswers: draft.clarificationAnswers ?? [],
    });
    setIssueId(created.id);
    // Persisted before activation is attempted, so a failure anywhere after this point retries
    // against the same issue. The Standard flow does NOT do this today — see the report.
    updateDraft({ issueId: created.id });
    return created.id;
  }, [issueId, draft, updateDraft]);

  const activate = useCallback(
    async (effectiveAddress: AddressValue) => {
      // ---- THE SOS AUTHENTICATION BOUNDARY ----
      //
      // Earlier than the standard flow's, and deliberately so. `POST /api/sos/requests` does not
      // merely record an intention: SosService.activate inserts the request, transitions it to
      // MATCHING and runs the first dispatch wave SYNCHRONOUSLY, writing offers and notifying real
      // professionals inside the same transaction. There is no later moment to stop at — the
      // commit and the notification are the same call.
      //
      // So the check goes here, before it, and an anonymous visitor can never cause a
      // professional's phone to ring. Everything up to this line is preparation the guest is
      // welcome to do: describing the emergency, confirming the address, reading what happens next.
      if (!token) {
        updateDraft({
          stage: 'BOOKING_CONFIRM',
          urgencyType: 'SOS',
          address: effectiveAddress,
          addressMode,
        });
        navigate('/login', { state: { from: { pathname: '/sos-booking' } } });
        return;
      }

      setActivationError(null);
      setNeedsIssueDescription(false);
      setIsActivating(true);
      // Declared out here so the recovery branch below can use the id this attempt actually sent.
      // Reading `issueId` state there would be wrong on the first activation of a freshly created
      // issue: `setIssueId` does not update this closure, so the lookup would compare against
      // `null` and silently fail to re-attach.
      let attemptedIssueId: number | null = null;
      try {
        // THE COMMIT. The issue is created here, not on the review screen, and only now that the
        // caller is authenticated -- the same boundary and the same ordering
        // `features/booking/BookingSummary` uses for the Standard flow. If this throws we stop:
        // `createSosRequest` is never reached, so a failed issue creation cannot dispatch anything.
        attemptedIssueId = await resolveIssueId();
        const created = await createSosRequest({
          issueId: attemptedIssueId,
          serviceCity: effectiveAddress.city.trim(),
          serviceStreet: effectiveAddress.street.trim(),
          serviceHouseNumber: effectiveAddress.houseNumber.trim(),
          serviceApartment: effectiveAddress.apartment.trim() || undefined,
          serviceFloor: effectiveAddress.floor.trim() || undefined,
          serviceEntrance: effectiveAddress.entrance.trim() || undefined,
          serviceAddressNotes: effectiveAddress.addressNotes.trim() || undefined,
          // V55. All four place fields or none of them -- see `toServicePlaceFields`. Omitted for
          // any saved default address, legacy or not: `/me` does not return that address's
          // coordinates, so the client cannot make a complete claim about it and the server
          // resolves it from the `users` row instead. SOS relies on this more than any other flow,
          // since it activates against the saved address without asking.
          ...toServicePlaceFields(effectiveAddress),
        });
        setSosRequestId(created.id);
      } catch (err) {
        if (err instanceof ApiError && err.code === 'SOS_REQUEST_ALREADY_EXISTS' && attemptedIssueId !== null) {
          try {
            const mine = await getMySosRequests();
            const active = mine.requests.find(
              (candidate) => candidate.issueId === attemptedIssueId && !isSosTerminalStatus(candidate.status),
            );
            if (active) {
              setSosRequestId(active.id);
              return;
            }
          } catch {
            // Fall through to the plain error below — the recovery lookup failing is not a
            // different problem from the customer's point of view.
          }
        }
        setNeedsIssueDescription(err instanceof MissingIssueDraftError);
        setActivationError(toUserMessage(err));
      } finally {
        setIsActivating(false);
      }
    },
    [resolveIssueId, token, addressMode, updateDraft, navigate],
  );

  /**
   * Address validation, mode-aware — and the mode really matters here.
   *
   * A one-off address typed for this SOS must be selected from autocomplete, exactly as in the
   * booking flow. The customer's own saved default address must NOT be, because it may predate
   * autocomplete and the backend grandfathers it. Applying the strict rule to a legacy default
   * address would send a customer with a burst pipe to an address-editing screen instead of
   * looking for a plumber -- and `hasUsableAddress` below would suppress the automatic
   * activation this whole screen is built around.
   */
  function validateCurrentAddress(): Partial<Record<keyof AddressValue, string>> {
    return addressMode === 'DEFAULT'
      ? validateAddressTextOnly(address)
      : validateAddress(address);
  }

  /**
   * The address step's own action. It no longer hands back to a confirmation card — filling in
   * where to send somebody is the last question this flow has, so answering it starts the search.
   */
  function handleAddressContinue() {
    const errors = validateCurrentAddress();
    setAddressErrors(errors);
    if (Object.keys(errors).length === 0) {
      setIsEditingAddress(false);
      void activate(address);
    }
  }

  /**
   * Retry from the live screen's terminal state — a new attempt on the same issue, same address,
   * nothing re-entered. The finished screen deliberately stays mounted (with its button in a
   * loading state) until the new request exists, rather than being torn down first: swapping it
   * for the activation card and back again would flash two screens for one action. The screen is
   * keyed on the request id, so it remounts clean the moment the new id lands.
   */
  const handleRetry = useCallback(() => {
    void activate(address);
  }, [activate, address]);

  const hasUsableAddress = Object.keys(validateCurrentAddress()).length === 0;

  /**
   * Exactly one automatic activation per mount. A ref, not state: two effect runs in the same
   * tick would both read a stale flag and fire twice, and while the backend's unique index would
   * refuse the second, the customer would see an error for something that worked.
   *
   * Note what this deliberately does *not* re-run on: a failed activation sets
   * `activationError` and stops. Retrying automatically would hammer a backend that just said no
   * (an issue that is not SOS, an issue no longer open) — the live screen's own "נסה שוב" is the
   * retry path, and it is a decision, not a loop.
   */
  const activationAttemptedRef = useRef(false);
  useEffect(() => {
    if (isResolving || sosRequestId !== null || activationAttemptedRef.current) {
      return;
    }
    // Nothing to activate: no issue, and no draft to build one from. The render below explains it
    // and offers the way back. Attempting anyway is what used to post a null issue id.
    if (!canCommit) {
      return;
    }
    // Nothing to send yet (no draft, no saved default) or the customer is mid-edit: the address
    // step is showing and its own continue action will start the search.
    if (!hasUsableAddress || isEditingAddress) {
      return;
    }
    // A STANDARD issue reaching this route is a mistake to explain, not a search to start.
    if (issue && issue.urgencyType !== 'SOS') {
      return;
    }
    // ---- a guest is not auto-dispatched, and is not auto-redirected either ----
    //
    // `activate` refuses without a token and sends the customer to login, which is the correct
    // boundary (the SOS commit notifies real professionals synchronously — see `activate`). But
    // reaching it from *this effect* meant a guest was bounced to `/login` in the first frame of a
    // screen they never saw: no explanation of what SOS does, no confirmation of the address it
    // was about to dispatch to, no statement that an account is needed and why. The whole point of
    // deferring authentication is that a visitor gets to understand the offer before being asked
    // to sign up, and an automatic redirect on mount is the one shape that cannot do that.
    //
    // So the boundary stays exactly where it is — nothing is dispatched, no professional is
    // contacted — and only the *trigger* moves: the guest gets the pre-dispatch card below with an
    // explicit button, which calls the very same `activate` and lands on the very same login
    // screen. The draft is written on the way, so `useSessionLanding` returns them here and the
    // search starts the moment they are signed in.
    if (!token) {
      return;
    }
    activationAttemptedRef.current = true;
    void activate(address);
  }, [isResolving, sosRequestId, canCommit, hasUsableAddress, isEditingAddress, issue, activate, address, token]);

  // ---- render ----

  if (isResolving) {
    return (
      <div className="focused-page">
        <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />
        <Skeleton variant="rect" className={styles.loading} />
      </div>
    );
  }

  if (loadError && !issue) {
    return (
      <div className="focused-page">
        <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />
        <Card className={styles.card}>
          <p className={styles.errorText}>{loadError}</p>
          <Button onClick={() => navigate('/')} fullWidth>
            חזרה לדף הבית
          </Button>
        </Card>
      </div>
    );
  }

  // Checked before everything below: once an attempt exists, the live screen is the answer
  // regardless of what any enrichment fetch did or didn't manage to load.
  if (sosRequestId !== null) {
    return (
      <div className="focused-page">
        <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />
        {/* Keyed on the request id so a retry mounts a genuinely fresh screen rather than
            reusing the finished attempt's state. */}
        <ProntoSosScreen
          key={sosRequestId}
          sosRequestId={sosRequestId}
          onRetry={handleRetry}
          isRetrying={isActivating}
          retryError={activationError}
        />
      </div>
    );
  }

  // A STANDARD issue can reach this route (a stale draft, a shared link). Say so and point at
  // the flow that does apply, rather than letting activation fail with a server error.
  if (issue && issue.urgencyType !== 'SOS') {
    return (
      <div className="focused-page">
        <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />
        <Card className={styles.card}>
          <p className={styles.cardTitle}>התקלה הזו לא מסומנת כדחופה</p>
          <p className={styles.cardBody}>
            אפשר לבחור בעל מקצוע ומועד שנוח לך בתהליך ההזמנה הרגיל.
          </p>
          {/* `/booking`, not the retired `/issues/{id}/booking` — that path no longer resolves
              since deferred authentication flattened the booking routes, so the old link sent the
              customer to a blank screen. The draft carries the issue, as it does everywhere else
              in this flow. */}
          <Button onClick={() => navigate('/booking')} fullWidth>
            להזמנה רגילה
          </Button>
        </Card>
      </div>
    );
  }

  if (!canCommit) {
    return (
      <div className="focused-page">
        <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />
        <Card className={styles.card}>
          <span className={styles.mark} aria-hidden="true">
            <Siren size={26} />
          </span>
          <h2 className={styles.cardTitle}>לא הצלחנו להתחיל את החיפוש</h2>
          <p className={styles.errorText} role="alert">
            לא מצאנו את פרטי התקלה. צריך לתאר אותה שוב כדי שנוכל לחפש בעל מקצוע.
          </p>
          <div className={styles.actions}>
            <Button onClick={() => navigate('/issues/new')} fullWidth>
              תיאור התקלה
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  // Nothing usable inherited, or the customer asked to change it: ask here, once.
  if (!hasUsableAddress || isEditingAddress) {
    return (
      <div className="focused-page">
        <PageHeader
          title="לאן שנגיע?"
          description="נשתמש בכתובת הזו כדי לקרוא לבעלי מקצוע שנמצאים קרוב אליך עכשיו."
          onBack={() => (hasUsableAddress ? setIsEditingAddress(false) : navigate('/'))}
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

  const addressSummary = [address.city, `${address.street} ${address.houseNumber}`.trim()]
    .filter(Boolean)
    .join(', ');

  /**
   * The trade this attempt is for. The issue when one exists, otherwise the category the review
   * step confirmed and wrote to the draft.
   *
   * The draft fallback is not cosmetic. On the normal deferred-authentication path there is no
   * issue yet — always for a guest, and for a signed-in customer until the commit one line later —
   * so reading the issue alone rendered a literal "—" where the customer's own confirmed
   * profession should be, on the last screen before real professionals are called out to their
   * home. The draft is the source of truth for everything else on this page; it is here too.
   */
  const summaryCategoryId = issue?.categoryId ?? draft?.categoryId;
  const draftCategoryName = summaryCategoryId !== undefined ? getCategoryNameHe(summaryCategoryId) : '—';

  // Activation failed. The only screen left with something to say — everything above either
  // resolved into the live screen or into a question. Deliberately not auto-retried: the backend
  // refused for a reason, and retrying in a loop would hide it.
  if (activationError) {
    return (
      <div className="focused-page">
        <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />
        <Card className={styles.card}>
          <span className={styles.mark} aria-hidden="true">
            <Siren size={26} />
          </span>
          <h2 className={styles.cardTitle}>לא הצלחנו להתחיל את החיפוש</h2>
          <p className={styles.errorText} role="alert">
            {activationError}
          </p>
          <div className={styles.actions}>
            {/* Nothing to activate: the only honest action is to describe the problem. Retrying
                would re-run a guard that cannot pass, which is how the previous version of this
                screen behaved when it posted a null issue id and offered "try again" for a
                request the server would refuse identically every time. */}
            {needsIssueDescription ? (
              <Button onClick={() => navigate('/issues/new')} fullWidth>
                תיאור התקלה
              </Button>
            ) : (
              <Button onClick={() => void activate(address)} loading={isActivating} fullWidth>
                ניסיון נוסף
              </Button>
            )}
            <Button
              variant="ghost"
              onClick={() => {
                setAddressErrors({});
                setIsEditingAddress(true);
              }}
              fullWidth
            >
              שינוי כתובת
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  // The one frame between "we have everything we need" and the live screen: the activation
  // request is in flight (or about to be). No button — see this page's doc comment.
  return (
    <div className="focused-page">
      <PageHeader title="Pronto SOS" onBack={() => navigate('/')} />

      <Card className={styles.card}>
        <span className={styles.mark} aria-hidden="true">
          <Siren size={26} />
        </span>
        <h2 className={styles.cardTitle}>מתחילים לחפש בעל מקצוע</h2>
        <p className={styles.cardBody}>
          פונים עכשיו לבעלי מקצוע פנויים באזור שלך. כל מי שיאשר שהוא יכול להגיע יופיע כאן מיד, עם
          זמן ההגעה שלו.
        </p>

        {/* The context that already exists — shown so it's clear nothing needs to be filled in
            again, per this page's doc comment. */}
        <dl className={styles.summary}>
          <div className={styles.summaryRow}>
            <dt className={styles.summaryLabel}>תחום</dt>
            <dd className={styles.summaryValue}>{draftCategoryName}</dd>
          </div>
          <div className={styles.summaryRow}>
            <dt className={styles.summaryLabel}>כתובת</dt>
            <dd className={styles.summaryValue}>{addressSummary}</dd>
          </div>
        </dl>

        {/* The guest boundary, made visible. Everything above this point is the same card a
            signed-in customer sees for the one frame before the search starts; the difference is
            that a guest is told an account is needed and why, and presses the button themselves.
            No request has been sent and no professional has been contacted. */}
        {token ? (
          <Skeleton variant="rect" className={styles.loading} />
        ) : (
          <div className={styles.actions}>
            <p className={styles.cardBody}>
              כדי לשלוח בעלי מקצוע לכתובת הזו צריך חשבון — כך נוכל לעדכן אותך ולתת לבעל המקצוע דרך
              ליצור איתך קשר. הפרטים שמילאת נשמרים ונמשיך בדיוק מכאן.
            </p>
            <Button onClick={() => void activate(address)} fullWidth>
              התחברות והפעלת SOS
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setAddressErrors({});
                setIsEditingAddress(true);
              }}
              fullWidth
            >
              שינוי כתובת
            </Button>
          </div>
        )}
      </Card>
    </div>
  );
}
