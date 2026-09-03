import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SosCandidate, SosCandidateState } from '../../shared/api';
import { SosCandidateCard } from './SosCandidateCard';

/**
 * REQUESTED vs ACCEPTED on the customer's SOS screen.
 *
 * <p>A customer with water coming through a ceiling has to be able to tell, at a glance, who has
 * actually committed to come. These tests pin the two things that would break that: showing an
 * arrival time for somebody who has not answered, and offering a way to choose them.
 */
describe('SosCandidateCard — waiting vs available', () => {
  function candidate(state: SosCandidateState, overrides: Partial<SosCandidate> = {}): SosCandidate {
    return {
      offerId: 1,
      professionalId: 2,
      state,
      fullName: 'דוד כהן',
      profileImageUrl: null,
      city: 'תל אביב-יפו',
      serviceRegion: 'גוש דן',
      averageRating: 4.6,
      reviewCount: 12,
      // The backend sends null for a REQUESTED candidate; a test that passed a number here would
      // be describing a response the server cannot produce.
      estimatedArrivalMinutes: state === 'ACCEPTED' ? 20 : null,
      distanceKm: 4.2,
      visitFee: 250,
      sosFee: 50,
      totalVisitCost: 300,
      platformCommission: 30,
      respondedAt: state === 'ACCEPTED' ? new Date().toISOString() : null,
      ...overrides,
    };
  }

  function renderCard(state: SosCandidateState) {
    const onSelect = vi.fn();
    render(
      <SosCandidateCard
        candidate={candidate(state)}
        selectionOpen
        isSubmitting={false}
        isPending={false}
        onSelect={onSelect}
        onOpenDetails={vi.fn()}
      />,
    );
    return { onSelect };
  }

  describe('REQUESTED — contacted, no answer yet', () => {
    it('is visible, and says it is waiting', () => {
      renderCard('REQUESTED');

      expect(screen.getByText('דוד כהן')).toBeInTheDocument();
      expect(screen.getByText('ממתין לתשובה')).toBeInTheDocument();
    });

    it('shows no arrival time, because nobody has promised one', () => {
      renderCard('REQUESTED');

      expect(screen.queryByText(/דק׳/)).not.toBeInTheDocument();
    });

    it('offers no way to choose them — not even a disabled one', () => {
      renderCard('REQUESTED');

      expect(screen.queryByRole('button', { name: 'בחר' })).not.toBeInTheDocument();
    });

    it('is never described as having accepted', () => {
      renderCard('REQUESTED');

      expect(screen.queryByText(/אישר/)).not.toBeInTheDocument();
    });

    it('can still be inspected while waiting', () => {
      renderCard('REQUESTED');

      expect(screen.getByRole('button', { name: 'פרטים נוספים' })).toBeInTheDocument();
    });
  });

  describe('ACCEPTED — answered and committed', () => {
    it('carries a clear badge', () => {
      renderCard('ACCEPTED');

      // "אישר זמינות", not "אישר" alone: this app reserves the bare word for a professional
      // confirming a job they were actually given.
      expect(screen.getByText('אישר זמינות ✓')).toBeInTheDocument();
      expect(screen.queryByText('ממתין לתשובה')).not.toBeInTheDocument();
    });

    it('shows the committed arrival time', () => {
      renderCard('ACCEPTED');

      expect(screen.getByText(/20 דק׳/)).toBeInTheDocument();
    });

    it('enables the choice', () => {
      renderCard('ACCEPTED');

      expect(screen.getByRole('button', { name: 'בחר' })).toBeEnabled();
    });
  });
});
