import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Drag + clamp + persist for the floating active-order toolbox (`app/ActiveIssueToolbox.tsx`).
 *
 * ## Free vertical drag, snap-to-nearest-edge horizontal drag (mobile-nav fix, 2026-08-28)
 *
 * The toolbox is a two-axis draggable object: vertically it behaves exactly as it always has
 * (free drag, clamped between the safe-area top and the bottom navigation, released wherever the
 * customer let go); horizontally it now follows the pointer live during the drag but, on release,
 * always settles against whichever screen edge — left or right — it ended up closer to. It never
 * rests mid-screen.
 *
 * This replaces the prior "pinned to `inset-inline-start` by CSS, horizontal drag removed
 * entirely" behaviour from the earlier mobile-shell redesign. That version over-corrected: its
 * own goal was "never float in the horizontal centre", but the fix it shipped was "never move
 * horizontally at all", which also made it impossible to move the toolbox off content it covers
 * on the side it happened to be pinned to. Snap-to-nearest-edge satisfies the original goal
 * (still never rests off-edge) without reintroducing the free-floating-anywhere behaviour an even
 * earlier revision had (which is what motivated removing horizontal drag in the first place).
 *
 * Both axes are owned here, in physical pixels — not logical/RTL-relative ones. `top`/`left` are
 * geometry, and geometry has to be axis-correct regardless of `dir`; RTL correctness here means
 * the toolbox's *default* resting side is the RTL-natural one (`DEFAULT_SIDE`, the right edge —
 * where §1 of the original redesign pinned it) and its Hebrew label keeps rendering RTL as
 * always, not that its horizontal position is logically constrained.
 *
 * No drag library. `setPointerCapture` plus three handlers covers touch, pen and mouse from one
 * code path.
 */

/**
 * `localStorage` key holding the toolbox's last position: a vertical pixel offset plus which
 * edge it was snapped to. New key, not the old `pronto.toolbox.top` (a bare number, no side) —
 * that shape can no longer answer "which edge", so a stale value under the old key is simply
 * ignored rather than half-read, same precedent that key itself set over the `{x, y}` shape
 * before it.
 */
const STORAGE_KEY = 'pronto.toolbox.pos';

type ToolboxSide = 'left' | 'right';

/** The RTL-natural starting edge — where §1 of the original redesign pinned the toolbox. Only a
 *  default: a customer can still drag it to the left edge and have that choice persist. */
const DEFAULT_SIDE: ToolboxSide = 'right';

interface StoredPosition {
  top: number;
  side: ToolboxSide;
}

/**
 * Movement, in CSS pixels, at which a press stops being a tap and becomes a drag. Measured on the
 * *total* pointer movement (both axes), so a mostly-horizontal swipe suppresses the tap exactly
 * as a mostly-vertical one does — both are now real, live drag gestures.
 *
 * 8px sits above the couple of pixels a thumb rolls during an ordinary tap and below a
 * deliberate drag.
 */
const DRAG_THRESHOLD_PX = 8;

/** Gap kept between the toolbox and every viewport edge, so it never sits flush — on all four
 *  sides: top, the two horizontal snap edges, and (via the nav/safe-area subtraction below) the
 *  bottom. */
const EDGE_MARGIN_PX = 12;

/** Matches `BottomNav.module.css`'s own `@media (max-width: 640px)` — the width at which the
 *  fixed bottom navigation exists at all, and therefore has to be avoided. */
const MOBILE_MAX_WIDTH_PX = 640;

interface AxisBounds {
  min: number;
  max: number;
}

