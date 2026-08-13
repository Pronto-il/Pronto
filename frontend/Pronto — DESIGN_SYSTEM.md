# Pronto Design System

## 0. Purpose

This document defines the visual and interaction system for the Pronto frontend.

It is the source of truth for:

- Colors
- Typography
- Spacing
- Buttons
- Inputs
- Cards
- Navigation
- Service categories
- Professional cards
- Statuses
- Modals and bottom sheets
- Responsive behavior
- RTL behavior
- Loading, error and empty states
- Motion
- Visual hierarchy

Every frontend implementation must follow this document.

Do not invent new styles on a screen-by-screen basis.

---

# 1. Design Direction

Pronto is not a traditional directory of service professionals.

The desired experience is:

> Describe the problem → find the right professional → understand when they can arrive → book.

The visual experience should therefore feel closer to a modern on-demand consumer app than a traditional marketplace website.

## Product personality

Pronto should feel:

- Fast
- Trustworthy
- Calm
- Clean
- Modern
- Local
- Human
- Professional
- Helpful

Pronto should NOT feel:

- Corporate
- Enterprise
- Overly luxurious
- Childish
- Overly colorful
- Like a classifieds website
- Like an admin dashboard
- Like a generic AI startup

---

# 2. Core UX Principle

Every screen should answer three questions immediately:

1. Where am I?
2. What information matters here?
3. What is the main thing I should do next?

Each screen should normally contain ONE visually dominant primary action.

Do not make several actions visually compete with each other.

---

# 3. Brand Color Direction

Use a clean neutral interface with a strong Pronto brand color.

The brand should rely primarily on:

- Deep Navy
- Pronto Green / Teal
- White
- Neutral grays

This gives Pronto both:

- trust
- modern consumer-app energy

without looking like a bank or enterprise system.

---

# 4. Color Tokens

## Primary

```css
--pronto-primary: #0F766E;
--pronto-primary-hover: #0D665F;
--pronto-primary-active: #0A5751;
--pronto-primary-light: #E8F5F3;
```

Primary is used for:

- Main CTA
- Selected items
- Active navigation
- Important links
- Focus states
- Positive brand moments

Do not cover large areas of the interface with the primary color.

---

## Dark / Text

```css
--pronto-dark: #111827;
--pronto-text-primary: #171717;
--pronto-text-secondary: #525252;
--pronto-text-muted: #737373;
```

Main body text should normally use:

```css
#171717
```

Avoid pure black `#000000` for normal UI text.

---

## Background

```css
--pronto-bg: #F7F8FA;
--pronto-surface: #FFFFFF;
--pronto-surface-secondary: #F2F4F5;
```

Main pages:

```css
background: #F7F8FA;
```

Cards:

```css
background: #FFFFFF;
```

---

## Borders

```css
--pronto-border: #E5E7EB;
--pronto-border-strong: #D1D5DB;
```

Borders should be subtle.

Cards should usually use a border instead of a large shadow.

---

# 5. Semantic Colors

## Success

```css
--success: #15803D;
--success-bg: #F0FDF4;
```

## Warning

```css
--warning: #B45309;
--warning-bg: #FFFBEB;
```

## Error

```css
--error: #DC2626;
--error-bg: #FEF2F2;
```

## Information

```css
--info: #2563EB;
--info-bg: #EFF6FF;
```

---

# 6. SOS Color

SOS must have its own semantic identity.

Use:

```css
--sos: #E5484D;
--sos-hover: #D93D42;
--sos-bg: #FFF1F2;
```

Do NOT use the normal Pronto primary color for SOS.

SOS should communicate urgency without making the application look dangerous.

Correct:

```text
SOS
Professional needed urgently
```

with a controlled red accent.

Incorrect:

- Flashing red backgrounds
- Large warning icons everywhere
- Whole page becoming red

---

# 7. Color Usage Rule

Approximate visual balance:

