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

  // Cross-account leakage guard (§4.6), now with the guest case that deferred authentication
  // introduced. localStorage isn't user-scoped, so the draft found there has to be matched
  // against the session — but "no session" and "a different session" are opposite situations
  // and the previous single condition (`!user || user.id !== draft.ownerId`) collapsed them:
  //
  //   guest draft + nobody signed in   KEEP.   This is the normal guest journey. Wiping here
  //                                            deleted the draft on every render before login.
  //   guest draft + somebody signs in  ADOPT.  That guest IS this user -- they just came back
  //                                            from the login screen we sent them to. Discarding
  //                                            their booking at the moment they did what we asked
  //                                            is the single worst thing this provider could do.
  //   owned draft + nobody signed in   CLEAR.  Logout. The draft belonged to a session that has
  //                                            ended.
  //   owned draft + a different user   CLEAR.  Account switch on a shared browser. The original
  //                                            leakage guard, unchanged.
  useEffect(() => {
    if (isAuthLoading || !draft) {
      return;
    }
    if (draft.ownerId === null) {
      if (user) {
        const adopted: BookingDraft = { ...draft, ownerId: user.id };
        setDraft(adopted);
        localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(adopted));
      }
      return;
    }
    if (!user || user.id !== draft.ownerId) {
      setDraft(null);
      localStorage.removeItem(DRAFT_STORAGE_KEY);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, isAuthLoading]);

  // No `if (!user) return` any more. That early return was what made the guest journey
  // impossible: a visitor could reach the booking screen but nothing they entered was ever
  // written down, so the login round trip lost everything. A guest draft is written with
  // `ownerId: null` and adopted by the effect above the moment they sign in.
  function updateDraft(patch: Partial<Omit<BookingDraft, 'version' | 'updatedAt' | 'ownerId'>>) {
    setDraft((prev) => {
      const base: BookingDraft =
        prev ??
        ({
          version: 2,
          ownerId: user ? user.id : null,
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
