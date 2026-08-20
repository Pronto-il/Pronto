# Mascot asset pipeline

Build-time tooling (Python, not part of the JS/TS app or its build) that turns the 4
official Pronto mascot renders into the transparent PNGs shipped from
`frontend/src/assets/mascot/`.

This is **not** a runtime dependency of the frontend — it is a one-time (re-runnable)
local script. Nothing here is imported by the React app.

## Why this exists

The 4 source renders in `source/` are "neon-sign" style artwork: a blue silhouette with a
white outline/keyline, glowing on a solid black background, no transparency, ~1.2MB each.
They need to become clean transparent PNGs before `Mascot.tsx` can use them, **without**
changing the mascot's actual brand color — `DESIGN_SYSTEM.md` never defines an official
mascot color, so there's no basis to recolor the supplied blue. Only the black
background/glow is removed; the blue fill and white outline ship exactly as supplied.

## Directory layout

```
frontend/tools/mascot/
  source/               original 4 PNGs, untouched, never overwritten by the script
  process_mascot.py     the pipeline (this is what you run)
  out/
    qa-contact-sheet.png   generated QA image: all 4 poses x 5 backgrounds
  README.md              this file
```

Shipped output goes to `frontend/src/assets/mascot/` (same base filenames as the
sources): `pronto-pointing.png`, `pronto-running-screwdriver.png`,
`pronto-running-wrench.png`, `pronto-success.png`.

## How to run it

```bash
pip install pillow        # numpy is assumed already available in the environment
cd frontend/tools/mascot
python process_mascot.py
```

The script is idempotent and safe to re-run any number of times:
- It always reads from `source/` (never from its own previous output).
- It overwrites its own outputs (`src/assets/mascot/*.png` and `out/qa-contact-sheet.png`)
  deterministically — running it twice in a row produces byte-identical files.

After running, open `out/qa-contact-sheet.png` and check, for each of the 4 poses:
- No black box or glow halo behind/around the figure on any of the 5 backgrounds.
- The white outline/keyline is continuous and clearly visible (check especially against
  the black and teal (`primary`) backgrounds, where a lost outline would be obvious).
- The blue hue looks the same as the original neon renders in `source/` (no shift toward
  teal/`--color-primary` or any other color).

## Algorithm

1. **Classify pixels** (`classify_masks`) into a `blue_mask` (saturated, blue-dominant —
   the character's fill color) and a `white_mask` (bright + achromatic — the outline/
   keyline). Both are threshold-based on chroma (saturation) and channel dominance, tuned
   against the actual source renders (see "Tuning" below). Critically, the soft glow halo
   around the figure never gets bright/achromatic enough to be misclassified as
   `white_mask` at these thresholds — it's a wide, slow gradient from black up to only a
   mid-tone before the outline itself takes over sharply, so simple thresholding already
   excludes almost all of it.
2. **Morphological closing** (`morphological_close`) on `blue_mask | white_mask`, with a
   small (5px) kernel. This exists only to bridge the handful of transitional/blended
   pixels sitting exactly between a blue-fill region and a white-outline region (anti-
   aliasing seams) into one hole-free alpha region. The kernel is deliberately small —
   much smaller than any real negative-space gap in the artwork (e.g. between the legs,
   or between an outstretched arm and the torso) — so it never accidentally fills in
   background glow that happens to sit between two separate parts of the figure.
