import { useState } from 'react';
import { AlertCircle, FileText, ImageOff } from 'lucide-react';
import { ImageLightbox } from '../../shared/components';
import type { SosIssuePhoto } from '../../shared/api';
import styles from './SosIssueDetails.module.css';

export interface SosIssueDetailsProps {
  /** The customer's own words. `null` on an older payload, or if the issue could not be read. */
  description: string | null;
  /** Every photo on the anchoring issue. May be empty — plenty of emergencies have no photo. */
  photos: SosIssuePhoto[];
  /** `issueSummary` — the optional short headline. Rendered above the description when present,
   *  and deliberately never *instead* of it. */
  summary?: string | null;
}

/**
 * **What the professional is actually being asked to judge**, on both SOS surfaces: the customer's
 * description in their own words, and the photos they attached.
 *
 * ## Why this exists
 *
 * The professional's card showed `issueSummary` and a street name. `issueSummary` is an optional
 * 300-character headline that the customer app has never sent, so in practice a professional was
 * offered an emergency described to them as a location. The description and photos were stored the
 * whole time, one foreign key away on `sos_requests.issue_id`, and were simply never mapped into
 * the response.
 *
 * ## The description is the customer's, verbatim
 *
 * Never an AI summary, and never truncated to a headline. A classifier's one-line paraphrase is
 * what routed the job to this trade; it is not what tells a plumber whether to bring a pump. When
 * a `summary` is also present it is shown *above* the description as a headline, not in place of
 * it.
 *
 * ## Photos
 *
 * Thumbnails, each opening the existing `ImageLightbox` at full size — the same click-to-enlarge
 * component the profile screen uses, rather than a second viewer with its own behaviour.
 *
 * Every URL here is a short-lived presigned capability minted by the server for *this* response
 * (300s TTL), so a thumbnail whose URL has expired while the screen sat open renders its own
 * inline failure state rather than a broken-image glyph. That state is per-photo on purpose: one
 * dead URL must not hide the other five, and the surrounding card must stay usable — a professional
 * mid-emergency needs the address and the accept button far more than they need a thumbnail.
 */
export function SosIssueDetails({ description, photos, summary }: SosIssueDetailsProps) {
  const [openPhoto, setOpenPhoto] = useState<SosIssuePhoto | null>(null);
  /** Keyed by `imageKey`, not by index: the stable identity, so a re-render cannot move a
   *  failure marker onto a different photo. */
  const [failed, setFailed] = useState<Record<string, true>>({});

  if (!description && photos.length === 0 && !summary) {
    return null;
  }

  return (
    <section className={styles.wrapper} aria-label="פרטי התקלה">
      {summary && <p className={styles.summary}>“{summary}”</p>}

      {description && (
        <div className={styles.descriptionBlock}>
          <p className={styles.label}>
            <FileText size={14} aria-hidden="true" />
            תיאור הלקוח
          </p>
          {/* `white-space: pre-wrap` in the stylesheet, so the customer's own line breaks survive
              — they frequently list symptoms one per line. */}
          <p className={styles.description}>{description}</p>
        </div>
      )}

      {photos.length > 0 && (
        <div className={styles.photosBlock}>
          <p className={styles.label}>
            {photos.length === 1 ? 'תמונה שצירף הלקוח' : `${photos.length} תמונות שצירף הלקוח`}
          </p>
          <ul className={styles.photoStrip}>
            {photos.map((photo) => (
              <li key={photo.imageKey}>
                {failed[photo.imageKey] ? (
                  <span className={styles.photoFailed} role="img" aria-label="לא הצלחנו לטעון את התמונה">
                    <ImageOff size={18} aria-hidden="true" />
                    <span className={styles.photoFailedText}>לא נטען</span>
                  </span>
                ) : (
                  <button
                    type="button"
                    className={styles.photoButton}
                    onClick={() => setOpenPhoto(photo)}
                    aria-label="הגדלת התמונה שצירף הלקוח"
                  >
                    <img
                      src={photo.url}
                      alt=""
                      className={styles.photo}
                      loading="lazy"
                      onError={() => setFailed((prev) => ({ ...prev, [photo.imageKey]: true }))}
                    />
                  </button>
                )}
              </li>
            ))}
          </ul>
          {Object.keys(failed).length > 0 && (
            <p className={styles.photoError} role="status">
              <AlertCircle size={14} aria-hidden="true" />
              חלק מהתמונות לא נטענו. אפשר לרענן את המסך כדי לנסות שוב.
            </p>
          )}
        </div>
      )}

      <ImageLightbox
        isOpen={openPhoto !== null}
        onClose={() => setOpenPhoto(null)}
        imageUrl={openPhoto?.url ?? ''}
        alt="התמונה שצירף הלקוח"
      />
    </section>
  );
}
