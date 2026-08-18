import { useEffect, useState } from 'react';
import { Pencil } from 'lucide-react';
import { Button, Card } from '../../shared/components';
import { getWorkingHours, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { WorkingHoursItem } from '../../shared/api';
import { SosAvailabilityToggle } from './SosAvailabilityToggle';
import { WorkingHoursForm } from './WorkingHoursForm';
import { WeeklyCalendarGrid } from './WeeklyCalendarGrid';
import styles from './WeeklyAvailabilityPage.module.css';

const WEEKDAY_LABELS = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];

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
 * anything. Once a week is configured, the page instead shows a compact read-only summary with
 * an "עריכת שעות עבודה" entry point that expands the same form inline.
 *
 * **Deviation from the design doc, flagged**: §7.2 says the later-edit entry point should open
 * "in a modal/drawer (reuse whatever new `Modal` primitive M5 introduces)." That primitive does
 * not exist yet — it's explicitly introduced in M5 (§7.4/§10), and this milestone's brief
 * explicitly says not to build it speculatively here. Until M5 lands, the edit entry point
 * expands the form **inline** on the page instead (the same "toggle an inline editor" pattern
 * this package's own `SlotList.tsx` already uses for its row-level edit mode) — a functionally
 * equivalent, lower-risk substitute that M5 can swap for the real modal without changing this
 * page's data flow (`WorkingHoursForm`'s own props already fully support either host).
 */
export default function WeeklyAvailabilityPage() {
  const [workingHours, setWorkingHours] = useState<WorkingHoursItem[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [isEditingHours, setIsEditingHours] = useState(false);
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
    setIsEditingHours(false);
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
        <>
          <div>
            <div className={styles.sectionHeaderRow}>
              <p className={styles.sectionTitle}>שעות עבודה</p>
              {!isEditingHours && (
                <button type="button" className={styles.editLink} onClick={() => setIsEditingHours(true)}>
                  <Pencil size={14} aria-hidden="true" />
                  עריכת שעות עבודה
                </button>
              )}
            </div>
            {isEditingHours ? (
              <Card>
                <WorkingHoursForm workingHours={workingHours} onSaved={handleSaved} onCancel={() => setIsEditingHours(false)} />
              </Card>
            ) : (
              <WorkingHoursSummary workingHours={workingHours} />
            )}
          </div>

          <div>
            <p className={styles.sectionTitle}>יומן זמינות שבועי</p>
            <WeeklyCalendarGrid />
          </div>
        </>
      )}
    </div>
  );
}

function WorkingHoursSummary({ workingHours }: { workingHours: WorkingHoursItem[] }) {
  const byWeekday = new Map(workingHours.map((wh) => [wh.weekday, wh]));
  return (
    <Card className={styles.summaryCard}>
      {Array.from({ length: 7 }, (_, weekday) => {
        const row = byWeekday.get(weekday);
        return (
          <div key={weekday} className={styles.summaryRow}>
            <span className={styles.summaryDay}>{WEEKDAY_LABELS[weekday]}</span>
            <span className={row?.enabled ? styles.summaryHours : styles.summaryOff}>
              {row?.enabled ? `${row.startTime}–${row.endTime}` : 'לא עובד/ת'}
            </span>
          </div>
        );
      })}
    </Card>
  );
}
