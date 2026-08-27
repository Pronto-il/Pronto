import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  computeTargetSize,
  isCompressionCandidate,
  JPEG_QUALITY,
  MAX_IMAGE_EDGE_PX,
  prepareImageForUpload,
  SKIP_BELOW_BYTES,
} from './imageCompression';

/**
 * The upload-side image optimisation, and specifically the property that makes it safe to ship:
 * **it degrades to the previous behaviour instead of failing.**
 *
 * The interesting risk here is not "does it resize correctly" — it is that this code runs on
 * the widest and least knowable hardware/OS surface in the product (every customer's phone),
 * inside the one flow where a thrown exception costs the business a job request. So most of
 * what is pinned down below is the set of paths that must yield the caller's original `File`
 * untouched, so that the backend applies exactly the validation it always did.
 */

const originalCreateImageBitmap = globalThis.createImageBitmap;

afterEach(() => {
  vi.unstubAllGlobals();
  globalThis.createImageBitmap = originalCreateImageBitmap;
});

/** A `File` of a given type and byte length, with no real image bytes inside it. */
function fakeFile(bytes: number, type = 'image/jpeg', name = 'IMG_0001.jpg'): File {
  return new File([new Uint8Array(bytes)], name, { type });
}

describe('computeTargetSize', () => {
  it('returns null when the image already fits, so callers can skip the re-encode entirely', () => {
    expect(computeTargetSize(1600, 1200)).toBeNull();
    expect(computeTargetSize(800, 600)).toBeNull();
    // Exactly at the bound is "fits" — not one pixel of resampling for nothing.
    expect(computeTargetSize(MAX_IMAGE_EDGE_PX, MAX_IMAGE_EDGE_PX)).toBeNull();
  });

  it('scales the long edge to the cap and preserves aspect ratio, whichever edge is longer', () => {
    // The real iPhone 17 photo from the production bucket, in its EXIF-corrected portrait form.
    expect(computeTargetSize(2160, 3840)).toEqual({ width: 900, height: 1600 });
    // The same frame landscape.
    expect(computeTargetSize(3840, 2160)).toEqual({ width: 1600, height: 900 });
    // The real 12.2 MP photo.
    expect(computeTargetSize(3024, 4032)).toEqual({ width: 1200, height: 1600 });
    // The real 22.5 MP Canon frame.
    expect(computeTargetSize(3872, 5808)).toEqual({ width: 1067, height: 1600 });
  });

  it('never produces a zero-length edge from an extreme panorama', () => {
    // 20000x3 scaled by 1600/20000 rounds the short edge to 0, and a zero-width canvas throws.
    const target = computeTargetSize(20000, 3);
    expect(target).toEqual({ width: 1600, height: 1 });
  });

  it('honours an explicit maxEdge override', () => {
    expect(computeTargetSize(4000, 2000, 1000)).toEqual({ width: 1000, height: 500 });
  });
});

describe('isCompressionCandidate', () => {
  it('accepts the three types the backend already allows, when they are big enough to matter', () => {
    const big = SKIP_BELOW_BYTES + 1;
    expect(isCompressionCandidate(fakeFile(big, 'image/jpeg'))).toBe(true);
    expect(isCompressionCandidate(fakeFile(big, 'image/png'))).toBe(true);
    expect(isCompressionCandidate(fakeFile(big, 'image/webp'))).toBe(true);
  });

  it('leaves small files alone — a re-encode cannot pay for itself and often grows them', () => {
    expect(isCompressionCandidate(fakeFile(SKIP_BELOW_BYTES, 'image/jpeg'))).toBe(false);
    expect(isCompressionCandidate(fakeFile(1024, 'image/jpeg'))).toBe(false);
  });

  it('passes HEIC/HEIF straight through rather than pretending to handle it', () => {
    const big = SKIP_BELOW_BYTES + 1;
    expect(isCompressionCandidate(fakeFile(big, 'image/heic', 'IMG_0002.heic'))).toBe(false);
    expect(isCompressionCandidate(fakeFile(big, 'image/heif', 'IMG_0002.heif'))).toBe(false);
    // An empty type is what some Android pickers hand over; it must not be re-encoded blind.
    expect(isCompressionCandidate(fakeFile(big, ''))).toBe(false);
  });
});

