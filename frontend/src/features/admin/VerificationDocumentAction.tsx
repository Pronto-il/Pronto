import { useState } from 'react';
import { FileText } from 'lucide-react';
import { Button } from '../../shared/components';
import { getVerificationDocumentUrl, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import styles from './VerificationDocumentAction.module.css';

export interface VerificationDocumentActionProps {
  professionalId: number;
  /** From the review response. When false there is nothing to open and no request is made. */
  hasVerificationDocument: boolean;
}

/**
 * Opening a professional's verification document — the one private compliance artifact this
 * package touches, and the reason it gets a component of its own rather than a line of JSX on the
 * review screen.
 *
 * Three rules, all deliberate:
 *
 * 1. **Deliberate action, never automatic.** The URL is minted only when an operator clicks. The
 *    backend keeps it off the review response for exactly this reason: browsing the queue must
 *    not mint a bearer capability for a stranger's identity document, and an audit line
 *    ("operator X viewed professional Y's document") is only meaningful if it corresponds to
 *    someone actually looking.
 * 2. **The URL is never held or rendered.** It is not put in React state, not rendered as an
 *    `<a href>`, not embedded in an `<img>`/`<iframe>`, and never logged. It exists as a local
 *    variable for the length of one `window.open` call. Anyone holding it can fetch the document
 *    without authenticating until it expires, so it is treated as a secret — including keeping it
 *    out of the DOM, where it would land in any screenshot, screen share or DOM dump of this
 *    screen.
 * 3. **A new tab, not this one.** The document does not render inside the review screen: an
 *    identity document permanently displayed beside the approve button is a document sitting in
 *    every screenshot of the review screen.
 *
 * `noopener,noreferrer` keeps the opened tab from reaching back through `window.opener` and stops
 * the URL travelling on as a `Referer` header. A consequence worth stating, because it was
 * originally got wrong here: **`window.open` returns `null` whenever `noopener`/`noreferrer` is
 * passed**, by specification, so its return value cannot be used to detect a blocked popup — an
 * earlier version of this component treated that `null` as "blocked" and showed a false error on
 * every successful open. Detection is dropped rather than the protection: the request runs inside
 * the click's transient user activation (a same-machine round trip of tens of milliseconds), so
 * the open is not blocked in practice, and the hint below tells an operator what to allow if their
 * browser is configured unusually strictly.
 */
export function VerificationDocumentAction({
  professionalId,
  hasVerificationDocument,
}: VerificationDocumentActionProps) {
  const [isOpening, setIsOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!hasVerificationDocument) {
    return <p className={styles.missing}>לא צורף מסמך אימות לבקשה הזו.</p>;
  }

  async function handleOpen() {
    setError(null);
    setIsOpening(true);
    try {
      const response = await getVerificationDocumentUrl(professionalId);
      window.open(response.url, '_blank', 'noopener,noreferrer');
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 404) {
        setError('לא נמצא מסמך אימות עבור בעל המקצוע הזה.');
      } else if (caught instanceof ApiError && caught.status === 403) {
        setError('אין לך הרשאה לצפות במסמך הזה.');
      } else {
        setError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsOpening(false);
    }
  }

  return (
    <div className={styles.wrapper}>
      <Button variant="secondary" onClick={handleOpen} loading={isOpening}>
        <span className={styles.buttonLabel}>
          <FileText size={18} aria-hidden="true" />
          פתיחת מסמך האימות
        </span>
      </Button>
      <p className={styles.hint}>
        המסמך נפתח בלשונית חדשה, ולכן יש לאפשר חלונות קופצים לאתר הזה. הקישור תקף לזמן קצר בלבד
        ואינו נשמר במסך הזה.
      </p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
