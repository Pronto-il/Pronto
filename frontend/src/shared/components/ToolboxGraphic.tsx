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
   * Plays the one-shot arrival animation: the lid swings open and a handful of sparkles rise
   * out of the box (§"Animation", ~800ms).
   *
   * The caller is responsible for it being one-shot. This component simply reflects the flag,
   * because "has this order already celebrated" is order-lifecycle knowledge that belongs with
   * the state resolver, not with a drawing.
   */
  celebrating?: boolean;
  /** Extra class on the root element, so a consumer can size/position the whole object. */
  className?: string;
}

/**
 * The deep-navy toolbox with a wrench and a screwdriver sticking out of it.
 *
 * Drawn as inline SVG rather than shipped as a PNG for three reasons that matter here: it has to
 * sit on a transparent background over arbitrary page content at any device pixel ratio; it is
 * tinted entirely from the `--color-navy*`/`--color-metal*` tokens, so a brand change is a token
 * change and not a new export; and the front face has to stay a known, addressable rectangle
 * because the label is positioned inside it.
 *
 * The label is HTML positioned over the face, not SVG `<text>`: `<text>` would not pick up the
 * app's font stack cleanly, cannot wrap, and fights RTL.
 *
 * `viewBox` is 120x104: 120 wide by 104 tall, with the tools occupying the top ~30 units and the
 * box body the lower ~70. Everything below is expressed in those units.
 */
