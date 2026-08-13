# Pronto — Frontend Agent Instructions

## Purpose

This file defines how an AI coding agent must operate when working on the Pronto frontend.

This is not a design specification.

Visual decisions are defined in:

```text
DESIGN_SYSTEM.md
```

General frontend principles are defined in:

```text
FRONTEND_GUIDELINES.md
```

This document defines the agent's **working process**.

Every frontend task must follow this workflow.

---

# 1. Source of Truth

Before implementing frontend work, read:

1. `FRONTEND_AGENT.md`
2. `DESIGN_SYSTEM.md`
3. `FRONTEND_GUIDELINES.md`

Then inspect the existing implementation.

The existing repository is part of the source of truth.

Never assume the documentation is the only relevant context.

If documentation and existing production behavior conflict, identify the conflict before making a large change.

---

# 2. Core Rule

Do not immediately write code after receiving a task.

First understand:

- What user is using this screen?
- What is the user's goal?
- What is the main action?
- What data already exists?
- What components already exist?
- What API/backend behavior already exists?
- What happens on mobile?
- What happens in RTL?
- What happens during loading or failure?

Then implement.

---

# 3. Understand the User Type

Pronto has two fundamentally different interfaces.

## Customer

Customer frontend is:

```text
Consumer product
Simple
Task-oriented
Low visual density
Mobile-first
```

The customer should rarely feel like they are using a dashboard.

Primary customer goal:

```text
I have a problem → help me solve it.
```

---

## Professional

Professional frontend is:

```text
Operational product
Higher information density
Workflow-oriented
```

Dashboard patterns are acceptable here.

Primary professional goals include:

```text
See new jobs
Respond to requests
Manage upcoming work
Manage availability
Review completed jobs
```

Never accidentally use Professional dashboard patterns in Customer UI.

---

# 4. Product Flow Awareness

The main customer flow is:

```text
Home
↓
I have a problem
↓
Choose service category
↓
Describe problem
↓
Upload photos if relevant
↓
Clarification if required
↓
Find matching professionals
↓
Compare professionals
↓
View professional
↓
Choose availability
↓
Booking summary
↓
Confirm
↓
Active request
```

Frontend decisions should preserve the simplicity of this flow.

Do not add unnecessary steps.

---

# 5. Before Creating a Screen

For every new screen, determine:

```text
USER
Who is this screen for?

GOAL
What does the user want here?

PRIMARY ACTION
What is the one most important action?

SECONDARY ACTIONS
What else can the user do?

DATA
What data is required?

STATES
Loading?
Empty?
Error?
Success?

NAVIGATION
Where does the user come from?
Where can they go next?

RESPONSIVE
How does this behave on mobile?

RTL
Does Hebrew change alignment or directional behavior?
```

Do not begin implementation until these are understood from the existing product context.

---

# 6. Inspect Existing Code First

Before creating new frontend code:

Search for existing:

```text
Components
Hooks
Utilities
Layouts
CSS tokens
Theme values
API services
Types
Interfaces
Models
State management
Routes
```

Do not create duplicates.

For example, before creating:

```text
NewProviderCard
ProviderCard2
ModernButton
CustomModal
BetterInput
```

search the repository.

If an equivalent component exists, reuse or improve it.

---

# 7. Reuse Before Creation

Preferred order:

```text
1. Reuse an existing component unchanged
2. Extend an existing component
3. Extract reusable behavior from existing code
4. Create a new component
```

Creating a new component is not the default solution.

---

# 8. Preserve Architecture

Follow the architecture already used by the frontend.

Do not introduce a new:

- State management library
- CSS framework
- UI framework
- HTTP client
- Form library
- Validation library
- Icon library
- Animation library

without a clear need.

Never install a package simply because it makes one small UI task easier.

---

# 9. Do Not Touch Backend Contracts Casually

Frontend tasks should not silently change backend behavior.

Do not:

- Rename API fields
- Change endpoints
- Invent endpoints
- Change request schemas
- Change response schemas
- Assume new backend fields exist

If frontend requirements need backend changes, clearly identify them.

Example:

```text
The UI requires provider verification status,
but the current API does not expose a verification field.
```

Do not fake the data.

---

# 10. Never Fake Product Functionality

