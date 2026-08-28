import { useEffect, useId, useRef, useState } from 'react';
import { Input } from './Input';
import type { AddressValue, ResolvedPlace } from './addressTypes';
import { isAddressResolved } from './addressTypes';
import { googlePlacesProvider } from './googlePlaces';
import type { AddressSuggestion, AddressSuggestionProvider } from './googlePlaces';
import styles from './AddressAutocompleteField.module.css';

export interface AddressAutocompleteFieldProps {
  value: AddressValue;
  /** Fired when a suggestion is chosen. The parent applies it with `withSelectedPlace`. */
  onSelect: (place: ResolvedPlace) => void;
  /** Fired when the customer edits the search text, which invalidates any previous selection. */
  onClear: () => void;
  error?: string;
  /** Injected in tests. Production uses the real Google provider. */
  provider?: AddressSuggestionProvider;
}

/** Long enough that a fast typist does not fire a request per character, short enough that the
 *  list feels live. Also the unit Google bills on when combined with a session token. */
const DEBOUNCE_MS = 250;

/**
 * The one place a customer picks a real address.
 *
 * ## The rule this component exists to enforce
 *
 * Typing is not selecting. The text box holds a *query*, not an address — the address only exists
 * once a suggestion is chosen, and editing the text afterwards destroys it again (`onClear`).
 * That is why the query is local state and the committed address lives in the parent's
 * `AddressValue`: they are genuinely different things, and conflating them is exactly how "the
 * user edited the street but we kept the old coordinates" happens.
 *
 * ## Mobile
 *
 * The list is plain buttons in normal document flow, not a floating popover: on a phone the
 * keyboard covers the bottom half of the screen, and an absolutely-positioned dropdown either
 * renders behind it or has to fight the visual viewport. `onMouseDown` rather than `onClick`
 * commits the choice, because a tap blurs the input first and a blur-driven close would eat the
 * selection. Touch targets are sized by the stylesheet to the design system's minimum.
 *
 * ## When Google is unreachable
 *
 * `isConfigured()` false, or every lookup failing, leaves the customer with no suggestions and
 * therefore no way to submit. That is the correct failure: the alternative is accepting free text
 * again, which is the defect being fixed. The field says so in Hebrew rather than looking broken.
 */
export function AddressAutocompleteField({
  value,
  onSelect,
  onClear,
  error,
  provider = googlePlacesProvider,
}: AddressAutocompleteFieldProps) {
  const resolved = isAddressResolved(value);

  /** What is in the box. Seeded from an already-resolved address so an edit screen opens showing
   *  the customer's saved address rather than an empty field. */
  const [query, setQuery] = useState(() => describe(value));
  const [suggestions, setSuggestions] = useState<AddressSuggestion[]>([]);
  const [isOpen, setIsOpen] = useState(false);
  const [isResolving, setIsResolving] = useState(false);
  const [resolveError, setResolveError] = useState<string | null>(null);

  const sessionTokenRef = useRef<unknown>(null);
  const listId = useId();

  // A parent may replace the address wholesale — the booking flow's "use my default address"
  // chip does exactly this. The box has to follow, or it would keep displaying the address the
  // customer just switched away from.
  const committed = describe(value);
  const lastCommittedRef = useRef(committed);
  useEffect(() => {
    if (committed !== lastCommittedRef.current) {
      lastCommittedRef.current = committed;
      setQuery(committed);
      setSuggestions([]);
      setIsOpen(false);
    }
  }, [committed]);

  useEffect(() => {
    // Nothing to search for: the box is empty, or it already shows a committed selection and the
    // customer has not touched it.
    if (!isOpen || query.trim().length < 2) {
      setSuggestions([]);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (sessionTokenRef.current === null) {
        try {
          sessionTokenRef.current = await provider.newSessionToken();
        } catch {
          // Non-fatal: a tokenless search still works, it is only billed less favourably.
          sessionTokenRef.current = undefined;
        }
      }
      const results = await provider.fetchSuggestions(query, sessionTokenRef.current);
      // The guard that stops an older, slower response overwriting a newer one — without it a
      // laggy request for "הרצ" can land after "הרצל 12" and repopulate the stale list.
      if (!cancelled) {
        setSuggestions(results);
      }
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, isOpen, provider]);

  function handleQueryChange(next: string) {
    setQuery(next);
    setIsOpen(true);
    setResolveError(null);
    if (resolved) {
      // THE invalidation. The moment the text stops being the selected address, the selection is
      // gone and the form is unsubmittable until a new one is chosen.
      onClear();
    }
  }

  async function handleSelect(suggestion: AddressSuggestion) {
    setIsResolving(true);
    setResolveError(null);
    try {
      const place = await provider.resolve(suggestion.placeId, sessionTokenRef.current);
      onSelect(place);
      setQuery(suggestion.description);
      setSuggestions([]);
      setIsOpen(false);
      // The session ends with the details lookup it paid for. The next edit starts a new one.
      sessionTokenRef.current = null;
    } catch {
      setResolveError('לא הצלחנו לאמת את הכתובת. יש לנסות שוב או לבחור כתובת אחרת.');
    } finally {
      setIsResolving(false);
    }
  }

  const unavailable = !provider.isConfigured();

  return (
    <div className={styles.wrapper}>
      <Input
        label="חיפוש כתובת"
        value={query}
        onChange={(e) => handleQueryChange(e.target.value)}
        onFocus={() => setIsOpen(true)}
        error={error ?? resolveError ?? undefined}
        hint={
          unavailable
            ? 'חיפוש הכתובות אינו זמין כרגע.'
            : resolved
              ? undefined
              : 'יש להתחיל להקליד ולבחור כתובת מתוך הרשימה'
        }
        required
        autoComplete="off"
        role="combobox"
        aria-expanded={isOpen && suggestions.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
      />

      {resolved && (
        <p className={styles.confirmed} data-testid="address-confirmed">
          <span aria-hidden="true">✓</span> {value.formattedAddress || describe(value)}
        </p>
      )}

      {isResolving && <p className={styles.status}>מאמתים את הכתובת…</p>}

      {isOpen && suggestions.length > 0 && (
        <ul className={styles.suggestions} id={listId} role="listbox" aria-label="הצעות כתובת">
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
                  void handleSelect(suggestion);
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

/** The committed address as one line, for seeding and re-seeding the search box. */
function describe(value: AddressValue): string {
  if (value.formattedAddress) {
    return value.formattedAddress;
  }
  const street = [value.street, value.houseNumber].filter(Boolean).join(' ');
  return [street, value.city].filter(Boolean).join(', ');
}
