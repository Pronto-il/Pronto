import { useEffect, useState } from 'react';
import { Timer } from 'lucide-react';
import styles from './AccountLockoutBanner.module.css';

export interface LoginRateLimitBannerProps {
  /** The `429 RATE_LIMITED` error's `details.retryAfterSeconds` (backend `RateLimitDetails`). */
  retryAfterSeconds: number;
}

/**
 * A full 5-minute window rendered as raw seconds ("בעוד 258 שניות") is technically correct but
 * reads badly, so anything over a minute switches to `m:ss דקות` — still exact to the second,
 * still ticking, just legible at a glance.
 */
function formatRemaining(seconds: number): string {
  if (seconds > 60) {
    const minutes = Math.floor(seconds / 60);
    const remainder = seconds % 60;
    return `${minutes}:${String(remainder).padStart(2, '0')} דקות`;
  }
  return seconds === 1 ? 'שנייה אחת' : `${seconds} שניות`;
}

/**
 * Renders the `429 RATE_LIMITED` error — the per-IP login limiter in
 * `backend/.../auth/config/AuthWebConfig.java` — as human Hebrew copy with a live countdown.
 *
 * Unlike `AccountLockoutBanner` (a 15-minute account lock, shown as a static "~N minutes"), a
 * rate-limit window is short enough that a second-by-second countdown is the useful thing to
 * show: the user is expected to wait it out on this screen and retry.
 *
 * Login is never blocked by this component — the submit button stays enabled throughout, so
 * "let them retry once it reaches zero" needs no re-enabling, only accurate copy.
 */
export function LoginRateLimitBanner({ retryAfterSeconds }: LoginRateLimitBannerProps) {
  const [secondsLeft, setSecondsLeft] = useState(() => Math.max(0, Math.ceil(retryAfterSeconds)));

  // A fresh 429 (a retry that got limited again) restarts the countdown from the new value.
  useEffect(() => {
    setSecondsLeft(Math.max(0, Math.ceil(retryAfterSeconds)));
  }, [retryAfterSeconds]);

  // One timeout per tick keyed on the current value — self-terminating at zero, and no
  // interval left running behind a stale closure.
  useEffect(() => {
    if (secondsLeft <= 0) {
      return;
    }
    const tick = setTimeout(() => setSecondsLeft((current) => current - 1), 1000);
    return () => clearTimeout(tick);
  }, [secondsLeft]);

  return (
    <div className={styles.banner} role="alert">
      <Timer size={20} aria-hidden="true" />
      <p>
        בוצעו יותר מדי ניסיונות התחברות.{' '}
        {secondsLeft <= 0
          ? 'ניתן לנסות שוב עכשיו.'
          : `ניתן לנסות שוב בעוד ${formatRemaining(secondsLeft)}.`}
      </p>
    </div>
  );
}
