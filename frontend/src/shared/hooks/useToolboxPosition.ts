import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Drag/clamp/persist for the floating active-issue toolbox (`app/ActiveIssueToolbox.tsx`).
 *
 * Kept out of the component because three separate concerns tangle here and each one is a
 * source of real bugs: pointer bookkeeping (§5), telling a tap from a drag (§6), and keeping
 * the thing inside a viewport that changes size under it (§4/§7).
 *
 * No drag library. `setPointerCapture` plus three handlers is the whole implementation, and it
 * covers touch, pen and mouse from one code path — which is what §5 asks for.
 */

/** `localStorage` key holding the last position the customer dragged the toolbox to (§7). */
const STORAGE_KEY = 'pronto.toolbox.position';

/**
 * Movement, in CSS pixels, at which a press stops being a tap and becomes a drag (§6).
 *
 * 8px sits inside the 5-10px the brief asks for and above the couple of pixels a thumb rolls
 * during an ordinary tap on glass — below ~5px every tap on a phone registers as a drag and the
 * toolbox stops navigating at all.
 */
const DRAG_THRESHOLD_PX = 8;

/** Gap kept between the toolbox and every viewport edge, so it never sits flush. */
const EDGE_MARGIN_PX = 12;

/** Matches `BottomNav.module.css`'s own `@media (max-width: 640px)` — the width at which the
 *  fixed bottom navigation exists at all, and therefore has to be avoided. */
const MOBILE_MAX_WIDTH_PX = 640;

export interface ToolboxPosition {
  /** Distance from the viewport's LEFT edge, in CSS pixels. Deliberately physical, not logical:
   *  a dragged position is a screen coordinate, and mirroring it under RTL would move the
   *  toolbox away from where the finger let go. */
  x: number;
  y: number;
}

interface Bounds {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
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
 * The rectangle the toolbox's top-left corner may occupy.
 *
 * The bottom edge subtracts the fixed navigation and the iOS home-indicator inset — both read
 * from the CSS custom properties that also lay the nav out, so the two can't disagree (§10's
 * "toolbox cannot be dragged over/under the nav bar"). Above 640px the nav is `display: none`,
 * so its height is not subtracted.
 */
function computeBounds(size: { width: number; height: number }): Bounds {
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const safeBottom = readRootPx('--safe-area-bottom');
  const safeTop = readRootPx('--safe-area-top');
  const navHeight = window.innerWidth <= MOBILE_MAX_WIDTH_PX ? readRootPx('--bottom-nav-height') : 0;

  const minX = EDGE_MARGIN_PX;
  const minY = EDGE_MARGIN_PX + safeTop;
  // `Math.max(minX, ...)` matters on a viewport narrower than the toolbox: without it maxX lands
  // below minX and the later clamp would pin the toolbox off-screen instead of at the edge.
  const maxX = Math.max(minX, viewportWidth - size.width - EDGE_MARGIN_PX);
  const maxY = Math.max(minY, viewportHeight - size.height - navHeight - safeBottom - EDGE_MARGIN_PX);

  return { minX, maxX, minY, maxY };
}

function clamp(position: ToolboxPosition, bounds: Bounds): ToolboxPosition {
  return {
    x: Math.min(Math.max(position.x, bounds.minX), bounds.maxX),
    y: Math.min(Math.max(position.y, bounds.minY), bounds.maxY),
  };
}

/**
 * §4's default: lower-left, clear of the bottom navigation. Computed rather than stored, so a
 * customer who has never dragged the toolbox gets a sensible spot on any screen size.
 */
function defaultPosition(bounds: Bounds): ToolboxPosition {
  // 24px is the middle of §4's "20-30px from the left edge"; clamped, so a very narrow
  // viewport still yields a legal coordinate.
  return clamp({ x: 24, y: bounds.maxY }, bounds);
}

function readStoredPosition(): ToolboxPosition | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as ToolboxPosition).x === 'number' &&
      typeof (parsed as ToolboxPosition).y === 'number' &&
      Number.isFinite((parsed as ToolboxPosition).x) &&
      Number.isFinite((parsed as ToolboxPosition).y)
    ) {
      return { x: (parsed as ToolboxPosition).x, y: (parsed as ToolboxPosition).y };
    }
    return null;
  } catch {
    // Private-mode denial, quota, or a hand-edited value. A toolbox at its default position is
    // a complete recovery, so this is never worth surfacing.
    return null;
  }
}

function writeStoredPosition(position: ToolboxPosition): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(position));
  } catch {
    // See readStoredPosition — losing the persisted spot is not worth an error path.
  }
}

export interface UseToolboxPositionResult {
  /** `null` until the element has been measured, which is when a real position can be computed. */
  position: ToolboxPosition | null;
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
  const [position, setPosition] = useState<ToolboxPosition | null>(null);
  const [isDragging, setIsDragging] = useState(false);

  const nodeRef = useRef<HTMLElement | null>(null);
  const sizeRef = useRef({ width: 0, height: 0 });
  /** Offset from the element's top-left to where the pointer actually grabbed it, so the
   *  toolbox doesn't jump to centre itself under the finger on the first move. */
  const grabOffsetRef = useRef({ x: 0, y: 0 });
  const startPointRef = useRef({ x: 0, y: 0 });
  const exceededThresholdRef = useRef(false);
  const positionRef = useRef<ToolboxPosition | null>(null);

  const commit = useCallback((next: ToolboxPosition) => {
    positionRef.current = next;
    setPosition(next);
  }, []);

  /** Re-measures and re-clamps. Also the mount path: the first call is what turns `position`
   *  from null into either the stored spot or the computed default. */
  const settle = useCallback(() => {
    const node = nodeRef.current;
    if (!node) {
      return;
    }
    const rect = node.getBoundingClientRect();
    if (rect.width > 0 && rect.height > 0) {
      sizeRef.current = { width: rect.width, height: rect.height };
    }
    const bounds = computeBounds(sizeRef.current);
    const current = positionRef.current ?? readStoredPosition();
    // §7: a stored position from a larger screen (or a rotated one) is clamped back in rather
    // than trusted, which is the difference between "restores where I left it" and "the toolbox
    // is gone".
    commit(current ? clamp(current, bounds) : defaultPosition(bounds));
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
  // is listened for separately because iOS fires it before `innerHeight` has settled, so the
  // rAF defers the re-clamp until after the new metrics are readable.
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
    sizeRef.current = { width: rect.width, height: rect.height };
    grabOffsetRef.current = { x: event.clientX - rect.left, y: event.clientY - rect.top };
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

      const bounds = computeBounds(sizeRef.current);
      commit(
        clamp(
          {
            x: event.clientX - grabOffsetRef.current.x,
            y: event.clientY - grabOffsetRef.current.y,
          },
          bounds,
        ),
      );
    },
    [commit],
  );

  const onPointerUp = useCallback((event: React.PointerEvent<HTMLElement>) => {
    const node = nodeRef.current;
    if (node?.hasPointerCapture(event.pointerId)) {
      node.releasePointerCapture(event.pointerId);
    }
    if (exceededThresholdRef.current) {
      setIsDragging(false);
      if (positionRef.current) {
        writeStoredPosition(positionRef.current);
      }
    }
    // `exceededThresholdRef` is deliberately NOT reset here: the click event fires after
    // pointerup, and `wasTap()` has to still be able to see what this gesture was.
  }, []);

  const wasTap = useCallback(() => !exceededThresholdRef.current, []);

  return { position, elementRef, onPointerDown, onPointerMove, onPointerUp, isDragging, wasTap };
}
