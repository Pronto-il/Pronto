import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useToolboxPosition } from './useToolboxPosition';

/**
 * The floating toolbox's drag/clamp/persist logic (mobile-nav fix, 2026-08-28): free vertical
 * drag (unchanged from the prior redesign) plus snap-to-nearest-horizontal-edge drag (new — the
 * prior redesign removed horizontal movement entirely and pinned the toolbox to one CSS
 * `inset-inline-start` side permanently, which is the bug this fixes).
 *
 * Worth pinning down here: the toolbox **stays inside the viewport and above the bottom
 * navigation** vertically (unchanged), it **always settles against one of the two horizontal
 * edges, never mid-screen**, a drag **can move it from either edge to the other**, a **tap is not
 * a drag** on either axis, and a **resize re-clamps without ever flipping which edge an
 * already-settled toolbox is pinned to** (side is authoritative state, not re-derived from raw
 * geometry every time).
 *
 * The element is faked rather than rendered: this hook's contract is a `getBoundingClientRect`
 * and a stream of pointer events, and driving it directly is what makes the boundary arithmetic
 * observable.
 */

const TOOLBOX_HEIGHT = 94;
const TOOLBOX_WIDTH = 80;
const STORAGE_KEY = 'pronto.toolbox.pos';

/** Mirrors `index.css` — the hook reads these off `:root` at runtime. */
function stubRootTokens({ navHeight = '68px', safeBottom = '0px', safeTop = '0px' } = {}) {
  vi.spyOn(window, 'getComputedStyle').mockReturnValue({
    getPropertyValue: (name: string) =>
      name === '--bottom-nav-height'
        ? navHeight
        : name === '--safe-area-bottom'
          ? safeBottom
          : name === '--safe-area-top'
            ? safeTop
            : '',
  } as unknown as CSSStyleDeclaration);
}

function setViewport(width: number, height: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true });
  Object.defineProperty(window, 'innerHeight', { value: height, configurable: true, writable: true });
}

/** A stand-in for the toolbox button: a fixed-size box whose rect follows the inline style. */
function fakeElement(): HTMLElement {
  const node = document.createElement('button');
  const captured = new Set<number>();
  node.setPointerCapture = (id: number) => void captured.add(id);
  node.releasePointerCapture = (id: number) => void captured.delete(id);
  node.hasPointerCapture = (id: number) => captured.has(id);
  node.getBoundingClientRect = () =>
    ({
      width: TOOLBOX_WIDTH,
      height: TOOLBOX_HEIGHT,
      top: Number.parseFloat(node.style.top || '0'),
      left: Number.parseFloat(node.style.left || '0'),
    }) as DOMRect;
  return node;
}

type Handlers = ReturnType<typeof useToolboxPosition>;

/** Minimal `React.PointerEvent` — only the fields the hook actually reads. */
function pointerEvent(x: number, y: number, pointerId = 1) {
  return { clientX: x, clientY: y, pointerId, button: 0, pointerType: 'touch' } as unknown as
    React.PointerEvent<HTMLElement>;
}

/** Mirrors what React does with the component's inline `top`/`left`, so the fake element's rect
 *  agrees with the hook's committed position — which is what makes the grab-offset arithmetic
 *  observable on both axes. */
function syncPosition(node: HTMLElement, result: { current: Handlers }) {
  node.style.top = `${result.current.top ?? 0}px`;
  node.style.left = `${result.current.left ?? 0}px`;
}

function mountHook(node: HTMLElement) {
  const view = renderHook(() => useToolboxPosition());
  act(() => {
    view.result.current.elementRef(node);
  });
  syncPosition(node, view.result);
  return view;
}

/** Drives a full press → move → release gesture, keeping the fake element's rect in step. */
function drag(result: { current: Handlers }, node: HTMLElement, from: [number, number], to: [number, number]) {
  syncPosition(node, result);
  act(() => result.current.onPointerDown(pointerEvent(from[0], from[1])));
  act(() => result.current.onPointerMove(pointerEvent(to[0], to[1])));
  act(() => {
    node.style.top = `${result.current.top ?? 0}px`;
    node.style.left = `${result.current.left ?? 0}px`;
    result.current.onPointerUp(pointerEvent(to[0], to[1]));
  });
}

