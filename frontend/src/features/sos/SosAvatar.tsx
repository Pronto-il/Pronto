import { useState } from 'react';

export interface SosAvatarProps {
  /** Presigned profile-image URL, or `null` when the professional has never uploaded one. */
  imageUrl: string | null;
  /** Drives the initials shown when there is no usable image. */
  fullName: string | null;
  /** The consumer's own CSS-module class for the `<img>`. */
  imageClassName: string;
  /** The consumer's own CSS-module class for the initials circle. */
  fallbackClassName: string;
}

/** First + last initial, or a single letter, or nothing — whatever the name actually supports. */
function initials(fullName: string | null): string {
  if (!fullName) {
    return '';
  }
  const parts = fullName.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '';
  }
  const first = parts[0][0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

/**
 * A professional's avatar across the SOS surfaces: their photo when there is one, their initials
 * when there is not — **including when the photo fails to load.**
 *
 * That last case is the reason this component exists rather than each surface inlining
 * `imageUrl ? <img> : <span>`. A profile image key can outlive the file it names (storage cleared,
 * a restore that missed the uploads directory, a key rewritten without the object being moved —
 * there is a professional in the current database in exactly that state). The URL is then non-null
 * and perfectly well-formed, so the ternary picks the `<img>` branch, the request 404s, and because
 * the image is decorative (`alt=""`, the name is already rendered beside it) the browser renders
 * *nothing at all*. Not a broken-image icon, not the initials — an empty circle. Which reads, to a
 * customer deciding who to let into their home, as a professional with something to hide.
 *
 * So a load failure is treated as "no usable image" and falls through to the same initials the
 * never-uploaded case gets. Reset on `imageUrl` change, so a professional who fixes their photo
 * mid-session is not stuck behind a stale failure.
 *
 * Sizing stays with the caller — each surface passes its own module classes — because the marker,
 * the card and the sheet want genuinely different dimensions and this component has no business
 * knowing about any of them.
 */
export function SosAvatar({ imageUrl, fullName, imageClassName, fallbackClassName }: SosAvatarProps) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const isUsable = imageUrl !== null && imageUrl !== failedUrl;

  if (isUsable) {
    return (
      <img
        src={imageUrl}
        alt=""
        className={imageClassName}
        onError={() => setFailedUrl(imageUrl)}
      />
    );
  }

  return (
    <span className={fallbackClassName} aria-hidden="true">
      {initials(fullName)}
    </span>
  );
}
