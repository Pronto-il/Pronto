import { useContext } from 'react';
import { BookingDraftContext, type BookingDraftContextValue } from './bookingDraftContext';

export function useBookingDraft(): BookingDraftContextValue {
  const context = useContext(BookingDraftContext);
  if (!context) {
    throw new Error('useBookingDraft must be used within a BookingDraftProvider');
  }
  return context;
}
