import styles from './ToolboxGraphic.module.css';

export interface ToolboxGraphicProps {
  /**
   * The main line painted on the toolbox's front face (§3 of the shell redesign: "not beside
   * it, not under it").
   *
   * Rendered by this component rather than by the consumer, deliberately. The face is a
   * rectangle inside the SVG's coordinate system, and the only way to keep an HTML label
   * pinned to it is for the element that positions the label to be the same element that
   * declares the geometry. An earlier revision published `--toolbox-face-*` custom properties
   * for the consumer to use and had the consumer render the label as a *sibling* of the
   * `<svg>` — where those properties do not inherit, so `top`/`width` resolved to nothing and
   * the label dropped out of the box and landed underneath it.
   */
  primaryText: string;
  /**
   * Optional small line under {@link primaryText}. Present only in the live-ETA state
   * ("12 דק׳" over "עד ההגעה"); when omitted the primary line is centred on the face and set
   * at the normal label size rather than the larger numeric size.
   */
  secondaryText?: string;
  /**
   * Which one-shot celebration to play, if any:
   *
   * - `'arrival'` — the professional reached the address: a brief teal pulse plus a few small
   *   dots rising off the latch (~700ms, unchanged from before).
   * - `'completion'` — the job just finished (~1.2s, added for the final completion animation):
   *   the lid gives a small "pop" as if lifting, plus 6 small star sparkles (teal, with a couple
   *   of small gold ones) rising off the latch — more festive than arrival, since it is the last
   *   thing the customer sees from this order before it settles into the review prompt.
   *
   * `undefined`/`null` plays nothing. The caller (`ActiveIssueToolbox`) is responsible for each
   * being one-shot per order — this component only reflects which one is currently playing,
   * because "has this order already celebrated this milestone" is order-lifecycle knowledge that
   * belongs with the state resolver, not with a drawing.
   */
  celebration?: 'arrival' | 'completion' | null;
  /** Extra class on the root element, so a consumer can size/position the whole object. */
  className?: string;
}

/**
 * The compact teal-and-white toolbox card (visual redesign, 2026-08-28 — design reference:
 * a small rounded card with a teal lid band, a centred loop handle, a single latch on the seam,
 * and a white body carrying a wrench glyph and the state label).
 *
 * Supersedes the earlier deep-navy illustrated toolbox (screwdriver/wrench sticking out of an
 * opening lid). That version read as a standalone illustration; this one is sized and colored to
 * read as a small piece of UI chrome — `--color-primary`/`--color-surface`/`--color-border`
 * tokens only, no bespoke palette. Drawn as inline SVG for the same three reasons as before: a
 * transparent background over arbitrary page content, tinting from CSS custom properties rather
 * than a shipped raster, and a front face that stays a known rectangle for the HTML label to sit
 * on top of.
 *
 * `viewBox` is 96x96. The handle occupies the top ~18 units (rendered above the card's own top
 * edge), the card body spans y 18-92, and the seam between the teal cap and the white body sits
 * at y 38.
 */
