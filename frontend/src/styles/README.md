# styles

## Purpose

Global, non-component-scoped CSS. Sits alongside `index.css` (design tokens + base
element resets, kept in the project root of `src/`) as the other half of the app's
global stylesheet surface. Everything here is imported once from `main.tsx`.

## Structure

- `motion.css` — the CSS tier of Pronto's motion system: shared `@keyframes`
  (`pronto-spin`, `pronto-skeleton`), the `.motion-list-item` entrance utility, and the
  single global `prefers-reduced-motion` compliance block. See "CSS vs framer-motion"
  below for what belongs here versus in `shared/motion/`.

## CSS vs framer-motion — which tier to use

Pronto's motion system is intentionally split into two tiers (binding decision from the
MS1 plan). This file and `shared/motion/README.md` both document the split so later
milestones don't reintroduce the ambiguity — read whichever one you land on first.

**Use CSS (this folder, or a component's own `.module.css`) for:**
- Hover/focus/color transitions
- Button press feedback (`--motion-press-scale`)
- Simple opacity toggles
- Loading spinners, skeleton shimmer
- Any effect naturally expressed as `transition:`/`animation:` on a single element
  without needing to coordinate mount/unmount

**Use `framer-motion` (`shared/motion/variants.ts`) for:**
- Page/step transitions
- Modal/toast mount + exit (needs `AnimatePresence` to animate out before unmount)
- Mascot movement (bounce, slide-in, success pop)
- List entrance stagger beyond the simple CSS case (`listStagger`/`staggerChildren`)
- Anything where a spring, not a fixed-duration easing curve, is the right feel

## Keyframe naming and CSS Modules

`pronto-spin` and `pronto-skeleton` are defined once here (a plain global stylesheet,
not a `.module.css`) so any component's CSS Module can reference them by bare name
(`animation: pronto-spin 700ms linear infinite;`) without going through the CSS Modules
local-scoping machinery — that machinery only rewrites/hashes an `animation-name` when a
same-named `@keyframes` is *also* declared locally in that same module file. Do not
redeclare a local `@keyframes` with either of these names in a `.module.css` file; it
will shadow the global one silently in that file only, and reintroduce the exact
duplication this file exists to remove.

## Reduced motion

The `prefers-reduced-motion: reduce` block here uses `animation-duration: 1ms` and
`transition-duration: 1ms`, not `0ms`/`none`. `none` (or removing the animation
entirely) silently suppresses the `animationend` event, which some consumers rely on to
know a transition finished (e.g. removing a mid-animation class, resolving a promise).
1ms keeps that event firing while making the motion imperceptible. This is a single
global rule — do not add per-component reduced-motion overrides for CSS-tier motion;
`framer-motion`-tier motion instead uses that library's own `useReducedMotion()` hook
per-consumer (see `shared/motion/README.md`).