describe('prepareImageForUpload', () => {
  it('returns the caller\'s exact File for a non-candidate, without touching the DOM', async () => {
    const createElement = vi.spyOn(document, 'createElement');
    const small = fakeFile(1024);

    const result = await prepareImageForUpload(small);

    expect(result.file).toBe(small);
    expect(result.compressed).toBe(false);
    expect(createElement).not.toHaveBeenCalled();
  });

  it('falls back to the original when the browser cannot decode the file', async () => {
    // The shape of every real failure worth surviving: a corrupt file, an exotic colour
    // profile, a codec the handset does not have. None of them may reject.
    globalThis.createImageBitmap = vi
      .fn()
      .mockRejectedValue(new Error('The source image could not be decoded.'));
    const file = fakeFile(SKIP_BELOW_BYTES + 1);

    const result = await prepareImageForUpload(file);

    expect(result.file).toBe(file);
    expect(result.compressed).toBe(false);
  });

  it('releases the decoded bitmap even when the encode step fails', async () => {
    // Six leaked 22 MP bitmaps is a tab crash, so the release must not be on the happy path only.
    const close = vi.fn();
    globalThis.createImageBitmap = vi.fn().mockResolvedValue({ width: 4032, height: 3024, close });
    vi.spyOn(document, 'createElement').mockImplementation(() => {
      throw new Error('canvas unavailable');
    });

    const result = await prepareImageForUpload(fakeFile(SKIP_BELOW_BYTES + 1));

    expect(result.compressed).toBe(false);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('does not re-encode an image that is already within the edge budget', async () => {
    const close = vi.fn();
    globalThis.createImageBitmap = vi.fn().mockResolvedValue({ width: 1200, height: 900, close });
    const createElement = vi.spyOn(document, 'createElement');
    const file = fakeFile(SKIP_BELOW_BYTES + 1);

    const result = await prepareImageForUpload(file);

    expect(result.file).toBe(file);
    expect(result.compressed).toBe(false);
    expect(createElement).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('decodes with EXIF orientation applied, so portrait photos do not upload sideways', async () => {
    // Two of the three real production photos carry Orientation 6/8 (quarter turns).
    const close = vi.fn();
    const createImageBitmap = vi.fn().mockResolvedValue({ width: 1000, height: 800, close });
    globalThis.createImageBitmap = createImageBitmap;

    await prepareImageForUpload(fakeFile(SKIP_BELOW_BYTES + 1));

    expect(createImageBitmap).toHaveBeenCalledWith(expect.anything(), {
      imageOrientation: 'from-image',
    });
  });

  it('compresses an oversized photo to JPEG at the derived dimensions and quality', async () => {
    const close = vi.fn();
    globalThis.createImageBitmap = vi.fn().mockResolvedValue({ width: 4032, height: 3024, close });

    const drawImage = vi.fn();
    const fillRect = vi.fn();
    const toBlob = vi.fn((callback: (blob: Blob) => void) =>
      callback(new Blob([new Uint8Array(200 * 1024)], { type: 'image/jpeg' })),
    );
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage, fillRect, fillStyle: '' }),
      toBlob,
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas as unknown as HTMLElement);

    const result = await prepareImageForUpload(fakeFile(1_604_868, 'image/jpeg', 'IMG_0003.jpeg'));

    expect(result.compressed).toBe(true);
    expect(result.file.type).toBe('image/jpeg');
    expect(result.file.size).toBe(200 * 1024);
    // Extension follows the actual encoding rather than the source's.
    expect(result.file.name).toBe('IMG_0003.jpg');
    // The mocked bitmap is landscape (4032x3024), so the long edge is the width.
    expect(canvas.width).toBe(1600);
    expect(canvas.height).toBe(1200);
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 1600, 1200);
    // The white matte, so a transparent PNG does not encode its transparency as black.
    expect(fillRect).toHaveBeenCalledWith(0, 0, 1600, 1200);
    expect(toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/jpeg', JPEG_QUALITY);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('keeps the original when the re-encode came out no smaller', async () => {
    // Real for already-aggressively-compressed sources; uploading the bigger one would make
    // this optimisation a pessimisation.
    const close = vi.fn();
    globalThis.createImageBitmap = vi.fn().mockResolvedValue({ width: 4032, height: 3024, close });
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage: vi.fn(), fillRect: vi.fn(), fillStyle: '' }),
      toBlob: (callback: (blob: Blob) => void) =>
        callback(new Blob([new Uint8Array(700 * 1024)], { type: 'image/jpeg' })),
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas as unknown as HTMLElement);
    const file = fakeFile(600 * 1024);

    const result = await prepareImageForUpload(file);

    expect(result.file).toBe(file);
    expect(result.compressed).toBe(false);
  });
});