```text
70–80% Neutral / White
15–20% Dark text / surfaces
5–10% Brand color
```

Brand color should communicate hierarchy.

It should not be decoration.

---

# 8. Typography

Use:

```text
Inter
```

for English and numbers.

For Hebrew use:

```text
Heebo
```

Preferred font stack:

```css
font-family:
  "Heebo",
  "Inter",
  system-ui,
  -apple-system,
  BlinkMacSystemFont,
  "Segoe UI",
  sans-serif;
```

---

# 9. Font Weights

Use primarily:

```text
400 Regular
500 Medium
600 SemiBold
700 Bold
```

Avoid excessive use of 800/900.

Most interface text should use 400–600.

---

# 10. Typography Scale

## Display

```css
font-size: 40px;
line-height: 48px;
font-weight: 700;
```

Desktop marketing or major onboarding only.

---

## H1

Desktop:

```css
font-size: 32px;
line-height: 40px;
font-weight: 700;
```

Mobile:

```css
font-size: 26px;
line-height: 34px;
font-weight: 700;
```

---

## H2

```css
font-size: 24px;
line-height: 32px;
font-weight: 700;
```

---

## H3

```css
font-size: 20px;
line-height: 28px;
font-weight: 600;
```

---

## Body Large

```css
font-size: 17px;
line-height: 26px;
font-weight: 400;
```

---

## Body

```css
font-size: 16px;
line-height: 24px;
font-weight: 400;
```

---

## Small

```css
font-size: 14px;
line-height: 20px;
font-weight: 400;
```

---

## Caption

```css
font-size: 12px;
line-height: 16px;
font-weight: 500;
```

Use sparingly.

Do not put important information in 12px text.

---

# 11. Spacing System

Use an 8px-based system.

Allowed primary spacing values:

```text
4px
8px
12px
16px
24px
32px
40px
48px
64px
80px
```

Default component spacing:

```text
Between icon and text: 8px
Between related elements: 8–12px
Between fields: 16px
Inside cards: 16–24px
Between sections: 32px
Between major page sections: 48–64px
```

Do not invent random spacing values.

---

# 12. Page Width

## Normal desktop page

```css
max-width: 1200px;
margin-inline: auto;
padding-inline: 24px;
```

## Focused workflow

For:

- Request creation
- Checkout
- Booking
- Forms

use:

```css
max-width: 680px;
margin-inline: auto;
```

---

# 13. Border Radius

Use four levels.

## Small

```css
8px
```

Used for:

- Small tags
- compact controls

## Standard

```css
12px
```

Used for:

- Buttons
- Inputs
- Selects

## Card

```css
16px
```

Used for:

- Cards
- Panels

## Large

```css
20px
```

Used for:

- Modals
- Mobile bottom sheets
- Major floating surfaces

Do not create random radius values.

---

# 14. Shadows

Pronto should not rely heavily on shadows.

Default card:

```css
border: 1px solid #E5E7EB;
box-shadow: none;
```

Elevated UI:

```css
box-shadow:
  0 4px 16px rgba(0, 0, 0, 0.06);
```

Large modal:

```css
box-shadow:
  0 20px 50px rgba(0, 0, 0, 0.12);
```

Avoid dramatic shadows.

---

# 15. Buttons

All standard buttons:

```css
min-height: 48px;
border-radius: 12px;
font-size: 16px;
font-weight: 600;
```

Mobile primary actions should normally be at least:

```text
48px high
```

Prefer:

```text
52px
```

for checkout / booking CTAs.

---

# 16. Primary Button

```css
background: #0F766E;
color: white;
```

Hover:

```css
background: #0D665F;
```

Example:

```text
המשך
```

or:

```text
בחירת בעל מקצוע
```

---

# 17. Secondary Button

```css
background: white;
color: #171717;
border: 1px solid #D1D5DB;
```

Never visually compete with the primary action.

---