Do not present mocked behavior as completed functionality.

Examples of unacceptable behavior:

```text
Random provider ETA
Fake rating
Fake live location
Fake verification
Fake pricing
Fake available appointments
setTimeout pretending to perform backend work
```

Mock data is allowed only when the task explicitly requires mock/prototype behavior.

Keep mocks clearly separated from production logic.

---

# 11. Provider Comparison Is Critical

When working on professional search/results, preserve the Pronto comparison hierarchy.

Users primarily need to understand:

```text
Who?
Can I trust them?
When can they arrive?
How much will it cost?
Why should I choose them?
```

Provider results should therefore prioritize:

1. Provider identity
2. Rating / trust
3. ETA
4. Price
5. Availability
6. Relevant recommendation reason

Avoid cluttering cards with low-priority metadata.

---

# 12. Sorting Modes

Pronto supports concepts such as:

```text
Recommended
Fastest
Cheapest
```

Do not create completely different card designs for each sorting mode.

The structure should stay stable.

Only emphasis changes.

## Recommended

Highlight the overall recommendation.

## Fastest

Increase visual emphasis on ETA.

## Cheapest

Increase visual emphasis on price.

Consistency is more important than dramatic visual changes.

---

# 13. SOS Rules

SOS is an urgent version of the normal flow.

It is NOT a completely different application mode.

When SOS is active:

- Make urgency visible.
- Prioritize availability and ETA.
- Show any SOS surcharge clearly.
- Preserve the normal Pronto component system.
- Avoid panic-style design.

Do not turn the entire screen red.

Do not hide additional pricing.

---

# 14. AI Interaction Rules

AI should feel invisible.

Never expose implementation terminology to customers.

Do not show:

```text
Classification
Model
Prediction
Confidence
Inference
LLM
Computer vision
```

unless specifically required for a technical/internal screen.

The user should experience:

```text
Pronto understands the problem
and asks useful questions when needed.
```

Example:

Instead of:

```text
The AI model could not classify your request.
```

use:

```text
כדי שנמצא את בעל המקצוע המתאים,
יש לנו עוד שאלה קטנה.
```

---

# 15. Design Hierarchy

When designing a customer screen, visual importance should normally follow:

```text
1. Current action
2. Status / ETA / price
3. Professional identity and trust
4. Supporting content
5. Metadata
```

Do not allow decorative content to dominate these elements.

---

# 16. One Primary CTA

Each major screen should normally have one obvious primary CTA.

Examples:

Home:

```text
יש לי תקלה
```

Provider profile:

```text
בחירת בעל מקצוע
```

Availability:

```text
המשך
```

Booking summary:

```text
אישור הזמנה
```

Professional request:

```text
צפייה בבקשה
```

Avoid multiple filled primary buttons competing on the same screen.

---

# 17. Mobile First

Customer-facing work must be designed mobile-first.

Always consider approximately:

```text
375px
390px
430px
```

mobile widths.

Then adapt to tablet and desktop.

Do not build desktop first and simply shrink everything.

---

# 18. Customer Mobile Behavior

On mobile prefer:

- Single-column layouts
- Bottom navigation
- Bottom sheets
- Sticky primary CTA
- Horizontal filter chips
- Large touch targets
- Short content blocks

Avoid:

- Sidebars
- Wide tables
- Tiny buttons
- Hover-dependent interactions
- Multiple columns containing dense data

---

# 19. Desktop Behavior

Desktop should use additional space intelligently.

Do not simply stretch mobile components to full width.

Consider:

- Maximum width containers
- Multi-column layouts where helpful
- Contextual side panels
- More visible information without increasing cognitive load

Customer UI must still feel like a consumer application.

---

# 20. RTL Is Mandatory

Hebrew support is not optional.

After implementing a component, verify:

- Alignment
- Icon direction
- Back arrows
- Chevron direction
- Form labels
- Mixed English/Hebrew content
- Numbers
- Phone numbers
- Ratings
- Prices
- Dates
- Times

Prefer CSS logical properties.

Do not solve RTL by adding random one-off overrides to each screen.

---

# 21. Visual System

Do not invent colors, spacing, radius or typography.

Use design tokens from `DESIGN_SYSTEM.md`.

Do not write:

