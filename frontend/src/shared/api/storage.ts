import { httpClient, getAuthToken } from './httpClient';
import type { UploadOptions } from './httpClient';
import { ensureGuestSessionToken } from './guestSession';
import { getGuestSessionToken } from './guestSessionStore';

export interface UploadImageResponse {
  imageKey: string;
  imageUrl: string;
  contentType: string;
  sizeBytes: number;
}

/**
 * `POST /api/storage/images` — uploads a single image and returns its storage key, later
 * passed to `classifyIssue`/`createIssue`. Multipart, single part named `file`, per
 * `docs/architecture/api-contract-issues.md` §2.3.
 *
 * Routed through `httpClient.upload` rather than `httpClient.post` so the caller can render a
 * real percentage: this is the only request in the app whose body is large enough, on the
 * connection that matters (a phone's uplink), for the difference to be visible. `options` is
 * optional and omitting it behaves exactly as the previous `post`-based implementation did.
 *
 * Callers should hand this an already-downscaled file — see
 * `shared/lib/imageCompression.ts`'s `prepareImageForUpload`. This function deliberately does
 * not compress on its own: `ImageUploadField`'s registration/profile-photo flows also live in
 * this api layer, and silently re-encoding every image every caller ever passes would be a
 * much wider behavioural change than the one being made here.
 */
export async function uploadImage(file: File, options?: UploadOptions): Promise<UploadImageResponse> {
  // Deferred authentication reaches the uploader. A guest describing a fault may attach photos
  // before they have an account, and the backend authorises those uploads against a guest session
  // token instead of a JWT -- same endpoint, same validation, same limits, same key template, only
  // a different owner namespace.
  //
  // Minted here, lazily, and only when there is no account: a visitor who never attaches a photo
  // never causes a session to exist, and a signed-in customer's upload is unchanged in every
  // respect (no extra call, no extra header beyond one they may already be carrying from an
  // earlier guest leg of this same journey -- see `httpClient`'s GUEST_SESSION_HEADER note).
  if (!getAuthToken() && !getGuestSessionToken()) {
    await ensureGuestSessionToken();
  }
  const formData = new FormData();
  formData.append('file', file);
  return httpClient.upload<UploadImageResponse>('/api/storage/images', formData, options);
}

export interface PresignedImageUrlEntry {
  imageKey: string;
  imageUrl: string;
}

export interface PresignedImageUrlsResponse {
  images: PresignedImageUrlEntry[];
}

/**
 * `POST /api/storage/images/presigned-urls` — batch re-resolves already-known image keys
 * into fresh presigned URLs (each valid for the standard TTL, see backend MS9 design
 * §6/§12). Used exclusively by `NewIssuePage`'s draft-resume flow: a paused draft only ever
 * persists `imageKey`s (never a URL, see `bookingDraftContext.ts`'s `BookingDraftPhoto`),
 * so this is how a resumed draft's photos become displayable again. May return fewer
 * entries than `imageKeys.length` requested — a missing entry means that key could not be
 * resolved (e.g. a corrupted/stale draft) and is dropped from the resulting `photos` state,
 * not treated as a hard error. See design doc §12.5.
 */
export function getPresignedImageUrls(imageKeys: string[]): Promise<PresignedImageUrlsResponse> {
  if (imageKeys.length === 0) {
    return Promise.resolve({ images: [] }); // avoid a pointless round trip on a photo-less draft
  }
  return httpClient.post<PresignedImageUrlsResponse>('/api/storage/images/presigned-urls', { imageKeys });
}
