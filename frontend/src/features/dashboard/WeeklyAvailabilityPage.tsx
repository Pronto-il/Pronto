import { useEffect, useState } from 'react';
import { Pencil } from 'lucide-react';
import { Button, Card, Modal } from '../../shared/components';
import { getWorkingHours, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { WorkingHoursItem } from '../../shared/api';
import { SosAvailabilityToggle } from './SosAvailabilityToggle';
import { WorkingHoursForm } from './WorkingHoursForm';
import { WeeklyCalendarGrid } from './WeeklyCalendarGrid';
import styles from './WeeklyAvailabilityPage.module.css';

/** A configured week has exactly 7 entries (`PUT` always writes the full week transactionally,
 *  design §4.2) — fewer than 7 means first-time setup hasn't completed yet (design §4.1). */
function isSetupComplete(workingHours: WorkingHoursItem[]): boolean {
  return workingHours.length === 7;
}

/**
 * `/pro/availability` — replaces the old `AvailabilityPage` (Standard-slot create/list) with
 * the professional weekly availability calendar. Composes, top to bottom: `SosAvailabilityToggle`
 * (rendered verbatim, unchanged, same position as before), the working-hours setup/edit entry
 * point (`WorkingHoursForm`, M3), and the read-only weekly grid (`WeeklyCalendarGrid`, M4). See
 * `docs/architecture/professional-weekly-calendar-design.md` §7.1/§7.2.
 *
 * **First-time setup vs. later edit (§7.2)**: if `GET /api/availability/working-hours` returns
 * fewer than 7 entries (a brand-new professional), this page renders `WorkingHoursForm`
 * full-page instead of the calendar — but it's **skippable**, not a hard gate (per the design's
 * own "a professional who skips simply sees an all-'outside working hours' calendar" framing):
 * a "דלג, אגדיר מאוחר יותר" ghost action reveals the (all-muted) calendar without saving
 * anything. Once a week is configured, the page instead makes `WeeklyCalendarGrid` the sole
 * dominant element, with a slim header row above it holding an "עריכת שעות עבודה" `Button`
 * that opens `WorkingHoursForm` inside the shared `Modal` primitive — no permanently-visible
 * working-hours list competes with the calendar (design
 * `docs/architecture/product-ms12-availability-ux-cleanup-design.md`).
 *
 * The later-edit entry point opening in a `Modal` matches §7.2's original intent exactly
 * (previously a temporary inline-expansion stand-in before `Modal.tsx` existed; MS12 replaced
 * it with the real modal, same pattern `CalendarBlockModal.tsx` already established).
 */
export default function WeeklyAvailabilityPage() {
  const [workingHours, setWorkingHours] = useState<WorkingHoursItem[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [hasSkippedSetup, setHasSkippedSetup] = useState(false);

  function loadWorkingHours() {
    setLoadError(null);
    getWorkingHours()
      .then((result) => setWorkingHours(result.workingHours))
      .catch(() => setLoadError(GENERIC_ERROR_MESSAGE));
  }

  useEffect(() => {
    loadWorkingHours();
  }, []);

  function handleSaved(saved: WorkingHoursItem[]) {
    setWorkingHours(saved);
    setIsEditModalOpen(false);
  }

  const setupComplete = workingHours !== null && isSetupComplete(workingHours);
  const showFullPageSetup = workingHours !== null && !setupComplete && !hasSkippedSetup;

  return (
    <div className={styles.wrapper}>
      <div>
        <p className={styles.sectionTitle}>עבודות דחופות (SOS)</p>
        <SosAvailabilityToggle />
      </div>

      {loadError && (
        <div className={styles.banner} role="alert">
          <p>{loadError}</p>
          <Button variant="secondary" onClick={loadWorkingHours}>
            נסה שוב
          </Button>
        </div>
      )}

      {workingHours === null && !loadError && <p className={styles.statusText}>טוען…</p>}

      {showFullPageSetup && (
        <div>
          <p className={styles.sectionTitle}>בואו נגדיר שעות עבודה</p>
          <p className={styles.sectionHint}>
            שעות העבודה קובעות מתי לקוחות יכולים להזמין אותך. אפשר לשנות אותן בכל שלב.
          </p>
          <Card>
            <WorkingHoursForm workingHours={workingHours ?? []} onSaved={handleSaved} />
          </Card>
          <div className={styles.skipRow}>
            <Button variant="ghost" onClick={() => setHasSkippedSetup(true)}>
              דלג, אגדיר מאוחר יותר
            </Button>
          </div>
        </div>
      )}

      {workingHours !== null && !showFullPageSetup && (
        <div>
          <div className={styles.sectionHeaderRow}>
            <p className={styles.sectionTitle}>יומן זמינות שבועי</p>
            <Button variant="secondary" onClick={() => setIsEditModalOpen(true)}>
              <span className={styles.editButtonLabel}>
                <Pencil size={14} aria-hidden="true" />
                עריכת שעות עבודה
              </span>
            </Button>
          </div>
          <WeeklyCalendarGrid />

          <Modal isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)} title="עריכת שעות עבודה" size="normal">
            <WorkingHoursForm workingHours={workingHours} onSaved={handleSaved} onCancel={() => setIsEditModalOpen(false)} />
          </Modal>
        </div>
      )}
    </div>
  );
}
