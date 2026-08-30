import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { matchRoutes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { isInBookingFlow } from './toolboxState';

/**
 * <b>Every path this app navigates to must actually resolve.</b>
 *
 * Four navigations were pointing at `/issues/:issueId/(booking|sos-booking)` after deferred
 * authentication removed those routes. There is no catch-all route, so each one landed the
 * customer on a blank screen — including the SOS recovery CTA, which fires at the exact moment a
 * search has just failed them, and the customer's SOS notification, whose whole purpose is to be
 * tapped. Nothing failed: not the typechecker (they are template strings), not the tests (no test
 * followed them), not the build.
 *
 * This suite is the check that was missing. It reads the route table out of `router.tsx` and
 * asserts that the concrete paths the codebase navigates to match something — so deleting a route
 * that is still linked, or linking to a route that was never added, fails here instead of in
 * Production.
 *
 * Deliberately a source-level check on a hand-maintained list rather than a crawl of every
 * template literal in the app: a regex over `navigate(...)` cannot resolve `${order.issueId}` to a
 * value, and pretending otherwise would produce a test that is either noisy or dishonest. The list
 * is the set of shapes that actually exist, each traceable to its call site.
 */

/**
 * The route patterns registered in `router.tsx`, read from the source.
 *
 * Parsed rather than imported because importing the module pulls in every lazy page component and
 * the whole provider tree; the property under test is which *paths* were written down.
 */
function registeredRoutePatterns(): string[] {
  const source = readFileSync(join(__dirname, 'router.tsx'), 'utf8');
  return [...source.matchAll(/\{\s*path:\s*'([^']+)'/g)].map((match) => match[1]);
}

/** `matchRoutes` needs objects; the elements are irrelevant to path matching. */
function routeObjects() {
  return registeredRoutePatterns().map((path) => ({ path: path.startsWith('/') ? path : `/${path}` }));
}

function resolves(pathname: string): boolean {
  return matchRoutes(routeObjects(), pathname) !== null;
}

/**
 * Every concrete path the app navigates to, with the call site that produces it. Ids are
 * substituted with a realistic value — the point is the *shape*.
 */
const NAVIGATION_TARGETS: ReadonlyArray<readonly [path: string, callSite: string]> = [
  // Booking creation (draft-driven, flattened by deferred authentication).
  ['/issues/new', 'ProfessionMatchPage, ProntoSosEntryPage (no-draft recovery)'],
  ['/matching', 'NewIssuePage.handleConfirmed'],
  ['/booking', 'ProfessionMatchPage, ProfessionalProfilePage, ProntoSosScreen, ProntoSosEntryPage'],
  ['/sos-booking', 'ProfessionMatchPage, ProfessionalProfilePage, resolveDraftRoute'],

  // Re-entry on an issue that already exists (id in the URL, no draft available).
  ['/issues/42/booking', 'OrderTrackingPage.renderTerminalAction (STANDARD)'],
  ['/issues/42/sos-booking', 'OrderTrackingPage.renderTerminalAction (SOS), NotificationBell'],

  // Ordinary app routes reached by navigation.
  ['/', 'ProntoSosEntryPage back, ProfessionMatchPage'],
  ['/login', 'BookingFlowPage.handleAuthRequired, ProntoSosEntryPage auth boundary'],
  ['/orders', 'ActiveIssueToolbox'],
  ['/orders/42', 'ActiveIssueToolbox, NotificationBell'],
  ['/orders/42/review', 'CompletionReviewPage route'],
  ['/professionals/7', 'ProfessionMatchPage, ProfessionalCard'],
  ['/pro/sos', 'NotificationBell (professional audience)'],
  ['/favorites', 'BottomNav'],
  ['/profile', 'BottomNav'],
  ['/verify-phone', 'PHONE_VERIFICATION_REQUIRED handler'],
];

describe('every navigation target resolves to a registered route', () => {
  it.each(NAVIGATION_TARGETS)('%s — navigated from %s', (path) => {
    expect(resolves(path)).toBe(true);
  });

  it('a path that was never registered does not resolve', () => {
    // Proves the check has teeth: without a catch-all, an unregistered path matches nothing, which
    // is exactly the blank screen the four broken links produced.
    expect(resolves('/issues/42/matching')).toBe(false);
    expect(resolves('/definitely-not-a-route')).toBe(false);
  });
});

describe('the re-entry routes and the booking-flow rule agree', () => {
  it.each(['/issues/42/booking', '/issues/42/sos-booking'])(
    'hides the order widget on the restored route %s',
    (path) => {
      // Restoring these routes must not reopen the widget bug: they are still booking screens, so
      // the shortcut back to a different order has nothing to resume there either.
      expect(isInBookingFlow(path)).toBe(true);
    },
  );
});
