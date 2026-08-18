import { useEffect, useState, type ReactNode } from 'react';
import { useAuth } from './useAuth';
import { BookingDraftContext, type BookingDraft } from './bookingDraftContext';

const DRAFT_STORAGE_KEY = 'pronto_booking_draft';

function readStoredDraft(): BookingDraft | null {
  try {
    const raw = localStorage.getItem(DRAFT_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as BookingDraft;
    if (parsed.version !== 2) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/**
 * Holds the current booking draft (issue-creation + booking-flow in-progress state) in React
 * context, persisted to `localStorage`. Nested inside `AuthProvider` in `App.tsx` so it can
 * call `useAuth()` for the cross-account leakage guard below.
 */
export function BookingDraftProvider({ children }: { children: ReactNode }) {
  const { user, isLoading: isAuthLoading } = useAuth();
  const [draft, setDraft] = useState<BookingDraft | null>(() => readStoredDraft());

  // Cross-account leakage guard (§4.6): localStorage isn't inherently user-scoped, so if the
  // session's user doesn't own the draft found there (logout, or a different account logging
  // in on the same browser), clear it automatically. Skipped while auth is still rehydrating
  // (`isAuthLoading`) so a legitimate draft isn't wiped before `user` has a chance to resolve.
  useEffect(() => {
    if (isAuthLoading || !draft) {
      return;
    }
    if (!user || user.id !== draft.ownerId) {
      setDraft(null);
      localStorage.removeItem(DRAFT_STORAGE_KEY);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, isAuthLoading]);

  function updateDraft(patch: Partial<Omit<BookingDraft, 'version' | 'updatedAt' | 'ownerId'>>) {
    if (!user) {
      return;
    }
    setDraft((prev) => {
      const base: BookingDraft =
        prev ??
        ({
          version: 2,
          ownerId: user.id,
          stage: 'ISSUE_DESCRIBE',
          urgencyType: 'STANDARD',
          description: '',
          photos: [],
          updatedAt: new Date().toISOString(),
        } satisfies BookingDraft);
      const next: BookingDraft = { ...base, ...patch, updatedAt: new Date().toISOString() };
      localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  }

  function clearDraft() {
    setDraft(null);
    localStorage.removeItem(DRAFT_STORAGE_KEY);
  }

  return (
    <BookingDraftContext.Provider value={{ draft, updateDraft, clearDraft }}>
      {children}
    </BookingDraftContext.Provider>
  );
}
