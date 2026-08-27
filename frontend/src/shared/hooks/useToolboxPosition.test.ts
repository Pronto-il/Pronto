import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useToolboxPosition } from './useToolboxPosition';

/**
 * The draggable toolbox's position logic.
 *
 * Three properties are worth pinning down here, because each one is a defect the customer would
 * hit rather than a detail: **the toolbox never leaves the viewport** (§4/§7 — including after a
 * rotation that invalidates a stored position), **a tap is not a drag** (§6 — get the threshold
 * wrong and the control either never navigates or navigates every time it is nudged), and **the
 * bottom navigation is never draggable-under** (§10).
 *
 * The element is faked rather than rendered: this hook's contract is a `getBoundingClientRect`
 * and a stream of pointer events, and driving it directly is what makes the boundary arithmetic
 * observable at all.
 */

const TOOLBOX_WIDTH = 108;
const TOOLBOX_HEIGHT = 94;
const STORAGE_KEY = 'pronto.toolbox.position';

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
      left: Number.parseFloat(node.style.left || '0'),
      top: Number.parseFloat(node.style.top || '0'),
    }) as DOMRect;
  return node;
}

type Handlers = ReturnType<typeof useToolboxPosition>;

/** Minimal `React.PointerEvent` — only the fields the hook actually reads. */
function pointerEvent(x: number, y: number, pointerId = 1) {
  return { clientX: x, clientY: y, pointerId, button: 0, pointerType: 'touch' } as unknown as
    React.PointerEvent<HTMLElement>;
}

/** Mirrors what React does with the component's inline `left`/`top`, so the fake element's
 *  rect agrees with the hook's committed position — which is what makes the grab-offset
 *  arithmetic in `onPointerDown` observable. */
function syncRect(node: HTMLElement, result: { current: Handlers }) {
  node.style.left = `${result.current.position?.x ?? 0}px`;
  node.style.top = `${result.current.position?.y ?? 0}px`;
}

function mountHook(node: HTMLElement) {
  const view = renderHook(() => useToolboxPosition());
  act(() => {
    view.result.current.elementRef(node);
  });
  syncRect(node, view.result);
  return view;
}

/** Drives a full press → move → release gesture, keeping the fake element's rect in step. */
function drag(result: { current: Handlers }, node: HTMLElement, from: [number, number], to: [number, number]) {
  syncRect(node, result);
  act(() => result.current.onPointerDown(pointerEvent(from[0], from[1])));
  act(() => result.current.onPointerMove(pointerEvent(to[0], to[1])));
  act(() => {
    node.style.left = `${result.current.position?.x ?? 0}px`;
    node.style.top = `${result.current.position?.y ?? 0}px`;
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

describe('initial placement', () => {
  it('defaults to the lower-left, clear of the bottom navigation', () => {
    const { result } = mountHook(fakeElement());

    // §4: 20-30px from the left edge.
    expect(result.current.position?.x).toBe(24);
    // §10: above the 68px nav, with the 12px edge margin. 844 - 94 - 68 - 12 = 670.
    expect(result.current.position?.y).toBe(670);
  });

  it('leaves room for the iOS home indicator on top of the nav bar', () => {
    stubRootTokens({ safeBottom: '34px' });
    const { result } = mountHook(fakeElement());

    // 844 - 94 - 68 - 34 - 12 = 636 — 34px higher than without the inset.
    expect(result.current.position?.y).toBe(636);
  });

  it('ignores the nav bar above the 640px breakpoint, where it is not rendered', () => {
    setViewport(1280, 900);
    const { result } = mountHook(fakeElement());

    // No 68px subtraction: 900 - 94 - 12 = 794.
    expect(result.current.position?.y).toBe(794);
  });

  it('restores a previously stored position', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: 200, y: 300 }));
    const { result } = mountHook(fakeElement());

    expect(result.current.position).toEqual({ x: 200, y: 300 });
  });

  it('clamps a stored position that no longer fits the viewport', () => {
    // Saved on a tablet, reopened on a small phone — §7's explicit requirement.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: 980, y: 1400 }));
    const { result } = mountHook(fakeElement());

    expect(result.current.position).toEqual({ x: 390 - TOOLBOX_WIDTH - 12, y: 670 });
  });

  it('falls back to the default when the stored value is corrupt', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"x":"left"}');
    const { result } = mountHook(fakeElement());

    expect(result.current.position).toEqual({ x: 24, y: 670 });
  });
});

describe('tap versus drag (§6)', () => {
  it('treats movement below the threshold as a tap and does not move the toolbox', () => {
    const node = fakeElement();
    const { result } = mountHook(node);
    const start = result.current.position;

    drag(result, node, [100, 400], [104, 402]); // ~4.5px — a thumb roll

    expect(result.current.wasTap()).toBe(true);
    expect(result.current.position).toEqual(start);
  });

  it('treats movement past the threshold as a drag and suppresses the tap', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [160, 300]);

    expect(result.current.wasTap()).toBe(false);
    expect(result.current.position).not.toEqual({ x: 24, y: 670 });
  });

  it('still reports a drag after the pointer is released, so the click handler can see it', () => {
    // The click event fires *after* pointerup; resetting the flag on release would make every
    // drag navigate.
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [200, 400]);

    expect(result.current.isDragging).toBe(false);
    expect(result.current.wasTap()).toBe(false);
  });
});

describe('viewport boundaries (§4)', () => {
  it('cannot be dragged off the left or top edge', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [-500, -500]);

    expect(result.current.position).toEqual({ x: 12, y: 12 });
  });

  it('cannot be dragged off the right edge', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [5000, 400]);

    expect(result.current.position?.x).toBe(390 - TOOLBOX_WIDTH - 12);
  });

  it('cannot be dragged down behind the bottom navigation', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [100, 5000]);

    // Bottom-most legal top is the same 670 the default uses.
    expect(result.current.position?.y).toBe(670);
  });
});

describe('persistence (§7)', () => {
  it('writes the position to localStorage after a drag', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [200, 500]);

    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? 'null');
    expect(stored).toEqual(result.current.position);
  });

  it('does not write anything for a mere tap', () => {
    const node = fakeElement();
    const { result } = mountHook(node);

    drag(result, node, [100, 400], [102, 401]);

    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('survives a localStorage that throws, rather than breaking the toolbox', () => {
    // Safari private mode. A toolbox at its default spot is a complete recovery.
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    const node = fakeElement();
    const { result } = mountHook(node);

    expect(() => drag(result, node, [100, 400], [200, 500])).not.toThrow();
    expect(result.current.position).toBeTruthy();
  });
});

describe('viewport changes', () => {
  it('re-clamps into view when the window shrinks under it', () => {
    const node = fakeElement();
    const { result } = mountHook(node);
    drag(result, node, [100, 400], [260, 200]);
    expect(result.current.position?.x).toBe(260 - (100 - 24));

    act(() => {
      node.style.left = `${result.current.position?.x}px`;
      node.style.top = `${result.current.position?.y}px`;
      setViewport(320, 568);
      window.dispatchEvent(new Event('resize'));
    });

    expect(result.current.position?.x).toBeLessThanOrEqual(320 - TOOLBOX_WIDTH - 12);
    expect(result.current.position?.y).toBeLessThanOrEqual(568 - TOOLBOX_HEIGHT - 68 - 12);
  });
});
