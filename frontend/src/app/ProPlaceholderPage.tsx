import { PageHeader } from '../shared/components';

/**
 * Minimal post-login landing spot for a professional so they don't hit a broken/blank
 * route. The real dashboard (incoming requests, availability, job-status actions) is
 * Milestone 6 scope — not built here.
 */
export default function ProPlaceholderPage() {
  return (
    <div className="focused-page">
      <PageHeader title="לוח בקרה לבעלי מקצוע" description="בקרוב" />
    </div>
  );
}
