# Frontend MS2 — Home + Authentication Experience — Design

Status: **Design only, not yet implemented.** Written by the planning/architecture agent
for `pronto-lead` to review before `pronto-coding` implements. Builds directly on
**MS1 — Visual Foundation & Motion System** (tokens, `Button`/`Card`/`Input`/`Modal`/
`PageHeader`/`StatusBadge`/`Skeleton`/`Badge`/`FilterChip`/`EmptyState`/`Mascot`,
`useToast`, `framer-motion` variants, `styles/motion.css`), currently sitting as
uncommitted working-tree edits on `frontend/MS1-visual-foundation` — nothing in this
doc redoes or duplicates that work, it consumes it.

## 0. Inputs read before designing anything

`frontend/Pronto — DESIGN_SYSTEM.md` (all 96 sections, in full — not just §35-38/§50-57),
`frontend/FRONTEND_GUIDELINES.md`, `frontend/FRONTEND_AGENT.md`,
`docs/architecture/overview.md` §1-4 + §6, plus every source file this doc touches:
`app/HomePage.tsx`/`.module.css`, `app/AppLayout.tsx`/`.module.css`,
`app/ActiveOrderIndicator.tsx`/`.module.css`, `app/BookingDraftIndicator.tsx`/`.module.css`,
`app/ProfilePage.tsx`, `app/router.tsx`, `features/auth/*` (all 15 files),
`features/dashboard/ProDashboardLayout.tsx` (read-only, for pattern precedent — untouched
by this milestone), `features/notifications/NotificationBell.tsx`,
`shared/components/Mascot.tsx`/`.module.css`, `shared/components/PageHeader.tsx`/`.module.css`,
`shared/components/Button.tsx`, `shared/components/Card.tsx`, `shared/components/index.ts`,
`shared/components/README.md`, `shared/motion/variants.ts`, `shared/motion/README.md`,
`shared/api/auth.ts`, `shared/api/professionals.ts`, `shared/api/categories.ts`,
`index.css` (design tokens), `backend/.../auth/dto/CustomerRegistrationData.java`,
`backend/.../auth/dto/ProfessionalRegistrationData.java`,
`backend/.../auth/service/AuthService.java` (in full).

## 1. Cross-cutting resolutions

### 1.1 Desktop-first vs. mobile-first (binding tension, resolved explicitly)

`docs/architecture/overview.md` §2 and pronto-lead's brief both state the project-wide,
poster-sourced decision: **desktop-first responsive web, not mobile-first**. But
`FRONTEND_GUIDELINES.md` §3 ("Mobile First") and `FRONTEND_AGENT.md` §17 ("Customer-facing
work must be designed mobile-first... Do not build desktop first and simply shrink
everything") say the opposite for the *customer* experience specifically.

**Resolution for this milestone**: desktop-first remains the base CSS authoring approach —
consistent with MS1's own precedent (`index.css`'s tokens, `HomePage.module.css`'s existing
`@media (max-width: 640px)` override pattern) and the explicit, poster-sourced project-wide
instruction, which this doc treats as the tie-breaker per `overview.md`'s own stated
precedence rule ("poster is the source of truth on architecture/tech conflicts"). **But**
every one of the five surfaces below is designed with a genuine, independently-considered
mobile composition — not a shrunk desktop layout — satisfying the *substance* of the
guideline (mobile users get real design attention) without adopting literal mobile-first
CSS methodology (writing the unprefixed rules for mobile and overriding upward). Each
section below has its own "Desktop" and "Mobile" subsection for exactly this reason.

### 1.2 Motion reuse policy

No new `framer-motion` variants are introduced. Every animated transition in this doc reuses
one of MS1's existing named variants (`pageTransition`, `stepTransition`, `mascotSlideIn`,
`listStagger`) exactly as `shared/motion/README.md` prescribes. `pageTransition` currently
has **zero real consumers** anywhere in the app (grep-verified) — this milestone makes it
the first one, on Home, Login, Register Choice, and both registration wizards' outer shell.
Micro-interactions (banner fade-in, hover/press) stay CSS-only per the same README's
CSS-vs-`framer-motion` split.

### 1.3 Reuse inventory (no new shared primitives beyond what's justified)

Reused as-is: `PageHeader` (including its already-shipped `steps` prop), `Button`, `Card`,
`Input`, `Select`, `Checkbox`, `AddressFormFields`, `ImageUploadField`,
`DocumentUploadField`, `Mascot`, `Skeleton`, `Badge`. Icons reused from already-established
choices elsewhere in the app rather than picking new ones ad hoc: `ClipboardList` (orders,
already used in `AppLayout`), `Heart` (favorites, already used in `ProfilePage`), `User`
(profile, already used in `AppLayout`), plus `Home` (new to this app, but a standard
`lucide-react` icon, no new icon pack).

One new shared component is introduced (justified below, §7.1): a registration **wizard
step-frame**, because it has two real, simultaneous consumers this same milestone
(customer + professional registration) — satisfying `FRONTEND_AGENT.md` §7's "creating a
new component is not the default solution" bar. No other new shared component is
introduced — trust indicators (Home) and role-option cards (Register Choice) each have
exactly one consumer this milestone and stay as local, non-exported JSX inside their own
page/component file, per the same discipline.

---

## 2. Surface 1 — Home Page Hero

Files: `app/HomePage.tsx`, `app/HomePage.module.css`.

### 2.1 Content hierarchy (per DESIGN_SYSTEM §35-36, followed not reinvented)