# 18. Ghost Button

For low-priority actions:

```css
background: transparent;
```

Examples:

- שינוי
- ביטול
- הצג הכל

---

# 19. Destructive Button

```css
background: #DC2626;
color: white;
```

Use only when the action actually destroys/removes something.

---

# 20. Full Width Buttons

On mobile, the primary CTA for a flow should usually be:

```css
width: 100%;
```

Desktop buttons should usually size according to content unless they belong to a narrow form.

---

# 21. Sticky Mobile CTA

For important final actions such as:

- Choose professional
- Continue
- Confirm booking
- Accept request

use a sticky mobile action area when the page can scroll.

Structure:

```text
--------------------------------
Page content
Page content
Page content

--------------------------------
[      בחירת בעל מקצוע      ]
--------------------------------
```

The CTA must remain visible near the bottom of the screen.

---

# 22. Inputs

Standard input:

```css
height: 48px;
border: 1px solid #D1D5DB;
border-radius: 12px;
padding-inline: 14px;
background: white;
```

Focus:

```css
border-color: #0F766E;
box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
```

---

# 23. Input Label

Labels must always exist.

```css
font-size: 14px;
font-weight: 600;
margin-bottom: 6px;
```

Do not replace labels with placeholders.

---

# 24. Textarea

Minimum height:

```css
120px
```

Problem description should feel comfortable to type into.

If character limits exist, show them subtly.

---

# 25. Cards

Base card:

```css
background: #FFFFFF;
border: 1px solid #E5E7EB;
border-radius: 16px;
padding: 20px;
```

Hoverable desktop card:

```css
transition: border-color 150ms ease,
            box-shadow 150ms ease,
            transform 150ms ease;
```

Hover:

```text
Slight border emphasis
Very subtle elevation
```

Do not move cards dramatically.

---

# 26. Clickable Cards

Clickable cards must clearly feel interactive.

Use:

- pointer cursor
- subtle hover state
- visible selected state

Selected:

```css
border: 2px solid #0F766E;
background: #F8FFFD;
```

---

# 27. Category Cards

Service categories should be visually simple.

Recommended mobile structure:

```text
┌──────────────┐
│      🔧      │
│ אינסטלטור    │
└──────────────┘
```

Use real consistent vector icons, not emojis in production.

Desktop:

```text
4 columns
```

Tablet:

```text
3 columns
```

Mobile:

```text
2 columns
```

Card:

```css
min-height: 112px;
padding: 16px;
border-radius: 16px;
```

Icon:

```text
28–32px
```

Category label:

```text
16px / 600
```

---

# 28. Icons

Use one icon system only.

Recommended:

```text
Lucide
```

Use stroke-style icons consistently.

Default sizes:

```text
16px — metadata
20px — actions
24px — navigation
28–32px — categories
```

Avoid mixing:

- filled icons
- outline icons
- emojis
- different icon packs

---

# 29. Provider Card

This is one of the most important components in Pronto.

Information hierarchy:

```text
Photo + Name + Verification
Rating + Reviews
Profession
Key reason to choose
ETA
Price
CTA
```

Recommended layout:

```text
┌────────────────────────────────────┐
│ [PHOTO]  אייל כהן ✓                │
│          אינסטלטור                 │
│          ★ 4.9 · 127 ביקורות       │
│                                    │
│ ⚡ יכול להגיע תוך כ־20 דקות         │
│                                    │
│ מחיר ביקור                         │
│ ₪220                               │
│                                    │
│              [ צפייה בפרופיל ]     │
└────────────────────────────────────┘
```

---

# 30. Provider Image

Standard:

```css
width: 64px;
height: 64px;
border-radius: 50%;
object-fit: cover;
```

Provider profile page:

```text
88–104px
```

Do not use tiny avatars when trust is important.

---

# 31. Rating

Recommended format:

```text
★ 4.9 · 127 ביקורות
```

