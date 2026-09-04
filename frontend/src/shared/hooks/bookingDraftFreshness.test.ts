import { describe, expect, it } from 'vitest';
import {
  GUEST_DRAFT_STALE_AFTER_MS,
  resolveDraftRoute,
  sanitizeRestoredDraft,
} from './bookingDraftContext';
import type { BookingDraft } from './bookingDraftContext';

/**
 * The freshness policy for restored guest drafts.
 *
 * <p>The bug: a guest starts a booking on a phone, closes the app, comes back the next day, and the
 * flow resumes from where they were — skipping address selection, because every screen decided
 * that from data with no age attached to it. Yesterday's address, professional and time became
 * today's booking, and for SOS that meant *dispatching* to yesterday's address without asking.
 *
 * <p>These tests pin both halves: what a stale restore drops, and — just as important, since this
 * runs on every load — what a fresh one does not touch.
 */

const NOW = Date.parse('2026-09-04T12:00:00.000Z');
const HOUR = 60 * 60 * 1000;

const ADDRESS = {
  city: 'תל אביב-יפו',
  street: 'הרצל',
  houseNumber: '10',
  apartment: '',
  floor: '',
  entrance: '',
  addressNotes: '',
  placeId: 'place-abc',
  formattedAddress: 'הרצל 10, תל אביב-יפו',
  latitude: 32.06,
  longitude: 34.77,
};

/** A guest mid-booking: professional chosen, time chosen, one step from confirming. */
function guestDraft(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: null,
    stage: 'BOOKING_CONFIRM',
    urgencyType: 'STANDARD',
    description: 'נזילה מתחת לכיור במטבח',
    photos: [{ imageKey: 'guests/abc/1.jpg' }],
    clarificationAnswers: [{ question: 'מאיפה דולף?', answer: 'מהצינור' }],
    categoryId: 1,
    address: ADDRESS,
    addressMode: 'CUSTOM',
    professionalId: 7,
    bookedStart: '2026-09-04T09:00:00.000Z',
    sort: 'CHEAPEST',
    updatedAt: new Date(NOW - HOUR).toISOString(),
    ...overrides,
  } as BookingDraft;
}

function agedBy(ms: number, overrides: Partial<BookingDraft> = {}): BookingDraft {
  return guestDraft({ updatedAt: new Date(NOW - ms).toISOString(), ...overrides });
}

describe('a fresh guest draft is untouched', () => {
  it('same-session refresh returns exactly what was stored', () => {
    // Seconds old — the F5 case. Anything less than identical here would be a regression in the
    // resume behaviour this policy is not supposed to change.
    const draft = agedBy(2_000);

    expect(sanitizeRestoredDraft(draft, NOW)).toEqual(draft);
  });

  it('a short reopen hours later still resumes where the customer was', () => {
    const draft = agedBy(6 * HOUR);

    expect(sanitizeRestoredDraft(draft, NOW)).toEqual(draft);
  });

  it('holds right up to the threshold', () => {
    const draft = agedBy(GUEST_DRAFT_STALE_AFTER_MS - 1_000);

    expect(sanitizeRestoredDraft(draft, NOW)).toEqual(draft);
  });
});

describe('a stale guest draft cannot skip a step', () => {
  const stale = () => sanitizeRestoredDraft(agedBy(20 * HOUR), NOW);

  it('rewinds to address selection rather than the step the customer left on', () => {
    expect(stale().stage).toBe('ADDRESS_SELECTION');
  });

  it('marks the address as needing confirmation instead of discarding it', () => {
    // Kept as prefill — re-typing an address the customer already gave would be its own insult —
    // but flagged, so the screens ask rather than infer from the fields being non-empty.
    expect(stale().address).toEqual(ADDRESS);
    expect(stale().addressUnconfirmed).toBe(true);
  });

  it('drops every other position-in-the-flow field', () => {
    const sanitized = stale();

    expect(sanitized.professionalId).toBeUndefined();
    expect(sanitized.bookedStart).toBeUndefined();
    expect(sanitized.sort).toBeUndefined();
    expect(sanitized.addressMode).toBeUndefined();
  });

  it('preserves the customer’s own account of the problem', () => {
    const sanitized = stale();

    expect(sanitized.description).toBe('נזילה מתחת לכיור במטבח');
    expect(sanitized.categoryId).toBe(1);
    expect(sanitized.photos).toEqual([{ imageKey: 'guests/abc/1.jpg' }]);
    expect(sanitized.clarificationAnswers).toEqual([{ question: 'מאיפה דולף?', answer: 'מהצינור' }]);
  });

  it('keeps Regular on the Regular route and SOS on the SOS route', () => {
    // `urgencyType` survives, so the rewind lands in the flow the customer chose — resetting into
    // the wrong one would be a far worse bug than the one being fixed.
    expect(resolveDraftRoute(stale())).toBe('/booking');

    const sos = sanitizeRestoredDraft(agedBy(20 * HOUR, { urgencyType: 'SOS' }), NOW);
    expect(sos.urgencyType).toBe('SOS');
    expect(resolveDraftRoute(sos)).toBe('/sos-booking');
  });

  it('treats an unreadable timestamp as stale', () => {
    // A draft that cannot say when it was written cannot be trusted to say where the customer was.
    const sanitized = sanitizeRestoredDraft(guestDraft({ updatedAt: 'not-a-date' }), NOW);

    expect(sanitized.stage).toBe('ADDRESS_SELECTION');
  });
});

describe('what the policy deliberately leaves alone', () => {
  it('a signed-in customer’s draft, however old', () => {
    // They have an account to come back to and a profile address; nothing here was reported wrong,
    // and widening the blast radius of this change is not worth the tidiness.
    const owned = agedBy(90 * HOUR, { ownerId: 42 });

    expect(sanitizeRestoredDraft(owned, NOW)).toEqual(owned);
  });

  it('a stale draft still describing the problem', () => {
    // Nothing to skip: the describe/clarify/review screens re-derive their classification anyway.
    const describing = agedBy(90 * HOUR, { stage: 'ISSUE_REVIEW' });

    expect(sanitizeRestoredDraft(describing, NOW)).toEqual(describing);
  });

  it('a stale draft with no address at all gets no flag to clear', () => {
    const noAddress = sanitizeRestoredDraft(agedBy(20 * HOUR, { address: undefined }), NOW);

    expect(noAddress.addressUnconfirmed).toBeUndefined();
    expect(noAddress.stage).toBe('ADDRESS_SELECTION');
  });
});
