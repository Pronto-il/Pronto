/**
 * Client-side downscale + re-encode for photos picked from a phone's camera or gallery,
 * applied immediately before `POST /api/storage/images`.
 *
 * ## Why this exists
 *
 * Nothing used to touch the picked `File` — the raw camera original went straight into a
 * `FormData` and up the wire. Measured against the three photos actually sitting in the
 * production uploads bucket, that meant sending 1.53 MB / 2.05 MB / 4.35 MB per photo, at
 * 12.2 MP / 8.3 MP / 22.5 MP respectively, over a phone's *uplink* — the slow direction —
 * with `maxCount` allowing six of them per issue. Production has already recorded the
 * predictable outcome: a `ClientAbortException: EOFException` on `/api/storage/images`,
 * which is Tomcat's way of saying the handset stopped sending partway through.
 *
 * ## Why these numbers
 *
 * {@link MAX_IMAGE_EDGE_PX} is derived from what Pronto actually does with an issue photo,
 * not from a general-purpose guess:
 *
 * - **Display.** Every render site is an 88x88 CSS-pixel thumbnail (`PhotoUploader`,
 *   `IncomingRequestCard`, `OrderDetailsCard`). Even at a 3x device pixel ratio that is 264
 *   device pixels.
 * - **Enlargement headroom.** `ImageLightbox` opens at `90vw`/`90vh`. On the largest phone
 *   viewport at 3x that is roughly 1170x2280 device pixels, so a 1600px long edge still has
 *   room to spare if issue photos are ever wired to it (they are not today).
 * - **The AI reader.** The photo's other consumer is OpenAI vision, which `IssueImageResolver`
 *   feeds by base64-ing the stored bytes inline. That pipeline resizes to at most a 2048px
 *   long edge and ~768px short edge before the model ever sees it, so pixels beyond ~1600
 *   are discarded upstream — they cost upload time and prompt bytes and buy no accuracy.
 *
 * 1600 is the smallest value that satisfies all three without being the binding constraint on
 * any of them. {@link JPEG_QUALITY} at 0.82 measured ~93% smaller across those same three real
 * photos while staying above the point where JPEG ringing starts showing up on the fine
 * texture (corrosion, hairline cracks, scorch marks) that a tradesperson is being asked to
 * judge.
 *
 * ## What this deliberately does not do
 *
 * - **No security relaxation.** Output is always `image/jpeg`, already one of the three types
 *   `storage.ImageContentType` accepts. The backend's MIME check, its 8 MB cap and the
 *   private-bucket model are untouched, and every failure path below falls back to uploading
 *   the *original* file, which then meets exactly the validation it met before.
 * - **No HEIC decoding.** See {@link prepareImageForUpload}.
 */

/** Longest edge, in pixels, of an uploaded issue photo. See this module's header for the derivation. */
export const MAX_IMAGE_EDGE_PX = 1600;

/** `canvas.toBlob` quality for the re-encode. See this module's header for the derivation. */
export const JPEG_QUALITY = 0.82;

/**
 * Below this, an image is left alone even if it is slightly over {@link MAX_IMAGE_EDGE_PX}.
 *
 * Re-encoding a file this small cannot save enough wire time to pay for the decode, the
 * full-frame canvas allocation and the generational garbage it makes on a mid-range handset —
 * and a re-encode of an already-compressed small JPEG frequently comes out *larger*. The
 * "resize only when needed" half of the brief.
 */
export const SKIP_BELOW_BYTES = 512 * 1024;

/**
 * The types worth putting through a canvas round-trip. Anything else — including a HEIC that
 * slipped past the file input's `accept` list — is passed through untouched, so the backend
 * applies precisely the validation it always did.
 */
const COMPRESSIBLE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export interface PreparedImage {
  /** What to upload. The original `File` whenever compression was skipped or failed. */
  file: File;
  /** False when {@link file} is the caller's original, for logging/telemetry at the call site. */
  compressed: boolean;
}

/**
 * The scale-to-fit calculation, split out because it is the one piece of this module with
 * interesting arithmetic and the only piece testable without a real canvas.
 *
 * Returns `null` when the image already fits, which the caller treats as "nothing to do" —
 * distinct from a computed size that happens to equal the input.
 */
