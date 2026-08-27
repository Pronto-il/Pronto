import { httpClient } from './httpClient';
import type { UploadOptions } from './httpClient';

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
export function uploadImage(file: File, options?: UploadOptions): Promise<UploadImageResponse> {
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
