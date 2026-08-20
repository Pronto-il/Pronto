"""
Pronto mascot asset pipeline (MS1 sequencing step 9).

Converts the 4 "neon-sign" source renders (blue silhouette + white outline/
keyline on a solid black background with a soft glow halo, no transparency)
into clean transparent PNGs suitable for shipping in the web app.

What this script does NOT do (by design, per the approved MS1 plan):
    - It does not recolor the mascot. Blue-fill and white-outline pixels keep
      their original RGB exactly as supplied in the source files. Only the
      alpha channel changes (the black background + glow halo is removed).
    - It does not redraw/restyle the illustration itself.
    - It never touches/overwrites the 4 original source files in `source/`.

Pipeline (see README.md for the full write-up and how to re-run/tune this):
    1. Classify every pixel as "blue fill", "white outline/keyline", or
       neither (background / soft glow halo) using chroma + blue-dominance
       and achromatic-brightness heuristics.
    2. Morphological closing (small kernel) on the union of the two masks to
       bridge the handful of transitional pixels between blue fill and white
       outline into one solid, hole-free alpha region. The kernel is
       intentionally small so it bridges anti-aliasing seams only -- it must
       NOT bridge real negative space (e.g. the gap between the legs, or
       between an outstretched arm and the torso), which is many times wider
       than the seam it needs to close.
    3. Anti-alias the resulting hard-edged mask (small Gaussian blur + a
       levels remap) so the cut-out edge is clean rather than jagged, since
       the source PNGs' own edge alpha is glow-contaminated and unusable
       as-is.
    4. Extend (push) opaque colors a few pixels into the fully-transparent
       region ("color decontamination"). This does not change the alpha
       mask; it only prevents the black background RGB from bleeding into
       semi-transparent edge pixels the next time a non-premultiplied
       resize/blur happens (browsers, bundlers, this very script) -- without
       it you get a faint dark halo around the figure even though the alpha
       channel is technically correct.
    5. Autocrop: compute the union bounding box of visible content across
       all 4 poses (with padding), then crop *all 4* images to that same
       rectangle and resize them all by the same scale factor. Because every
       pose shares the same crop box and scale, they end up on an identical
       coordinate grid, so the character doesn't jump vertically or
       horizontally when the UI swaps between mascot poses/states.
    6. Emit the processed, transparent, original-color assets to
       `frontend/src/assets/mascot/` and a QA contact sheet (each pose over
       5 different backgrounds) to `frontend/tools/mascot/out/`.

Usage:
    pip install pillow          # numpy is assumed already available
    python process_mascot.py

Re-runnable / idempotent: always reads from `source/` and overwrites its own
outputs, so parameters below can be tuned and the script re-run freely.
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
SOURCE_DIR = SCRIPT_DIR / "source"
OUT_DIR = SCRIPT_DIR / "out"
SHIPPED_DIR = SCRIPT_DIR.parent.parent / "src" / "assets" / "mascot"

POSES = [
    "pronto-pointing.png",
    "pronto-running-screwdriver.png",
    "pronto-running-wrench.png",
    "pronto-success.png",
]

# ---------------------------------------------------------------------------
# Tunable parameters (see README.md "Tuning" section)
# ---------------------------------------------------------------------------

# Step 1: pixel classification
BLUE_DOMINANCE_THRESH = 35    # B - max(R, G) must exceed this (0-255 scale)
BLUE_SAT_THRESH = 0.45        # (max-min)/max must exceed this
BLUE_VAL_THRESH = 0.25        # max(R,G,B)/255 must exceed this (drop near-black noise)

WHITE_VAL_THRESH = 0.82       # max(R,G,B)/255 must exceed this
WHITE_SAT_THRESH = 0.12       # (max-min)/max must be under this (achromatic)

# Step 2: morphological closing -- bridges the ~1-3px transitional seam
# between blue fill and white outline. Must stay well below the smallest
# real negative-space gap in the artwork (tens of px) so it never bridges
# background glow between two separate limbs.
CLOSE_KERNEL_PX = 5

# Step 3: anti-alias (Gaussian blur + levels remap)
BLUR_SIGMA = 1.4
LEVELS_LOW = 0.32   # alpha below this (post-blur, 0-1) -> 0
LEVELS_HIGH = 0.68  # alpha above this -> 1

# Step 4: color decontamination -- extend opaque RGB into transparent area
COLOR_EXTEND_ITERATIONS = 8

# Step 5: autocrop + shared framing
CONTENT_PADDING_PX = 36     # padding added around the union bbox, source-res
TARGET_HEIGHT_PX = 512      # final shipped canvas height
AUTOCROP_ALPHA_THRESHOLD = 20  # 0-255; alpha above this counts as "content"

# QA contact sheet backgrounds (name, hex)
CONTACT_SHEET_BACKGROUNDS = [
    ("white", "#ffffff"),
    ("neutral-bg", "#f7f8fa"),
    ("primary-tint", "#E8F5F3"),
    ("primary", "#0f766e"),
    ("black", "#000000"),
]
CONTACT_SHEET_CELL_HEIGHT = 260
CONTACT_SHEET_PADDING = 24
CONTACT_SHEET_LABEL_HEIGHT = 28


# ---------------------------------------------------------------------------
# Step 1-4: build a clean transparent RGBA from one source render
# ---------------------------------------------------------------------------

def classify_masks(rgb: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Return (blue_mask, white_mask) boolean arrays for an HxWx3 uint8 image."""
    arr = rgb.astype(np.float32)
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    mx = arr.max(axis=2)
    mn = arr.min(axis=2)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)
    val = mx / 255.0

    blue_mask = (
        (b - np.maximum(r, g) > BLUE_DOMINANCE_THRESH)
        & (sat > BLUE_SAT_THRESH)
        & (val > BLUE_VAL_THRESH)
    )
    white_mask = (val > WHITE_VAL_THRESH) & (sat < WHITE_SAT_THRESH)
    return blue_mask, white_mask