export function computeTargetSize(
  width: number,
  height: number,
  maxEdge: number = MAX_IMAGE_EDGE_PX,
): { width: number; height: number } | null {
  const longest = Math.max(width, height);
  if (longest <= maxEdge) {
    return null;
  }
  const scale = maxEdge / longest;
  return {
    // `max(1, ...)` guards the degenerate panorama case: a 20000x3 source scaled by
    // 1600/20000 rounds the short edge to 0, and a zero-dimension canvas throws.
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

/**
 * Whether {@link prepareImageForUpload} will leave this file alone without even decoding it.
 * Exported for the test suite and for callers that want to skip a "compressing" UI state they
 * know will not happen.
 */
export function isCompressionCandidate(file: File): boolean {
  return COMPRESSIBLE_TYPES.has(file.type) && file.size > SKIP_BELOW_BYTES;
}

/** Swaps whatever extension a file has for `.jpg`, since the re-encode is always JPEG. */
function toJpegFilename(name: string): string {
  const withoutExtension = name.replace(/\.[^./\\]+$/, '');
  return `${withoutExtension || 'photo'}.jpg`;
}

/**
 * Decodes to something drawable, with EXIF orientation already applied.
 *
 * Both branches produce upright pixels, which matters more than it looks: two of the three
 * real production photos carry a non-default EXIF `Orientation` (6 and 8, i.e. quarter turns).
 * Drawing raw pixel data to a canvas without honouring that tag would silently rotate every
 * portrait photo in the marketplace 90 degrees.
 *
 * - `createImageBitmap` is asked explicitly for `imageOrientation: 'from-image'`. Browsers
 *   that predate the option ignore the unknown dictionary member rather than throwing.
 * - The `<img>` fallback gets orientation for free: `image-orientation: from-image` is the
 *   CSS initial value everywhere this app runs.
 */
async function decode(file: File): Promise<{
  source: CanvasImageSource;
  width: number;
  height: number;
  release: () => void;
}> {
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return {
      source: bitmap,
      width: bitmap.width,
      height: bitmap.height,
      // Not hygiene-for-its-own-sake: six undisposed 22 MP bitmaps is ~500 MB of backing
      // store, which is a tab crash on a mid-range phone rather than a slow leak.
      release: () => bitmap.close(),
    };
  }

  const objectUrl = URL.createObjectURL(file);
  try {
    const image = await new Promise<HTMLImageElement>((resolve, reject) => {
      const element = new Image();
      element.onload = () => resolve(element);
      element.onerror = () => reject(new Error('decode failed'));
      element.src = objectUrl;
    });
    return {
      source: image,
      width: image.naturalWidth,
      height: image.naturalHeight,
      release: () => URL.revokeObjectURL(objectUrl),
    };
  } catch (error) {
    URL.revokeObjectURL(objectUrl);
    throw error;
  }
}

function encode(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
}

/**
 * Downscales and re-encodes `file` when that is worth doing, and returns the original
 * untouched when it is not.
 *
 * **This function never rejects.** Every failure mode — an undecodable file, a browser with no
 * canvas, an out-of-memory `toBlob`, a re-encode that came out bigger than the source — resolves
 * to the original `File`. A photo that uploads slowly is a performance problem; a photo that
 * cannot be attached at all because an optimisation threw is a broken issue report, and this
 * path runs on hardware and OS versions that cannot be enumerated in advance.
 *
 * **HEIC/HEIF is intentionally not handled here.** The `accept="image/jpeg,image/png,image/webp"`
 * list on the file input already makes iOS transcode on the way out of the picker, and the
 * production bucket confirms it end to end: the iPhone 17 photo in there arrived as a JPEG,
 * with iOS 26.6 in its EXIF `Software` tag, from a handset that stores HEIC natively. Shipping
 * a HEIC decoder would add a WASM payload to every session to convert a format the browser has
 * never actually been handed. If one ever does arrive, {@link COMPRESSIBLE_TYPES} passes it
 * straight through and the backend rejects it with `UNSUPPORTED_IMAGE_TYPE` — the same answer
 * as today, not a regression.
 */
export async function prepareImageForUpload(file: File): Promise<PreparedImage> {
  if (!isCompressionCandidate(file)) {
    return { file, compressed: false };
  }

  let release = () => {};
  try {
    const decoded = await decode(file);
    release = decoded.release;

    const target = computeTargetSize(decoded.width, decoded.height);
    if (!target) {
      // Already within the edge budget. It is over SKIP_BELOW_BYTES, so a re-encode might
      // still shrink it, but not by enough to justify degrading a photo the customer chose.
      return { file, compressed: false };
    }

    const canvas = document.createElement('canvas');
    canvas.width = target.width;
    canvas.height = target.height;
    const context = canvas.getContext('2d');
    if (!context) {
      return { file, compressed: false };
    }
    // JPEG has no alpha. Without this, a transparent PNG's transparent pixels encode as
    // black; white matches every surface the thumbnails sit on.
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, target.width, target.height);
    context.drawImage(decoded.source, 0, 0, target.width, target.height);

    const blob = await encode(canvas, JPEG_QUALITY);
    // Larger output is a real outcome for small or already-aggressively-compressed sources;
    // uploading it would make this optimisation a pessimisation.
    if (!blob || blob.size >= file.size) {
      return { file, compressed: false };
    }

    return {
      file: new File([blob], toJpegFilename(file.name), {
        type: 'image/jpeg',
        lastModified: file.lastModified,
      }),
      compressed: true,
    };
  } catch {
    return { file, compressed: false };
  } finally {
    release();
  }
}
