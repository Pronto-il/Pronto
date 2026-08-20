import type { HTMLAttributes } from 'react';
import styles from './Card.module.css';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /**
   * Opt-in hover/press feedback (border-strong + `--shadow-elevated` on hover, subtle
   * scale on press) for cards that act as clickable targets, per DESIGN_SYSTEM §65.
   * Hover is desktop-only (`@media (hover: hover) and (pointer: fine)`) so it never
   * sticks on touch. Defaults to `false` — plain/informational cards are unaffected.
   */
  interactive?: boolean;
}

/** Base card, per DESIGN_SYSTEM.md §25: white surface, subtle border, no heavy shadow. */
export function Card({ interactive = false, className, children, ...rest }: CardProps) {
  const classNames = [styles.card, interactive ? styles.interactive : '', className ?? '']
    .filter(Boolean)
    .join(' ');

  return (
    <div className={classNames} {...rest}>
      {children}
    </div>
  );
}
