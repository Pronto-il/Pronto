import type { ReactNode } from 'react';
import { AlertTriangle, Inbox } from 'lucide-react';
import styles from './EmptyState.module.css';

export type EmptyStateTone = 'neutral' | 'error';

export interface EmptyStateProps {
  /** Default `'neutral'`. `'error'` adds `role="alert"` and switches the default icon/icon
   *  color, per DESIGN_SYSTEM.md §61. */
  tone?: EmptyStateTone;
  /** Custom icon, rendered instead of the tone's default icon. Supply at most one of `icon`/
   *  `mascotState`. */
  icon?: ReactNode;
  /**
   * Forward-compatible hook for the `Mascot` component (`shared/components/Mascot.tsx`),
   * which is being built by a separate, parallel MS1 task and did not exist on disk at the
   * time this component was written. Typed loosely as `string` (rather than importing
   * `MascotState`) so this file has no build-time dependency on a component that doesn't
   * exist yet. **Currently accepted but not rendered** — a follow-up task should tighten this
   * to `MascotState` and render `<Mascot state={mascotState} ... />` in place of the default
   * icon once `Mascot.tsx` lands.
   */
  mascotState?: string;
  title: string;
  description?: string;
  /** Optional CTA, e.g. a `<Button>`. */
  action?: ReactNode;
  className?: string;
}

const DEFAULT_ICON: Record<EmptyStateTone, ReactNode> = {
  neutral: <Inbox size={40} strokeWidth={1.5} aria-hidden="true" />,
  error: <AlertTriangle size={40} strokeWidth={1.5} aria-hidden="true" />,
};

/**
 * Shared empty/error-state surface — covers both DESIGN_SYSTEM.md §60 (empty) and §61
 * (error) with one component, switched by `tone`. Deliberately no card/border wrapper (plain
 * surface), per the project's anti-generic-SaaS-container rule. `mascotState` is accepted for
 * forward compatibility but not yet wired to a render (see its doc comment) — `icon`/the
 * tone's default icon is what actually renders in MS1.
 */
export function EmptyState({ tone = 'neutral', icon, title, description, action, className }: EmptyStateProps) {
  const resolvedIcon = icon ?? DEFAULT_ICON[tone];

  return (
    <div
      className={[styles.wrapper, styles[tone], className ?? ''].filter(Boolean).join(' ')}
      role={tone === 'error' ? 'alert' : undefined}
    >
      <div className={styles.icon}>{resolvedIcon}</div>
      <p className={styles.title}>{title}</p>
      {description && <p className={styles.description}>{description}</p>}
      {action && <div className={styles.action}>{action}</div>}
    </div>
  );
}
