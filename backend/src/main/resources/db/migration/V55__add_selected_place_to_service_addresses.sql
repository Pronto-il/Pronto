-- Real address validation -- the identity of the place the customer actually SELECTED.
--
-- Until now every service address in this schema was free text: a customer could type any
-- street, any house number and any city, in any combination, including one that does not
-- exist. That text drives professional matching, distance, ETA, service coverage, SOS
-- dispatch and the address a professional drives to, so an address that resolves to nowhere
-- is not a cosmetic data-quality problem -- it is a job nobody can arrive at.
--
-- MS2 (V50) already added the RESOLVED COORDINATES of each address, and those columns are
-- reused unchanged here. What was missing is the answer to a different question: not "where
-- is this text?" but "did a human pick this place from a list of real ones?". A geocode can
-- be attempted against anything; a place id only exists because Google returned that place
-- as a suggestion and somebody chose it.
--
-- Two columns, in the three places a service address already lives:
--
--   users.default_*        the customer's default address (V20, coordinates V50)
--   orders.service_*       the per-order snapshot (V18, coordinates V50)
--   sos_requests.service_* the SOS destination (V34, coordinates V34/V50)
--
-- APPEND-ONLY AND NON-DESTRUCTIVE. Nothing is dropped, narrowed, renamed or rewritten; no
-- existing column changes type or nullability; no UPDATE runs. This migration only adds six
-- nullable columns.
--
-- ---------------------------------------------------------------------------------------
-- BACKWARD COMPATIBILITY -- what happens to everyone who is already here.
-- ---------------------------------------------------------------------------------------
--
-- Every existing row gets NULL in both new columns, and NULL is a meaningful, handled value:
-- "this address predates address validation, so nobody selected it from a list". It is NOT
-- treated as corrupt, and it is deliberately NOT backfilled.
--
--   * No mass geocode. Backfilling would mean one paid Places lookup for every historical
--     user and order, to guess which real place a free-text string had meant -- guesses that
--     would then be indistinguishable from a customer's own confirmed choice, which is the
--     one distinction these columns exist to record.
--   * Existing customers are not locked out. A saved default address with a NULL place id
--     keeps working for booking exactly as it does today: the backend recognises a submitted
--     address as the caller's own stored default (by the V50 address digest) and accepts it
--     without a place id. Nobody is stopped mid-flow to re-enter an address that has been
--     serving them fine.
--   * The data heals on edit, not on a schedule. The next time a customer edits their
--     default address they must select a real suggestion, and the row gains its place id
--     then. A newly entered one-off booking address must be validated immediately, because
--     it is new text nobody has ever confirmed.
--   * Orders are untouched, permanently. The service_* columns on orders are a SNAPSHOT of
--     what was agreed at creation (see V50's header) and stay exactly as they were written.
--     A historical order simply records that its destination was never place-validated,
--     which is true.
--
-- Reverting the application to a previous version is also safe: nothing reads these columns
-- in an older build, and they are nullable, so an older writer inserting without them
-- succeeds.
--
-- ---------------------------------------------------------------------------------------
-- COLUMN SIZING
-- ---------------------------------------------------------------------------------------
--
-- place_id: VARCHAR(255). Google documents place ids as variable-length and explicitly warns
-- against assuming a maximum; 255 is comfortably past anything observed and matches the
-- convention every other opaque external identifier in this schema uses. Not a foreign key,
-- not unique, and deliberately not indexed -- nothing looks a row up by it, and two customers
-- in the same building legitimately share one.
--
-- formatted_address: VARCHAR(500), the same cap as the existing *_address_notes columns.
-- This is Google's own normalized single-line rendering of the selected place. It is stored
-- ALONGSIDE the customer's city/street/house-number text and never over it -- V50's header
-- states that rule for coordinates and it holds here for exactly the same reason: the
-- human-readable address belongs to the customer and to the professional who has to find the
-- door, not to a provider's idea of how it should be spelled.

-- ---------------------------------------------------------------------------------------
-- 1. users -- the customer's default address.
-- ---------------------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN default_place_id          VARCHAR(255),
    ADD COLUMN default_formatted_address VARCHAR(500);

COMMENT ON COLUMN users.default_place_id IS
    'Google place id of the suggestion the customer selected for their default address. NULL '
    'means the address predates address validation (or was never re-selected after an edit); '
    'such a row is grandfathered for booking but must be re-selected the next time the '
    'address is edited.';
COMMENT ON COLUMN users.default_formatted_address IS
    'The provider''s own normalized rendering of the selected place. Stored beside the '
    'customer''s address text, never over it.';

-- ---------------------------------------------------------------------------------------
-- 2. orders -- the per-order destination snapshot.
-- ---------------------------------------------------------------------------------------
--
-- Snapshot semantics, exactly like every other service_* column: written once at order
-- creation and never rewritten. A customer who later corrects their default address does not
-- retroactively move an order that already exists.

ALTER TABLE orders
    ADD COLUMN service_place_id          VARCHAR(255),
    ADD COLUMN service_formatted_address VARCHAR(500);

COMMENT ON COLUMN orders.service_place_id IS
    'Google place id of the destination selected at order creation. Immutable for the life of '
    'the order. NULL for orders created before address validation, and for orders booked to a '
    'grandfathered legacy default address.';

-- ---------------------------------------------------------------------------------------
-- 3. sos_requests -- the SOS destination.
-- ---------------------------------------------------------------------------------------

ALTER TABLE sos_requests
    ADD COLUMN service_place_id          VARCHAR(255),
    ADD COLUMN service_formatted_address VARCHAR(500);

COMMENT ON COLUMN sos_requests.service_place_id IS
    'Google place id of the SOS destination. Same snapshot and grandfathering semantics as '
    'orders.service_place_id.';
