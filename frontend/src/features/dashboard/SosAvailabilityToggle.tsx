import { useState } from 'react';
import { getSosAvailability, updateSosAvailability, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { SosAvailabilityResponse } from '../../shared/api';
import { SOS_AVAILABILITY_KEY } from '../../shared/api/resourceKeys';
import { usePolling, primeResource } from '../../shared/hooks';
import styles from './SosAvailabilityToggle.module.css';

/**
 * "זמין/ה לעבודות דחופות (SOS) כרגע" toggle — the professional's own `sos_availability` flag
 * (`GET`/`PUT /api/availability/sos-availability`), per PRD §3.5.2's framing. Rendered at the
 * top of `WeeklyAvailabilityPage`, above the Standard-slot section, rather than as a new
 * dashboard tab — see that page's own doc comment for why a fourth tab for a single toggle would
 * be a "dead/thin nav item". No `Switch` primitive exists yet in `shared/components`; this is a
 * one-off accessible toggle button (`role="switch"`), not a new generic primitive built for
 * a single usage, per this milestone's brief.
 *
 * **Reads through the shared `SOS_AVAILABILITY_KEY`.** `CommandCenterBanner`, a few pixels above
 * this on the same screen, shows the same flag as a badge; before this key existed the two
 * fetched it separately (and could therefore disagree, since only one of them re-read it). Now
 * there is one request between them, and `primeResource` publishes the `PUT`'s own response into
 * that entry — so flipping the switch updates the badge above with no `GET` at all, rather than
 * leaving it stale until its next tick.
 *
 * `enabled: false` because this value only changes when *this* component changes it: a
 * professional's own toggle is not something the server flips underneath them, so a recurring
 * read would be asking a question whose answer this screen already holds.
 */
export function SosAvailabilityToggle() {
  const { data, error } = usePolling<SosAvailabilityResponse>(() => getSosAvailability(), {
    key: SOS_AVAILABILITY_KEY,
    enabled: false,
    fetchOnMountWhenDisabled: true,
  });

  const [isToggling, setIsToggling] = useState(false);
  const [toggleError, setToggleError] = useState<string | null>(null);

  const isAvailable = data?.isAvailable ?? null;
  const isBookable = data?.bookable ?? null;
  const loadError = error ? GENERIC_ERROR_MESSAGE : null;

  async function handleToggle() {
    if (isAvailable === null || isToggling) {
      return;
    }
    setToggleError(null);
    setIsToggling(true);
    try {
      const result = await updateSosAvailability(!isAvailable);
      primeResource(SOS_AVAILABILITY_KEY, result);
    } catch {
      setToggleError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsToggling(false);
    }
  }

  return (
    <div className={styles.wrapper}>
      {loadError && (
        <div className={styles.banner} role="alert">
          <p>{loadError}</p>
        </div>
      )}
      {toggleError && (
        <div className={styles.banner} role="alert">
          <p>{toggleError}</p>
        </div>
      )}
      <div className={styles.row}>
        <div>
          <p className={styles.label}>זמין/ה לעבודות דחופות (SOS) כרגע</p>
          <p className={isAvailable ? styles.stateOn : styles.stateOff}>
            {isAvailable === null ? 'טוען…' : isAvailable ? 'פעיל' : 'כבוי'}
          </p>
          {/* MS1 (D-G): the toggle is the professional's *intent*; `bookable` is whether the
              platform can actually dispatch to them. Saying "פעיל" while they are ineligible
              would claim they're live when no SOS offer can ever reach them. */}
          {isAvailable && isBookable === false && (
            <p className={styles.notEligible}>לא יישלחו אליך קריאות SOS עד להשלמת פרטי החשבון ואישורו.</p>
          )}
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={isAvailable ?? false}
          aria-label="זמינות לעבודות דחופות (SOS)"
          className={`${styles.switch} ${isAvailable ? styles.switchOn : ''}`}
          onClick={handleToggle}
          disabled={isAvailable === null || isToggling}
        >
          <span className={styles.knob} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