Do not display five large stars inside every provider card.

The numerical rating is easier to scan.

Use:

```text
Star icon + number + review count
```

---

# 32. Provider Card — Primary Comparison Information

Users should be able to compare providers quickly.

The following information should visually stand out:

## Recommended mode

```text
Recommendation
Rating
ETA
Price
```

## Cheapest mode

Emphasize:

```text
Price
```

## Fastest mode

Emphasize:

```text
ETA
```

Do not radically redesign the card when sorting changes.

Only adjust emphasis.

---

# 33. Recommended Badge

Example:

```text
מומלץ עבורך
```

Style:

```css
background: #E8F5F3;
color: #0F766E;
```

Do not use "BEST", trophies or aggressive marketing language.

---

# 34. Filters

Use horizontal filter chips.

Example:

```text
[ מומלצים ] [ הכי מהירים ] [ הזולים ביותר ]
```

Selected:

```css
background: #171717;
color: white;
```

Unselected:

```css
background: white;
color: #525252;
border: 1px solid #E5E7EB;
```

Allow horizontal scrolling on narrow mobile screens rather than squeezing all labels.

---

# 35. Home Screen

The customer home screen should NOT look like a dashboard.

Recommended hierarchy:

```text
Header

שלום, אורי
איך אפשר לעזור היום?

[ יש לי תקלה ]

Popular services
[Plumber] [Electrician]
[AC]      [Locksmith]

Active booking
(if one exists)
```

Main attention should go to:

```text
יש לי תקלה
```

not profile/navigation features.

---

# 36. Main Customer CTA

The central home action can be visually larger than a normal button.

Example:

```text
┌───────────────────────────────────┐
│                                   │
│           יש לי תקלה              │
│     בוא נמצא את האדם המתאים       │
│                                   │
└───────────────────────────────────┘
```

Avoid aggressive gradients.

A very light brand background may be used.

---

# 37. Request Creation

Use a step-by-step flow.

Do not show the complete form at once.

Recommended:

### Step 1

```text
באיזה תחום התקלה?
```

### Step 2

```text
מה קרה?
```

### Step 3

```text
אפשר להוסיף תמונה?
```

### Step 4

Clarifying questions if necessary.

### Step 5

Results.

---

# 38. Step Progress

Do not use a giant wizard progress UI.

Use a subtle indicator.

Example:

```text
שלב 2 מתוך 4
━━━━━━━────────
```

or a simple progress bar.

The user should understand progress without feeling like they are completing a long form.

---

# 39. Photo Upload

Recommended:

```text
┌─────────────┐
│      +      │
│ הוספת תמונה │
└─────────────┘
```

After upload:

```text
[thumbnail] [thumbnail] [+]
```

Image cards:

```css
width: 88px;
height: 88px;
border-radius: 12px;
```

---

# 40. AI Interaction

AI is infrastructure, not the product personality.

Never show:

```text
AI Classification
Model confidence
Prediction
Inference
```

Instead:

```text
כדי שנמצא את בעל המקצוע המתאים,
יש לנו עוד שאלה קטנה.
```

Questions should feel like part of the normal booking flow.

---

# 41. Search / Matching Loading Screen

When matching professionals, do not show only a spinner.

Use a short transition state.

Example:

```text
מחפשים בעלי מקצוע זמינים באזור שלך…
```

Optionally show lightweight skeleton cards beneath it.

Avoid fake percentages unless there is real progress data.

---

# 42. Results Screen

Hierarchy:

```text
מצאנו 8 בעלי מקצוע מתאימים

[מומלצים] [הכי מהירים] [הזולים ביותר]

Provider Card
Provider Card
Provider Card
```

Do not overload the top of the screen with filters.

---

# 43. Provider Profile

Recommended mobile structure:

