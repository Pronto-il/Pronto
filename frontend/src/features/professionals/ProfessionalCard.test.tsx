import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ProfessionalCard } from './ProfessionalCard';
import type { ProfessionalCard as ProfessionalCardData } from '../../shared/api';

/**
 * The professional card's travel line, which Production MS2 made nullable.
 *
 * <p>The specific regression these tests exist to prevent is the one the pre-MS2 code shipped for
 * months: a card that renders a number regardless of whether the platform actually knows it. The
 * old shape made that unavoidable — `etaMinutes` was a non-nullable `number`, so there was no
 * value meaning "unknown" and the backend invented 34 minutes. Now that `null` is a real, common
 * outcome, the two failure modes to guard against are rendering `0` and hiding the professional
 * entirely; both are asserted against below.
 */

function card(overrides: Partial<ProfessionalCardData> = {}): ProfessionalCardData {
  return {
    professionalId: 1,
    fullName: 'דנה כהן',
    serviceRegion: 'גוש דן',
    basePrice: 250,
    reliabilityScore: null,
    city: 'תל אביב',
    profileImageUrl: null,
    averageRating: 4.5,
    reviewCount: 12,
    favorited: false,
    categoryIds: [3],
    distanceKm: 4.2,
    etaMinutes: 11,
    etaTrafficAware: true,
    etaUnavailableReason: null,
    ...overrides,
  };
}

function renderCard(data: ProfessionalCardData) {
  return render(
    <MemoryRouter>
      <ProfessionalCard professional={data} categoryId={3} onSelect={vi.fn()} />
    </MemoryRouter>,
  );
}

describe('ProfessionalCard travel figures', () => {
  it('shows the real ETA and distance when the platform has them', () => {
    renderCard(card());

    expect(screen.getByText(/יכול להגיע תוך כ־11 דקות/)).toBeInTheDocument();
    expect(screen.getByText(/4\.2 ק״מ ממך/)).toBeInTheDocument();
  });

  it('says the arrival time is unavailable rather than showing a number', () => {
    renderCard(card({ etaMinutes: null, distanceKm: null, etaUnavailableReason: 'PROFESSIONAL_LOCATION_STALE' }));

    expect(screen.getByText('זמן הגעה לא זמין כרגע')).toBeInTheDocument();
    expect(screen.queryByText(/יכול להגיע תוך/)).not.toBeInTheDocument();
  });

  /**
   * The exact wrong output. `0 דקות` and `0.0 ק״מ` are what a `?? 0` or a `.toFixed(1)` on a
   * null would produce, and both read to a customer as "this person is already outside".
   */
  it('never renders a zero ETA or a zero distance', () => {
    const { container } = renderCard(
      card({ etaMinutes: null, distanceKm: null, etaUnavailableReason: 'PROVIDER_UNAVAILABLE' }),
    );

    expect(container.textContent).not.toContain('0 דקות');
    expect(container.textContent).not.toContain('0.0 ק״מ');
  });

  /**
   * Being unroutable right now is not a reason to hide somebody a customer could book for next
   * Tuesday — the standard listing is not SOS, and the two flows deliberately differ here.
   */
  it('still renders the professional, their price and their CTA without travel figures', () => {
    renderCard(card({ etaMinutes: null, distanceKm: null, etaUnavailableReason: 'PROFESSIONAL_LOCATION_MISSING' }));

    expect(screen.getByText('דנה כהן')).toBeInTheDocument();
    expect(screen.getByText('₪250')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'בחירת בעל מקצוע' })).toBeInTheDocument();
  });

  /**
   * The region is genuine information and reads perfectly well on its own, so the distance
   * clause is dropped rather than replaced with a placeholder that would need explaining.
   */
  it('keeps the service region when the distance is unknown', () => {
    renderCard(card({ distanceKm: null, etaMinutes: null, etaUnavailableReason: 'DESTINATION_UNKNOWN' }));

    expect(screen.getByText(/גוש דן/)).toBeInTheDocument();
    expect(screen.queryByText(/ק״מ ממך/)).not.toBeInTheDocument();
  });

  /** Mixed availability is possible in principle; neither half may fabricate the other. */
  it('renders each figure independently', () => {
    renderCard(card({ distanceKm: null }));

    expect(screen.getByText(/יכול להגיע תוך כ־11 דקות/)).toBeInTheDocument();
    expect(screen.queryByText(/ק״מ ממך/)).not.toBeInTheDocument();
  });
});
