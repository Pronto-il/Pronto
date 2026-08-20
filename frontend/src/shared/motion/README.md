# shared/motion

## Purpose

The `framer-motion` tier of Pronto's motion system: shared, named `Variants` objects
for meaningful product motion (page/step transitions, modal/toast mount+exit, mascot
movement, list entrance stagger, success celebration). Pairs with `styles/motion.css`,
which owns the CSS-only tier (hover, focus, press feedback, spinners, skeleton
shimmer). Both READMEs document the same split so later milestones don't reintroduce
the ambiguity — whichever one you land on first is enough to read.

## CSS vs framer-motion — which tier to use

**Use CSS (`styles/motion.css` or a component's own `.module.css`) for:**
- Hover/focus/color transitions
- Button press feedback (`--motion-press-scale`)
- Simple opacity toggles
- Loading spinners, skeleton shimmer
- Any effect naturally expressed as `transition:`/`animation:` on a single element that
  doesn't need to coordinate mount/unmount

**Use `framer-motion` (this folder) for:**
- Page/step transitions
- Modal/toast mount + exit — needs `AnimatePresence` to animate *out* before unmount,
  which plain CSS can't do without extra JS bookkeeping
- Mascot movement (bounce, slide-in, success pop)
- List entrance stagger beyond the simple CSS case (`listStagger`)
- Anything where a spring, not a fixed-duration easing curve, is the right feel

## Structure

- `variants.ts` — the shared, named `Variants` objects: `pageTransition`,
  `stepTransition`, `modalTransition`, `toastTransition`, `mascotBounce`,
  `mascotSlideIn`, `successPop`, `listStagger`. All spring-based ("slightly springy":
  short, well-damped springs, no visible overshoot), not duration-based. See each
  export's own doc comment for its intended usage and any `custom` prop it expects
  (`stepTransition` and `modalTransition` are parameterized via `custom` — see
  `variants.ts`).

## Usage pattern

```tsx
import { AnimatePresence, motion } from 'framer-motion';
import { modalTransition } from '@/shared/motion/variants';

<AnimatePresence>
  {isOpen && (
    <motion.div
      variants={modalTransition}
      custom={isMobile}
      initial="initial"
      animate="animate"
      exit="exit"
    >
      ...
    </motion.div>
  )}
</AnimatePresence>;
```

## Reduced motion and presence

Do **not** write a custom reduced-motion hook or a custom presence/mount-exit hook here.
`framer-motion` already ships both:

- `useReducedMotion()` — call it per-consumer where a variant is used, and either skip
  the animated props or swap to a near-instant transition when it returns `true`.
- `AnimatePresence` — already handles exit-before-unmount; don't reimplement it.

This mirrors the CSS tier's own reduced-motion handling (a single global
`prefers-reduced-motion` block in `styles/motion.css`), just via the library's own
primitives instead of a hand-rolled one.

## RTL

`stepTransition` and `mascotSlideIn` derive a direction constant from
`document.dir` (Pronto ships `dir="rtl"` only, per `index.html`) so horizontal slide
motion goes the correct physical direction. If you add a new horizontally-sliding
variant, follow the same pattern rather than hardcoding a physical `x` sign.
