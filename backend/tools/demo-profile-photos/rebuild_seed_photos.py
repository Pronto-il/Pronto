"""Rebuilds the TEST/DEMO seed-ready professional profile photos.

Input  (never modified): backend/src/main/resources/demo/pronto_demo_profiles_50/
Output (regenerated):   backend/src/main/resources/demo/profile-photos/

Why this script exists
----------------------
The supplied folder is not 50 portraits. It is two different things:

* ``professional_026.png`` .. ``professional_050.png`` (25 files) really are single,
  correctly-framed portraits.
* ``professional_001.png`` .. ``professional_025.png`` (25 files) are 512x512 tiles cut out
  of a *larger 6-across contact collage* at the wrong boundaries. Individually each one
  holds fragments of two or three different people (split faces, headless torsos), so none
  of them is usable as a profile photo on its own.

Those 25 tiles are laid out row-major, 5 per row, so pasting them into a 5x5 / 2560x2560
mosaic puts each one back where it belongs, and the collage's own white gutters then give the
cell rectangles. Four of the collage's six columns land wholly (or all-but-a-background-sliver)
inside a single tile and come back as complete, correctly framed portraits. The other two do
not, and are dropped rather than shipped:

* collage column 3 straddles the tile seam at x=1024 and is headless — the head is simply not
  present anywhere in the supplied files;
* collage column 4 straddles the seam at x=1536, which runs down the middle of the subject's
  face; the two halves do not register, so the recovered face is visibly spliced.

The tiles are therefore not a lossless cut of the sheet: some source content is missing. What
survives is 4 columns x 5 rows = 20 recovered portraits, every one of which was reviewed by
eye before being accepted.

20 recovered + 25 already-clean = 45 seed-ready photos.

Run:  python backend/tools/demo-profile-photos/rebuild_seed_photos.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
SOURCE_DIR = ROOT / "backend/src/main/resources/demo/pronto_demo_profiles_50"
OUTPUT_DIR = ROOT / "backend/src/main/resources/demo/profile-photos"

TILE = 512
MOSAIC_COLUMNS = 5
MOSAIC_ROWS = 5

# Cell rectangles of the reconstructed collage, measured from its own white gutters
# (columns are stable across every row band; row heights taper slightly, as in the source).
COLLAGE_COLUMNS = [(0, 472), (478, 845), (851, 1242), (1253, 1635), (1641, 2042), (2050, 2560)]
COLLAGE_ROWS = [(0, 565), (580, 1126), (1141, 1630), (1645, 2097), (2111, 2560)]

# Collage columns 3 and 4 (0-based 2 and 3) straddle a tile seam the supplied files do not
# reconstruct across — headless and face-spliced respectively. See the module docstring.
UNUSABLE_COLLAGE_COLUMNS = {2, 3}

CLEAN_SOURCE_RANGE = range(26, 51)

OUTPUT_SIZE = 512
JPEG_QUALITY = 88

# Floor on how far the face-centring crop may shrink the largest available square.
MIN_CROP_RATIO = 0.72


def trim_border(image: Image.Image, threshold: int = 244) -> Image.Image:
    """Drops the near-white rows/columns hugging an image's edges (collage gutter bleed)."""
    grey = np.asarray(image.convert("L")).astype(int)
    rows = (grey >= threshold).mean(axis=1)
    cols = (grey >= threshold).mean(axis=0)

    def first_content(profile: np.ndarray) -> int:
        for i, value in enumerate(profile):
            if value < 0.98:
                return i
        return 0

    top = first_content(rows)
    bottom = len(rows) - first_content(rows[::-1])
    left = first_content(cols)
    right = len(cols) - first_content(cols[::-1])
    if right - left < 32 or bottom - top < 32:
        return image
    return image.crop((left, top, right, bottom))


def face_centre_x(image: Image.Image) -> float:
    """Horizontal centroid of skin-toned pixels in the upper half — i.e. roughly the face.

    A crude rule rather than a face detector (none is available offline), and it only has to
    choose an x-offset for the handful of cells that are wider than they are tall. Every
    output is reviewed visually afterwards, which is what actually guarantees the framing.
    """
    upper = np.asarray(image.convert("RGB")).astype(int)[: image.height * 55 // 100]
    r, g, b = upper[:, :, 0], upper[:, :, 1], upper[:, :, 2]
    skin = (
        (r > 95) & (g > 40) & (b > 20)
        & (r > g) & (r > b)
        & ((upper.max(axis=2) - upper.min(axis=2)) > 15)
        & (np.abs(r - g) > 12)
    )
    columns = skin.sum(axis=0)
    if columns.sum() < 200:
        return image.width / 2
    xs = np.arange(image.width)
    return float((columns * xs).sum() / columns.sum())


def to_square_portrait(image: Image.Image) -> Image.Image:
    """Square crop centred on the face, then normalised to OUTPUT_SIZE.

    The largest square the source allows is preferred, but it is shrunk (never below
    MIN_CROP_RATIO of that) when the subject sits off to one side, so the face ends up centred
    instead of hugging an edge. Head-and-shoulders framing puts the face in the top half, so
    the square is taken from near the top: that loses chest, never the head.
    """
    side = min(image.width, image.height)
    centre = face_centre_x(image)
    reachable = 2 * min(centre, image.width - centre)
    side = int(max(side * MIN_CROP_RATIO, min(side, reachable)))

    left = int(round(centre - side / 2))
    left = max(0, min(image.width - side, left))
    top = min(image.height - side, max(0, (image.height - side) // 6))
    return image.crop((left, top, left + side, top + side)).resize(
        (OUTPUT_SIZE, OUTPUT_SIZE), Image.LANCZOS)


def build_mosaic() -> Image.Image:
    mosaic = Image.new("RGB", (MOSAIC_COLUMNS * TILE, MOSAIC_ROWS * TILE))
    for i in range(MOSAIC_COLUMNS * MOSAIC_ROWS):
        row, column = divmod(i, MOSAIC_COLUMNS)
        tile = Image.open(SOURCE_DIR / f"professional_{i + 1:03d}.png").convert("RGB")
        mosaic.paste(tile, (column * TILE, row * TILE))
    return mosaic


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for stale in OUTPUT_DIR.glob("professional_*.jpg"):
        stale.unlink()

    provenance: list[dict[str, str]] = []
    mosaic = build_mosaic()

    number = 0
    for row_index, (y0, y1) in enumerate(COLLAGE_ROWS):
        for column_index, (x0, x1) in enumerate(COLLAGE_COLUMNS):
            if column_index in UNUSABLE_COLLAGE_COLUMNS:
                continue
            number += 1
            cell = trim_border(mosaic.crop((x0, y0, x1, y1)))
            name = f"professional_{number:03d}.jpg"
            to_square_portrait(cell).save(OUTPUT_DIR / name, "JPEG", quality=JPEG_QUALITY, optimize=True)
            provenance.append({
                "file": name,
                "origin": "recovered",
                "cell": f"r{row_index + 1}c{column_index + 1}",
            })

    for source_number in CLEAN_SOURCE_RANGE:
        source_name = f"professional_{source_number:03d}.png"
        cell = trim_border(Image.open(SOURCE_DIR / source_name).convert("RGB"))
        name = f"professional_{source_number:03d}.jpg"
        to_square_portrait(cell).save(OUTPUT_DIR / name, "JPEG", quality=JPEG_QUALITY, optimize=True)
        provenance.append({"file": name, "origin": "supplied", "source": source_name})

    (OUTPUT_DIR / "PROVENANCE.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(provenance)} photos to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