def morphological_close(mask: np.ndarray, kernel_px: int) -> np.ndarray:
    """Binary closing (dilate then erode) via PIL rank filters -- no scipy dep."""
    im = Image.fromarray((mask * 255).astype(np.uint8), mode="L")
    k = kernel_px if kernel_px % 2 == 1 else kernel_px + 1
    dilated = im.filter(ImageFilter.MaxFilter(k))
    closed = dilated.filter(ImageFilter.MinFilter(k))
    return np.asarray(closed) > 127


def build_alpha(mask: np.ndarray) -> np.ndarray:
    """Anti-alias a hard binary mask into a smooth 0..255 alpha channel."""
    im = Image.fromarray((mask * 255).astype(np.uint8), mode="L")
    blurred = im.filter(ImageFilter.GaussianBlur(BLUR_SIGMA))
    a = np.asarray(blurred).astype(np.float32) / 255.0
    lo, hi = LEVELS_LOW, LEVELS_HIGH
    a = np.clip((a - lo) / (hi - lo), 0.0, 1.0)
    return (a * 255.0).round().astype(np.uint8)


def extend_colors(rgb: np.ndarray, known: np.ndarray, iterations: int) -> np.ndarray:
    """
    Push opaque RGB outward into the fully-transparent region so a later
    non-premultiplied blur/resize can't pull black background color into
    semi-transparent edge pixels (color decontamination / "color spread").

    Does not change alpha; only fills RGB where alpha is currently 0.
    """
    rgb = rgb.astype(np.float32).copy()
    known = known.copy()
    offsets = [(dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1) if not (dy == 0 and dx == 0)]

    for _ in range(iterations):
        unknown = ~known
        if not unknown.any():
            break
        sum_c = np.zeros_like(rgb)
        cnt = np.zeros(rgb.shape[:2], dtype=np.float32)
        for dy, dx in offsets:
            shifted_known = np.roll(np.roll(known, dy, axis=0), dx, axis=1)
            shifted_rgb = np.roll(np.roll(rgb, dy, axis=0), dx, axis=1)
            sum_c += shifted_rgb * shifted_known[..., None]
            cnt += shifted_known
        fillable = unknown & (cnt > 0)
        if not fillable.any():
            break
        avg = sum_c[fillable] / cnt[fillable][..., None]
        rgb[fillable] = avg
        known = known | fillable

    return rgb


def process_source(path: Path) -> Image.Image:
    """Run steps 1-4 on one source PNG, returning a full-resolution RGBA image."""
    src = Image.open(path).convert("RGB")
    rgb = np.asarray(src)

    blue_mask, white_mask = classify_masks(rgb)
    combined = blue_mask | white_mask
    closed = morphological_close(combined, CLOSE_KERNEL_PX)
    alpha = build_alpha(closed)

    out_rgb = extend_colors(rgb, alpha > 250, COLOR_EXTEND_ITERATIONS)
    out_rgb = np.clip(out_rgb, 0, 255).astype(np.uint8)

    rgba = np.dstack([out_rgb, alpha])
    return Image.fromarray(rgba, mode="RGBA")


