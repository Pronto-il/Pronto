import { Drill, Hammer, PaintRoller, Plug, Ruler, Wrench } from 'lucide-react';
import type { ComponentType } from 'react';
import styles from './SosScanPanel.module.css';

/**
 * The decorative layer of **Pronto Scan**: the tools and the hardware drifting around the rings.
 *
 * ## What it is for
 *
 * The rings alone say "something is loading". These say *what* is being looked for — a plumber, an
 * electrician, a painter, somebody with the right thing in the back of the van. The scan is the one
 * screen in the product whose whole job is to make waiting feel like work is happening, and a bare
 * dial does not do that.
 *
 * ## What it is not
 *
 * **None of it is data.** No icon corresponds to a professional, a category, a location or a
 * distance; the platform has no live position for anybody, and a moving mark that implied otherwise
 * would be inventing it. Everything here is `aria-hidden` and `pointer-events: none`, sits below the
 * centre and below every candidate card, and can be deleted without the screen losing a single fact.
 *
 * ## Two tiers, deliberately
 *
 * - **Tools** — six recognisable silhouettes at a readable size. These carry the story.
 * - **Hardware** — twelve much smaller pieces (screws, nuts, washers, bolts, nails, a key) scattered
 *   between them. These carry the *texture*: the thing the customer notices on the second glance
 *   rather than the first, which is what keeps the surface from reading as a spinner with props.
 *
 * The size gap between the tiers is the whole reason it works. Hardware at tool size would be
 * clutter; tools at hardware size would be noise.
 *
 * ## Placement and motion
 *
 * Each entry carries a `top`/`left` percentage of the scan box and a motion class. Placement is
 * `left`/`top` + `transform: translate(-50%, -50%)`, and every animation drives the separate
 * `translate`/`rotate` properties — which **compose** with `transform` rather than replacing it.
 * That split is not a stylistic choice: the previous pass positioned with `translate` and then
 * animated `translate`, so the keyframe overwrote the placement and the icons collapsed toward the
 * centre the moment their animation started. See the CSS for the full note.
 *
 * Percentages, not pixels, so the whole arrangement scales with the dial at every breakpoint
 * instead of needing a second table for mobile.
 */

// ---------------------------------------------------------------------------
// Hardware shapes lucide does not have
// ---------------------------------------------------------------------------

/*
 * Six tiny pieces, hand-drawn because the icon set has no vocabulary for them: a screw is not a
 * "bolt" glyph and a nail is not a "line". They are deliberately drawn on the same 24-unit grid and
 * with the same 2-unit stroke weight as the lucide tools around them, so the two tiers read as one
 * family at very different sizes.
 */

type DecorIcon = ComponentType<{ size?: number | string; strokeWidth?: number | string }>;

const svgProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
};

/** A screw: round slotted head, tapering threaded shank. */
function Screw({ size = 12, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      <circle cx="12" cy="5" r="3.5" />
      <path d="M9.6 4.2h4.8" />
      <path d="M9.5 8.5h5l-.6 4.5H10z" />
      <path d="M12 13v6.5" />
    </svg>
  );
}

/** A hex nut: hexagon with a bore. */
function Nut({ size = 12, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      <path d="M12 2.5 20 7v10l-8 4.5L4 17V7z" />
      <circle cx="12" cy="12" r="3.5" />
    </svg>
  );
}

/** A washer: two concentric rings, nothing else. */
function Washer({ size = 12, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      <circle cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="3.5" />
    </svg>
  );
}

/** A bolt: hex head on a threaded shank. */
function BoltPiece({ size = 12, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      <path d="M8 3.5h8l2 3-2 3H8l-2-3z" />
      <path d="M10 9.5v11" />
      <path d="M14 9.5v11" />
      <path d="M10 13h4M10 16.5h4" />
    </svg>
  );
}

/** A nail: flat head, straight shank, point. */
function Nail({ size = 12, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      <path d="M6.5 4h11" />
      <path d="M12 4v14.5l-1.5 2.5" />
    </svg>
  );
}

/** A key: bow, shaft, two bits. */
function KeyPiece({ size = 12, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      <circle cx="7" cy="7" r="4" />
      <path d="M10 10l10 10" />
      <path d="M17 17l2.5-2.5" />
      <path d="M14 14l2-2" />
    </svg>
  );
}

/**
 * A screwdriver and a pair of pliers — the two tools the product brief names that lucide has no
 * glyph for. Drawn to the same grid and weight as the lucide tools they sit beside.
 *
 * Both got a second pass after being looked at on a real phone rather than in isolation: the first
 * screwdriver read as a pen (a tapered diagonal body ending in a point) and the first pliers read
 * as scissors (two straight blades from a pivot). The cues that fix them are a chunky collared
 * handle with a *flat* blade, and curved handles below the pivot — that curve is the whole
 * difference between pliers and scissors at 28px.
 */
function Screwdriver({ size = 24, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      {/* Handle, with the collar that stops it reading as a barrel. */}
      <rect x="9" y="2.5" width="6" height="8" rx="1.8" />
      <path d="M8.5 10.5h7" />
      {/* Shaft, then the flat blade — a screwdriver ends in an edge, a pen ends in a point. */}
      <path d="M12 10.5v7" />
      <path d="M10.4 17.5h3.2l-.5 4h-2.2z" />
    </svg>
  );
}

