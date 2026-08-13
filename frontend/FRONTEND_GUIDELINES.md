# Pronto — Frontend Design & Implementation Guidelines

## 1. Purpose

This document is the source of truth for all frontend design and UI implementation decisions in the Pronto application.

Every agent working on the frontend must follow these guidelines before creating, modifying, or refactoring UI components.

The goal is to maintain a consistent, modern, simple, trustworthy, and production-quality interface across the entire application.

Do not redesign existing patterns without a clear reason.

When a new screen or component is required, first try to reuse the patterns defined in this document and existing components in the codebase.

---

# 2. Product Context

Pronto is an on-demand home services platform connecting customers with professionals such as:

- Plumbers
- Electricians
- Air-conditioner technicians
- Boiler technicians
- Locksmiths
- Painters
- Cleaning professionals
- Other home-service professionals

The user experience should feel fast and simple.

The application should reduce the feeling of:

> "I have a problem at home and I don't know who to call."

The ideal experience is closer to ordering a ride than browsing a traditional service-provider directory.

---

# 3. Core Design Principles

## Simplicity

Every screen should have one obvious primary action.

Avoid unnecessary controls, text, cards, borders, and navigation elements.

The user should immediately understand what they are expected to do.

## Trust

Users may be inviting a professional into their home.

The interface should therefore communicate:

- Professionalism
- Safety
- Transparency
- Reliability
- Clear pricing
- Clear availability
- Real identities
- Reviews and ratings

## Speed

The UI should feel fast.

Avoid unnecessary multi-step flows.

Use loading states and skeletons rather than leaving empty areas.

## Mobile First

The main customer experience should be designed mobile-first.

Desktop should feel spacious, but must preserve the same hierarchy and interaction model.

## Consistency

The same visual language must be reused throughout the application.

Do not create a new version of:

- Buttons
- Inputs
- Cards
- Modals
- Status indicators
- Ratings
- Provider cards
- Navigation

if an equivalent component already exists.

---

# 4. Visual Direction

The application should feel:

- Modern
- Minimal
- Friendly
- Premium but not luxurious
- Clean
- Technological without looking technical
- Trustworthy

Avoid an overly corporate or enterprise look.

Avoid an overly playful/cartoonish look.

Avoid excessive gradients, animations, glassmorphism, shadows, or decorative elements.

Visual hierarchy should come primarily from:

- Typography
- Spacing
- Background surfaces
- Component sizing

and not from excessive visual effects.

---

# 5. Layout

Use a consistent page container.

Desktop content should not stretch across the entire viewport.

Prefer:

```text
max-width: 1200px
margin: auto
```

depending on the page.

For forms and focused flows, use narrower containers.

Example:

```text
max-width: 600–720px
```

## Spacing

Use a consistent spacing scale.

Preferred values:

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
```

Avoid arbitrary spacing such as:

```text
13px
27px
37px
```

unless absolutely necessary.

---

# 6. Border Radius

Use rounded interfaces consistently.

Recommended:

Small elements:

```text
8px
```

Inputs / buttons:

```text
10–12px
```

Cards:

```text
14–16px
```

Large panels / modals:

```text
16–20px
```

Avoid exaggerated pill-shaped containers unless the component is naturally suited to it.

---

# 7. Shadows

Use shadows sparingly.

Cards should normally rely on borders and background contrast rather than heavy shadows.

If a shadow is used, keep it subtle.

Avoid strong floating-card effects across the application.

---

# 8. Typography

Typography must create clear visual hierarchy.

Suggested hierarchy:

## Page title

Large and bold.

## Section title

Clearly separated from content but smaller than the main title.

## Card title

Medium weight.

## Supporting text

Smaller and visually quieter.

## Metadata

Use muted text for information such as:

- Distance
- Number of reviews
- Estimated arrival
- Secondary descriptions

Do not use very small text.

Mobile body text should normally remain at least around 14–16px.

---

# 9. Buttons

There must always be a clear primary action.

## Primary Button

Used for the main action on the screen.

Examples:

- Continue
- Choose professional
- Confirm booking
- Send request
- Save availability

Primary buttons should have strong visual contrast.

## Secondary Button

Used for alternative actions.

Should not compete visually with the primary button.

## Destructive Button

Only for destructive actions such as:

- Delete
- Cancel permanently
- Remove account/data

Destructive actions must be visually distinct.

## Button states

Every button must support:

- Default
- Hover
- Active
- Disabled
- Loading

Do not allow double submissions.

---

# 10. Form Inputs

Inputs should have:

- Clear labels
- Helpful placeholder only when needed
- Visible focus state
- Error state
- Disabled state

Do not rely on placeholders instead of labels.

Validation messages should explain how to fix the problem.

Bad:

```text
Invalid input
```

Good:

```text
Please enter a valid phone number.
```

---

# 11. Cards

Cards are one of the primary visual components in Pronto.

A card must represent a meaningful unit of information.

Do not wrap every section in a card.

Preferred structure:

```text
Card
 ├─ Primary information
 ├─ Secondary information
 ├─ Status / metadata
 └─ Action
