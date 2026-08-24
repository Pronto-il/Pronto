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
} from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { AddressSelectionStep } from '../booking';
import type { AddressMode } from '../booking';
import { useAuth, useBookingDraft } from '../../shared/hooks';
import {
  ApiError,
  GENERIC_ERROR_MESSAGE,
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

/** Required-field check, identical to the one the booking flow's own address step applies. */
function validateAddress(address: AddressValue): Partial<Record<keyof AddressValue, string>> {
  const errors: Partial<Record<keyof AddressValue, string>> = {};
  if (!address.city.trim()) errors.city = 'יש להזין עיר.';
  if (!address.street.trim()) errors.street = 'יש להזין רחוב.';
  if (!address.houseNumber.trim()) errors.houseNumber = 'יש להזין מספר בית.';
  return errors;
}

function toUserMessage(error: unknown): string {
  if (error instanceof ApiError && SOS_ERROR_MESSAGES[error.code]) {
    return SOS_ERROR_MESSAGES[error.code];
  }
  return GENERIC_ERROR_MESSAGE;
}

/**
 * `/issues/:issueId/sos-booking` — the customer's way into Pronto SOS, **from an issue that
 * already exists**.
 *
 * ## The one rule this page is built around
 *
 * The customer has already described the problem. They do not describe it again — not to activate
 * SOS, and not to retry after a failed attempt. The issue carries the category, the description,
 * the photos and the AI analysis, and an SOS request is *an attempt to find someone for it*, not
 * a second copy of it. So there is no path from here back to issue creation, ever.
 *
 * ## What it does
 *
 * 1. Loads the issue for context, and looks for an SOS attempt already in flight on it
 *    (`GET /api/sos/requests/me`). That lookup is what makes a refresh, a returning customer and
 *    a second tab all land back on the live screen instead of trying to activate a second time —
 *    the backend permits only one active attempt per issue, enforced by a unique index.
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
  const { user } = useAuth();
  const { draft } = useBookingDraft();
  const { issueId: issueIdParam } = useParams<{ issueId: string }>();
  const issueId = Number(issueIdParam);

  const [issue, setIssue] = useState<IssueDetailResponse | null>(null);
  const [sosRequestId, setSosRequestId] = useState<number | null>(null);
  const [isResolving, setIsResolving] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [activationError, setActivationError] = useState<string | null>(null);
  const [isActivating, setIsActivating] = useState(false);

  const defaultAddress: AddressValue | null = user?.defaultAddress ? toAddressValue(user.defaultAddress) : null;
  const draftAddress = draft?.issueId === issueId ? (draft.address ?? null) : null;

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
    if (!Number.isFinite(issueId)) {
      setLoadError(GENERIC_ERROR_MESSAGE);
      setIsResolving(false);
      return;
    }
    let cancelled = false;

    async function resolve() {
      const [issueResult, mineResult] = await Promise.allSettled([getIssue(issueId), getMySosRequests()]);
      if (cancelled) {
        return;
      }
      if (issueResult.status === 'fulfilled') {
        setIssue(issueResult.value);
      }
      if (mineResult.status === 'fulfilled') {
        const active = mineResult.value.requests.find(
          (candidate) => candidate.issueId === issueId && !isSosTerminalStatus(candidate.status),
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
  const activate = useCallback(
    async (effectiveAddress: AddressValue) => {
      setActivationError(null);
      setIsActivating(true);
      try {
        const created = await createSosRequest({
          issueId,
          serviceCity: effectiveAddress.city.trim(),
          serviceStreet: effectiveAddress.street.trim(),
          serviceHouseNumber: effectiveAddress.houseNumber.trim(),
          serviceApartment: effectiveAddress.apartment.trim() || undefined,
          serviceFloor: effectiveAddress.floor.trim() || undefined,
          serviceEntrance: effectiveAddress.entrance.trim() || undefined,
          serviceAddressNotes: effectiveAddress.addressNotes.trim() || undefined,
        });
        setSosRequestId(created.id);
      } catch (err) {
        if (err instanceof ApiError && err.code === 'SOS_REQUEST_ALREADY_EXISTS') {
          try {
            const mine = await getMySosRequests();
            const active = mine.requests.find(
              (candidate) => candidate.issueId === issueId && !isSosTerminalStatus(candidate.status),
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
        setActivationError(toUserMessage(err));
      } finally {
        setIsActivating(false);
      }
    },
    [issueId],
  );

  /**
   * The address step's own action. It no longer hands back to a confirmation card — filling in
   * where to send somebody is the last question this flow has, so answering it starts the search.
   */
  function handleAddressContinue() {
    const errors = validateAddress(address);
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

  const hasUsableAddress = Object.keys(validateAddress(address)).length === 0;

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
    // Nothing to send yet (no draft, no saved default) or the customer is mid-edit: the address
    // step is showing and its own continue action will start the search.
    if (!hasUsableAddress || isEditingAddress) {
      return;
    }
    // A STANDARD issue reaching this route is a mistake to explain, not a search to start.
    if (issue && issue.urgencyType !== 'SOS') {
      return;
    }
    activationAttemptedRef.current = true;
    void activate(address);
  }, [isResolving, sosRequestId, hasUsableAddress, isEditingAddress, issue, activate, address]);

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
          <Button onClick={() => navigate(`/issues/${issueId}/booking`)} fullWidth>
            להזמנה רגילה
          </Button>
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
            <Button onClick={() => void activate(address)} loading={isActivating} fullWidth>
              ניסיון נוסף
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
            <dd className={styles.summaryValue}>{issue ? getCategoryNameHe(issue.categoryId) : '—'}</dd>
          </div>
          <div className={styles.summaryRow}>
            <dt className={styles.summaryLabel}>כתובת</dt>
            <dd className={styles.summaryValue}>{addressSummary}</dd>
          </div>
        </dl>

        <Skeleton variant="rect" className={styles.loading} />
      </Card>
    </div>
  );
}