beforeEach(() => {
  setViewport(390, 844); // iPhone 14
  stubRootTokens();
  window.localStorage.clear();
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    cb(0);
    return 0;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

// Horizontal bounds on the default 390px viewport: min = 12 (EDGE_MARGIN), max = 390 - 80 - 12 = 298.
const RIGHT_EDGE = 298;
const LEFT_EDGE = 12;

describe('initial placement', () => {
  it('defaults to the right edge, low on the side, just above the bottom navigation', () => {
    const { result } = mountHook(fakeElement());

    // 844 - 94 - 68 (nav) - 12 (margin) = 670.
    expect(result.current.top).toBe(670);
    expect(result.current.left).toBe(RIGHT_EDGE);
  });

  it('leaves room for the iOS home indicator on top of the nav bar', () => {
    stubRootTokens({ safeBottom: '34px' });
    const { result } = mountHook(fakeElement());

    // 844 - 94 - 68 - 34 - 12 = 636 — 34px higher than without the inset.
    expect(result.current.top).toBe(636);
  });

  it('ignores the nav bar above the 640px breakpoint, where it is not rendered', () => {
    setViewport(1280, 900);
    const { result } = mountHook(fakeElement());

    // No 68px subtraction: 900 - 94 - 12 = 794. Horizontal: 1280 - 80 - 12 = 1188.
    expect(result.current.top).toBe(794);
    expect(result.current.left).toBe(1188);
  });

  it('restores a previously stored position — both the vertical offset and the snapped side', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ top: 300, side: 'left' }));
    const { result } = mountHook(fakeElement());

    expect(result.current.top).toBe(300);
    expect(result.current.left).toBe(LEFT_EDGE);
  });

  it('clamps a stored vertical offset that no longer fits the viewport', () => {
    // Saved on a tall screen, reopened on a short one.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ top: 1400, side: 'right' }));
    const { result } = mountHook(fakeElement());

    expect(result.current.top).toBe(670); // clamped down to the max
  });

  it('falls back to the defaults when the stored value is malformed', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"y":"low"}');
    const { result } = mountHook(fakeElement());

    expect(result.current.top).toBe(670);
    expect(result.current.left).toBe(RIGHT_EDGE);
  });

  it('ignores a stale bare-number value left by the previous (pre-side) storage shape', () => {
    // The old key, `pronto.toolbox.top`, held a bare number under the vertical-only redesign.
    // A value of that shape under the new key must not be half-read.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(300));
    const { result } = mountHook(fakeElement());

    expect(result.current.top).toBe(670); // default, not 300
    expect(result.current.left).toBe(RIGHT_EDGE);
  });

  it('ignores a stale {x, y} value from an even earlier storage shape', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: 24, y: 300 }));
    const { result } = mountHook(fakeElement());

    expect(result.current.top).toBe(670); // default, not 300
  });
});

describe('tap versus drag', () => {
  it('treats movement below the threshold as a tap and does not move the toolbox', () => {
    const node = fakeElement();
    const { result } = mountHook(node);
    const startTop = result.current.top;
    const startLeft = result.current.left;

    drag(result, node, [340, 400], [343, 402]); // ~3.6px — a thumb roll

    expect(result.current.wasTap()).toBe(true);
    expect(result.current.top).toBe(startTop);
    expect(result.current.left).toBe(startLeft);
  });

  it('treats vertical movement past the threshold as a drag and suppresses the tap', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [340, 300]);

    expect(result.current.wasTap()).toBe(false);
    expect(result.current.top).not.toBe(670);
  });

  it('treats horizontal movement past the threshold as a drag and suppresses the tap', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [40, 400]);

    expect(result.current.wasTap()).toBe(false);
  });

  it('still reports a drag after the pointer is released, so the click handler can see it', () => {
    // The click event fires *after* pointerup; resetting the flag on release would make every
    // drag navigate.
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [40, 400]);

    expect(result.current.isDragging).toBe(false);
    expect(result.current.wasTap()).toBe(false);
  });
});

describe('vertical boundaries (free drag, unchanged)', () => {
  it('cannot be dragged off the top edge', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [340, -500]);

    expect(result.current.top).toBe(12); // EDGE_MARGIN
  });

  it('cannot be dragged down behind the bottom navigation', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [340, 5000]);

    expect(result.current.top).toBe(670); // same max the default uses
  });

  it('is released exactly where the gesture ended, not snapped to any particular value', () => {
    const node = fakeElement();
    const { result } = mountHook(node);
    // Grabbed exactly at the element's current corner (zero offset), so the drop point commits
    // directly — isolates this assertion from grab-offset arithmetic.
    const start: [number, number] = [result.current.left!, result.current.top!];

    drag(result, node, start, [start[0], 250]);

    expect(result.current.top).toBe(250);
  });
});

