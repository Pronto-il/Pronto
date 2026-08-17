import { useEffect, useState } from 'react';
import { getSosAvailability, updateSosAvailability, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import styles from './SosAvailabilityToggle.module.css';

/**
 * "זמין/ה לעבודות דחופות (SOS) כרגע" toggle — the professional's own `sos_availability` flag
 * (`GET`/`PUT /api/availability/sos-availability`), per PRD §3.5.2's framing. Rendered at the
 * top of `AvailabilityPage`, above the Standard-slot section, rather than as a new dashboard
 * tab — see that page's own doc comment for why a fourth tab for a single toggle would be a
 * "dead/thin nav item". No `Switch` primitive exists yet in `shared/components`; this is a
 * one-off accessible toggle button (`role="switch"`), not a new generic primitive built for
 * a single usage, per this milestone's brief.
 */
export function SosAvailabilityToggle() {
  const [isAvailable, setIsAvailable] = useState<boolean | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [isToggling, setIsToggling] = useState(false);
  const [toggleError, setToggleError] = useState<string | null>(null);

  useEffect(() => {
    getSosAvailability()
      .then((result) => setIsAvailable(result.isAvailable))
      .catch(() => setLoadError(GENERIC_ERROR_MESSAGE));
  }, []);

  async function handleToggle() {
    if (isAvailable === null || isToggling) {
      return;
    }
    setToggleError(null);
    setIsToggling(true);
    try {
      const result = await updateSosAvailability(!isAvailable);
      setIsAvailable(result.isAvailable);
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