```
Greeting (authenticated only) / welcome line (guest)
Headline — "איך אפשר לעזור היום?"
Short supporting copy
CTA panel — "יש לי תקלה" (mascot composed into it)
Trust indicator row
```

`DESIGN_SYSTEM.md` §35 lists a longer hierarchy (…Popular services / Active booking).
**Deliberately not built this milestone** — see §2.7 below; this section is genuinely
scoped to "Home Page Hero," not the whole Home page.

### 2.2 Copy

- Guest: `שלום! ` is omitted; headline stands alone: **"איך אפשר לעזור היום?"**
- Authenticated: small greeting line above the headline: **"שלום, {שם פרטי} 👋"** (first
  token of `user.fullName`). Existing behavior (Home is reachable by any role, no gate) is
  left unchanged — a `PROFESSIONAL` who navigates to `/` still sees the customer Hero. This
  is a pre-existing product gap (professionals are never *sent* here after login — `login()`
  routes them straight to `/pro` — but nothing stops manual navigation), not something this
  visual milestone fixes; flagged in §9.
- Supporting copy (unchanged from today, already good, keeps DESIGN_SYSTEM §89's
  conversational tone): **"ספר לנו מה קרה, ו-Pronto יעזור לך למצוא את בעל המקצוע המתאים."**
