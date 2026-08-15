import type { HTMLAttributes } from 'react';
import styles from './Card.module.css';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {}

/** Base card, per DESIGN_SYSTEM.md §25: white surface, subtle border, no heavy shadow. */
export function Card({ className, children, ...rest }: CardProps) {
  return (
    <div className={[styles.card, className ?? ''].filter(Boolean).join(' ')} {...rest}>
      {children}
    </div>
  );
}
