import { ArrowRight } from 'lucide-react';
import styles from './PageHeader.module.css';

export interface PageHeaderProps {
  title: string;
  description?: string;
  onBack?: () => void;
  backLabel?: string;
  /**
   * Renders a thin animated progress track for multi-step flows (e.g. issue creation,
   * booking) below the title/description, in addition to whatever step text the caller
   * already passes via `description` (e.g. "שלב 1 מתוך 3") — this only adds the visual
   * bar, per DESIGN_SYSTEM §38. `current` is 1-indexed. Omitting `steps` keeps today's
   * exact text-only behavior — no bar is rendered.
   */
  steps?: { current: number; total: number };
}

/**
 * Simple page title (+ optional back action), reusable across screens. The back arrow
 * points right (`ArrowRight`) since in RTL "back" reads visually rightward — DESIGN_SYSTEM
 * §71's directional-icon mirroring rule.
 */
export function PageHeader({ title, description, onBack, backLabel = 'חזרה', steps }: PageHeaderProps) {
  return (
    <div className={styles.header}>
      {onBack && (
        <button type="button" className={styles.backButton} onClick={onBack}>
          <ArrowRight size={18} aria-hidden="true" />
          <span>{backLabel}</span>
        </button>
      )}
      <h1 className={styles.title}>{title}</h1>
      {description && <p className={styles.description}>{description}</p>}
      {steps && (
        <div
          className={styles.progressTrack}
          role="progressbar"
          aria-valuenow={steps.current}
          aria-valuemin={1}
          aria-valuemax={steps.total}
        >
          <div
            className={styles.progressFill}
            style={{ width: `${(steps.current / steps.total) * 100}%` }}
          />
        </div>
      )}
    </div>
  );
}
