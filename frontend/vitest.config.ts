import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Vitest, added by Production MS2.
 *
 * ## Why the frontend gained a test runner now
 *
 * Until MS2 every frontend behaviour was verifiable by reading it: a component rendered a field
 * the API sent, and the compiler proved the field existed. MS2 introduced logic that TypeScript
 * cannot check and a reviewer cannot reliably eyeball — a permission-and-timeout state machine
 * around `navigator.geolocation` whose failure modes are precisely the ones that are hard to
 * reproduce by hand (denied, timed out, answered-but-too-coarse), and card rendering whose whole
 * job is to behave correctly when a value is `null`.
 *
 * Kept deliberately small: a runner, a DOM, and React Testing Library. No component-snapshot
 * tooling, no mock-service worker, no coverage gate — this project has no test culture to
 * disrupt and adding infrastructure nobody asked for is its own kind of debt.
 *
 * Separate from `vite.config.ts` rather than merged into it so the application build has no
 * opinion about, or dependency on, the test setup.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: true,
  },
});