3. **Anti-alias** (`build_alpha`): the hard binary mask from step 2 is Gaussian-blurred
   and then remapped through a steep levels ramp, producing a clean, smooth alpha edge.
   (The source PNGs' own edge alpha can't be reused directly — it's glow-contaminated.)
4. **Color decontamination** (`extend_colors`): pushes opaque RGB outward a few pixels
   into the now-transparent region. This does not touch alpha or recolor anything visible
   — it only prevents the original black background RGB from bleeding into
   semi-transparent edge pixels the next time something does a non-premultiplied
   resize/blur (browsers, bundlers, this script's own downscale step). Without this step
   you get a faint dark fringe around the figure even though the alpha channel is
   perfectly correct.
5. **Shared autocrop + framing** (`crop_and_scale_all`): computes the bounding box of
   visible content for each of the 4 poses, takes the union of all 4 (plus padding), and
   crops **every** pose to that identical rectangle before scaling **all** of them by the
   same factor to a 512px-tall canvas. Because every pose ends up on the same coordinate
   grid, none of them jump vertically or horizontally when the UI swaps between mascot
   states/poses.
6. **Palettization** (`build_frequency_palette` / `save_shipped_asset`): PNG output size
   is reduced by building a palette from the ~255 most frequent exact colors in the image
   (this always includes the flat blue fill, the flat white outline, and full
   transparency, by a wide count margin) and nearest-matching only the long tail of rare,
   already-blended anti-aliasing colors onto that palette.

   Pillow's built-in `Image.quantize(method=Image.Quantize.FASTOCTREE)` was tried first
   and rejected: across repeated runs on identical input it was **not deterministic**,
   and on some runs it measurably shifted the dominant blue-fill color (observed: source
   `(7, 54, 141)` came out as `(0, 43, 133)` — a visible hue shift). That's not acceptable
   for a brand asset, so this script builds the palette manually instead, which is fully
   deterministic and guarantees the dominant/majority colors are preserved exactly.
   `save_shipped_asset` also validates this at save time (dominant opaque color must be
   byte-identical to the pre-palette image, alpha error must stay under a small
   tolerance) and falls back to a plain truecolor RGBA PNG if that check ever fails on
   future source art.
7. **QA contact sheet** (`build_contact_sheet`): composites all 4 processed assets over 5
   backgrounds (white, `#f7f8fa`, `#E8F5F3`, `#0f766e`, black) in a grid, written to
   `out/qa-contact-sheet.png`, so a glow halo or lost keyline detail is obvious at a
   glance without opening each PNG individually.

## Tuning

All thresholds live as module-level constants at the top of `process_mascot.py`
(`BLUE_DOMINANCE_THRESH`, `BLUE_SAT_THRESH`, `WHITE_VAL_THRESH`, `CLOSE_KERNEL_PX`,
`BLUR_SIGMA`, `LEVELS_LOW`/`LEVELS_HIGH`, `TARGET_HEIGHT_PX`, etc.), each with a short
comment on what it controls. If a future source render (different lighting/contrast in
the neon effect) produces a bad contact sheet:

- **Residual glow / halo visible**: tighten `WHITE_VAL_THRESH` upward (require pixels to
  be brighter to count as outline) and/or `BLUE_SAT_THRESH` upward.
- **Outline looks broken/dotted**: increase `CLOSE_KERNEL_PX` slightly (stay well under
  the narrowest real gap in the art, e.g. between fingers) or loosen `WHITE_SAT_THRESH`.
- **Jagged/hard edge**: increase `BLUR_SIGMA` slightly, or widen the gap between
  `LEVELS_LOW` and `LEVELS_HIGH`.
- **File size out of the ~30-60KB target range**: adjust `PALETTE_MAX_COLORS` in
  `build_frequency_palette` (max 255 for a `P`-mode PNG).

After changing any constant, just re-run `python process_mascot.py` and re-check
`out/qa-contact-sheet.png`.

## Adding a new mascot pose in the future

1. Drop the new neon-render source PNG into `source/` (same style: blue silhouette +
   white outline on solid black, no transparency).
2. Add its filename to the `POSES` list near the top of `process_mascot.py`.
3. Re-run `python process_mascot.py`. The new pose is automatically included in the
   shared union-bbox framing (step 5 above) so it stays baseline-aligned with the
   existing 4 poses, and in the QA contact sheet.
4. Wire the new physical pose into `frontend/src/shared/components/Mascot.tsx`'s pose
   mapping (see that component's own docs) if it's meant to back a new/existing semantic
   `MascotState`.