```text
[Back]

      [Large photo]
       אייל כהן ✓
       אינסטלטור

★ 4.9 · 127 ביקורות

[ ETA ] [ Jobs ] [ Rating ]

About

Pricing

Availability

Reviews

----------------------
[ בחירת בעל מקצוע ]
----------------------
```

---

# 44. Trust Indicators

Trust information should appear near the provider identity.

Examples:

```text
✓ זהות מאומתת
✓ מספר טלפון מאומת
```

Only show verification claims that are actually supported by the backend.

Never visually claim verification if it does not exist.

---

# 45. Reviews

Review card:

```text
נועה ל.
★★★★★  4 ימים

הגיע בזמן, היה מקצועי והסביר
בדיוק מה הבעיה.
```

Reviews should not dominate provider cards.

Show them primarily inside the provider profile.

---

# 46. Availability

Avoid desktop-style calendar complexity for customer booking.

Prefer:

```text
היום
מחר
יום א׳
יום ב׳

10:00
11:30
14:00
16:30
```

Date as horizontal selection.

Times as selectable chips/buttons.

---

# 47. Selected Time

```css
background: #0F766E;
color: white;
border-color: #0F766E;
```

Unavailable:

```css
background: #F5F5F5;
color: #A3A3A3;
```

Unavailable times must not be clickable.

---

# 48. Booking Summary

Use one clean summary card.

```text
אייל כהן
אינסטלטור

יום ראשון, 16 באוגוסט
14:30

דיזנגוף 100, תל אביב

מחיר ביקור
₪220

----------------

סה״כ
₪220
```

Avoid hiding price information behind expandable sections.

---

# 49. SOS Interaction

SOS is an optional urgency mode.

Do not display it as the primary default action.

Example:

```text
צריך מישהו בדחיפות?

[ SOS — חיפוש בעל המקצוע הזמין ביותר ]
```

When activated:

```text
SOS פעיל
נעדיף בעלי מקצוע שיכולים להגיע אליך במהירות.
```

Any additional fee must be visible before final confirmation.

---

# 50. Navigation — Customer Mobile

Use bottom navigation.

Recommended:

```text
בית
הזמנות
מועדפים
פרופיל
```

Maximum:

```text
4–5 items
```

Do not put every product feature in bottom navigation.

---

# 51. Mobile Bottom Navigation

Recommended:

```css
height: 64–72px;
background: white;
border-top: 1px solid #E5E7EB;
```

Active:

```text
Primary brand color
```

Inactive:

```text
Muted gray
```

Always include label + icon.

Do not use icons without labels for main navigation.

---

# 52. Desktop Navigation

For normal customer experience:

Use a clean top navigation bar.

Example:

```text
Pronto

בית   ההזמנות שלי   מועדפים

                           [Profile]
```

Do not create an admin-style sidebar for customer pages.

---

# 53. Professional Dashboard Navigation

The professional side may use a sidebar on desktop.

Example:

```text
Pronto Pro

▢ בקשות חדשות
▢ עבודות קרובות
▢ יומן
▢ עבודות שהושלמו
▢ ביקורות
▢ פרופיל
▢ הגדרות
```

Because the professional side is operational rather than consumer discovery, a dashboard pattern is appropriate here.

---

# 54. Professional Mobile Navigation

Do not simply shrink the desktop sidebar.

Use mobile navigation.

Primary destinations:

```text
בקשות
עבודות
יומן
פרופיל
```

---

# 55. New Professional Request Card

Make decision-critical information immediately visible.

```text
בקשה חדשה

אינסטלציה
תל אביב · 2.3 ק״מ

"יש נזילה מתחת לכיור"

היום
18:00–19:00

[ צפייה בבקשה ]
```

For SOS:

```text
SOS
```

should be immediately visible.

---

# 56. Status Badges

Use consistent statuses globally.

## New

Neutral / blue.

## Accepted

Primary teal.

## On the way

Blue.

## In progress

Amber.

## Completed

Green.

## Cancelled

Gray or muted red.