/** Reads a `--custom-property` off `:root` and returns its px value (0 when unset/auto). */
function readRootPx(name: string): number {
  if (typeof window === 'undefined') {
    return 0;
  }
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const parsed = Number.parseFloat(raw);
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * The vertical range the toolbox's top edge may occupy — unchanged from before this fix.
 *
 * The bottom edge subtracts the fixed navigation and the iOS home-indicator inset — both read
 * from the CSS custom properties that also lay the nav out, so the two can't disagree ("the
 * floating toolbox does not collide with the bottom navigation"). Above 640px the nav is
 * `display: none`, so its height is not subtracted.
 */
function computeVerticalBounds(height: number): AxisBounds {
  const viewportHeight = window.innerHeight;
  const safeBottom = readRootPx('--safe-area-bottom');
  const safeTop = readRootPx('--safe-area-top');
  const navHeight = window.innerWidth <= MOBILE_MAX_WIDTH_PX ? readRootPx('--bottom-nav-height') : 0;

  const min = EDGE_MARGIN_PX + safeTop;
  // `Math.max(min, ...)` guards a viewport shorter than the toolbox: without it max lands above
  // min and the clamp would pin the toolbox off-screen instead of at the edge.
  const max = Math.max(min, viewportHeight - height - navHeight - safeBottom - EDGE_MARGIN_PX);

  return { min, max };
}

/** The horizontal range the toolbox's left edge may occupy — `min` is the left-edge snap
 *  target, `max` is the right-edge one. Nothing about the bottom nav applies horizontally. */
function computeHorizontalBounds(width: number): AxisBounds {
  const viewportWidth = window.innerWidth;
  const min = EDGE_MARGIN_PX;
  const max = Math.max(min, viewportWidth - width - EDGE_MARGIN_PX);
  return { min, max };
}

function clamp(value: number, bounds: AxisBounds): number {
  return Math.min(Math.max(value, bounds.min), bounds.max);
}

/** §1's default resting spot: low on the side, just above the bottom navigation — where a
 *  floating side shortcut is least likely to cover the content the customer is reading. */
function defaultTop(bounds: AxisBounds): number {
  return bounds.max;
}

/** The pixel `left` for a given snapped edge, against the current horizontal bounds. */
function leftForSide(side: ToolboxSide, bounds: AxisBounds): number {
  return side === 'left' ? bounds.min : bounds.max;
}

/** Which edge a given `left` is closer to — used only at the moment a drag ends, never to
 *  re-derive a resting position (that always goes through the authoritative `sideRef`, so a
 *  viewport resize can't flip which edge an already-settled toolbox is pinned to). */
function nearestSide(left: number, bounds: AxisBounds): ToolboxSide {
  const midpoint = (bounds.min + bounds.max) / 2;
  return left < midpoint ? 'left' : 'right';
}

function readStoredPosition(): StoredPosition | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (
      parsed !== null &&
      typeof parsed === 'object' &&
      'top' in parsed &&
      'side' in parsed &&
      typeof (parsed as StoredPosition).top === 'number' &&
      Number.isFinite((parsed as StoredPosition).top) &&
      ((parsed as StoredPosition).side === 'left' || (parsed as StoredPosition).side === 'right')
    ) {
      return parsed as StoredPosition;
    }
    return null;
  } catch {
    // Private-mode denial, quota, or a hand-edited value. A toolbox at its default position is
    // a complete recovery, so this is never worth surfacing.
    return null;
  }
}

function writeStoredPosition(position: StoredPosition): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(position));
  } catch {
    // See readStoredPosition — losing the persisted spot is not worth an error path.
  }
}

export interface UseToolboxPositionResult {
  /** Vertical offset in CSS pixels from the viewport top, applied as `top`. `null` until the
   *  element has been measured, which is when a real position can be computed. */
  top: number | null;
  /** Horizontal offset in CSS pixels from the viewport left, applied as `left`. Follows the
   *  pointer live during a drag; at rest it always equals one of the two edge snap targets.
   *  `null` until measured, in lockstep with `top`. */
  left: number | null;
  /** Attach to the toolbox element — used for measurement and pointer capture. */
  elementRef: (node: HTMLElement | null) => void;
  onPointerDown: (event: React.PointerEvent<HTMLElement>) => void;
  onPointerMove: (event: React.PointerEvent<HTMLElement>) => void;
  onPointerUp: (event: React.PointerEvent<HTMLElement>) => void;
  /** True from the moment {@link DRAG_THRESHOLD_PX} is exceeded until the pointer is released.
   *  Drives the "lifted" styling and, more importantly, suppresses the click (§6). */
  isDragging: boolean;
  /** Whether the gesture that just ended was a tap rather than a drag. Call in the click
   *  handler; the component navigates only when this returns true. */
  wasTap: () => boolean;
}

