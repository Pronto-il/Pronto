import { useNavigate } from 'react-router-dom';
import { Clock, X } from 'lucide-react';
import { useBookingDraft, resolveDraftRoute } from '../shared/hooks';
import styles from './BookingDraftIndicator.module.css';

/**
 * Persistent nav indicator for an in-progress booking draft (`ms3-ms4-corrections-design.md`
 * §4.6). Rendered inside `AppLayout`'s nav, conditional on a draft existing — naturally never
 * true for a `PROFESSIONAL` session (issue creation/booking are CUSTOMER-only routes) and
 * further protected by `BookingDraftProvider`'s ownerId guard against stale cross-account
 * drafts. Clicking the body navigates to the resume route; the dismiss icon discards the
 * draft directly (no confirmation dialog, per §4.5.1's explicit MVP-simplicity call).
 */
export function BookingDraftIndicator() {
  const navigate = useNavigate();
  const { draft, clearDraft } = useBookingDraft();

  if (!draft) {
    return null;
  }

  return (
    <div className={styles.wrapper}>
      <button type="button" className={styles.body} onClick={() => navigate(resolveDraftRoute(draft))}>
        <Clock size={16} aria-hidden="true" />
        <span>התקלה שלי</span>
      </button>
      <button type="button" className={styles.dismiss} onClick={clearDraft} aria-label="ביטול הבקשה הפעילה">
        <X size={14} aria-hidden="true" />
      </button>
    </div>
  );
}
