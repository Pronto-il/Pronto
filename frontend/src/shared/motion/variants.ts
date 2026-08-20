import type { Transition, Variants } from 'framer-motion';

/**
 * Shared `framer-motion` variants for Pronto's "meaningful product motion" tier — page
 * transitions, modal/toast mount+exit, mascot movement, list entrance stagger, success
 * celebration. Simple micro-interactions (hover, focus, press feedback) stay CSS-only;
 * see `shared/motion/README.md` / `styles/README.md` for the full CSS-vs-framer-motion
 * split.
 *
 * All transitions here are springs, not fixed durations, per the "slightly springy"
 * feel requested for Pronto's motion: short, well-damped springs that settle quickly
 * with no visible overshoot/bounce (damping tuned high relative to stiffness).
 *
 * These are variant *definitions* only — no custom reduced-motion or presence hooks
 * live here. Consumers use `framer-motion`'s own `useReducedMotion()` and
 * `AnimatePresence` directly.
 */

/** Snappy, no-overshoot spring for small/quick UI motion (steps, mascot slide-in). */
const springSnappy: Transition = {
  type: 'spring',
  stiffness: 420,
  damping: 32,
  mass: 0.7,
};

/** Softer spring for larger surfaces (page transitions, modals, toasts). */
const springSoft: Transition = {
  type: 'spring',
  stiffness: 320,
  damping: 30,
  mass: 0.9,
};

/** Slightly livelier spring for one-shot celebratory motion (success pop). */
const springPop: Transition = {
  type: 'spring',
  stiffness: 380,
  damping: 22,
  mass: 0.8,
};

/**
 * Direction constant for RTL-aware horizontal motion, derived from the document's
 * writing direction: `-1` in RTL (Pronto's only shipped direction — `dir="rtl"` in
 * `index.html`), `1` in LTR. Guarded for non-DOM environments (e.g. test runners).
 */
const RTL_DIRECTION = typeof document !== 'undefined' && document.dir === 'rtl' ? -1 : 1;

/** Page-level enter/exit (route transitions). */
export const pageTransition: Variants = {
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0, transition: springSoft },
  exit: { opacity: 0, y: -8, transition: springSoft },
};

/**
 * Multi-step flow transition (e.g. issue-creation wizard steps). RTL-aware slide via
 * `x: dir * 12`. Pass a numeric `custom` prop when animating (`1` = advancing forward,
 * `-1` = going back) — `framer-motion` forwards `custom` into these functions.
 */
export const stepTransition: Variants = {
  initial: (direction: number = 1) => ({
    opacity: 0,
    x: RTL_DIRECTION * direction * 12,
  }),
  animate: { opacity: 1, x: 0, transition: springSnappy },
  exit: (direction: number = 1) => ({
    opacity: 0,
    x: RTL_DIRECTION * direction * -12,
    transition: springSnappy,
  }),
};

/**
 * Modal mount/exit — scale+fade on desktop, slide-up on mobile. Pass a boolean
 * `custom` prop (`isMobile`) when animating so the same variant object serves both
 * presentations.
 */
export const modalTransition: Variants = {
  initial: (isMobile: boolean = false) =>
    isMobile ? { opacity: 0, y: 24 } : { opacity: 0, scale: 0.96 },
  animate: { opacity: 1, y: 0, scale: 1, transition: springSoft },
  exit: (isMobile: boolean = false) =>
    isMobile
      ? { opacity: 0, y: 24, transition: springSoft }
      : { opacity: 0, scale: 0.96, transition: springSoft },
};

/** Toast mount/exit. */
export const toastTransition: Variants = {
  initial: { opacity: 0, y: 16, scale: 0.98 },
  animate: { opacity: 1, y: 0, scale: 1, transition: springSoft },
  exit: { opacity: 0, scale: 0.96, transition: springSnappy },
};

/** Small idle/attention bounce for the mascot (vertical, subtle, no overshoot). */
export const mascotBounce: Variants = {
  animate: {
    y: [0, -4, 0],
    transition: {
      duration: 1.1,
      repeat: Infinity,
      ease: 'easeInOut',
    },
  },
};

/** One-shot mascot entrance, RTL-aware slide-in (e.g. "found a professional"). */
export const mascotSlideIn: Variants = {
  initial: { opacity: 0, x: RTL_DIRECTION * -16, scale: 0.94 },
  animate: { opacity: 1, x: 0, scale: 1, transition: springSnappy },
};

/** One-shot success celebration pop. */
export const successPop: Variants = {
  initial: { opacity: 0, scale: 0.7 },
  animate: { opacity: 1, scale: 1, transition: springPop },
};

/**
 * Container variant for list entrance stagger. Apply to the list container
 * (`initial="initial" animate="animate"`); children use their own item variant.
 * Conceptually capped at ~8 items — beyond that a staggered entrance reads as slow
 * rather than lively, so long lists should either not stagger or only stagger the
 * first ~8 items. Not enforced here; it's a usage guideline for consumers.
 */
export const listStagger: Variants = {
  initial: {},
  animate: {
    transition: {
      staggerChildren: 0.05,
      delayChildren: 0.02,
    },
  },
};