function Pliers({ size = 24, strokeWidth = 2 }: { size?: number | string; strokeWidth?: number | string }) {
  return (
    <svg width={size} height={size} strokeWidth={strokeWidth} {...svgProps}>
      {/* Short straight jaws above the pivot… */}
      <path d="M8.6 3.2 11.3 9.9" />
      <path d="M15.4 3.2 12.7 9.9" />
      <circle cx="12" cy="11.2" r="1.5" />
      {/* …and curved handles below it. */}
      <path d="M11 12.5c-1 3.4-2 5.9-2.9 8.4" />
      <path d="M13 12.5c1 3.4 2 5.9 2.9 8.4" />
    </svg>
  );
}

// ---------------------------------------------------------------------------
// The arrangement
// ---------------------------------------------------------------------------

interface ScanDecorItem {
  Icon: DecorIcon;
  /** Percentage of the scan box. `50/50` is the centre pin, so nothing sits there. */
  top: number;
  left: number;
  size: number;
  /** One of the motion classes in `SosScanPanel.module.css`. */
  motion: string;
  delay: string;
  /** Per-item cycle length. Prime-ish and unequal, so the set never re-synchronises. */
  duration: string;
}

/**
 * The six tools. Placed on a wide, uneven ring so they read as scattered rather than as clock
 * positions, and paired with four different motion classes so no two neighbours move alike.
 *
 * Kept out of the horizontal band the candidate columns occupy at the top of the dial, and off the
 * centre — the layering rules in the CSS mean they could never obscure a card anyway, but not
 * needing the rule is better than relying on it.
 */
const SCAN_TOOLS: ScanDecorItem[] = [
  { Icon: Wrench, top: 8, left: 50, size: 30, motion: 'toolFloat', delay: '0s', duration: '9s' },
  { Icon: Hammer, top: 26, left: 84, size: 28, motion: 'toolArc', delay: '1.1s', duration: '11s' },
  { Icon: Plug, top: 68, left: 88, size: 26, motion: 'toolSway', delay: '2.4s', duration: '10s' },
  { Icon: PaintRoller, top: 90, left: 56, size: 28, motion: 'toolFloat', delay: '3.6s', duration: '12s' },
  { Icon: Screwdriver, top: 70, left: 12, size: 30, motion: 'toolTilt', delay: '0.7s', duration: '10.5s' },
  { Icon: Pliers, top: 24, left: 14, size: 28, motion: 'toolArc', delay: '2.9s', duration: '13s' },
];

/**
 * Twelve pieces of hardware, roughly a third the size of the tools and at half their opacity.
 *
 * Scattered between and slightly beyond the tools rather than on a ring of their own — a second
 * neat circle would read as a diagram. Ruler and drill appear here at tool size but placed further
 * out, which is what stops the outer band looking empty next to the busier middle.
 */
const SCAN_HARDWARE: ScanDecorItem[] = [
  { Icon: Screw, top: 18, left: 68, size: 15, motion: 'partDrift', delay: '0.3s', duration: '14s' },
  { Icon: Nut, top: 44, left: 92, size: 15, motion: 'partSpin', delay: '1.7s', duration: '17s' },
  { Icon: Washer, top: 82, left: 78, size: 14, motion: 'partDrift', delay: '3.1s', duration: '15s' },
  { Icon: Nail, top: 96, left: 34, size: 14, motion: 'partSway', delay: '0.9s', duration: '13s' },
  { Icon: BoltPiece, top: 84, left: 22, size: 15, motion: 'partDrift', delay: '2.2s', duration: '16s' },
  { Icon: KeyPiece, top: 52, left: 6, size: 16, motion: 'partSpin', delay: '4.1s', duration: '18s' },
  { Icon: Screw, top: 34, left: 4, size: 13, motion: 'partSway', delay: '1.3s', duration: '15.5s' },
  { Icon: Washer, top: 6, left: 30, size: 14, motion: 'partDrift', delay: '2.7s', duration: '14.5s' },
  { Icon: Nut, top: 10, left: 74, size: 13, motion: 'partSway', delay: '3.9s', duration: '16.5s' },
  { Icon: Nail, top: 60, left: 96, size: 14, motion: 'partSpin', delay: '0.5s', duration: '19s' },
  { Icon: Ruler, top: 40, left: 26, size: 20, motion: 'partDrift', delay: '4.6s', duration: '13.5s' },
  { Icon: Drill, top: 58, left: 72, size: 20, motion: 'partSway', delay: '5.2s', duration: '12.5s' },
];

/**
 * Renders one tier. Split from `SosScanPanel` so the panel reads as layout and this reads as
 * decoration — and so the whole tier can be dropped from a render with one line if it ever needs to
 * be.
 *
 * The arrangement tables stay private to this module and are selected by `tier` rather than passed
 * in: the caller has no business knowing where a wrench goes, and this is the only file that should
 * ever change when the arrangement is retuned.
 *
 * `aria-hidden` on the group, never on each item: one hidden subtree is cheaper for a screen reader
 * to skip than eighteen.
 */
export function SosScanDecor({ tier }: { tier: 'tool' | 'part' }) {
  const items = tier === 'tool' ? SCAN_TOOLS : SCAN_HARDWARE;

  return (
    <div className={styles.decorLayer} aria-hidden="true">
      {items.map(({ Icon, top, left, size, motion, delay, duration }, index) => (
        <span
          key={index}
          className={`${styles.decor} ${styles[tier]} ${styles[motion]}`}
          style={{
            top: `${top}%`,
            left: `${left}%`,
            animationDelay: delay,
            animationDuration: duration,
          }}
        >
          <Icon size={size} strokeWidth={tier === 'tool' ? 1.6 : 1.5} />
        </span>
      ))}
    </div>
  );
}
