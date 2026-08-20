import type { CSSProperties, HTMLAttributes } from 'react';
import styles from './Skeleton.module.css';

export type SkeletonVariant = 'text' | 'rect' | 'circle';

export interface SkeletonProps extends HTMLAttributes<HTMLDivElement> {
  /** Default `'rect'`. */
  variant?: SkeletonVariant;
  /** Number of lines to render for the `text` variant (ignored otherwise). Default `1`. */
  lines?: number;
  /** Overrides the variant's default border-radius (`rect` → `--radius-lg`, `circle` → `50%`,
   *  `text` line → `--radius-sm`). */
  radius?: string;
}

/**
 * Shared loading placeholder, replacing the duplicated `.skeleton` blocks in
 * `ProfessionalList.module.css` and `StartTimePicker.module.css` (dedupe happens in a
 * follow-up task, not here). Uses the shared `pronto-skeleton` shimmer keyframe from
 * `styles/motion.css`. Sizing (width/height) is intentionally not a prop — callers size the
 * placeholder to match the real content via `className`/`style`, the same way the two
 * existing ad hoc blocks do today (e.g. a fixed card height); this component only owns the
 * shimmer look and the per-variant shape/radius.
 */
export function Skeleton({ variant = 'rect', lines = 1, radius, className, style, ...rest }: SkeletonProps) {
  const mergedStyle: CSSProperties | undefined = radius ? { ...style, borderRadius: radius } : style;
  const wrapperClassName = [styles.pulse, styles[variant], className ?? ''].filter(Boolean).join(' ');

  if (variant === 'text') {
    const lineCount = Math.max(1, lines);
    return (
      <div className={[styles.textGroup, className ?? ''].filter(Boolean).join(' ')} aria-hidden="true" {...rest}>
        {Array.from({ length: lineCount }).map((_, index) => (
          <div
            key={index}
            className={[styles.pulse, styles.text, index === lineCount - 1 && lineCount > 1 ? styles.textShort : '']
              .filter(Boolean)
              .join(' ')}
            style={mergedStyle}
          />
        ))}
      </div>
    );
  }

  return <div className={wrapperClassName} style={mergedStyle} aria-hidden="true" {...rest} />;
}