Do not assign new colors independently on different pages.

---

# 57. Bottom Sheets

On mobile prefer bottom sheets for:

- Filters
- Simple selections
- Small confirmations
- Provider preview
- Address selection
- Date selection

Bottom sheet:

```css
border-radius: 20px 20px 0 0;
```

Include:

```text
drag handle
```

when appropriate.

Do not use bottom sheets for long multi-step forms.

---

# 58. Desktop Modals

Recommended widths:

Small:

```text
420px
```

Normal:

```text
560px
```

Large:

```text
720px
```

Avoid full-screen desktop modals unless necessary.

---

# 59. Mobile Modals

For important content prefer:

```text
bottom sheet
```

or full-screen workflow.

Avoid tiny centered desktop-style modal boxes on mobile.

---

# 60. Empty States

Structure:

```text
Simple icon

Title

One short explanation

Optional CTA
```

Example:

```text
אין עדיין הזמנות

כשאתה מזמין בעל מקצוע,
ההזמנות שלך יופיעו כאן.

[ הזמנת בעל מקצוע ]
```

---

# 61. Error States

Example:

```text
לא הצלחנו לטעון את בעלי המקצוע

אפשר לנסות שוב בעוד רגע.

[ נסה שוב ]
```

Avoid:

```text
Error 500
NetworkException
API failed
```

User-facing text must never expose implementation terminology.

---

# 62. Skeletons

Use skeletons when content structure is predictable.

Provider loading:

```text
[avatar] ███████
         █████
         ████

██████████████
████████

      ███████
```

Skeleton dimensions should approximately match final content.

---

# 63. Toasts

Use toasts for temporary confirmation.

Examples:

```text
השינויים נשמרו
```

```text
התמונה הועלתה
```

Do not use toast notifications for errors that require action.

---

# 64. Motion

Motion should communicate state changes.

Recommended durations:

```text
100–150ms — hover / press
180–220ms — dropdown / small UI
250–300ms — modal / bottom sheet
```

Use:

```text
ease-out
```

for incoming UI.

Avoid animations longer than approximately 300–350ms for normal interactions.

---

# 65. Hover Effects

Desktop only.

Buttons:

```text
Color change
```

Cards:

```text
Subtle border/shadow change
```

Do not use dramatic:

```text
scale(1.1)
```

effects.

---

# 66. Press Feedback

Mobile buttons should provide immediate visual feedback.

Example:

```css
transform: scale(0.98);
```

for a very short interaction is acceptable.

Do not use noticeable bouncing.

---

# 67. Responsive Breakpoints

Recommended conceptual breakpoints:

```css
mobile: < 640px
tablet: 640px–1023px
desktop: >= 1024px
```

The exact implementation may follow the project's CSS framework.

---

# 68. Mobile Page Padding

Use:

```css
padding-inline: 16px;
```

Standard mobile.

Large devices may use:

```text
20px
```

---

# 69. Desktop Page Padding

Use:

```css
padding-inline: 24px;
```

or:

```text
32px
```

within the maximum-width container.

---

# 70. RTL

Hebrew is a first-class UI mode.

Default Hebrew customer experience should use:

```css
direction: rtl;
```

Do not manually reverse every component.

Components must support logical CSS properties.

Prefer:

```css
margin-inline-start
margin-inline-end
padding-inline
inset-inline-start
```

instead of:

```css
margin-left
margin-right
```

where possible.

---

# 71. RTL Icons

Directional icons must mirror appropriately.

Examples:

```text
Back
Forward
Chevron
Navigation arrows
```

Icons that represent physical objects should not automatically mirror.

---

# 72. Numbers in RTL

Be careful with:

- ₪
- Phone numbers
- Times
- Ratings
- Addresses
- Distances

Example:

```text
4.9 ★
```

should remain visually readable inside RTL layouts.

Explicit direction handling may be required for mixed text.

---

# 73. Accessibility

