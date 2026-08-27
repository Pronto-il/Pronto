import { afterEach, expect } from 'vitest';
import { cleanup } from '@testing-library/react';
import * as matchers from '@testing-library/jest-dom/matchers';

/**
 * Global test setup.
 *
 * Two things only: DOM matchers, and unmounting between tests. React Testing Library's
 * `cleanup` is not automatic outside its own globals integration, and without it a test that
 * queries by text finds the previous test's component and passes for the wrong reason — which
 * is the failure mode worth spending three lines to prevent.
 */
expect.extend(matchers);

/**
 * jsdom implements no `matchMedia` at all, and accessing it throws rather than returning a
 * benign default. `shared/components/Modal` reads it during its very first render to decide
 * between its bottom-sheet and centred-dialog treatments, so *any* test that mounts a component
 * containing a `Modal` — even a closed one — dies before it can assert anything.
 *
 * Stubbed as "no query matches", which yields the desktop/centred branch. Tests that care about
 * the mobile branch should override this per-test rather than rely on the default.
 */
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList;
}

afterEach(() => {
  cleanup();
});