export function ToolboxGraphic({
  primaryText,
  secondaryText,
  celebration = null,
  className,
}: ToolboxGraphicProps) {
  const celebrationClass =
    celebration === 'arrival' ? styles.celebratingArrival : celebration === 'completion' ? styles.celebratingCompletion : '';

  return (
    <span className={`${styles.root} ${celebrationClass} ${className ?? ''}`}>
      <svg
        viewBox="0 0 96 96"
        className={styles.svg}
        // Purely decorative: `ActiveIssueToolbox` puts the accessible name on the button itself,
        // so announcing this too would read the control out twice.
        aria-hidden="true"
        focusable="false"
      >
        {/* Card body: a single rounded rectangle, teal on the top band and white below, achieved
            by clipping two plain (unrounded) fills to the card's own rounded outline. This is
            what keeps the teal/white seam a clean straight line instead of a rounded notch.
            Clip applied per-element (not via a wrapping `<g clip-path>`) so the "lid" pieces
            below can be grouped with the handle for the completion celebration's pop motion
            while the white body stays put underneath them. */}
        <defs>
          <clipPath id="toolbox-card-clip">
            <rect x="4" y="18" width="88" height="74" rx="14" />
          </clipPath>
        </defs>
        <rect x="4" y="18" width="88" height="74" clipPath="url(#toolbox-card-clip)" fill="var(--color-surface)" />

        {/* The "lid": handle + teal cap band. Both carry `.lidGroup`, so the completion
            celebration's single keyframe lifts them together as one small opening gesture — the
            closest a flat, minimal card gets to a hinged lid without becoming an illustration. */}
        <path
          d="M36 20c0-8.5 5.4-15 12-15s12 6.5 12 15"
          fill="none"
          stroke="var(--color-primary)"
          strokeWidth="5"
          strokeLinecap="round"
          className={`${styles.handle} ${styles.lidGroup}`}
        />
        <rect
          x="4"
          y="18"
          width="88"
          height="20"
          clipPath="url(#toolbox-card-clip)"
          fill="var(--color-primary)"
          className={styles.lidGroup}
        />

        {/* Card outline — a hairline border keeps the white body from disappearing into a light
            page background (§ "part of the UI, not a separate illustration"). Deliberately NOT
            part of `.lidGroup`: the outline stays fixed to the card while the lid pops, or the
            card would read as coming apart rather than a lid lifting on it. */}
        <rect
          x="4"
          y="18"
          width="88"
          height="74"
          rx="14"
          fill="none"
          stroke="var(--color-border)"
          strokeWidth="1"
        />

        {/* Latch — straddles the teal/white seam, centred. Flat two-tone circle-on-square rather
            than a gradient, per "not cartoonish/3D". */}
        <rect x="41" y="33" width="14" height="10" rx="3" fill="var(--color-border-strong)" />
        <circle cx="48" cy="38" r="2.25" fill="var(--color-text-muted)" className={styles.latchDot} />

        {/* Wrench glyph — static across every state; the label below it carries the status. */}
        <g transform="translate(38 44) scale(0.75)" className={styles.wrench}>
          <path
            d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"
            fill="none"
            stroke="var(--color-primary)"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </g>

        {/* ---- Arrival celebration: a soft ring pulse off the latch plus a few small dots.
                Unchanged from before this file gained a distinct completion celebration —
                shares the ring with completion (below), but keeps its own plain teal dots. ---- */}
        <circle cx="48" cy="38" r="3" className={styles.pulseRing} />
        <g className={styles.dots}>
          {DOTS.map((dot, index) => (
            <g key={index} transform={`translate(${dot.x} ${dot.y}) scale(${dot.scale})`}>
              <circle
                r="2.4"
                fill="var(--color-primary)"
                className={styles.dot}
                style={
                  {
                    '--dot-dx': `${dot.dx}px`,
                    '--dot-dy': `${dot.dy}px`,
                    '--dot-delay': `${dot.delay}ms`,
                  } as React.CSSProperties
                }
              />
            </g>
          ))}
        </g>

        {/* ---- Completion celebration: 6 small four-point star sparkles off the latch (mostly
                teal, two small gold ones — "existing Pronto teal/white palette, with small
                yellow/gold sparkles allowed"). A distinct shape/count from arrival's plain teal
                dots above, since this is meant to read as a bit more festive: the job is done.
                Painted last so nothing occludes it. ---- */}
        <g className={styles.completionSparkles}>
          {COMPLETION_SPARKLES.map((sparkle, index) => (
            <g key={index} transform={`translate(${sparkle.x} ${sparkle.y}) scale(${sparkle.scale})`}>
              <path
                className={styles.completionSparkle}
                style={
                  {
                    '--dot-dx': `${sparkle.dx}px`,
                    '--dot-dy': `${sparkle.dy}px`,
                    '--dot-delay': `${sparkle.delay}ms`,
                  } as React.CSSProperties
                }
                d="M0-6c.6 3.5 1.9 4.8 5.4 5.4C1.9.6.6 1.9 0 5.4-.6 1.9-1.9.6-5.4-.6-1.9-1.2-.6-2.5 0-6z"
                fill={sparkle.color}
              />
            </g>
          ))}
        </g>
      </svg>

      <span className={`${styles.face} ${secondaryText ? styles.faceStacked : ''}`}>
        <span className={secondaryText ? styles.primaryLarge : styles.primary}>{primaryText}</span>
        {secondaryText && <span className={styles.secondary}>{secondaryText}</span>}
      </span>
    </span>
  );
}

/** Four small dots, hand-placed off-centre around the latch so the burst reads as organic rather
 *  than a perfect radial fan. */
const DOTS = [
  { x: 48, y: 38, scale: 0.9, dx: -10, dy: -14, delay: 0 },
  { x: 48, y: 38, scale: 0.7, dx: -2, dy: -18, delay: 60 },
  { x: 48, y: 38, scale: 0.8, dx: 6, dy: -15, delay: 40 },
  { x: 48, y: 38, scale: 0.6, dx: 12, dy: -10, delay: 100 },
] as const;

/**
 * Six small four-point stars for the completion celebration — within the "4-8" range asked for.
 * Mostly teal (`--color-primary`, matching every other accent on the object); two are a small
 * warm gold (`#ffd76a`, the same hex the pre-redesign version of this component used for its own
 * gold sparkles — reused rather than inventing a new one) as the one deliberate exception to the
 * teal/white palette, kept a clear minority so it reads as an accent, not a second theme.
 * Hand-placed off-centre, like `DOTS`, so the burst reads as organic rather than a radial fan.
 */
const COMPLETION_SPARKLES = [
  { x: 48, y: 38, scale: 0.85, dx: -16, dy: -20, delay: 0, color: 'var(--color-primary)' },
  { x: 48, y: 38, scale: 0.6, dx: -8, dy: -28, delay: 70, color: '#ffd76a' },
  { x: 48, y: 38, scale: 0.9, dx: 1, dy: -25, delay: 30, color: 'var(--color-primary)' },
  { x: 48, y: 38, scale: 0.65, dx: 9, dy: -30, delay: 110, color: 'var(--color-primary)' },
  { x: 48, y: 38, scale: 0.8, dx: 17, dy: -17, delay: 50, color: '#ffd76a' },
  { x: 48, y: 38, scale: 0.55, dx: 4, dy: -34, delay: 90, color: 'var(--color-primary)' },
] as const;