Minimum clickable/touchable target:

```text
44×44px
```

Preferred primary button:

```text
48px+
```

All interactive elements require:

- visible focus
- semantic HTML
- keyboard access
- appropriate ARIA only when necessary

---

# 74. Contrast

Never use very light gray text for important information.

Primary information should have strong contrast.

Muted text is for:

- metadata
- secondary explanations
- timestamps

not important prices or actions.

---

# 75. Price Presentation

Important prices:

```css
font-size: 18–22px;
font-weight: 700;
```

Example:

```text
₪220
```

Do not write:

```text
220 ILS
```

for normal Hebrew consumer UI.

---

# 76. ETA Presentation

ETA is highly important in Pronto.

Examples:

```text
יכול להגיע תוך כ־20 דקות
```

or:

```text
הגעה משוערת: 20–30 דקות
```

When sorting by fastest, ETA may use stronger visual hierarchy.

---

# 77. Distance

Distance is secondary metadata.

Example:

```text
1.4 ק״מ ממך
```

Do not make distance compete with ETA unless distance is directly relevant to the workflow.

---

# 78. Confirmation Screens

After a successful booking, provide a calm success state.

Example:

```text
✓ ההזמנה נקבעה

אייל קיבל את הבקשה שלך.

הגעה משוערת:
14:30

[ צפייה בהזמנה ]
```

Do not immediately throw the user back to the homepage.

---

# 79. Active Job Screen

Once a professional accepts a request, priorities change.

Show:

```text
Professional
Current status
ETA
Contact
Address
Job details
```

The status should become the main visual element.

Example:

```text
אייל בדרך אליך

הגעה משוערת
18 דקות
```

---

# 80. Image Philosophy

Use photography strategically.

Important:

- Real professional profile photos
- User-uploaded problem photos

Avoid generic stock photos throughout the product.

The product should feel useful rather than promotional.

---

# 81. Desktop vs Mobile Philosophy

Do NOT simply stretch mobile cards across desktop.

Desktop should make better use of space.

Example provider results:

Mobile:

```text
1 column
```

Desktop:

```text
Provider list + optional contextual panel
```

or:

```text
2-column card layout
```

depending on information density.

---

# 82. Visual Density

Customer UI:

```text
Low–medium density
```

Professional dashboard:

```text
Medium density
```

Do not use dense admin-style tables in customer-facing pages.

---

# 83. Tables

Tables should primarily appear in operational/professional interfaces.

Avoid tables for customer mobile experiences.

Convert rows to cards on small screens when necessary.

---

# 84. Design Token Implementation

Create centralized tokens rather than repeating values.

Example:

```css
:root {
  --color-primary: #0F766E;
  --color-primary-hover: #0D665F;

  --color-text-primary: #171717;
  --color-text-secondary: #525252;

  --color-background: #F7F8FA;
  --color-surface: #FFFFFF;
  --color-border: #E5E7EB;

  --color-error: #DC2626;
  --color-success: #15803D;
  --color-sos: #E5484D;

  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --radius-xl: 20px;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 32px;
  --space-12: 48px;
  --space-16: 64px;
}
```

If the project already has a token system, adapt these values to the existing architecture instead of creating a second system.

---

# 85. Component Naming

Reusable primitives:

```text
Button
IconButton
Input
Textarea
Select
Checkbox
Radio
Switch
Badge
Avatar
Card
Modal
BottomSheet
Skeleton
Toast
Tabs
FilterChip
```

Pronto-specific components:

```text
ServiceCategoryCard
ProviderCard
ProviderAvatar
ProviderRating
ProviderBadge
ProviderAvailability
PriceDisplay
EtaDisplay
StatusBadge
RequestCard
BookingSummary
TimeSlot
DateSelector
SOSBanner
PhotoUploader
```

---

# 86. Do Not Duplicate Components

Before implementing:

