import type { HTMLAttributes, ReactNode } from 'react';
import styles from './Badge.module.css';

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'error' | 'info';
export type BadgeSize = 'sm' | 'md';

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  /** Default `'neutral'`. `'primary'` is DESIGN_SYSTEM.md §33's "מומלץ עבורך" recommended-badge
   *  treatment (`background: #E8F5F3; color: #0F766E`, i.e. `--color-primary-light`/`--color-primary`). */
  tone?: BadgeTone;
  /** Default `'md'` — `md` is an exact visual match for `StatusBadge`'s existing badge sizing
   *  (28px height, `--space-3` inline padding, `--font-size-small` semibold), so `StatusBadge`
   *  can render this component internally with zero visual change. `sm` is for tighter inline
   *  contexts (e.g. next to compact chip text). */
  size?: BadgeSize;
  icon?: ReactNode;
  children: ReactNode;
}

/**
 * Generic status/label pill. Backs `StatusBadge` (tone mapping happens there) and covers
 * DESIGN_SYSTEM.md §33's recommended badge on its own.
 */
export function Badge({ tone = 'neutral', size = 'md', icon, className, children, ...rest }: BadgeProps) {
  const classNames = [styles.badge, styles[tone], styles[size], className ?? ''].filter(Boolean).join(' ');

  return (
    <span className={classNames} {...rest}>
      {icon && (
        <span className={styles.icon} aria-hidden="true">
          {icon}
        </span>
      )}
      {children}
    </span>
  );
}
