import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { SosIssuePhoto, SosOfferResponse } from '../../shared/api';
import { SosIssueDetails } from './SosIssueDetails';
import { SosOfferCard } from './SosOfferCard';

/**
 * **What the professional can see about the emergency they are being offered.**
 *
 * The bug: the card showed `issueSummary` — an optional headline the customer app never sends —
 * and a street name. The customer's description and photos existed on the anchoring issue the
 * whole time and were never mapped into the response, so a professional was asked to accept a job
 * described to them as a location.
 *
 * These cover the rendering half. `backend/.../SosIssueDetailsDisclosureTest` covers the payload,
 * and `StorageServiceTest` the authorization fence around the photo URLs.
 */

const DESCRIPTION = 'צינור מתחת לכיור התפוצץ ויש מים על הרצפה.\nסגרתי את הברז הראשי.';

function photo(name: string): SosIssuePhoto {
  return { imageKey: `customers/2/issues/temp/${name}.jpg`, url: `https://signed.example/${name}?sig=x` };
}

describe('SosIssueDetails', () => {
  it("shows the customer's description in full, verbatim", () => {
    render(<SosIssueDetails description={DESCRIPTION} photos={[]} />);

    // Both lines — the customer's own line breaks are content, not formatting noise.
    expect(screen.getByText(/צינור מתחת לכיור התפוצץ/)).toBeInTheDocument();
    expect(screen.getByText(/סגרתי את הברז הראשי/)).toBeInTheDocument();
  });

  it('shows the headline in addition to the description, never instead of it', () => {
    // The regression that started this: a short summary standing in for the real description.
    render(<SosIssueDetails summary="נזילה במטבח" description={DESCRIPTION} photos={[]} />);

    expect(screen.getByText(/נזילה במטבח/)).toBeInTheDocument();
    expect(screen.getByText(/סגרתי את הברז הראשי/)).toBeInTheDocument();
  });

  it('renders every attached photo', () => {
    render(<SosIssueDetails description={DESCRIPTION} photos={[photo('a'), photo('b'), photo('c')]} />);

    expect(screen.getAllByRole('button', { name: /הגדלת התמונה/ })).toHaveLength(3);
    expect(screen.getByText('3 תמונות שצירף הלקוח')).toBeInTheDocument();
  });

  it('opens a photo at full size', async () => {
    render(<SosIssueDetails description={DESCRIPTION} photos={[photo('a')]} />);

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /הגדלת התמונה/ }));

    // `ImageLightbox` portals a full-viewport overlay; the enlarged image is named by its alt.
    const enlarged = await screen.findByAltText('התמונה שצירף הלקוח');
    expect(enlarged).toHaveAttribute('src', 'https://signed.example/a?sig=x');
  });

  it('handles an emergency with no photos at all', () => {
    // Common and entirely valid — somebody with water rising does not stop to take pictures.
    render(<SosIssueDetails description={DESCRIPTION} photos={[]} />);

    expect(screen.getByText(/צינור מתחת לכיור/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /הגדלת התמונה/ })).not.toBeInTheDocument();
  });

  it('renders nothing at all when there is nothing to show', () => {
    const { container } = render(<SosIssueDetails description={null} photos={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('marks a photo whose URL has expired without hiding the others', () => {
    // Presigned URLs live 300s. A card left open outlives them, and one dead thumbnail must not
    // take the rest of the strip — or the accept button — with it.
    const { container } = render(
      <SosIssueDetails description={DESCRIPTION} photos={[photo('a'), photo('b')]} />,
    );

    // Thumbnails are decorative (`alt=""`), so they are queried structurally rather than by role.
    const images = Array.from(container.querySelectorAll('img'));
    expect(images).toHaveLength(2);
    // Simulate the browser failing to fetch the first one — an expired presigned URL.
    fireEvent.error(images[0]);

    expect(screen.getByLabelText('לא הצלחנו לטעון את התמונה')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/חלק מהתמונות לא נטענו/);
    // The surviving photo is still openable.
    expect(screen.getByRole('button', { name: /הגדלת התמונה/ })).toBeInTheDocument();
  });
});

describe('the professional offer card', () => {
  function offer(overrides: Partial<SosOfferResponse> = {}): SosOfferResponse {
    return {
      id: 1,
      sosRequestId: 10,
      professionalId: 63,
      status: 'OFFERED',
      requestStatus: 'MATCHING',
      categoryId: 1,
      issueSummary: null,
      issueDescription: DESCRIPTION,
      issuePhotos: [photo('a'), photo('b')],
      urgency: 'EMERGENCY',
      serviceCity: 'תל אביב-יפו',
      serviceStreet: 'דיזנגוף',
      matchRank: 1,
      distanceKm: 3.2,
      estimatedArrivalMinutes: null,
      visitFee: 250,
      sosFee: 50,
      platformCommission: 30,
      professionalNet: 270,
      orderId: null,
      offeredAt: new Date().toISOString(),
      viewedAt: null,
      respondedAt: null,
      expiresAt: new Date(Date.now() + 120_000).toISOString(),
      ...overrides,
    } as unknown as SosOfferResponse;
  }

  it('shows the description and photos before the professional accepts', () => {
    // The decision surface. Withholding the fault until after acceptance is asking somebody to
    // commit blind — which is exactly what the card did.
    render(<SosOfferCard offer={offer()} onRespondAvailable={vi.fn()} onDecline={vi.fn()} />);

    expect(screen.getByText(/צינור מתחת לכיור התפוצץ/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /הגדלת התמונה/ })).toHaveLength(2);
  });

  it('still shows the street and city it always did', () => {
    render(<SosOfferCard offer={offer()} onRespondAvailable={vi.fn()} onDecline={vi.fn()} />);

    expect(screen.getByText(/דיזנגוף, תל אביב-יפו/)).toBeInTheDocument();
  });

  it('survives an older payload with no description or photos', () => {
    // A card rendered from a cached/realtime payload minted before this field existed must not
    // throw — `issuePhotos` is defaulted at the call site for exactly this reason.
    render(
      <SosOfferCard
        offer={offer({ issueDescription: null, issuePhotos: undefined as unknown as SosIssuePhoto[] })}
        onRespondAvailable={vi.fn()}
        onDecline={vi.fn()}
      />,
    );

    expect(screen.getByText(/דיזנגוף, תל אביב-יפו/)).toBeInTheDocument();
  });
});