```text
ProviderCardV2
NewButton
ModernInput
CardNew
BetterModal
```

STOP.

Search the component library first.

Extend existing components whenever reasonable.

---

# 87. Page Composition

Pages should compose reusable components.

Bad:

```text
One 900-line page component containing everything.
```

Good:

```text
ProviderResultsPage

 ├── PageHeader
 ├── FilterChips
 ├── ProviderList
 │    └── ProviderCard
 └── EmptyState
```

---

# 88. Design Consistency Rule

If two components perform the same function, they should normally look and behave the same.

For example:

All primary buttons should not have:

```text
Different radius
Different height
Different green
Different hover
Different font
```

depending on the page.

---

# 89. Copy Style

Hebrew copy should feel conversational but professional.

Prefer:

```text
באיזה תחום התקלה?
```

instead of:

```text
בחר קטגוריית שירות
```

Prefer:

```text
מתי נוח לך?
```

instead of:

```text
בחר מועד לביצוע השירות
```

Prefer:

```text
מצאנו בעלי מקצוע שמתאימים לבקשה
```

instead of:

```text
תוצאות החיפוש
```

The product talks like a helpful human.

---

# 90. Visual Priority Rule

When deciding what should stand out, use this order:

### 1. Current user action

### 2. Important dynamic information

Examples:

```text
ETA
Status
Price
```

### 3. Identity / trust

Examples:

```text
Professional
Rating
Verification
```

### 4. Supporting information

### 5. Metadata

---

# 91. No Visual Noise

Avoid:

- Gradients on every screen
- Decorative blobs
- Glassmorphism
- Neon colors
- Multiple card shadows
- Excessive badges
- Excessive borders
- Huge icons
- Giant illustrations
- Animated backgrounds

Pronto is a utility product.

The interface should disappear and let the user complete the task.

---

# 92. Never Use Generic Dashboard Design for Customer UI

Do not create:

```text
Sidebar
8 statistics cards
Graphs
Tables
Widgets
```

for the customer homepage.

Customer UI is task-oriented.

Professional UI may use dashboard patterns where appropriate.

---

# 93. Main Reference Philosophy

When designing a new Pronto experience, think:

```text
Uber:
How quickly can the user understand what happens next?

Wolt:
How smooth can mobile interaction feel?

Task marketplace:
How easy is it to compare professionals?

Home services:
What information makes the user trust this person?
```

Do not visually copy another company's UI.

Use these principles to make Pronto internally consistent.

---

# 94. Agent Pre-Implementation Checklist

Before implementing any new page:

1. Read this design system.
2. Read FRONTEND_GUIDELINES.md.
3. Inspect existing components.
4. Inspect at least one related existing screen.
5. Identify the primary CTA.
6. Identify loading state.
7. Identify error state.
8. Identify empty state if relevant.
9. Define mobile behavior.
10. Verify RTL behavior.
11. Reuse design tokens.
12. Only then implement.

---

# 95. Agent Post-Implementation Checklist

Before declaring frontend work complete:

- [ ] Matches Pronto colors.
- [ ] Matches typography scale.
- [ ] Uses spacing tokens.
- [ ] Uses correct button hierarchy.
- [ ] Uses existing components where possible.
- [ ] Works on mobile.
- [ ] Works on desktop.
- [ ] Works in RTL.
- [ ] Has loading states.
- [ ] Has error states.
- [ ] Has empty states where required.
- [ ] Does not contain unnecessary visual effects.
- [ ] Main CTA is immediately obvious.
- [ ] Important price/ETA information is easy to scan.
- [ ] No backend functionality was invented.
- [ ] No duplicate design patterns were introduced.

---

# 96. Final Design Rule

When deciding between two designs:

Choose the design that requires less thinking from the user.

Then choose the design that is more consistent with the rest of Pronto.

Then choose the design that uses fewer visual elements.

Pronto should feel fast because the interface is obvious — not because it looks futuristic.