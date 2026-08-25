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

afterEach(() => {
  cleanup();
});
