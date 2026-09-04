import { useContext } from 'react';
import { HeaderBackContext } from '../shared/hooks';

/**
 * Test stand-in for `AppLayout`'s header back slot: renders whatever the screen under test
 * published through `useHeaderBackAction`, and nothing when it published nothing.
 *
 * Lets a screen-level test drive the real back control without mounting the whole app shell. The
 * bar's own markup and placement (inline start, beside the brand) are asserted against the real
 * `AppLayout` in `app/AppLayout.test.tsx`.
 */
export function HeaderSlot() {
  const { action } = useContext(HeaderBackContext);
  return (
    <div data-testid="header-slot">
      {action && (
        <button type="button" onClick={action.onBack}>
          {action.label}
        </button>
      )}
    </div>
  );
}