export function useToolboxPosition(): UseToolboxPositionResult {
  const [top, setTop] = useState<number | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const [isDragging, setIsDragging] = useState(false);

  const nodeRef = useRef<HTMLElement | null>(null);
  const heightRef = useRef(0);
  const widthRef = useRef(0);
  /** Offset from the element's top/left edge to where the pointer grabbed it, so the toolbox
   *  doesn't jump to centre itself under the finger on the first move. */
  const grabOffsetYRef = useRef(0);
  const grabOffsetXRef = useRef(0);
  const startPointRef = useRef({ x: 0, y: 0 });
  const exceededThresholdRef = useRef(false);
  const topRef = useRef<number | null>(null);
  const leftRef = useRef<number | null>(null);
  /** The authoritative "which edge is this pinned to", updated only on load and on each
   *  successful snap — never re-derived from geometry, so a resize re-clamps position without
   *  ever flipping which edge an already-settled toolbox is on. */
  const sideRef = useRef<ToolboxSide>(DEFAULT_SIDE);

  const commit = useCallback((nextTop: number, nextLeft: number) => {
    topRef.current = nextTop;
    leftRef.current = nextLeft;
    setTop(nextTop);
    setLeft(nextLeft);
  }, []);

  /** Re-measures and re-clamps both axes. Also the mount path: the first call is what turns
   *  `top`/`left` from null into either the stored position or the computed default. */
  const settle = useCallback(() => {
    const node = nodeRef.current;
    if (!node) {
      return;
    }
    const rect = node.getBoundingClientRect();
    if (rect.height > 0) {
      heightRef.current = rect.height;
    }
    if (rect.width > 0) {
      widthRef.current = rect.width;
    }
    const vBounds = computeVerticalBounds(heightRef.current);
    const hBounds = computeHorizontalBounds(widthRef.current);

    if (topRef.current === null) {
      // First settle: load the stored position (top + side), or fall back to the defaults.
      const stored = readStoredPosition();
      sideRef.current = stored?.side ?? DEFAULT_SIDE;
      const nextTop = stored ? clamp(stored.top, vBounds) : defaultTop(vBounds);
      commit(nextTop, leftForSide(sideRef.current, hBounds));
      return;
    }

    // Viewport changed (resize, rotation, desktop window resize): re-clamp the vertical offset
    // (§7: a position from a taller screen must not leave the toolbox off-screen on a shorter
    // one) and re-derive the horizontal one from the authoritative side, never from raw geometry.
    commit(clamp(topRef.current, vBounds), leftForSide(sideRef.current, hBounds));
  }, [commit]);

  const elementRef = useCallback(
    (node: HTMLElement | null) => {
      nodeRef.current = node;
      if (node) {
        settle();
      }
    },
    [settle],
  );

  // Viewport changes: rotation, browser-chrome collapse, desktop window resize. `orientationchange`
  // is listened for separately because iOS fires it before `innerHeight` has settled, so the rAF
  // defers the re-clamp until after the new metrics are readable.
  useEffect(() => {
    function handleResize() {
      requestAnimationFrame(settle);
    }
    window.addEventListener('resize', handleResize);
    window.addEventListener('orientationchange', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
      window.removeEventListener('orientationchange', handleResize);
    };
  }, [settle]);

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLElement>) => {
    // Secondary/middle mouse buttons must not start a drag — they have their own meanings and
    // capturing them strands the element mid-gesture.
    if (event.button !== 0 && event.pointerType === 'mouse') {
      return;
    }
    const node = nodeRef.current;
    if (!node) {
      return;
    }
    const rect = node.getBoundingClientRect();
    heightRef.current = rect.height;
    widthRef.current = rect.width;
    grabOffsetYRef.current = event.clientY - rect.top;
    grabOffsetXRef.current = event.clientX - rect.left;
    startPointRef.current = { x: event.clientX, y: event.clientY };
    exceededThresholdRef.current = false;
    // Capture means pointermove/up keep arriving even when the finger outruns the element,
    // which is what stops a fast drag from leaving the toolbox stuck mid-screen.
    node.setPointerCapture(event.pointerId);
  }, []);

  const onPointerMove = useCallback(
    (event: React.PointerEvent<HTMLElement>) => {
      const node = nodeRef.current;
      if (!node || !node.hasPointerCapture(event.pointerId)) {
        return;
      }

      if (!exceededThresholdRef.current) {
        const dx = event.clientX - startPointRef.current.x;
        const dy = event.clientY - startPointRef.current.y;
        if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) {
          return; // still a tap in progress — do not move, do not commit
        }
        exceededThresholdRef.current = true;
        setIsDragging(true);
      }

      // Both axes follow the pointer live while dragging. Horizontal only snaps to an edge on
      // release (below); while the gesture is in progress it can sit anywhere between the two
      // horizontal bounds, same as vertical always could.
      const vBounds = computeVerticalBounds(heightRef.current);
      const hBounds = computeHorizontalBounds(widthRef.current);
      commit(
        clamp(event.clientY - grabOffsetYRef.current, vBounds),
        clamp(event.clientX - grabOffsetXRef.current, hBounds),
      );
    },
    [commit],
  );

  const onPointerUp = useCallback(
    (event: React.PointerEvent<HTMLElement>) => {
      const node = nodeRef.current;
      if (node?.hasPointerCapture(event.pointerId)) {
        node.releasePointerCapture(event.pointerId);
      }
      if (exceededThresholdRef.current) {
        setIsDragging(false);
        const hBounds = computeHorizontalBounds(widthRef.current);
        // leftRef is set by every onPointerMove once the threshold is exceeded, so it reflects
        // the pointer's last live position — exactly what "nearest edge" should be measured
        // against.
        const finalSide = nearestSide(leftRef.current ?? hBounds.max, hBounds);
        sideRef.current = finalSide;
        const snappedLeft = leftForSide(finalSide, hBounds);
        const settledTop = topRef.current ?? defaultTop(computeVerticalBounds(heightRef.current));
        // The snap itself: horizontal jumps (CSS-transitions, since `.dragging`'s `transition:
        // none` is removed the same render) to whichever edge it ended up closer to. Vertical is
        // left exactly where the gesture released it — unchanged clamped-free-drag behaviour.
        commit(settledTop, snappedLeft);
        writeStoredPosition({ top: settledTop, side: finalSide });
      }
      // `exceededThresholdRef` is deliberately NOT reset here: the click event fires after
      // pointerup, and `wasTap()` has to still be able to see what this gesture was.
    },
    [commit],
  );

  const wasTap = useCallback(() => !exceededThresholdRef.current, []);

  return { top, left, elementRef, onPointerDown, onPointerMove, onPointerUp, isDragging, wasTap };
}