```

Cards should maintain consistent:

- Padding
- Border
- Radius
- Typography
- Interaction states

---

# 12. Professional / Provider Card

Professional cards are especially important.

Each provider card should make it easy to compare professionals quickly.

Important information should generally include:

- Profile photo
- Name
- Profession
- Rating
- Review count
- Distance
- Estimated arrival time
- Relevant price information
- Availability
- Recommended indicator when applicable

The primary action should be obvious.

Example:

```text
[Photo]  David Cohen
         ★ 4.9 (126)
         Plumber

         1.2 km away
         Available in ~20 min

         Estimated visit: ₪XXX

                       [View profile]
```

Do not overload the card with unnecessary data.

---

# 13. Ranking / Filtering Professionals

The professional list supports filters such as:

- Recommended
- Cheapest
- Fastest

Recommended should be the default.

The active filter must be immediately visible.

Changing a filter should not feel like navigating to a different page.

The list should update smoothly.

---

# 14. SOS Mode

SOS represents urgency.

It must be visible but should not make the entire interface feel alarming.

When SOS is active:

- Clearly indicate that urgent mode is enabled.
- Prioritize professionals based on availability and arrival time.
- Make relevant ETA information more prominent.
- Clearly communicate any additional SOS cost before confirmation.

Never surprise the user with an SOS surcharge at checkout.

---

# 15. Creating a Service Request

The service request flow should feel lightweight.

Main steps:

```text
Choose category
↓
Describe the problem
↓
Add photos
↓
AI validation / clarification if required
↓
Choose professional
↓
Choose time
↓
Confirm
```

Avoid presenting all steps at once.

Each step should focus on the current decision.

---

# 16. Service Categories

Current categories include:

- Plumber
- Electrician
- Air Conditioner Technician
- Boiler Technician
- Locksmith
- Painter
- Cleaning
- Other

Categories should be visually easy to scan.

Prefer:

- Icon
- Short label

Avoid long explanatory text directly inside category cards.

---

# 17. Photo Upload

Photo upload should be simple and obvious.

Support:

- Add photo
- Preview image
- Remove image
- Upload progress
- Upload failure state

Do not immediately submit files before the user understands what is happening.

Photo previews should preserve aspect ratio.

---

# 18. AI Classification Interaction

AI classification happens behind the scenes.

Do not present technical AI terminology to the user.

Bad:

```text
Our AI model detected a classification mismatch.
```

Prefer:

```text
We want to make sure we find the right professional.
```

If clarification is needed, ask short and direct questions.

Prefer closed questions when possible.

Example:

```text
Where is the leak coming from?