describe('horizontal snap-to-edge (mobile-nav fix)', () => {
  it('follows the pointer live while the drag is in progress, before any snap happens', () => {
    const node = fakeElement();
    const { result } = mountHook(node); // starts pinned to the right edge (298)

    syncPosition(node, result);
    act(() => result.current.onPointerDown(pointerEvent(340, 400)));
    // grabOffsetX = 340 - 298 = 42; next left = clamp(150 - 42, bounds) = 108.
    act(() => result.current.onPointerMove(pointerEvent(150, 400)));

    expect(result.current.left).toBe(108);
    expect(result.current.left).not.toBe(LEFT_EDGE);
    expect(result.current.left).not.toBe(RIGHT_EDGE);
  });

  it('can snap to the right edge after a drag that ends on the right half', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    // Start from the left edge so the drag is a genuine move, not a no-op at the same edge.
    drag(result, node, [340, 400], [20, 400]);
    expect(result.current.left).toBe(LEFT_EDGE);

    drag(result, node, [20, 400], [350, 400]);

    expect(result.current.left).toBe(RIGHT_EDGE);
  });

  it('can snap to the left edge after a drag that ends on the left half', () => {
    const node = fakeElement();
    const { result } = mountHook(node); // starts at the right edge

    drag(result, node, [340, 400], [20, 400]);

    expect(result.current.left).toBe(LEFT_EDGE);
  });

  it('switches the snapped side each time a drag crosses to the other half', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    expect(result.current.left).toBe(RIGHT_EDGE);
    drag(result, node, [340, 400], [20, 400]);
    expect(result.current.left).toBe(LEFT_EDGE);
    drag(result, node, [20, 400], [370, 400]);
    expect(result.current.left).toBe(RIGHT_EDGE);
    drag(result, node, [370, 400], [30, 400]);
    expect(result.current.left).toBe(LEFT_EDGE);
  });

  it('never rests mid-screen — a release near the midpoint still snaps to the nearer edge', () => {
    const node = fakeElement();
    const { result } = mountHook(node);
    const midpoint = (LEFT_EDGE + RIGHT_EDGE) / 2; // 155
    const top0 = result.current.top!;

    // Each drag is grabbed at zero offset (the element's own current corner), so the release
    // x lands exactly on the pre-snap value the nearest-edge decision is made from.
    drag(result, node, [result.current.left!, top0], [midpoint - 5, top0]);
    expect(result.current.left).toBe(LEFT_EDGE);

    drag(result, node, [result.current.left!, top0], [midpoint + 5, top0]);
    expect(result.current.left).toBe(RIGHT_EDGE);
  });

  it('preserves the current vertical position while snapping horizontally', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [result.current.left!, result.current.top!], [40, 250]);

    expect(result.current.top).toBe(250); // vertical: released exactly here, unchanged behaviour
    expect(result.current.left).toBe(LEFT_EDGE); // horizontal: snapped
  });

  it('keeps the toolbox clear of the bottom nav / safe area after snapping horizontally', () => {
    stubRootTokens({ safeBottom: '20px' });
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [20, 5000]); // drag toward the left edge, off the bottom

    expect(result.current.left).toBe(LEFT_EDGE);
    // 844 - 94 - 68 - 20 - 12 = 650 — still clamped above the nav + inset, exactly as vertical
    // clamping already guaranteed before this fix.
    expect(result.current.top).toBe(650);
  });

  it('persists the snapped side (not a raw x) so a resize cannot flip which edge it is on', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [20, 400]); // snap left
    expect(result.current.left).toBe(LEFT_EDGE);

    act(() => {
      node.style.top = `${result.current.top}px`;
      node.style.left = `${result.current.left}px`;
      setViewport(320, 480); // much narrower and shorter
      window.dispatchEvent(new Event('resize'));
    });

    // Still the left edge (12) on the new, narrower viewport — re-clamped, not re-derived from
    // the old raw x, which on a narrower screen could otherwise have read as "closer to the
    // other side" and silently flipped which edge the toolbox rests against.
    expect(result.current.left).toBe(LEFT_EDGE);
  });
});

describe('persistence', () => {
  it('writes the vertical offset and the snapped side to localStorage after a drag', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [20, 250]);

    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? 'null');
    expect(stored).toEqual({ top: result.current.top, side: 'left' });
  });

  it('does not write anything for a mere tap', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [340, 400], [341, 401]);

    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('survives a localStorage that throws, rather than breaking the toolbox', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    const node = fakeElement();
    const { result } = mountHook(node);

    expect(() => drag(result, node, [340, 400], [20, 250])).not.toThrow();
    expect(result.current.top).not.toBeNull();
    expect(result.current.left).not.toBeNull();
  });
});

describe('viewport changes', () => {
  it('re-clamps the vertical position into view when the window shrinks under it', () => {
    const node = fakeElement();
    const { result } = mountHook(node);
    // Default on the tall screen sits at 670, which is below the short screen's usable range.
    expect(result.current.top).toBe(670);

    act(() => {
      node.style.top = `${result.current.top}px`;
      node.style.left = `${result.current.left}px`;
      setViewport(320, 480);
      window.dispatchEvent(new Event('resize'));
    });

    // Max top on the short screen: 480 - 94 - 68 - 12 = 306. The stale 670 is clamped to it.
    expect(result.current.top).toBe(306);
  });
});