export function ToolboxGraphic({
  primaryText,
  secondaryText,
  celebrating = false,
  className,
}: ToolboxGraphicProps) {
  return (
    <span className={`${styles.root} ${celebrating ? styles.celebrating : ''} ${className ?? ''}`}>
      <svg
        viewBox="0 0 120 104"
        className={styles.svg}
        // Purely decorative: `ActiveIssueToolbox` puts the accessible name on the button itself,
        // so announcing this too would read the control out twice.
        aria-hidden="true"
        focusable="false"
      >
        {/* ---- Tools, drawn first so the box body overlaps their shafts and they read as being
                *inside* the box rather than glued behind it. ---- */}

        {/* Screwdriver, leaning inline-start. Shaft + amber handle + tip. */}
        <g className={styles.tool}>
          <rect x="34" y="16" width="5" height="30" rx="2.5" fill="var(--color-metal)" />
          <rect x="33" y="40" width="7" height="6" rx="1.5" fill="var(--color-metal-dark)" />
          <path d="M30.5 4.5c0-2 1.6-3.5 3.5-3.5h1.5c1.9 0 3.5 1.5 3.5 3.5v12h-8.5z" fill="#e0a33c" />
          <rect x="30.5" y="14" width="8.5" height="3" rx="1" fill="#c98a28" />
        </g>

        {/* Wrench, leaning inline-end — open-ended head at the top, shaft running into the box. */}
        <g className={styles.tool}>
          <rect
            x="78"
            y="14"
            width="6"
            height="32"
            rx="3"
            fill="var(--color-metal)"
            transform="rotate(12 81 30)"
          />
          <path
            d="M88.5 2.5a10 10 0 0 0-9.8 12.2l7.4 1.6 2.2-3.6 4.2.9a10 10 0 0 0-4-11.1z"
            fill="var(--color-metal)"
          />
          <path
            d="M88.5 2.5a10 10 0 0 0-4.6 1.4l3.3 5.4-2.2 3.6 4.6 1 2.2-3.6 4.2.9a10 10 0 0 0-7.5-8.7z"
            fill="var(--color-metal-dark)"
          />
        </g>

        {/* ---- Box body ---- */}

        {/* Handle — deliberately NOT part of the lid group. It is drawn as fixed to the body, so
            the lid can hinge open behind it. Rotating the handle with the lid made the whole
            assembly swing off the box and cover the tools, which read as broken rather than as a
            toolbox opening. */}
        <path
          d="M42 40c0-9.9 8.1-18 18-18s18 8.1 18 18"
          fill="none"
          stroke="var(--color-metal-dark)"
          strokeWidth="6"
          strokeLinecap="round"
        />
        <path
          d="M42 40c0-9.9 8.1-18 18-18s18 8.1 18 18"
          fill="none"
          stroke="var(--color-metal)"
          strokeWidth="3"
          strokeLinecap="round"
        />

        {/* Lid — slightly lighter than the front face, which is what gives the object its
            top-lit, three-dimensional read without any gradient. Hinges on its own back-left
            corner; see the stylesheet's `transform-origin`. */}
        <g className={styles.lid}>
          <rect x="4" y="38" width="112" height="18" rx="6" fill="var(--color-navy-light)" />
          {/* Specular highlight along the top of the lid. */}
          <rect x="10" y="41" width="100" height="3" rx="1.5" fill="#ffffff" opacity="0.16" />
        </g>

        {/* Front face. The label sits on top of this rectangle. */}
        <rect x="4" y="52" width="112" height="48" rx="8" fill="var(--color-navy)" />

        {/* Shadowed lower strip, for weight along the bottom edge. */}
        <path
          d="M4 88h112v4a8 8 0 0 1-8 8H12a8 8 0 0 1-8-8z"
          fill="var(--color-navy-dark)"
          opacity="0.85"
        />

        {/* Two latches straddling the lid seam. */}
        <rect x="24" y="47" width="12" height="12" rx="3" fill="var(--color-metal-dark)" />
        <rect x="26" y="49" width="8" height="8" rx="2" fill="var(--color-metal)" />
        <rect x="84" y="47" width="12" height="12" rx="3" fill="var(--color-metal-dark)" />
        <rect x="86" y="49" width="8" height="8" rx="2" fill="var(--color-metal)" />

        {/* ---- Sparkles ----
            Four-point stars that rise out of the box as the lid lifts. Invisible at rest.
            Painted LAST, after the lid and the front face: drawn earlier they were occluded by
            the very lid they are supposed to escape from, so the burst was invisible even
            though it was running. Inside the SVG rather than as absolutely-positioned HTML so
            they scale with the artwork; `overflow: visible` lets them leave the viewBox. */}
        <g className={styles.sparkles}>
          {SPARKLES.map((sparkle, index) => (
            // Position and scale live on a WRAPPER group, never on the animated <path> itself.
            // A CSS `transform` replaces an element's SVG `transform` attribute outright rather
            // than composing with it, so animating the path directly threw each sparkle back to
            // the SVG origin and flung it out of the top-left corner. A parent group's transform
            // is a separate matrix, so the child's animated transform composes with it.
            <g key={index} transform={`translate(${sparkle.x} ${sparkle.y}) scale(${sparkle.scale})`}>
              <path
                className={styles.sparkle}
                // `--dx`/`--dy` are the arc this one travels; `--delay` staggers the burst.
                style={
                  {
                    '--sparkle-dx': `${sparkle.dx}px`,
                    '--sparkle-dy': `${sparkle.dy}px`,
                    '--sparkle-delay': `${sparkle.delay}ms`,
                  } as React.CSSProperties
                }
                d="M0-6c.6 3.5 1.9 4.8 5.4 5.4C1.9.6.6 1.9 0 5.4-.6 1.9-1.9.6-5.4-.6-1.9-1.2-.6-2.5 0-6z"
                fill="#ffd76a"
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

/** Five sparkles — inside §"Animation"'s 3-6 range. Hand-placed across the lid opening so the
 *  burst is not symmetrical, which is what keeps it reading as premium rather than mechanical. */
const SPARKLES = [
  // `y` sits on the lid seam (~52) so each star reads as coming out of the opening itself.
  { x: 36, y: 52, scale: 0.8, dx: -13, dy: -24, delay: 0 },
  { x: 50, y: 50, scale: 1.1, dx: -5, dy: -32, delay: 60 },
  { x: 62, y: 49, scale: 0.65, dx: 3, dy: -28, delay: 130 },
  { x: 76, y: 50, scale: 0.95, dx: 12, dy: -30, delay: 90 },
  { x: 88, y: 52, scale: 0.75, dx: 19, dy: -22, delay: 170 },
] as const;
