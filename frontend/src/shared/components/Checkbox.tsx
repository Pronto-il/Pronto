import { forwardRef, useId } from 'react';
import type { InputHTMLAttributes } from 'react';
import styles from './Checkbox.module.css';

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string;
}

/**
 * Labeled checkbox (native `<input type="checkbox">` + `<label>`), per DESIGN_SYSTEM.md §85
 * (listed as a planned-but-unbuilt primitive name before this). Styled per this project's
 * existing token system, matching `Input`/`Textarea`'s label/spacing conventions. First
 * consumer: `features/dashboard/ProfileEditorPage.tsx`'s sub-services checklist (MS11 —
 * Services & Sub-services, `docs/architecture/product-ms11-sub-services-design.md` §5.1).
 */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, id, className, ...rest },
  ref,
) {
  const generatedId = useId();
  const checkboxId = id ?? generatedId;

  return (
    <div className={[styles.field, className ?? ''].filter(Boolean).join(' ')}>
      <input ref={ref} id={checkboxId} type="checkbox" className={styles.input} {...rest} />
      <label htmlFor={checkboxId} className={styles.label}>
        {label}
      </label>
    </div>
  );
});
