import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  IncompleteServiceLocationError,
  getProfessionalsForIssue,
  prefetchProfessionalListing,
} from './bookings';
import type { ServiceLocation } from './bookings';
import { httpClient } from './httpClient';

/**
 * `GET /api/bookings/professionals` is never issued without an address.
 *
 * This is the last line of defence behind the reported `400 Bad Request`. The screens above this
 * module gate on a complete address of their own — but this is where every listing request in the
 * app is actually assembled, so it is the only place that can *guarantee* an empty one never
 * leaves the browser. The bug it closes was a screen asking "is there an address object?" instead
 * of "is there an address in it?", and firing `?city=&street=&houseNumber=` when the answer was an
 * `EMPTY_ADDRESS`.
 */
const COMPLETE: ServiceLocation = { city: 'תל אביב-יפו', street: 'דיזנגוף', houseNumber: '100' };

describe('the professional listing refuses to ask without an address', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(httpClient, 'get').mockResolvedValue({ issueId: null, categoryId: 2, professionals: [] });
  });

  it.each([
    ['every field empty', { city: '', street: '', houseNumber: '' }],
    ['no city', { city: '', street: 'דיזנגוף', houseNumber: '100' }],
    ['no street', { city: 'תל אביב-יפו', street: '', houseNumber: '100' }],
    ['no house number', { city: 'תל אביב-יפו', street: 'דיזנגוף', houseNumber: '' }],
    ['whitespace only', { city: '  ', street: '  ', houseNumber: '  ' }],
    ['a non-numeric house number', { city: 'תל אביב-יפו', street: 'דיזנגוף', houseNumber: '12א' }],
  ])('rejects and sends nothing when the location has %s', async (_label, location) => {
    await expect(getProfessionalsForIssue({ categoryId: 2 }, location)).rejects.toBeInstanceOf(
      IncompleteServiceLocationError,
    );

    expect(httpClient.get).not.toHaveBeenCalled();
  });

  it('names which fields were missing, so a caller bug is diagnosable', async () => {
    const error: unknown = await getProfessionalsForIssue({ categoryId: 2 }, {
      city: '',
      street: 'דיזנגוף',
      houseNumber: '',
    }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(IncompleteServiceLocationError);
    expect((error as IncompleteServiceLocationError).missing).toEqual(['city', 'houseNumber']);
  });

  it('rejects rather than throwing synchronously, so callers can use .catch()', () => {
    // The prefetch is stored and `.catch`ed by an effect; a synchronous throw there would take the
    // screen down instead of degrading into that handler.
    const result = prefetchProfessionalListing({ categoryId: 2 }, { city: '', street: '', houseNumber: '' });

    expect(result).toBeInstanceOf(Promise);
    return expect(result).rejects.toBeInstanceOf(IncompleteServiceLocationError);
  });

  it('prefetching an incomplete location sends nothing either', async () => {
    await prefetchProfessionalListing({ categoryId: 2 }, { city: '', street: '', houseNumber: '' }).catch(
      () => undefined,
    );

    expect(httpClient.get).not.toHaveBeenCalled();
  });
});

describe('a complete address produces the request it always did', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(httpClient, 'get').mockResolvedValue({ issueId: null, categoryId: 2, professionals: [] });
  });

  it('sends every required param, for a category-keyed (guest) listing', async () => {
    await getProfessionalsForIssue({ categoryId: 2 }, COMPLETE, 'CHEAPEST');

    const path = vi.mocked(httpClient.get).mock.calls[0][0];
    const params = new URLSearchParams(path.split('?')[1]);
    expect(params.get('categoryId')).toBe('2');
    expect(params.get('city')).toBe('תל אביב-יפו');
    expect(params.get('street')).toBe('דיזנגוף');
    expect(params.get('houseNumber')).toBe('100');
    expect(params.get('sort')).toBe('CHEAPEST');
    expect(params.get('issueId')).toBeNull();
  });

  it('sends issueId instead when the customer is returning to an existing issue', async () => {
    await getProfessionalsForIssue({ issueId: 42 }, COMPLETE);

    const params = new URLSearchParams(vi.mocked(httpClient.get).mock.calls[0][0].split('?')[1]);
    expect(params.get('issueId')).toBe('42');
    expect(params.get('categoryId')).toBeNull();
  });

  it('omits an absent apartment rather than sending it empty', async () => {
    await getProfessionalsForIssue({ categoryId: 2 }, COMPLETE);

    expect(vi.mocked(httpClient.get).mock.calls[0][0]).not.toContain('apartment=');
  });
});