# ---------------------------------------------------------------------------
# Step 5: shared autocrop + baseline alignment across all 4 poses
# ---------------------------------------------------------------------------

def content_bbox(rgba: Image.Image, threshold: int) -> tuple[int, int, int, int]:
    alpha = np.asarray(rgba)[..., 3]
    ys, xs = np.where(alpha > threshold)
    if len(xs) == 0:
        return 0, 0, rgba.width, rgba.height
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def union_bbox(boxes: list[tuple[int, int, int, int]]) -> tuple[int, int, int, int]:
    lefts, tops, rights, bottoms = zip(*boxes)
    return min(lefts), min(tops), max(rights), max(bottoms)


def crop_and_scale_all(images: list[Image.Image]) -> list[Image.Image]:
    """
    Crop every pose to the SAME rectangle (union bbox + padding) and resize
    all of them by the SAME scale factor, so all 4 outputs share one
    coordinate grid -- this is what keeps the character visually stable
    (no jump) when the UI swaps between mascot poses.
    """
    boxes = [content_bbox(im, AUTOCROP_ALPHA_THRESHOLD) for im in images]
    left, top, right, bottom = union_bbox(boxes)

    w, h = images[0].size
    left = max(0, left - CONTENT_PADDING_PX)
    top = max(0, top - CONTENT_PADDING_PX)
    right = min(w, right + CONTENT_PADDING_PX)
    bottom = min(h, bottom + CONTENT_PADDING_PX)

    crop_h = bottom - top
    scale = TARGET_HEIGHT_PX / crop_h
    target_w = round((right - left) * scale)

    results = []
    for im in images:
        cropped = im.crop((left, top, right, bottom))
        resized = cropped.resize((target_w, TARGET_HEIGHT_PX), Image.LANCZOS)
        results.append(resized)
    return results


# ---------------------------------------------------------------------------
# Step 6: emit shipped assets (palettized where possible) + QA contact sheet
# ---------------------------------------------------------------------------

PALETTE_MAX_COLORS = 255


def build_frequency_palette(rgba: Image.Image, max_colors: int = PALETTE_MAX_COLORS) -> Image.Image:
    """
    Deterministic, fidelity-preserving palettization.

    Pillow's built-in `Image.quantize(method=FASTOCTREE)` was tried first but
    rejected: it is not guaranteed deterministic across runs for this art
    (repeat runs of the identical pipeline produced different palettes) and,
    worse, on several runs it measurably shifted the dominant flat blue-fill
    color (e.g. source (7, 54, 141) came out as (0, 43, 133) -- a visible hue
    shift). That is unacceptable for a brand asset that must ship in its
    exact original color.

    Instead: take the N most frequent exact (R, G, B, A) colors in the image
    as the palette verbatim (this always includes the flat blue fill, the
    flat white outline, and full transparency, by a wide count margin -- so
    those never change), and nearest-match only the long tail of rare,
    already-blended anti-aliasing colors onto that palette. This is fully
    deterministic and guarantees exact color fidelity for every pixel that
    isn't already a soft edge blend.
    """
    arr = np.asarray(rgba)
    h, w = arr.shape[:2]
    flat = arr.reshape(-1, 4).astype(np.int16)
    colors, inverse, counts = np.unique(flat, axis=0, return_inverse=True, return_counts=True)

    order = np.argsort(-counts)
    n_colors = min(max_colors, len(colors))
    chosen_idx = order[:n_colors]
    chosen = colors[chosen_idx]

    mapping = np.zeros(len(colors), dtype=np.int64)
    mapping[chosen_idx] = np.arange(n_colors)

    rest_idx = order[n_colors:]
    if len(rest_idx):
        rest_colors = colors[rest_idx].astype(np.float32)
        chosen_f = chosen.astype(np.float32)
        # Weight alpha more heavily so a rare semi-transparent edge color
        # never gets matched to a fully opaque or fully transparent entry.
        weights = np.array([1.0, 1.0, 1.0, 2.0], dtype=np.float32)
        dist = (((rest_colors[:, None, :] - chosen_f[None, :, :]) * weights) ** 2).sum(axis=2)
        mapping[rest_idx] = dist.argmin(axis=1)

    palette_idx = mapping[inverse].reshape(h, w).astype(np.uint8)

    pal_im = Image.new("P", (w, h))
    rgb_palette = np.zeros((256, 3), dtype=np.uint8)
    rgb_palette[:n_colors] = chosen[:, :3].astype(np.uint8)
    pal_im.putpalette(rgb_palette.flatten().tolist())
    pal_im.putdata(palette_idx.flatten().tolist())

    alpha_palette = np.full(256, 255, dtype=np.uint8)
    alpha_palette[:n_colors] = chosen[:, 3].astype(np.uint8)
    pal_im.info["transparency"] = alpha_palette.tobytes()
    return pal_im


