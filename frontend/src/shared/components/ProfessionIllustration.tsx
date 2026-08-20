import { Mascot } from './Mascot';
import { getCategoryNameHe } from '../api/categories';
import plumbing from '../../assets/rollete-animation-images/plumbing.png';
import electrical from '../../assets/rollete-animation-images/electrical.png';
import acHvac from '../../assets/rollete-animation-images/ac-hvac.png';
import applianceRepair from '../../assets/rollete-animation-images/appliance-repair.png';
import locksmith from '../../assets/rollete-animation-images/locksmith.png';
import painting from '../../assets/rollete-animation-images/painting.png';
import generalHandyman from '../../assets/rollete-animation-images/general-handyman.png';
import styles from './ProfessionIllustration.module.css';

/**
 * The single source of truth for "which drawing represents this profession", keyed by
 * `CATEGORIES[].id` (`shared/api/categories.ts`). Kept here, in one map, rather than as
 * filename checks spread across screens — every consumer goes through
 * `<ProfessionIllustration categoryId={...} />`.
 *
 * The files come from `assets/rollete-animation-images/`, renamed from their original
 * generator filenames (`ChatGPT Image Aug 20, 2026, 10_03_49 PM (1).png` …) to the profession
 * each one actually depicts, so this mapping is verifiable by reading it.
 *
 * **Category 6 (`carpentry`/נגרות) has no illustration in that folder** — seven drawings were
 * supplied for eight categories. It is deliberately absent from this map rather than pointed
 * at a loosely-related drawing, and renders the shared `Mascot` fallback below. Dropping a
 * carpentry illustration into the folder and adding one line here is all that is needed to
 * complete it.
 */
const CATEGORY_ILLUSTRATIONS: Record<number, string> = {
  1: plumbing, // אינסטלציה — kneeling by a sink trap with a wrench
  2: electrical, // חשמל — wiring a wall outlet with a screwdriver
  3: acHvac, // מיזוג אוויר — servicing a split unit from a stepladder with manifold gauges
  4: applianceRepair, // תיקון מוצרי חשמל — wall-mounted boiler/water heater
  5: locksmith, // מנעולן — fitting a door lock, keyring on the belt
  // 6: carpentry — no asset supplied, see the doc comment above.
  7: painting, // צביעה — rolling a wall, paint can and brushes
  8: generalHandyman, // הנדימן כללי — carrying a full kit, on the move
};

/** Emitted once per unmapped category id, so a missing illustration is reported rather than
 *  silently swallowed by the fallback. Module-scoped so a re-render can't spam the console. */
const reportedMissing = new Set<number>();

export function hasProfessionIllustration(categoryId: number): boolean {
  return categoryId in CATEGORY_ILLUSTRATIONS;
}

export interface ProfessionIllustrationProps {
  categoryId: number;
  /** Rendered as the image's `alt`. Omit for a decorative instance (`alt=""`, `aria-hidden`) —
   *  e.g. the non-selected faces of the matching wheel, which are scenery, not content. */
  label?: string;
  className?: string;
}

/**
 * A profession's illustration, or the project's existing `Mascot` fallback when that
 * profession has no drawing yet. Never renders a broken image and never substitutes another
 * profession's drawing — showing a plumber for a carpenter would be a quieter bug than a
 * missing image, not a smaller one.
 */
export function ProfessionIllustration({ categoryId, label, className }: ProfessionIllustrationProps) {
  const src = CATEGORY_ILLUSTRATIONS[categoryId];

  if (!src) {
    if (import.meta.env.DEV && !reportedMissing.has(categoryId)) {
      reportedMissing.add(categoryId);
      console.warn(
        `[ProfessionIllustration] no illustration mapped for category ${categoryId} ` +
          `("${getCategoryNameHe(categoryId)}") — falling back to the Mascot. Add the asset to ` +
          'assets/rollete-animation-images/ and map it in ProfessionIllustration.tsx.',
      );
    }
    return <Mascot state="idle" size="lg" label={label} className={className} />;
  }

  return (
    <img
      src={src}
      alt={label ?? ''}
      aria-hidden={label ? undefined : true}
      className={[styles.illustration, className ?? ''].filter(Boolean).join(' ')}
      draggable={false}
    />
  );
}