```css
color: #147b72;
margin: 17px;
border-radius: 13px;
```

because it "looks good".

Use the existing tokens.

---

# 22. Component States

Every interactive reusable component must consider appropriate states.

Buttons:

```text
default
hover
active
focus
disabled
loading
```

Inputs:

```text
default
focus
filled
error
disabled
```

Cards when interactive:

```text
default
hover
selected
disabled if relevant
```

Do not implement only the ideal state.

---

# 23. Async Data States

Every screen that depends on remote data must consider:

```text
Loading
Success
Empty
Error
```

Where relevant also consider:

```text
Refreshing
Partial data
Retry
```

Never leave a blank screen while waiting for API data.

---

# 24. Loading

Prefer skeletons when the layout is known.

Example:

Provider results should use:

```text
ProviderCard skeletons
```

rather than a giant centered spinner.

Use button-level loading for submissions.

Prevent duplicate submissions.

---

# 25. Empty States

Empty states should help the user continue.

Bad:

```text
No data.
```

Better:

```text
אין עדיין הזמנות

כשאתה מזמין בעל מקצוע,
ההזמנות שלך יופיעו כאן.

[ הזמנת בעל מקצוע ]
```

---

# 26. Error Handling

Errors should:

1. Explain what failed in user language.
2. Provide an action when possible.

Never expose:

```text
HTTP status
Stack trace
Java exception
Axios error
Backend implementation details
```

to customers.

---

# 27. Forms

Forms should be simple.

Do not ask for information Pronto already knows.

Do not create one giant form when a short guided flow is more appropriate.

For the main customer request process, prefer one decision group per step.

---

# 28. Validation

Validation should help correction.

Bad:

```text
Invalid
```

Better:

```text
יש להזין מספר טלפון תקין
```

Show validation near the relevant input.

Do not wait until the final step to reveal obvious field errors.

---

# 29. Navigation

Navigation should reflect product priorities.

Customer navigation should prioritize:

```text
Home
Bookings
Favorites
Profile
```

Professional navigation should prioritize operational workflows.

Do not add a destination to primary navigation simply because a page exists.

---

# 30. Accessibility

Use semantic HTML.

Prefer:

```html
<button>
<a>
<label>
<input>
<nav>
main
section
```

over clickable generic `div` elements.

Support keyboard navigation.

Ensure visible focus.

Maintain usable touch targets.

Accessibility improvements should not be removed for visual convenience.

---

# 31. Copy Rules

Customer-facing Hebrew should sound human.

Prefer conversational questions.

Example:

```text
מה קרה?
```

instead of:

```text
תיאור התקלה
```

Prefer:

```text
מתי נוח לך?
```

instead of:

```text
בחר מועד
```

Avoid unnecessary formal language.

---

# 32. Do Not Over-Explain

Customer UI copy should normally be concise.

Do not add paragraphs where one sentence is enough.

Example:

Instead of:

```text
Please provide a detailed description of the service issue you are experiencing so that we can identify a suitable service professional.
```

prefer:

```text
ספר לנו בקצרה מה קרה
```

---

# 33. Trust Before Decoration

When working on provider-related screens, prioritize trust elements over decorative design.

Useful:

- Profile image
- Rating
- Number of reviews
- Verification
- Job history
- Clear pricing
- Clear ETA

Less useful:

- Decorative gradients
- Huge illustrations
- Animated backgrounds
- Marketing slogans

---

# 34. No Dark Patterns

Never intentionally:

- Hide pricing
- Hide SOS fees
- Preselect paid upgrades without clarity
- Make cancellation unnecessarily difficult
- Use misleading button hierarchy
- Fake scarcity
- Fake urgency

Pronto should build long-term trust.

---

# 35. Confirmation Before Commitment

Before a customer confirms a booking, clearly present:

```text
Professional
Service
Date
Time
Address
Price / expected fee
Additional fees
SOS fee if relevant
```

Users must understand what they are agreeing to.

---

# 36. Active Booking Changes Priorities

After booking, the interface is no longer primarily about discovery.

The new hierarchy becomes:

```text
Status
ETA
Professional
Contact
Job information
```

Do not keep showing discovery-oriented CTAs after the booking has been accepted.

---

