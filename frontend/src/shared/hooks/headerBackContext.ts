import { createContext } from 'react';

/** The back affordance a screen wants rendered inside the app header, next to the brand. */
export interface HeaderBackAction {
  /** Visible text beside the arrow. Defaults to "חזרה" at the registration site. */
  label: string;
  onBack: () => void;
}

export interface HeaderBackContextValue {
  /** `null` when the current screen has no back action — the header then renders nothing. */
  action: HeaderBackAction | null;
  /** Referentially stable, so a screen can register once and not re-register per render. */
  setAction: (action: HeaderBackAction | null) => void;
}

/**
 * Lets a routed screen hoist its "חזרה" control into `AppLayout`'s header bar instead of
 * rendering a row of its own below it (`PageHeader`'s `onBack`, which every other screen still
 * uses).
 *
 * **Defaulted rather than `undefined`-and-throw** — unlike `useToast`/`useAuth`, whose absence
 * means a real bug. A header slot is decoration: a page rendered outside the shell (every
 * component test does exactly that) must still render, just without the hoisted button.
 */
export const HeaderBackContext = createContext<HeaderBackContextValue>({
  action: null,
  setAction: () => {},
});