- CTA panel: **"יש לי תקלה"** / **"בוא נמצא את האדם המתאים"** (unchanged, already correct
  per FRONTEND_AGENT §16's canonical Home CTA text).
- Trust indicators (3, per the dispatch's own suggested ideas, reworded to stay honest
  about what v1.0 actually does — see reasoning below):
  1. **"בעלי מקצוע מאומתים"** — `ShieldCheck` icon.
  2. **"מחיר ברור מראש"** — `Tag` icon.
  3. **"עדכוני סטטוס בזמן אמת"** — `Activity` icon (deliberately **not** "מעקב בזמן אמת" /
     "live tracking" wording — GPS/live-location tracking is a hard v1.0 exclusion per
     `overview.md` §2; the product does have real-time *status* notifications via polling,
     which is what this line accurately describes. Wording chosen specifically to avoid
     implying a live map that doesn't exist.)

### 2.3 Desktop composition (>=1024px)

Single hero section, text content centered/start-aligned per today's existing pattern
(no new asymmetric two-column split — that would compete with §36's own recommended single
dominant CTA panel and add layout complexity with no product benefit). Structure:

```
┌─────────────────────────────────────────────────────────────┐
│  שלום, אורי 👋                                                │
│  איך אפשר לעזור היום?                                          │
│  ספר לנו מה קרה, ו-Pronto יעזור לך למצוא את בעל המקצוע המתאים.  │
│                                                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                                        [Mascot, lg,   │   │
│  │   יש לי תקלה                            running,      │   │
│  │   בוא נמצא את האדם המתאים   ←            loop=true]    │   │
│  │                                                        │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                                │
│  🛡 בעלי מקצוע מאומתים   🏷 מחיר ברור מראש   ⚡ עדכוני סטטוס בזמן אמת │
└─────────────────────────────────────────────────────────────┘
```

The CTA panel becomes a horizontal flex row (still one clickable `<Link>`, still
`--radius-lg`, `--color-primary-light` background, per today's tokens): mascot as a flex
child on the inline-end side (visually left in RTL — "coming toward" the text/arrow, which
sit inline-start), text block + arrow circle inline-start. This is the concrete fix for
MS1's documented placeholder ("HomePage's restrained MS1 usage" — `loop={false}`,
absolutely-positioned above the button): the mascot is now a real layout participant inside
the CTA's own box model, not an overlay.

Trust indicator row: plain text row below the panel (not inside it — a CTA panel should
keep exactly one dominant action per DESIGN_SYSTEM §2/§90; trust info is priority-3
"identity/trust," rendered smaller and muted, `--color-text-secondary`, `--font-size-small`,
icon 16px per §28's "metadata" icon size). Horizontal `flex` with `gap: var(--space-8)`,
`justify-content: center`.

### 2.4 Mobile composition (<640px)

Same content, independently laid out (not desktop-shrunk):

```
┌─────────────────────┐
│ שלום, אורי 👋         │
│ איך אפשר לעזור       │
│ היום?                │
│ ספר לנו מה קרה...     │
│                      │
│ ┌──────────────────┐│
│ │  [Mascot, md,     ││
│ │   running,        ││
│ │   loop=true]      ││
│ │                   ││
│ │  יש לי תקלה        ││
│ │  בוא נמצא...   ←   ││
│ └──────────────────┘│
│                      │
│ 🛡 בעלי מקצוע מאומתים │
│ 🏷 מחיר ברור מראש     │
│ ⚡ עדכוני סטטוס       │
└─────────────────────┘
```

CTA panel stacks vertically (mascot above text, `size="md"`, centered) — same box, same
"composed inside, not floating above" fix. Trust row wraps to a vertical stack (one
indicator per line) rather than the desktop horizontal row, since 3 short lines read better
than 3 wrapped horizontal fragments at 375px. Headline uses the existing `--font-size-h1-mobile`
override already in `HomePage.module.css`.

### 2.5 Motion

- Whole hero content: wrapped once in `<motion.div variants={pageTransition} initial="initial" animate="animate">` — Home's first real `pageTransition` consumer.
- Mascot block: additionally wrapped in its own `<motion.div variants={mascotSlideIn} initial="initial" animate="animate">` for a one-shot entrance slide-in, per `shared/motion/README.md`'s own listed use ("Mascot movement... slide-in"). After that one-shot entrance settles, `Mascot`'s own `loop=true` CSS bounce + motion-lines (already built in MS1, `running` state) take over continuously — no new CSS is written, this only wires up existing component behavior.
- Trust row: no motion (static, secondary content — motion should communicate state change per §64, and this has none).

### 2.6 Component/file impact

- `app/HomePage.tsx` — rewritten: greeting logic (`useAuth()` already available via
  `shared/hooks`), CTA panel restructured to a flex row/column composing `Mascot` inline,
  new trust-indicator row (local JSX, 3 items, `lucide-react` icons — no new shared
  component, single consumer).
- `app/HomePage.module.css` — rewritten: `.mascotArea`'s `position: absolute` overlay rules
  removed entirely; new flex-based composition rules for the CTA panel; new
  `.trustRow`/`.trustItem` rules.
- No API/type changes.

### 2.7 Explicitly deferred (not built this milestone, flagged not silently dropped)

`DESIGN_SYSTEM.md` §35's full Home hierarchy also lists **"Popular services"** (category
quick-links) and **"Active booking"** below the CTA. Deferred:

- **Active booking**: already substantially served, product-wide, by
  `ActiveOrderIndicator` (a persistent floating indicator across every page, not just
  Home) — arguably a stronger treatment than an in-page section that only a customer who
  happens to be on `/` would see. No functional gap.
- **Popular services**: a real gap relative to §35, but out of scope for "Home Page Hero"
  specifically — it needs new product decisions this milestone wasn't asked to make (which
  categories count as "popular," a `ServiceCategoryCard` component with real icon-per-category
  choices — none exist anywhere in the codebase today; `shared/api/categories.ts`'s static
  list has no icon field) and a real destination (prefilled `/issues/new`?). Recommended as
  a candidate for a future, dedicated milestone rather than bolted on here — see §9.

---

## 3. Surface 2 — Header / Navigation

Files: `app/AppLayout.tsx`, `app/AppLayout.module.css`. New file:
`app/BottomNav.tsx`/`.module.css`. Small required follow-on edits:
`app/ActiveOrderIndicator.module.css` (one value), `app/AppLayout.module.css`'s `.main`
(one rule). **`features/dashboard/ProDashboardLayout.tsx` and its sidebar/tab-bar are
untouched** — out of scope per the dispatch, confirmed by direct inspection.

### 3.1 Desktop redesign (>=1024px, applies to both roles + guests)

Goals from the brief: cleaner header, stronger brand presence, reduce visual weight,
demote logout.

- **Brand**: keep the existing cropped-logo asset (no new asset work), but give it a bit
  more presence — increase the header height from `64px` to `72px` and the logo's rendered
  box from `96×32` to `112×38` (proportionally, using the same background-image
  crop/position technique already documented in `AppLayout.module.css`'s comment — only the
  size numbers change, not the technique). Add a subtle inline-start `padding-inline-end`
  gap so the brand doesn't crowd the first nav item on narrow desktop widths.
- **Nav link weight reduction**: today's `.navLink` is already a low-weight ghost-style text
  link (no background, `--color-text-secondary`) — kept as-is, it's already correct per
  §17-18. The one real weight problem is the **primary-looking `.navLinkPrimary`
  registration CTA competing with the guest state's own hierarchy** — left as-is, since a
  guest header genuinely should have one primary action ("הרשמה"), consistent with §2's "one
  visually dominant primary action" rule; not a violation.
- **Logout demotion (the actual ask)**: today's `.logoutButton` renders icon **+ visible
  "יציאה" label**, sitting at the same visual weight as "הפרופיל שלי"/"ההזמנות שלי" — reads
  as a co-equal primary nav action, which it shouldn't. **Resolution: icon-only,
  `aria-label="יציאה מהחשבון"`, native `title` tooltip, no visible text label**, moved to sit
  after (visually, inline-end of) the profile link, with an explicit small
  `margin-inline-start` gap separating it from the "real" nav links — reads as a secondary
  utility action, not a nav destination, matching the brief's "icon-only / secondary
  treatment" option. Hover color unchanged (`--color-error`), so intent is still legible on
  interaction, just not shouting by default. `ProfilePage.tsx` already has its own full
  "יציאה מהחשבון" button (with confirmation-adjacent account-deletion flow right below it)
  — the header's icon-only logout is a convenience shortcut, not the only path, so demoting
  it costs nothing.

### 3.2 Mobile (<640px): top bar + bottom nav

**Top bar** (all roles, all auth states — this is `AppLayout`'s shared shell):
- Shrinks to a compact single row: brand (smaller logo crop, e.g. `84×28`) on one side.
- Authenticated: `NotificationBell` + `BookingDraftIndicator` (already role/condition-gated
  correctly — `BookingDraftIndicator` naturally never renders for a `PROFESSIONAL` session)
  on the other side, both already compact/icon-forward components, reused unchanged (only a
  responsive check that `BookingDraftIndicator`'s pill still fits — its existing
  `white-space: nowrap` label may need a narrow-viewport variant that shows just the clock
  icon + dismiss, no text, under ~360px; a small addition to its own `.module.css`, not a
  redesign of the component).
- Guest: `התחברות` / `הרשמה` links, same as desktop, no bottom nav (see below).
- **No profile/orders/logout links in the mobile top bar** — those destinations move to the
  new bottom nav for `CUSTOMER`, and remain reachable via `/profile` regardless of how you
  got there (professionals still reach their dashboard the same way they already do today:
  `ProDashboardLayout`'s own `<640px` tab bar, one level down, untouched).

**Bottom nav** (new `app/BottomNav.tsx`, **`CUSTOMER`-only, authenticated-only** — same
gating condition `AppLayout.tsx` already uses for `ActiveOrderIndicator`):

```
┌───────────────────────────────────┐
│                                   │
│         (page content)           │
│                                   │
├───────────────────────────────────┤
│   🏠      📋       ❤️       👤    │
│  בית    הזמנות   מועדפים   פרופיל  │
└───────────────────────────────────┘
```

- `position: fixed; inset-inline: 0; bottom: 0;`, height `68px` (within DESIGN_SYSTEM §51's
  64-72px range), white background, `border-top: 1px solid var(--color-border)`, `z-index:
  var(--z-sticky)` (100).
- 4 items, icon (24px, lucide) + label (`--font-size-caption`, 600 weight) always both
  present — §51 explicitly forbids icon-only main nav.
- Built on React Router's `NavLink` (auto `aria-current="page"` when active — no manual
  wiring needed), active color `--color-primary`, inactive `--color-text-muted`, exactly
  matching §51's active/inactive spec.
- Each item's real hit area is the full `68px`-tall cell (>44px, satisfies §73 without any
  extra inset trick).
- `app/AppLayout.module.css`'s `.main` gains a mobile-only
  `padding-block-end: 68px` (only active under the same `<640px` breakpoint, and
  conceptually only "needed" when the bar is present — simplest correct implementation is
  an unconditional mobile rule, since the alternative — conditioning page padding on auth
  state — is meaningfully more complex for a purely cosmetic few extra px of whitespace on
  the rare unauthenticated/professional mobile view) so the last real content isn't hidden
  behind the fixed bar.

Routes (all already exist, none new): בית → `/`, הזמנות → `/orders`, מועדפים → `/favorites`,
פרופיל → `/profile`.

### 3.3 Bottom-nav item decision — explicit resolution of the flagged discrepancy

Two different sources disagree on the 4th mobile-nav slot:

- The milestone dispatch that reached `pronto-lead` suggested, as an example:
  **"Home/Orders/Notifications/Profile."**
- `DESIGN_SYSTEM.md` §50 (binding — "every frontend implementation must follow this
  document") explicitly lists: **בית / הזמנות / מועדפים / פרופיל** — favorites, not
  notifications.

**Decision: follow `DESIGN_SYSTEM.md`'s explicit list — בית/הזמנות/מועדפים/פרופיל.**
Reasoning:

1. The dispatch's own wording was an *example* ("suggested"), not a spec; the design system
   section is an explicit, named, binding list with no hedging language.
2. `favorites` is a real, shipped, standalone feature (`/favorites`, Frontend Milestone 8) —
   putting it in primary nav is additive discoverability, not exposing an unfinished
   surface.
3. Losing notifications from primary nav costs nothing: it already has a fully working,
   dedicated interaction pattern — the bell + anchored dropdown (`NotificationBell`,
   Frontend Milestone 5) — that doesn't need a nav tab/route at all (the backend feed has no
   pagination, so a dedicated page was deliberately never built). Per §3.2 above, the bell
   moves into (stays in) the mobile top bar rather than disappearing, so the capability is
   fully preserved, just not duplicated into the bottom nav as a second, redundant access
   path to the same feature.
4. §50 itself says "do not put every product feature in bottom navigation" — a 5th slot
   squeezing both favorites and notifications in would violate the section's own max-4-5-items
   guidance for arguably no benefit, since the bell already works well.

### 3.4 Required "spillover" fixes (small, mechanical, flagged explicitly)

Introducing a fixed bottom bar creates one real collision: `ActiveOrderIndicator` (the
floating action circle) is `position: fixed; bottom: var(--space-4)` (16px) on mobile —
sitting exactly where the new bottom nav now lives, for the exact same audience
(`CUSTOMER`, authenticated). **Required fix**: raise its mobile `bottom` offset to clear the
bar, e.g. `bottom: calc(68px + var(--space-3))`, inside its existing
`@media (max-width: 640px)` block — a one-value CSS change, not a redesign of that
component. Flagged here so it isn't rediscovered as a visual bug during QA.

### 3.5 Component/file impact

- `app/AppLayout.tsx` — desktop nav markup adjusted (logout icon-only), mobile top-bar
  markup added/adjusted, `<BottomNav />` rendered conditionally (mirrors the existing
  `{user?.role === 'CUSTOMER' && <ActiveOrderIndicator />}` pattern).
- `app/AppLayout.module.css` — header height/logo-size tokens, `.logoutButton` markup/CSS
  for icon-only treatment, mobile-only `.main` bottom padding, mobile top-bar layout rules.
- `app/BottomNav.tsx` (new) + `app/BottomNav.module.css` (new).
- `app/ActiveOrderIndicator.module.css` — one `bottom` value inside its existing mobile
  media query.
- `features/notifications/NotificationBell.module.css` — verify/adjust the dropdown panel's
  anchor positioning still works from the new, narrower mobile top bar (likely no change
  needed, flagged for pronto-coding to verify rather than pre-emptively rewritten here,
  since the component's actual anchoring logic isn't being touched).
- No route changes — every bottom-nav destination already exists in `router.tsx`.

---

## 4. Surface 3 — Login

Files: `features/auth/LoginPage.tsx`, `features/auth/LoginForm.tsx`. No new files.
**Zero logic changes** — every behavior below is preserved exactly: account-lockout banner
(`AccountLockoutBanner`), unverified-email banner + `/verify` link, field validation,
`ApiError` code handling (`ACCOUNT_LOCKED`/`INVALID_CREDENTIALS`/`EMAIL_NOT_VERIFIED`/
generic fallback).

### 4.1 Copy

- Title: **"התחברות ל-Pronto"** (was: "התחברות" — adds brand presence without losing
  clarity, per §89's "conversational but professional," not over-friendly).
- Description (new, `PageHeader`'s existing `description` prop): **"שמחים לראות אתכם שוב"**
  (a light, human touch, one short line — not over-explaining, per FRONTEND_AGENT §32).

### 4.2 Desktop composition

Kept inside the existing `.focused-page` (680px) container — login is a focused workflow
per §12, unchanged. Additions:

```
┌──────────────────────────────────┐
│  [Mascot idle, sm]                │
│  התחברות ל-Pronto                  │
│  שמחים לראות אתכם שוב               │
│                                    │
│  ┌──────────────────────────────┐│
│  │ [banners if any]              ││
│  │ אימייל  [___________]         ││
│  │ סיסמה   [___________]         ││
│  │                                ││
│  │      [ התחברות ]               ││
│  └──────────────────────────────┘│
│                                    │
│  אין לכם חשבון? הרשמה              │
└──────────────────────────────────┘
```

- `LoginForm`'s fields wrap in a `<Card>` (white surface, border, `--radius-lg`,
  `padding: var(--space-6)`) — today the form renders bare against the page background;
  wrapping it gives real visual weight/hierarchy to "the form" as one unit, per the brief's
  "better hierarchy, polished form" ask, and matches the pattern `ProfilePage.tsx` already
  uses (form content inside a `Card`).
- Small `<Mascot state="idle" size="sm" />` above the title (decorative, `label` omitted —
  `idle` is always static per MS1's own documented behavior, so this stays calm/restrained,
  not distracting from the form — appropriate for a low-stakes utility screen).
- **New footer link** (small addition, purely additive navigation, not a logic change):
  **"אין לכם חשבון? <Link to="/register">הרשמה</Link>"** below the card. This closes a real,
  pre-existing small gap — `LoginForm.tsx` today has no path to registration at all, and
  `RegisterChoicePage.tsx` has no reciprocal link back to login either (not fixed here,
  out of this surface's scope, flagged in §9).

### 4.3 Mobile composition (<640px)

Same structure, single column, `Card` full-width within the existing `--space-4` mobile
page padding. Mascot may be omitted at the very narrowest widths if vertical space is tight
on short devices (a CSS `display: none` under e.g. `max-height: 700px` is a reasonable
implementer call — not prescribed precisely here, since it's a minor refinement, not a
structural decision).

### 4.4 Motion / interaction polish

- Outer content wrapped in `pageTransition` (mount fade+rise), same as Home.
- Banner appearance (lockout/unverified-email/generic error) gets a quick CSS fade-in
  (`opacity`/`transform` transition using the existing `--duration-fast`/`--ease-out`
  tokens) when it first mounts — CSS tier per `shared/motion/README.md` ("simple opacity
  toggles"), not `framer-motion`. No change to *when* banners appear/disappear, only how
  they visually enter.
- `Button`'s existing `loading` prop (spinner, MS1) is the only loading-state mechanism —
  already correct, nothing to add.

### 4.5 Component/file impact

- `LoginPage.tsx` — adds `description`, mascot, register-link footer; `PageHeader` usage
  otherwise unchanged.
- `LoginForm.tsx` — wraps existing JSX in `<Card>`; **no state/handler changes**.
- No new CSS module needed beyond `formStyles.module.css`'s existing shared rules plus a
  couple of new small classes for the card wrapper/footer link (can live in a new
  `LoginPage.module.css` or extend `formStyles.module.css` — implementer's call, both are
  reasonable given the file is already shared across auth screens).

---

## 5. Surface 4 — Register Choice

Files: `features/auth/RegisterChoicePage.tsx`, `features/auth/RoleChooser.tsx`,
`features/auth/RoleChooser.module.css`.

### 5.1 The actual problem being fixed

Today's `RoleChooser.tsx` renders two `Card`s that are visually identical containers,
differentiated only by icon glyph + text content — exactly what the brief calls out
("current `RoleChooser.tsx` is literally two identical `Card`s"). Fix: give each option a
genuinely distinct visual personality, not just different words.

### 5.2 Redesign

Two option cards, each restructured with: icon → headline → one-line subcopy → 2 short
value-prop tags (`Badge`, `size="sm"`) → an inline-arrow affordance reinforcing
clickability (§26 — clickable cards must clearly feel interactive).

**"אני צריך עזרה בבית"** (customer):
- Icon: `Home` (new to this app, distinct from the professional option's `Wrench` — more
  literally "home" than the current generic `User`).
- Subcopy: **"מחפש/ת בעל מקצוע אמין ומהיר לתיקון בבית"**.
- Tags: `בעלי מקצוע מאומתים` · `מחיר ברור`.
- Background tint: `--color-primary-light` (the app's warm/brand tint) — reinforces "this is
  the primary/default consumer path."

**"אני בעל מקצוע"** (professional):
- Icon: `Wrench` (kept — already correct/distinct from the customer option).
- Subcopy: **"רוצה לקבל פניות איכותיות מלקוחות באזור שלך"**.
- Tags: `פניות איכותיות` · `בלי דמי הרשמה`.
- Background tint: `--color-surface-secondary` (neutral gray, deliberately **not**
  primary-teal) — visually signals "a different track," not a lesser one; avoids implying
  professionals are the secondary/lower-priority option through color alone.

Only 2 tags per card, small `Badge`s — deliberately restrained, not a marketing wall of
claims, per §91's anti-visual-noise rule and §33's "no aggressive marketing language."

### 5.3 Desktop composition (>=1024px)

2-column grid (kept from today), each card taller/richer than today's compact version
(roughly `240-280px` min-height to comfortably fit icon/headline/subcopy/tags/arrow without
crowding), `gap: var(--space-6)`.

### 5.4 Mobile composition (<640px)

Single column, stacked (kept from today's existing `@media (max-width: 640px)` collapse) —
each card still full content, not compressed; cards simply stack instead of sitting
side-by-side. `min-height` may relax slightly on mobile to avoid excess scroll, implementer's
call.

### 5.5 Motion

Container: `listStagger`. Each card: reuses `pageTransition`'s own fade+rise shape as its
item variant (a deliberate reuse, not a new variant — `pageTransition`'s
`{opacity:0,y:8} → {opacity:1,y:0}` is exactly what a staggered card entrance needs; no new
`Variants` object is defined for this).

### 5.6 Component/file impact

- `RoleChooser.tsx` — restructured to a small local config array (2 entries: icon, title,
  subcopy, tags, tint) mapped into cards — **not** a new shared component (single consumer,
  this file only, per §1.3's reuse discipline).
- `RoleChooser.module.css` — richer card layout rules, tag row, tint variants.
- `RegisterChoicePage.tsx` — unchanged structurally (still `PageHeader` + `RoleChooser`);
  may gain the `pageTransition` wrapper.

---

## 6. Surface 5 — Registration → progressive onboarding

Files: `features/auth/CustomerRegisterForm.tsx`, `features/auth/ProfessionalRegisterForm.tsx`,
`features/auth/CustomerRegisterPage.tsx`, `features/auth/ProfessionalRegisterPage.tsx`,
`shared/api/auth.ts`. New file: a shared wizard step-frame (name TBD by implementer, e.g.
`features/auth/RegistrationWizardShell.tsx`).

### 6.1 Shared wizard shell — justified, built once

Both wizards need identical mechanics: a `PageHeader` with `steps={{current, total}}` (MS1
already ships this), an `AnimatePresence`-wrapped `stepTransition`-animated region for the
current stage's fields, and a back/continue (or back/submit-on-last-stage) footer. That's a
real second consumer inside this same milestone — satisfies the "only introduce a new shared
abstraction if it has a real second consumer" bar explicitly, unlike the single-consumer
cases in §2.6/§5.6.

**Shape** (conceptual — implementer decides exact prop names): owns rendering of
`PageHeader` (title + `steps`) + the animated stage region + a footer button row (`Button
variant="secondary"` back, `Button variant="primary"` continue/submit with its own
`loading` state). Does **not** own form field state, per-field validation, or the actual
API call — those stay inside `CustomerRegisterForm`/`ProfessionalRegisterForm` exactly like
today, just reorganized into per-stage field groups + a `currentStage`/`direction` local
state driving which group is visible and which way `stepTransition` animates
(`custom={direction}`, `1` forward / `-1` back, per `variants.ts`'s own documented usage).

Because this component now owns the page header, `CustomerRegisterPage.tsx`/
`ProfessionalRegisterPage.tsx` become thin wrappers: no more standalone `<PageHeader
onBack=... />` at the page level (that would duplicate/conflict with the wizard's own
per-stage header) — instead each page passes an `onExit` callback (`() =>
navigate('/register')`) into its form, used only by **stage 1's** back button; stage 2+'s
back button moves to the previous stage internally, not out of the flow.

### 6.2 Customer wizard — 3 stages

**Stage 1 — פרטים בסיסיים (Basic info)**: `fullName`, `email`, `password`,
`confirmPassword`, **`phone` (new — see §6.4)**. Validated together before "המשך" advances.

**Stage 2 — כתובת (Address)**: unchanged `AddressFormFields` (city/street/houseNumber
required, apartment/floor/entrance/addressNotes optional) — this is the existing address
step's fields, just isolated onto its own screen instead of appended below stage 1's fields
in one long form.

**Stage 3 — אישור (Confirmation)**: read-only summary of everything entered across stages
1-2 (name, email, phone, full address) + the real submit button ("יצירת חשבון"), which fires
the **same, unchanged** `registerCustomer()` call (still one `POST /api/auth/register`, all
fields collected across 3 UI stages then sent together — no backend/contract change beyond
§6.4's `phone` addition).

Phone placement: stage 1 (basic info), per the dispatch's own default suggestion — no
strong reason found to place it elsewhere (it's not address-shaped, and grouping it with
name/email/password keeps "who you are" together as one stage, "where you live" as another).

### 6.3 Error routing across stages (new concern introduced by splitting the form)

Today's flat form sets field errors directly on the one visible screen. A 3-stage wizard
needs explicit handling for what happens when the final `POST` (fired from stage 3) comes
back with a field error that belongs to an earlier stage — e.g. `DUPLICATE_EMAIL` (stage 1's
`email`) or an address validation error (stage 2). **Required behavior**: on such an error,
the wizard must navigate back to the stage that owns the offending field and surface the
error there (reusing the exact same `getFieldErrorMessages`/`ADDRESS_FIELD_KEYS`-style
routing logic `CustomerRegisterForm.tsx` already has today — just re-targeted to jump stages
instead of only setting local state on a single visible screen). A generic/banner-level
error (no field mapping) stays on stage 3, where the submit button lives. This is called out
explicitly because it's easy to silently drop during the refactor and would otherwise
regress a working error-handling path.

### 6.4 P0 bug fix — `phone` missing end-to-end (in scope, not just a UI tweak)

Confirmed by direct inspection of `backend/.../auth/dto/CustomerRegistrationData.java`:
`phone` is `@NotBlank`. Confirmed by direct inspection of `shared/api/auth.ts`:
`RegisterCustomerPayload`/`RegisterRequestData.customer` have **no `phone` field at all**,
and `CustomerRegisterForm.tsx` never collects it. Every real customer signup today gets a
silent `400`. Required fix, all three layers:

1. `shared/api/auth.ts` — add `phone: string` to `RegisterCustomerPayload`; add `phone` to
   `RegisterRequestData.customer`'s shape (sibling of `defaultAddress`, matching the
   backend's `CustomerRegistrationData(defaultAddress, phone)` shape exactly — **not** nested
   inside the address object); `registerCustomer()` passes `payload.phone` through.
2. `CustomerRegisterForm.tsx` — new `phone` state + `Input` (stage 1), `autoComplete="tel"`,
   required, non-blank client validation mirroring the backend's `@NotBlank @Size(max=20)`
   (length-capped, no stricter regex — consistent with this codebase's existing minimal
   validation style elsewhere, e.g. `ProfilePage.tsx`'s own phone field has no format regex
   either).
3. Wired into the final `registerCustomer()` call's payload.

### 6.5 Professional wizard — backend-capability audit (why the literal 6-stage ask isn't buildable as specified)

Verified directly against source, not assumed:

- `ProfessionalRegistrationData` (`backend/.../auth/dto/ProfessionalRegistrationData.java`)
  has exactly 3 fields: `categoryId`, `serviceArea`, `basePrice`. **No sub-service field at
  all.**
- Sub-services are only settable via `PUT /api/professionals/me/sub-services`
  (`shared/api/professionals.ts`) — a `PROFESSIONAL`-only, authenticated, self-service
  endpoint.
- There is no working-hours/availability field or endpoint reachable at registration time —
  the weekly calendar lives entirely at `/pro/availability`, a fully separate, later,
  authenticated feature.
- **Critical, verified directly in `AuthService.java`**: `register()` returns
  `RegisterResponse` — no JWT/token field. `verify()` returns `VerifyResponse` — also no
  token field. Only `login()` returns a `LoginResponse` with a real `token`. **There is
  genuinely no authenticated session available at any point during or immediately after
  registration.** A professional must: register → check email → enter the verify code
  (a separate action, `VerifyCodeForm`) → log in with their password (a third, separate
  action) — only *then* does any bearer token exist that `PUT
  /api/professionals/me/sub-services` (or any `/pro/*` authenticated route) could use.

**Conclusion**: the literal 6-stage list — "(1) personal details, (2) profession/sub-services,
(3) service area, (4) pricing, (5) availability, (6) profile completion" — is not achievable
as 6 real, backend-writable data-collection steps without inventing new backend behavior,
which this milestone explicitly must not do (`FRONTEND_AGENT.md` §9-10 — don't assume fields
exist, don't fake product functionality).

### 6.6 Professional wizard — resolved design: **4 stages**, not 6

Two options were offered by the dispatch: (a) collect sub-service selections UI-only and
silently drop them (flagged as a possible future backend-extension candidate), or (b) treat
unbuildable steps as honest informational/preview content. **Chosen: a hybrid closer to
(b), with one small twist that recovers real value from (a) without its downside.**

Collecting a real selection (checkboxes) and then *silently discarding it* on submit is
worse than not asking at all — `FRONTEND_AGENT.md` §10 ("Never Fake Product Functionality")
and §53 ("No Placeholder Completion") both argue directly against it: a professional who
carefully checks 4 sub-services and sees them vanish with no explanation is a worse
experience than never being asked. So this design does **not** collect sub-service
selections in the wizard. Instead:

**Stage 1 — פרטים אישיים (Personal details)**: `fullName`, `email`, `password`,
`confirmPassword`. **No `phone`** — confirmed not a backend field for `PROFESSIONAL`
registration (only `CUSTOMER`'s `CustomerRegistrationData` has it); adding one here would be
inventing a field the backend doesn't accept.

**Stage 2 — תחום עיסוק ואזור שירות (Profession & service area)**: `categoryId` (`Select`,
real field), `serviceArea` (`Input`, real field) — merges the dispatch's original stages 2+3,
since both are real, closely-related fields ("what you do" + "where you do it") and neither
alone justifies a full stage. **Honest sub-service preview** (the recovered value from
option (a), without faking a save): once a category is chosen, fetch
`getCategoriesWithSubServices()` (already-built, public, no-auth-required endpoint — safe to
call before login exists) and render that category's sub-service names as small,
**non-interactive** preview chips beneath the select, with one explicit line: **"לאחר אישור
החשבון תוכל לבחור מתוכם בעמוד הפרופיל שלך"** (you'll be able to choose from these on your
profile page once your account is approved). This shows real data from a real endpoint,
sets accurate expectations, and never implies a selection is being saved. Loading state:
`Skeleton` chips while fetching; on fetch failure, the preview is silently omitted (a
non-critical enhancement — must never block registration).

**Stage 3 — תמחור ומסמכים (Pricing & documents)**: `basePrice` (`Input`, real field),
`profilePhoto` (`ImageUploadField`, optional, real field), `verificationDocument`
(`DocumentUploadField`, required, real field) — merges the dispatch's stage 4 (pricing) with
the *real* content of its stage 6 (profile completion is, at registration time, exactly
these two file uploads and nothing else).

**Stage 4 — סיכום ומה הלאה (Summary & what's next)**: read-only summary (name, email,
category, service area, price) + the real submit button ("יצירת חשבון," same unchanged
`registerProfessional()` call) + an honest, clearly-framed **next-steps block** (not a data
form) recasting the dispatch's stage 5 (availability): **"1. אימות האימייל שלך · 2. התחברות
לחשבון · 3. השלמת הפרופיל: תת-התמחויות ושעות זמינות, בעמוד הפרופיל שלך"** — framed
explicitly as what happens *after* signup, with no interactive controls, so nothing can be
mistaken for a save.

**Net result: 4 stages, not 6.** Two of the requested six "stages" (sub-services,
availability) had zero real backend-writable content at registration time; folding them
into the stages that do have real content, plus one honest closing "what's next" screen,
avoids both fake data collection and two content-free filler stages, while still
substantively covering everything the 6-stage list wanted a professional to see/understand
during onboarding.

**This is a known limitation/deviation from the milestone dispatch, prominently repeated in
§9 — not just stated once here.**

### 6.7 Component/file impact

- `ProfessionalRegisterForm.tsx` — restructured into the 4-stage wizard above; gains a
  `getCategoriesWithSubServices()` call (already-exported from `shared/api/professionals.ts`,
  no new API function needed) triggered on category selection, `Skeleton`-backed loading
  state for the preview chips.
- `CustomerRegisterForm.tsx` — restructured into the 3-stage wizard (§6.2), gains the
  `phone` field (§6.4).
- `CustomerRegisterPage.tsx`/`ProfessionalRegisterPage.tsx` — become thin wrappers per §6.1.
- `shared/api/auth.ts` — `phone` addition per §6.4. No other `auth.ts` changes — the
  professional registration payload shape is completely unchanged (still `categoryId`/
  `serviceArea`/`basePrice`/`verificationDocument`/`profilePhoto`).
- New: wizard step-frame component (§6.1).
- `features/auth/README.md` — needs updating by whoever implements this (per this project's
  standing rule that every package/module gets a maintained `.md` doc) to reflect: the new
  wizard shell, the 3-stage/4-stage structure, and the `phone` field addition.

---

## 7. Open Questions / Limitations (prominent — not buried in prose above)

1. **Professional registration is 4 stages, not the dispatch's requested 6** (§6.5-6.6).
   Root cause: no authenticated session exists during/immediately after registration
   (verified in `AuthService.java`), and `ProfessionalRegistrationData` has no
   sub-service/availability fields. This is the single most consequential design decision
   in this doc — pronto-lead should confirm agreement with the resolution before
   `pronto-coding` builds it, since it's a visible product-shape change from what was asked.
2. **Recommendation (not a decision made here) for pronto-lead to route as separate scope**:
   a small backend extension — `ProfessionalRegistrationData` gaining an optional
   `subServiceIds: List<Long>`, atomically saved during `register()` alongside the
   professional row — would let a future milestone upgrade stage 2's preview chips into a
   real, saved selection, closing the gap identified in §6.6 properly instead of working
   around it. Not designed in detail here, not approved, purely flagged as a candidate per
   the dispatch's own instruction not to decide backend scope unilaterally.
3. **Home Hero deliberately does not build DESIGN_SYSTEM §35's "Popular services" section**
   (§2.7) — a real gap relative to the full Home hierarchy, recommended as a future,
   dedicated milestone (needs new per-category icon decisions with no existing precedent
   anywhere in the codebase).
4. **A `PROFESSIONAL` account can still land on the customer Home Hero** if they navigate to
   `/` manually (pre-existing, not introduced or fixed by this milestone — `login()` already
   routes them to `/pro`, nothing gates `/` itself by role). Worth a product decision
   eventually (redirect professionals away from `/`?) but out of scope here.
5. **Login ↔ Register Choice reciprocal linking is only half-fixed**: this doc adds a
   "הרשמה" link from Login (§4.2), but does not add a "כבר יש לך חשבון? התחברות" link back
   from `RegisterChoicePage.tsx` — noticed while designing Login, but adding it to Register
   Choice too is a trivial, low-risk follow-on `pronto-coding` should just also do while
   in this file, not something requiring separate sign-off.
6. **Toast on successful registration** (`useToast`, shipped by MS1, currently unused
   anywhere) would be a nice, low-risk enhancement right before navigating to `/verify` —
   e.g. `נרשמת בהצלחה! שלחנו קוד אימות לאימייל שלך`. Not mandated by this doc (today's
   navigate-to-`/verify` already communicates success contextually), just flagged as an easy
   win now that the primitive exists and has zero consumers yet.
7. **`BookingDraftIndicator`'s mobile top-bar fit** (§3.2) may need a narrow-width label
   variant — flagged as a likely-needed small addition, not fully specified pixel-by-pixel
   here, since it's a minor responsive refinement to an already-correct component, not a
   structural decision.

---

## 8. Suggested implementation order (for `pronto-coding`, sequencing only — not a task breakdown)

1. `shared/api/auth.ts` `phone` fix (§6.4) — small, self-contained, unblocks the customer
   wizard and fixes a live P0 bug independently of everything else here.
2. Wizard step-frame shared component (§6.1) — both registration forms depend on it.
3. Customer registration wizard (§6.2-6.4).
4. Professional registration wizard (§6.5-6.7).
5. Register Choice redesign (§5) — no dependency on the wizards, but naturally sits
   "before" them in the user flow, easy to sequence here.
6. Login redesign (§4) — fully independent, can be done in parallel with 1-5.
7. Header/Navigation (§3) — touches shared shell code every other screen renders inside;
   safest done after (or carefully coordinated with) the auth-screen work above, since
   `AppLayout` wraps all of it.
8. Home Hero (§2) — fully independent, can be done in parallel with anything above.