○ Sink
○ Toilet
○ Wall
○ I’m not sure
```

---

# 19. Professional Profile

The professional profile should emphasize trust.

Priority information:

1. Name and photo
2. Rating and reviews
3. Profession
4. Availability
5. Estimated arrival
6. Pricing
7. Description
8. Reviews
9. Relevant credentials or verification

Primary CTA:

```text
Choose professional
```

The CTA should remain easy to reach on mobile.

A sticky mobile action area may be used.

---

# 20. Availability Calendar

The calendar should feel familiar.

Do not invent unusual scheduling interactions.

Available times must be visually distinct from unavailable times.

Selected time must be obvious.

Recommended flow:

```text
Select date
↓
Select available time
↓
Continue
```

Do not allow users to select unavailable slots.

---

# 21. Booking Confirmation

Before final confirmation clearly show:

- Professional
- Service
- Date
- Time
- Address
- Expected price / fee
- SOS surcharge if relevant

The user should understand exactly what they are confirming.

Primary CTA:

```text
Confirm booking
```

---

# 22. Customer Dashboard

Important sections:

- My Requests
- Upcoming bookings
- Completed requests
- Favorite professionals
- Addresses
- Payment methods
- Support

The dashboard should prioritize active and upcoming requests.

Historical requests should be secondary.

---

# 23. Professional Dashboard

Main areas:

- New requests
- Accepted / upcoming jobs
- Completed jobs
- Availability calendar
- Profile and reviews
- Settings

New opportunities must be immediately visible.

Do not bury active requests inside deep navigation.

---

# 24. Statuses

Statuses must have a consistent visual representation.

Examples:

```text
New
Waiting for professional
Accepted
Scheduled
On the way
In progress
Completed
Cancelled
```

Use consistent badge styles.

Do not create unique status colors on individual pages.

---

# 25. Empty States

Every meaningful list must have an empty state.

Bad:

```text
No data
```

Good:

```text
No upcoming requests

When you book a professional, your upcoming appointments will appear here.
```

Provide a CTA when relevant.

---

# 26. Loading States

Avoid blank screens during loading.

Use:

- Skeletons
- Inline spinners
- Button loading indicators

Choose the loading pattern according to context.

Do not replace the entire screen with a giant spinner unless the whole application genuinely cannot render.

---

# 27. Error States

Errors must explain:

1. What happened
2. What the user can do next

Example:

```text
We couldn't load available professionals.

