# shared/lib

## Purpose
Framework-agnostic browser helpers — the awkward parts of Web APIs, wrapped once so screens
and hooks call something with a defined contract instead of each re-deriving the failure modes.

## Responsibilities
- Wrap a browser capability that is asynchronous, permission-gated, or fails in more than one
  interesting way.
- Own the policy decisions that go with it (timeouts, accuracy thresholds, size budgets), as
  named exported constants rather than literals buried at a call site.
- Stay free of React and of `shared/api` — nothing here may import a component or issue a
  request. Consumers wire them together.

## Modules

### `geolocation.ts`
`navigator.geolocation` wrapped so it **always settles, within a bounded time, with a reason**.
Distinguishes the five ways a real fix fails (denied, unavailable, timed out, too coarse for
routing, too coarse for arrival) instead of collapsing them into one rejection, because the
professional standing in the street needs different advice for each. Accuracy budgets are
exported as `ROUTING_MAX_ACCURACY_METERS` / `ARRIVAL_MAX_ACCURACY_METERS`.

### `imageCompression.ts`
Client-side downscale + JPEG re-encode for camera/gallery photos, applied by
`shared/components/PhotoUploader` immediately before `POST /api/storage/images`.

**Why it exists.** Nothing used to touch the picked `File`. Measured against the photos actually
in the production uploads bucket, that meant sending 1.53 MB / 2.05 MB / 4.35 MB per photo
(12.2 MP / 8.3 MP / 22.5 MP) up a phone's *uplink*, six at a time, with no progress indicator.
Production had already logged the result: `ClientAbortException: EOFException` on
`/api/storage/images` — Tomcat's way of recording that the handset stopped sending partway
through.

**The two knobs, and where they come from.** `MAX_IMAGE_EDGE_PX = 1600` is derived from what
Pronto does with an issue photo, not from a general-purpose guess — every render site is an
88×88 CSS-pixel thumbnail; `ImageLightbox` would need ~1170 device pixels at 3× DPR; and the
photo's other consumer, OpenAI vision via `ai.service.IssueImageResolver`, downsamples to a
2048px long / ~768px short edge before the model sees anything. 1600 is the smallest value that
is not the binding constraint on any of the three. `JPEG_QUALITY = 0.82` measured ~93% smaller
across those same real photos while holding the fine texture (corrosion, hairline cracks) a
tradesperson is being asked to judge. `SKIP_BELOW_BYTES` is the "resize only when needed" half:
under it, a re-encode cannot repay its own decode and frequently comes out *larger*.

**It never rejects.** Undecodable file, no canvas, out-of-memory encode, output bigger than the
input — every path resolves to the caller's original `File`. This runs on the widest hardware
surface in the product, inside the one flow where a thrown exception costs a job request; a slow
photo is a performance problem, a photo that cannot be attached is a broken issue report.

**Security posture is unchanged.** Output is always `image/jpeg`, already one of the three types
`storage.ImageContentType` accepts. The backend's MIME check, its 8 MB cap and the private-bucket
model are untouched, and a passed-through original meets exactly the validation it met before.

**HEIC/HEIF is deliberately not decoded.** `PhotoUploader`'s
`accept="image/jpeg,image/png,image/webp"` already makes iOS transcode on the way out of the
picker, and the production bucket confirms it end to end — the iPhone 17 photo in there arrived
as JPEG with iOS 26.6 in its EXIF `Software` tag, from a handset that stores HEIC natively.
A HEIC decoder would ship a WASM payload to every session to convert a format the browser has
never been handed. One that somehow arrives is passed through and rejected by the backend with
`UNSUPPORTED_IMAGE_TYPE` — the same answer as before, not a regression.

**EXIF orientation is applied, not stripped-and-ignored.** Two of the three real production
photos carry a quarter-turn `Orientation` (6 and 8). Both decode paths produce upright pixels —
`createImageBitmap` is asked explicitly for `imageOrientation: 'from-image'`, and the `<img>`
fallback gets it from the CSS initial value — because drawing raw pixel data to a canvas without
honouring the tag would silently rotate every portrait photo in the marketplace.
