import { useEffect, useId, useRef, useState } from 'react';
import { Input } from './Input';
import type { AddressSuggestion } from './googlePlaces';
import styles from './PlaceSuggestionField.module.css';

export interface PlaceSuggestionFieldProps {
  label: string;
  /** The committed selection's text — `''` when nothing is selected yet. The box is seeded and
   *  re-seeded from this, so a parent that replaces the address wholesale (the booking flow's
   *  "use my saved address" chip) is followed rather than ignored. */
  value: string;
  /** Suggestions for what has been typed so far. Contractually never throws — see
   *  `googlePlaces.ts`. Re-read from a ref on every run, so an inline arrow function in the
   *  parent does not restart the debounce on every render. */
  fetchSuggestions: (query: string) => Promise<AddressSuggestion[]>;
  onSelect: (suggestion: AddressSuggestion) => void;
  /** The text was edited, so whatever was selected before no longer describes it. */
  onClear: () => void;
  /** Whether {@link value} represents a real, chosen suggestion (drives the ✓ confirmation). */
  isSelected: boolean;
  disabled?: boolean;
  error?: string;
  hint?: string;
}

/** Long enough that a fast typist does not fire a request per character, short enough that the
 *  list feels live. Also the unit Google bills on when combined with a session token. */
const DEBOUNCE_MS = 250;

/**
 * One "type, then pick from a list" field. Used twice by `AddressFormFields` — once for the city,
 * once for the street — which is the reason it is generic rather than address-shaped: the two
 * differ only in which question they ask Google, and that question is a prop.
 *
 * ## The rule this component exists to enforce
 *
 * Typing is not selecting. The text box holds a *query*, not an answer — the answer only exists
 * once a suggestion is chosen, and editing the text afterwards destroys it again (`onClear`).
 * That is why the query is local state and the committed value lives in the parent: they are
 * genuinely different things, and conflating them is exactly how "the customer edited the street
 * but we kept the old coordinates" happens.
 *
 * ## Mobile
 *
 * The list is plain buttons in normal document flow, not a floating popover: on a phone the
 * keyboard covers the bottom half of the screen, and an absolutely-positioned dropdown either
 * renders behind it or has to fight the visual viewport. `onMouseDown` rather than `onClick`
 * commits the choice, because a tap blurs the input first and a blur-driven close would eat the
 * selection. Touch targets are sized by the stylesheet to the design system's minimum.
 */
export function PlaceSuggestionField({
  label,
  value,
  fetchSuggestions,
  onSelect,
  onClear,
  isSelected,
  disabled,
  error,
  hint,
}: PlaceSuggestionFieldProps) {
  const [query, setQuery] = useState(value);
  const [suggestions, setSuggestions] = useState<AddressSuggestion[]>([]);
  const [isOpen, setIsOpen] = useState(false);
  const listId = useId();

  const fetchRef = useRef(fetchSuggestions);
  fetchRef.current = fetchSuggestions;

  /**
   * A parent may replace the committed value wholesale — the booking flow's "use my saved
   * address" chip, or picking a city, which clears the street. The box has to follow, or it would
   * keep displaying what the customer just switched away from.
   *
   * **Except when this field caused the change itself.** Typing over a committed value calls
   * `onClear`, which empties it; re-seeding the box from that empty value would delete the
   * characters the customer is in the middle of typing. So a self-inflicted clear is skipped
   * exactly once.
   */
  const lastValueRef = useRef(value);
  const skipNextReseedRef = useRef(false);
  useEffect(() => {
    if (value === lastValueRef.current) {
      return;
    }
    lastValueRef.current = value;
    if (skipNextReseedRef.current) {
      skipNextReseedRef.current = false;
      return;
    }
    setQuery(value);
    setSuggestions([]);
    setIsOpen(false);
  }, [value]);

  useEffect(() => {
    // Nothing to search for: the box is empty, or it already shows a committed selection and the
    // customer has not touched it.
    if (!isOpen || disabled || query.trim().length < 2) {
      setSuggestions([]);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(async () => {
      const results = await fetchRef.current(query);
      // The guard that stops an older, slower response overwriting a newer one — without it a
      // laggy request for "הרצ" can land after "הרצל" and repopulate the stale list.
      if (!cancelled) {
        setSuggestions(results);
      }
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, isOpen, disabled]);

  function handleQueryChange(next: string) {
    setQuery(next);
    setIsOpen(true);
    if (isSelected) {
      // THE invalidation. The moment the text stops being the selected value, the selection is
      // gone and the form is unsubmittable until a new one is chosen.
      skipNextReseedRef.current = true;
      onClear();
    }
  }

  function handleSelect(suggestion: AddressSuggestion) {
    // Shown immediately; the effect above then replaces it with whatever the parent actually
    // committed (the bare city or street name rather than the full prediction line).
    setQuery(suggestion.description);
    setSuggestions([]);
    setIsOpen(false);
    onSelect(suggestion);
  }

  return (
    <div className={styles.wrapper}>
      <Input
        label={label}
        value={query}
        onChange={(e) => handleQueryChange(e.target.value)}
        onFocus={() => setIsOpen(true)}
        error={error}
        hint={hint}
        disabled={disabled}
        required
        autoComplete="off"
        role="combobox"
        aria-expanded={isOpen && suggestions.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
      />

      {isOpen && suggestions.length > 0 && (
        <ul className={styles.suggestions} id={listId} role="listbox" aria-label={label}>
          {suggestions.map((suggestion) => (
            <li key={suggestion.placeId} role="presentation">
              <button
                type="button"
                role="option"
                aria-selected={false}
                className={styles.suggestion}
                // mousedown, not click: a tap blurs the input first, and a blur handler that
                // closed the list would cancel the very selection being made.
                onMouseDown={(e) => {
                  e.preventDefault();
                  handleSelect(suggestion);
                }}
              >
                {suggestion.description}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