Please try again.
```

Provide a retry action where relevant.

---

# 28. Modals

Use modals only for focused tasks.

Good uses:

- Professional preview
- Confirmation
- Small editing workflows

Bad uses:

- Entire multi-step processes
- Complex pages
- Long content

On mobile, a bottom sheet may be preferable.

---

# 29. Responsive Behavior

Every screen must be tested conceptually at:

```text
Mobile
Tablet
Desktop
```

Mobile is not simply a scaled-down desktop.

For mobile:

- Stack layouts vertically.
- Preserve large touch targets.
- Avoid tiny buttons.
- Avoid wide tables.
- Use bottom sheets when appropriate.
- Consider sticky primary actions.

---

# 30. Accessibility

Frontend implementation should support:

- Keyboard navigation
- Visible focus states
- Semantic HTML
- Accessible labels
- Adequate contrast
- Alt text when appropriate

Do not create clickable `<div>` elements when a semantic button or link should be used.

---

# 31. Icons

Use one icon library consistently.

Do not mix multiple icon styles.

Icons should support comprehension, not decorate every text label.

Avoid emojis as UI icons unless specifically required.

---

# 32. Animations

Animations should be subtle.

Recommended use:

- Modal transitions
- Dropdown opening
- Button feedback
- Loading
- List updates

Avoid:

- Large entrance animations
- Excessive bouncing
- Decorative movement
- Slow transitions

Animations should generally feel fast.

---

# 33. Component Architecture

Before creating a new component:

1. Search the existing component library.
2. Check whether an existing component can be extended.
3. Only create a new reusable component if necessary.

Common reusable components should include concepts such as:

```text
Button
Input
Textarea
Select
Modal
BottomSheet
Card
Badge
Avatar
Rating
EmptyState
ErrorState
LoadingSkeleton
PageHeader
ProviderCard
StatusBadge
```

Pages should primarily compose components rather than contain large amounts of duplicated UI code.

---

# 34. Component Responsibility

Keep components focused.

Avoid giant page components containing:

- API logic
- Complex calculations
- UI
- validation
- modal logic
- formatting

all together.

Extract reusable logic and UI appropriately.

---

# 35. Design Tokens

Do not hardcode styling values repeatedly.

Prefer centralized design tokens for:

- Colors
- Typography
- Spacing
- Border radius
- Shadows
- Breakpoints

The same values should be reused across the application.

---

# 36. RTL / Hebrew

Pronto must support Hebrew correctly.

When the UI is Hebrew:

```text
direction: rtl
```

must be handled properly.

Pay special attention to:

- Icons
- Arrows
- Input alignment
- Phone numbers
- Numbers
- Currency
- Mixed Hebrew/English content

Do not assume that reversing the entire UI automatically produces correct RTL behavior.

---

# 37. Currency

Prices should normally be shown in Israeli shekels.

Example:

```text
₪250
```

Formatting must be consistent throughout the application.

---

# 38. Dates and Times

Date and time presentation should remain consistent.

Do not expose technical formats such as:

```text
2026-08-13T17:30:00Z
```

to the user.

Use localized, human-readable formats.

---

# 39. UX Copy

Text should be:

- Short
- Human
- Clear
- Action-oriented

Avoid technical terminology.

Avoid unnecessary explanation.

Prefer:

```text
Choose a professional
```

instead of:

```text
Please select your preferred service provider from the list below.
```

---

# 40. Before Implementing a New Screen

Before writing code, the frontend agent must:

1. Understand the purpose of the screen.
2. Identify the primary user action.
3. Search for reusable existing components.
4. Check existing page patterns.
5. Determine loading, empty, and error states.
6. Determine mobile behavior.
7. Determine required API data.
8. Only then implement the screen.

---

# 41. Before Modifying an Existing Screen

Do not immediately rewrite existing code.

First inspect:

- Current page structure
- Existing components
- Shared styling
- Existing state management
- API integrations
- Other pages using the same components

Changes must avoid breaking visual consistency elsewhere.

---

# 42. Agent Rules

When acting as the frontend implementation agent:

DO:

- Reuse components.
- Follow existing architecture.
- Keep UI consistent.
- Keep code maintainable.
- Prefer simple solutions.
- Implement responsive behavior.
- Include loading/error/empty states.
- Preserve RTL compatibility.
- Explain significant architectural decisions when relevant.

DO NOT:

- Invent new design systems for individual screens.
- Add libraries without a strong reason.
- Add dependencies solely for minor visual effects.
- Redesign unrelated parts of the application.
- Change backend contracts without explicitly identifying the need.
- Add functionality that was not requested.
- Use placeholder functionality and present it as complete.
- Duplicate existing components.
- Over-engineer simple UI.

---

# 43. Definition of Done

A frontend feature is not complete simply because the happy path renders.

Before considering a feature complete, verify:

- Desktop layout works.
- Mobile layout works.
- RTL works.
- Loading state exists.
- Error state exists.
- Empty state exists when relevant.
- Buttons have disabled/loading behavior.
- Forms validate correctly.
- Existing components are reused.
- No obvious duplicate UI components were introduced.
- API failures do not break the page.
- Navigation behavior is correct.
- User can clearly identify the main action.

---

# 44. Decision Rule

When there are multiple possible design solutions, choose the one that is:

1. Simpler for the user.
2. More consistent with the existing application.
3. Easier to maintain.
4. More reusable.
5. Less visually noisy.

Do not choose a more sophisticated design simply because it is technically possible.

---

# 45. Final Instruction to the Agent

Treat this document as a persistent design contract.

Before implementing frontend work:

- Read this document.
- Inspect the relevant existing UI.
- Preserve existing working patterns.
- Make the smallest coherent change needed.
- Keep the experience consistent across the entire Pronto product.

If a requested implementation conflicts with these guidelines, explicitly identify the conflict before making a major architectural or design change.