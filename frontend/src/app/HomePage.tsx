/**
 * Placeholder home route for Milestone 0 (Foundation).
 * Real content (issue reporting entry point, etc.) lands in later milestones
 * (see docs/architecture/implementation-plan.md). Content unchanged since Milestone 0 —
 * only the wrapping element changed from `<main>` to a `<div>` in Milestone 1, since
 * `AppLayout` (which now wraps every route) already provides the page's `<main>` landmark.
 */
export default function HomePage() {
  return (
    <div className="page-container">
      <h1>Pronto</h1>
      <p>ברוכים הבאים ל-Pronto — הפלטפורמה לחיבור בין לקוחות לבעלי מקצוע לתחזוקת הבית.</p>
    </div>
  )
}
