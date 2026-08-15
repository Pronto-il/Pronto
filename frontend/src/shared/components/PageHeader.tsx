import { ArrowRight } from 'lucide-react';
import styles from './PageHeader.module.css';

export interface PageHeaderProps {
  title: string;
  description?: string;
  onBack?: () => void;
  backLabel?: string;
}

/**
 * Simple page title (+ optional back action), reusable across screens. The back arrow
 * points right (`ArrowRight`) since in RTL "back" reads visually rightward — DESIGN_SYSTEM
 * §71's directional-icon mirroring rule.
 */
export function PageHeader({ title, description, onBack, backLabel = 'חזרה' }: PageHeaderProps) {
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
    </div>
  );
}