def save_shipped_asset(rgba: Image.Image, out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    palettized = build_frequency_palette(rgba)

    # Validate: the dominant (highest-count) opaque color in the palettized
    # output must exactly match the dominant opaque color of the source --
    # i.e. the mascot's blue fill must not have shifted at all. Also confirm
    # alpha wasn't degraded. If either check fails, ship plain RGBA instead
    # of ever risking a color/edge regression.
    check = np.asarray(palettized.convert("RGBA"))
    original = np.asarray(rgba)

    alpha_err = np.abs(check[..., 3].astype(np.int16) - original[..., 3].astype(np.int16)).mean()

    def dominant_opaque_color(a: np.ndarray) -> np.ndarray:
        opaque = a[..., 3] == 255
        colors, counts = np.unique(a[..., :3][opaque].reshape(-1, 3), axis=0, return_counts=True)
        return colors[np.argmax(counts)]

    dom_orig = dominant_opaque_color(original)
    dom_check = dominant_opaque_color(check)
    color_shift = int(np.abs(dom_orig.astype(int) - dom_check.astype(int)).max())

    if alpha_err < 3.0 and color_shift == 0:
        palettized.save(out_path, optimize=True)
        return

    # Fallback: plain optimized truecolor RGBA (still small for flat art).
    rgba.save(out_path, optimize=True)


def hex_to_rgb(hex_color: str) -> tuple[int, int, int]:
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def build_contact_sheet(processed: dict[str, Image.Image], out_path: Path) -> None:
    n_poses = len(processed)
    n_bg = len(CONTACT_SHEET_BACKGROUNDS)
    cell_h = CONTACT_SHEET_CELL_HEIGHT
    pad = CONTACT_SHEET_PADDING
    label_h = CONTACT_SHEET_LABEL_HEIGHT

    max_w = max(im.width * (cell_h / im.height) for im in processed.values())
    cell_w = int(max_w) + pad

    sheet_w = pad + n_bg * (cell_w + pad)
    sheet_h = label_h + n_poses * (label_h + cell_h + pad) + pad

    sheet = Image.new("RGB", (sheet_w, sheet_h), "#dddddd")

    from PIL import ImageDraw

    draw = ImageDraw.Draw(sheet)
    for col, (bg_name, bg_hex) in enumerate(CONTACT_SHEET_BACKGROUNDS):
        x = pad + col * (cell_w + pad)
        draw.text((x, 4), bg_name, fill="#000000")

    for row, (pose_name, im) in enumerate(processed.items()):
        y = label_h + row * (label_h + cell_h + pad)
        draw.text((4, y), pose_name, fill="#000000")
        scale = cell_h / im.height
        disp_w = int(im.width * scale)
        disp = im.resize((disp_w, cell_h), Image.LANCZOS)
        for col, (bg_name, bg_hex) in enumerate(CONTACT_SHEET_BACKGROUNDS):
            x = pad + col * (cell_w + pad)
            cell = Image.new("RGB", (cell_w, cell_h), hex_to_rgb(bg_hex))
            paste_x = (cell_w - disp_w) // 2
            cell.paste(disp, (paste_x, 0), disp)
            sheet.paste(cell, (x, y + label_h))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    print(f"Reading source assets from: {SOURCE_DIR}")
    raw_processed: dict[str, Image.Image] = {}
    for name in POSES:
        path = SOURCE_DIR / name
        if not path.exists():
            raise FileNotFoundError(f"Missing source asset: {path}")
        print(f"  processing {name} ...")
        raw_processed[name] = process_source(path)

    scaled = crop_and_scale_all([raw_processed[name] for name in POSES])
    final_by_name = dict(zip(POSES, scaled))

    SHIPPED_DIR.mkdir(parents=True, exist_ok=True)
    for name, im in final_by_name.items():
        out_path = SHIPPED_DIR / name
        save_shipped_asset(im, out_path)
        size_kb = out_path.stat().st_size / 1024
        print(f"  wrote {out_path} ({im.width}x{im.height}, {size_kb:.1f} KB)")

    contact_sheet_path = OUT_DIR / "qa-contact-sheet.png"
    build_contact_sheet(final_by_name, contact_sheet_path)
    print(f"Wrote QA contact sheet: {contact_sheet_path}")


if __name__ == "__main__":
    main()
