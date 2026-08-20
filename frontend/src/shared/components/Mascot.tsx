import { motion } from 'framer-motion';
import { mascotSlideIn, successPop } from '../motion/variants';
import pointingSrc from '../../assets/mascot/pronto-pointing.png';
import runningScrewdriverSrc from '../../assets/mascot/pronto-running-screwdriver.png';
import runningWrenchSrc from '../../assets/mascot/pronto-running-wrench.png';
import successSrc from '../../assets/mascot/pronto-success.png';
import styles from './Mascot.module.css';

export type MascotState = 'idle' | 'running' | 'thinking' | 'searching' | 'found' | 'success';
export type MascotSize = 'sm' | 'md' | 'lg' | 'xl';

export interface MascotProps {
  /** Which semantic state to render — maps to one of 4 physical poses, see module doc. */
  state: MascotState;
  /** Default `'md'`. Explicit width/height per size, keyed off the shared 402:512 asset
   *  aspect ratio, to avoid layout shift. */
  size?: MascotSize;
  /** Omit for a fully decorative mascot (`aria-hidden`, `alt=""`); provide to make it a
   *  meaningful image (used as `alt` text, no `aria-hidden`). */
  label?: string;
  className?: string;
  /**
   * Default `true` for `'running'`/`'searching'`/`'thinking'` — renders their looping CSS
   * motion (bounce + motion-lines for running/searching, subtle pulse for thinking). Pass
   * `false` to render the pose statically instead (e.g. HomePage's restrained MS1 usage).
   * Has no effect on `'idle'` (always static) or `'found'`/`'success'` (always one-shot).
   */
  loop?: boolean;
}

/**
 * The 4 shipped, transparent mascot poses this component draws from. All 4 share the same
 * 402x512 canvas (per the asset pipeline's baseline-alignment step), so a single
 * width/height-per-size mapping in `Mascot.module.css` works for every pose without jumping.
 */
const POSE_SRC: Record<'pointing' | 'runningScrewdriver' | 'runningWrench' | 'success', string> = {
  pointing: pointingSrc,
  runningScrewdriver: runningScrewdriverSrc,
  runningWrench: runningWrenchSrc,
  success: successSrc,
};

/**
 * 4 physical poses back 6 semantic states. `idle`/`thinking`/`found` share the `pointing`
 * pose — a known, documented limitation (not a bug): those 3 states are differentiated only
 * by motion treatment and the surrounding copy of whatever page renders them, not by distinct
 * artwork. `running` vs `searching` intentionally use different poses (wrench vs screwdriver)
 * so "Pronto is coming to you" reads visually distinct from "Pronto is looking for someone".
 */
const STATE_POSE: Record<MascotState, keyof typeof POSE_SRC> = {
  idle: 'pointing',
  thinking: 'pointing',
  found: 'pointing',
  running: 'runningWrench',
  searching: 'runningScrewdriver',
  success: 'success',
};

/** States whose `loop` prop defaults to `true` (looping CSS motion) when the pose supports it. */
const LOOPABLE_STATES: ReadonlySet<MascotState> = new Set(['running', 'searching', 'thinking']);

/**
 * Pronto's mascot, state-driven per the motion system's binding decision that mascot motion
 * reflects real product state rather than being decorative. See `shared/motion/README.md`
 * for the overall CSS-vs-framer-motion split this component follows: `running`/`searching`/
 * `thinking`'s looping motion is CSS (`Mascot.module.css`); `found`/`success` are one-shot
 * `framer-motion` entrances (`shared/motion/variants.ts`), since they play once on mount
 * rather than looping.
 */
export function Mascot({ state, size = 'md', label, className, loop }: MascotProps) {
  const pose = STATE_POSE[state];
  const src = POSE_SRC[pose];
  const resolvedLoop = loop ?? LOOPABLE_STATES.has(state);
  const showMotionLines = (state === 'running' || state === 'searching') && resolvedLoop;

  const wrapperClassNames = [
    styles.wrapper,
    styles[size],
    state === 'thinking' && resolvedLoop ? styles.thinking : '',
    (state === 'running' || state === 'searching') && resolvedLoop ? styles.bouncing : '',
    className ?? '',
  ]
    .filter(Boolean)
    .join(' ');

  const image = <img src={src} alt={label ?? ''} className={styles.image} />;

  const content =
    state === 'found' || state === 'success' ? (
      <motion.div
        className={styles.animatedInner}
        initial="initial"
        animate="animate"
        variants={state === 'success' ? successPop : mascotSlideIn}
      >
        {image}
      </motion.div>
    ) : (
      image
    );

  return (
    <div className={wrapperClassNames} aria-hidden={label ? undefined : 'true'}>
      {showMotionLines && (
        <span className={styles.motionLines}>
          <span />
          <span />
          <span />
        </span>
      )}
      {content}
    </div>
  );
}