# 37. Professional Workflow Priorities

For professional screens, prioritize:

```text
New requests
Upcoming work
Current job
Availability
```

Historical and profile information is secondary.

Professional UI may use:

- Tables
- Denser cards
- Sidebar navigation
- Operational indicators

when appropriate.

---

# 38. File Organization

Follow existing repository conventions.

Do not arbitrarily create a new folder hierarchy.

If there is no established pattern, prefer feature/domain organization rather than dumping all components into one folder.

Example concept:

```text
features/
  providers/
  booking/
  requests/
  availability/

components/
  ui/
```

Only apply this if it is compatible with the current project.

---

# 39. Keep Components Focused

Avoid giant components.

A component should normally have one clear responsibility.

Do not put:

```text
API requests
form validation
business logic
modal state
complex transformations
500 lines of JSX
```

inside one component when responsibilities can be separated cleanly.

Do not over-split trivial components either.

---

# 40. Business Logic

Do not duplicate business logic in the UI.

Examples:

```text
Matching score
SOS pricing
Booking eligibility
Professional availability calculations
```

should not be independently reimplemented in random frontend components if backend/domain logic already exists.

Frontend should present authoritative product state.

---

# 41. Types

Use existing shared types where possible.

Do not create separate slightly different versions such as:

```text
Provider
ProviderData
Professional
ProfessionalDTO
ProviderInfo
ProviderCardData
```

unless there is a real architectural distinction.

---

# 42. Remove Dead UI Carefully

When refactoring, confirm that a component or route is not used elsewhere before removing it.

Do not delete functionality merely because it is not needed for the current task.

Keep changes scoped.

---

# 43. Scope Discipline

When asked:

```text
Improve provider results page
```

do not also redesign:

```text
Login
Professional dashboard
Navbar
Settings
Home
```

unless required by shared-component changes.

Make the smallest coherent product-level change.

---

# 44. Refactoring Rule

Do not mix major visual redesign and major architectural refactoring unless necessary.

Prefer:

```text
1. Understand
2. Stabilize structure
3. Implement UI change
4. Refactor reusable pieces if needed
```

Large unrelated refactors increase risk.

---

# 45. Dependency Rule

Before adding a dependency ask:

```text
Can this be done cleanly using what already exists?
```

If yes, do not add the dependency.

A new library is justified only when it materially simplifies an important capability.

---

# 46. Icon Rule

Use the icon library already selected for Pronto.

Do not mix icon packs.

Do not use emojis as production interface icons.

---

# 47. Animation Rule

Animations should clarify interaction.

Allowed examples:

```text
Bottom sheet opening
Modal transition
Selected state
List refresh
Loading
Button interaction
```

Avoid adding animation purely to make a page look more impressive.

---

# 48. Performance

Avoid unnecessary rerenders or large client-side work.

Important mobile flows should remain responsive.

Optimize images appropriately.

Avoid loading large media when a thumbnail is sufficient.

Do not prematurely optimize trivial components at the cost of readability.

---

# 49. Image Handling

Provider photos:

- Preserve aspect ratio.
- Use `object-fit: cover`.
- Provide fallback state.
- Avoid layout shift.

Problem photos:

- Show previews.
- Allow removal when appropriate.
- Clearly indicate upload progress/failure.

---

# 50. Security/UI Boundaries

Do not assume frontend restrictions are security controls.

Examples:

```text
Hidden admin button
Disabled professional action
Hidden customer field
```

do not replace backend authorization.

Do not introduce security-sensitive assumptions into the frontend.

---

# 51. When Requirements Are Ambiguous

Before inventing a completely new behavior:

1. Inspect nearby screens.
2. Inspect existing product flow.
3. Follow `DESIGN_SYSTEM.md`.
4. Choose the simplest behavior consistent with Pronto.

If the ambiguity materially affects business behavior, identify it rather than silently inventing product rules.

---

# 52. Preserve Existing Working Behavior

A visual improvement should not accidentally break:

- Navigation
- Forms
- API calls
- Authentication
- Booking
- Availability
- File uploads
- Role handling
- RTL

Inspect surrounding behavior before editing.

---

# 53. No Placeholder Completion

Do not mark a feature complete if important elements are still:

