import { Lock } from 'lucide-react';
import styles from './AccountLockoutBanner.module.css';

export interface AccountLockoutBannerProps {
  /** The `423 ACCOUNT_LOCKED` error's `details.retryAfterSeconds`. */
  retryAfterSeconds: number;
}

/**
 * Renders the `423 ACCOUNT_LOCKED` error as human Hebrew copy — a static "try again in
 * ~N minutes" message, not a raw error code or a live-ticking countdown.
 */
export function AccountLockoutBanner({ retryAfterSeconds }: AccountLockoutBannerProps) {
  const minutes = Math.max(1, Math.round(retryAfterSeconds / 60));

  return (
    <div className={styles.banner} role="alert">
      <Lock size={20} aria-hidden="true" />
      <p>יותר מדי ניסיונות התחברות. אפשר לנסות שוב בעוד כ-{minutes} דקות.</p>
    </div>
  );
}
