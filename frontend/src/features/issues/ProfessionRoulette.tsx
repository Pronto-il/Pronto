import { useEffect, useMemo, useRef, useState } from 'react';
import { useReducedMotion } from 'framer-motion';
import { ProfessionIllustration } from '../../shared/components';
import type { Category } from '../../shared/api';
import styles from './ProfessionRoulette.module.css';

export interface ProfessionRouletteProps {
  /** Every profession shown on the wheel, in wheel order. */
  categories: Category[];
  /** The profession the wheel must land on — the issue's own classified category. */
  targetCategoryId: number;
  /** Fired exactly once, when the wheel has settled on the target. */
  onSettled: () => void;
  /** Spin length. DESIGN brief: roughly 2.5-3.5s; defaults to 3s. */
  spinDurationMs?: number;
}

/** Full turns before landing. Enough to read as motion, few enough to avoid a prize-wheel
 *  spin-off. Ignored when the wheel would be pointless (see `shouldSpin` below). */
const SPINS = 3;
/** Reduced-motion / single-profession path: a short move straight to the answer. */
const INSTANT_DURATION_MS = 320;
/** Strong ease-out — fast off the mark, long settle. Not a bounce: nothing here should feel
 *  like a game show. */
const SPIN_EASING = 'cubic-bezier(0.12, 0.78, 0.14, 1)';

/**
 * The matching wheel: every supported profession arranged around a circle, rotating to a stop
 * with the issue's own profession under the pointer.
 *
 * **Deterministic, not random.** `targetCategoryId` comes from the issue's classified
 * category; the final angle is computed from that category's fixed position on the wheel
 * (`-index * segmentAngle`, plus whole turns), so the same issue always lands the same way.
 * There is no random draw anywhere in this component.
 *
 * Each face counter-rotates by exactly the wheel's own angle, so illustrations stay upright
 * through the spin instead of tumbling — the difference between "a matching animation" and
 * "a carnival wheel".
 *
 * Respects `prefers-reduced-motion`: no spin, no turns, just a short move that highlights the
 * right profession. Same for a wheel with fewer than two faces, where spinning would be
 * theatre.
 */
export function ProfessionRoulette({
  categories,
  targetCategoryId,
  onSettled,
  spinDurationMs = 3000,
}: ProfessionRouletteProps) {
  const shouldReduceMotion = useReducedMotion();
  const targetIndex = Math.max(0, categories.findIndex((category) => category.id === targetCategoryId));
  const segmentAngle = categories.length > 0 ? 360 / categories.length : 360;
  const shouldSpin = !shouldReduceMotion && categories.length > 1;
  const durationMs = shouldSpin ? spinDurationMs : INSTANT_DURATION_MS;

  // Landing angle: bring the target's own fixed position to the pointer at the top. The whole
  // turns are cosmetic; the `-targetIndex * segmentAngle` term is what makes the stop exact.
  const finalRotation = useMemo(
    () => (shouldSpin ? 360 * SPINS : 0) - targetIndex * segmentAngle,
    [shouldSpin, targetIndex, segmentAngle],
  );

  const [rotation, setRotation] = useState(0);
  const [hasSettled, setHasSettled] = useState(false);

  // Start on the next frame so the browser paints the 0deg state first — setting the final
  // transform in the same frame as mount would skip the transition entirely.
  useEffect(() => {
    const frame = requestAnimationFrame(() => setRotation(finalRotation));
    return () => cancelAnimationFrame(frame);
  }, [finalRotation]);

  const onSettledRef = useRef(onSettled);
  onSettledRef.current = onSettled;
  useEffect(() => {
    const timer = setTimeout(() => {
      setHasSettled(true);
      onSettledRef.current();
    }, durationMs);
    return () => clearTimeout(timer);
  }, [durationMs]);

  const transition = `transform ${durationMs}ms ${SPIN_EASING}`;

  return (
    <div className={styles.stage}>
      <div className={styles.pointer} aria-hidden="true" />

      {/* The ring is static scenery — only the faces travel. Keeping it outside the rotating
          element matters for more than tidiness: a rotating full-size square reports a bounding
          box up to 1.41x its side, which pushed the page into a horizontal scroll mid-spin on
          mobile. The rotating element below is a zero-size point at the centre. */}
      <div className={styles.track} aria-hidden="true" />

      <div className={styles.wheel} style={{ transform: `rotate(${rotation}deg)`, transition }}>
        {categories.map((category, index) => {
          const angle = index * segmentAngle;
          const isTarget = category.id === targetCategoryId;
          return (
            <div
              key={category.id}
              className={styles.slot}
              style={{ transform: `rotate(${angle}deg) translateY(calc(var(--wheel-radius) * -1))` }}
            >
              <div
                className={`${styles.face} ${isTarget && hasSettled ? styles.faceActive : ''}`}
                // Cancels both the wheel's rotation and the slot's own placement angle, so the
                // drawing stays upright for the whole spin. Same timing as the wheel, so the
                // two stay locked together frame for frame.
                style={{ transform: `rotate(${-(angle + rotation)}deg)`, transition }}
              >
                <ProfessionIllustration categoryId={category.id} className={styles.illustration} />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