```text
TODO
placeholder
fake data
console.log
disabled functionality
hardcoded production data
```

Clearly distinguish between:

```text
Implemented
UI-only
Mocked
Blocked by backend
```

---

# 54. Testing Expectations

After frontend changes, check the relevant project tooling.

Where applicable run:

```text
Type checking
Linting
Frontend tests
Build
```

Fix errors introduced by the change.

Do not ignore failing build/type errors.

---

# 55. Visual Review

Before considering a UI task complete, mentally or technically verify:

### Mobile

```text
375px
390px
430px
```

### Tablet

Approximately:

```text
768px
```

### Desktop

Approximately:

```text
1280px+
```

Exact dimensions may vary according to project tooling.

---

# 56. RTL Review

Always explicitly review a Hebrew layout.

Do not assume responsive testing automatically covers RTL.

Check:

```text
Text alignment
Arrows
Tabs
Chips
Buttons
Cards
Input contents
Price
Rating
Phone
Time
Date
```

---

# 57. New Screen Completion Checklist

Before finishing a new customer screen:

- [ ] Purpose is clear.
- [ ] One obvious primary CTA exists.
- [ ] Uses Pronto design tokens.
- [ ] Existing components were reused where possible.
- [ ] Mobile layout works.
- [ ] Desktop layout works.
- [ ] RTL works.
- [ ] Loading state exists.
- [ ] Error state exists.
- [ ] Empty state exists if applicable.
- [ ] User-facing text is human and concise.
- [ ] Pricing is transparent.
- [ ] No fake backend functionality exists.
- [ ] No unnecessary dependencies were introduced.
- [ ] No unrelated screens were redesigned.

---

# 58. Provider Screen Completion Checklist

Additionally verify:

- [ ] Provider identity is obvious.
- [ ] Rating is visible.
- [ ] Review count is visible where appropriate.
- [ ] ETA is easy to scan.
- [ ] Price is easy to scan.
- [ ] Verification is shown only if real.
- [ ] Sorting emphasis behaves correctly.
- [ ] Primary action is obvious.
- [ ] Card is not overloaded.

---

# 59. Booking Completion Checklist

Additionally verify:

- [ ] Professional shown.
- [ ] Service shown.
- [ ] Date shown.
- [ ] Time shown.
- [ ] Address shown.
- [ ] Price shown.
- [ ] Additional fees shown.
- [ ] SOS fee shown when relevant.
- [ ] Double submission is prevented.
- [ ] Success state is handled.
- [ ] Failure state is handled.

---

# 60. Professional Screen Completion Checklist

Additionally verify:

- [ ] New work is prioritized.
- [ ] Upcoming work is easy to find.
- [ ] Current status is clear.
- [ ] Actions are operationally obvious.
- [ ] Mobile version does not simply shrink a desktop table/sidebar.
- [ ] No customer-style decorative complexity interferes with workflow.

---

# 61. Implementation Response Format

After completing a frontend task, provide a concise implementation summary containing:

```text
What changed
Which existing components were reused
Which new reusable components were created
Any API/backend assumptions
Any unresolved issue or limitation
```

Do not provide a long explanation unless required.

---

# 62. Do Not Claim Completion If Blocked

If implementation depends on missing backend data, say so.

Example:

```text
UI implementation is complete, but the verification badge
is intentionally not displayed because the current provider
API does not expose verification status.
```

This is better than inventing the missing behavior.

---

# 63. Decision Framework

If multiple implementations are possible, evaluate them in this order:

## 1. User simplicity

Which version requires less thinking?

## 2. Product consistency

Which version behaves most like the rest of Pronto?

## 3. Reuse

Which version uses existing patterns/components?

## 4. Maintainability

Which version will be easiest to safely change later?

## 5. Visual simplicity

Which version contains less unnecessary UI?

---

# 64. Final Agent Principle

Do not optimize Pronto for screenshots.

Optimize Pronto for the moment when a real person has:

```text
a leaking pipe,
a broken air conditioner,
a locked door,
or another problem at home
```

and wants to solve it quickly.

Every frontend decision should help the user answer:

```text
What do I do now?
Who can help me?
Can I trust them?
When can they arrive?
How much will it cost?
```

If the interface makes these answers easier to understand, it is moving in the right direction.