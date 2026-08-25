-- Production MS2 -- geocoded coordinates for every customer-side service location.
--
-- Three places a service address lives in this schema, and all three now carry the resolved
-- coordinates alongside the human-readable text they already had:
--
--   users.default_*         the customer's default address (V20)
--   orders.service_*        the per-order snapshot (V18)
--   sos_requests.*          the SOS destination (V34) -- already had latitude/longitude
--
-- THE TEXT SNAPSHOT IS NEVER DISCARDED. Coordinates are added beside it, never instead of
-- it: a professional still needs to read "רחוב הרצל 12, דירה 4, קומה 2" to find the door, and
-- an order's address must remain exactly what was agreed even if a geocoder later resolves
-- the same string differently.
--
-- EVERY COLUMN IS NULLABLE, and that is load-bearing rather than lazy. Legacy rows have no
-- coordinates and no way to acquire them without calling a paid external API for every
-- historical row; forcing NOT NULL would either destroy demo/dev data or require exactly that.
-- A NULL means "no usable coordinates", which every MS2 consumer already has to handle,
-- because a live geocode can fail too.

-- ---------------------------------------------------------------------------------------
-- 1. users -- the customer default address.
-- ---------------------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN default_latitude          NUMERIC(9,6),
    ADD COLUMN default_longitude         NUMERIC(9,6),
    -- Geocoding STATE, not just "are the coordinates null". Four situations that a bare NULL
    -- cannot tell apart, and that behave differently:
    --   NULL / 'PENDING'   never attempted, or the address changed and must be re-resolved
    --   'RESOLVED'         coordinates below are good
    --   'FAILED'           the provider answered, and the answer was "no such place".
    --                      Retrying the identical string will fail identically -- do not.
    --   'UNAVAILABLE'      the provider could not be reached. Retrying later is correct.
    -- Kept as a small VARCHAR + CHECK rather than a Postgres ENUM, matching every other
    -- status column in this schema (orders.order_status, professionals.approval_status).
    ADD COLUMN default_geocode_status    VARCHAR(20),
    ADD COLUMN default_geocoded_at       TIMESTAMPTZ,
    -- A stable digest of the exact address text that produced the coordinates above. This is
    -- what makes "do not geocode an unchanged address repeatedly" enforceable without
    -- comparing seven nullable strings on every read, and what makes an address edit
    -- self-invalidating: the digest stops matching, so the row is re-geocoded.
    ADD COLUMN default_address_hash      VARCHAR(64),
    -- Optional reconciliation of the free-text default_city against the closed
    -- service_cities catalogue (V43). NULLABLE AND ADVISORY: a customer whose city is not in
    -- the catalogue, or a legacy row, keeps working exactly as before -- this never gates
    -- anything, it only lets the platform stop routing on arbitrary inconsistent city
    -- strings where a canonical city is known. ON DELETE SET NULL, because removing a city
    -- from the catalogue must not delete customers.
    ADD COLUMN default_service_city_id   BIGINT;

ALTER TABLE users
    ADD CONSTRAINT ck_users_default_latitude
        CHECK (default_latitude IS NULL OR (default_latitude >= -90 AND default_latitude <= 90)),
    ADD CONSTRAINT ck_users_default_longitude
        CHECK (default_longitude IS NULL OR (default_longitude >= -180 AND default_longitude <= 180)),
    ADD CONSTRAINT ck_users_default_geocode_status
        CHECK (default_geocode_status IS NULL
               OR default_geocode_status IN ('PENDING', 'RESOLVED', 'FAILED', 'UNAVAILABLE')),
    -- Coordinates and the RESOLVED state are two halves of one fact; neither may exist alone.
    ADD CONSTRAINT ck_users_default_geocode_consistency
        CHECK ((default_geocode_status = 'RESOLVED')
               = (default_latitude IS NOT NULL AND default_longitude IS NOT NULL)),
    ADD CONSTRAINT fk_users_default_service_city FOREIGN KEY (default_service_city_id)
        REFERENCES service_cities (id) ON DELETE SET NULL;

COMMENT ON COLUMN users.default_address_hash IS
    'Digest of the address text the coordinates were resolved from. A mismatch means the '
    'address was edited and the coordinates are stale.';
COMMENT ON COLUMN users.default_service_city_id IS
    'Advisory reconciliation of default_city against service_cities. NULL for legacy rows and '
    'for cities outside the catalogue; never gates any flow.';

-- ---------------------------------------------------------------------------------------
-- 2. orders -- the per-order destination snapshot.
-- ---------------------------------------------------------------------------------------
--
-- SNAPSHOT SEMANTICS, exactly like the service_* text columns beside them: written once at
-- order creation and never rewritten afterwards. A customer who later edits their default
-- address does not move an order that already exists, and must not -- the professional was
-- dispatched to a specific place, arrival is verified against that place, and rewriting it
-- retroactively would invalidate an arrival that already happened.

ALTER TABLE orders
    ADD COLUMN service_latitude   NUMERIC(9,6),
    ADD COLUMN service_longitude  NUMERIC(9,6);

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_service_latitude
        CHECK (service_latitude IS NULL OR (service_latitude >= -90 AND service_latitude <= 90)),
    ADD CONSTRAINT ck_orders_service_longitude
        CHECK (service_longitude IS NULL OR (service_longitude >= -180 AND service_longitude <= 180));

COMMENT ON COLUMN orders.service_latitude IS
    'Destination coordinates snapshotted at order creation. Immutable for the life of the '
    'order; NULL when the address could not be geocoded (arrival cannot then be verified '
    'geographically).';

-- ---------------------------------------------------------------------------------------
-- 3. sos_requests -- latitude/longitude already exist (V34); only the state is new.
-- ---------------------------------------------------------------------------------------
--
-- V34's column comment recorded these as "captured but unused". As of MS2 they are the SOS
-- destination that every candidate's real driving distance is measured to, and they are
-- filled by the geocoder when the customer did not supply them.

ALTER TABLE sos_requests
    ADD COLUMN geocode_status VARCHAR(20);

ALTER TABLE sos_requests
    ADD CONSTRAINT ck_sos_requests_geocode_status
        CHECK (geocode_status IS NULL
               OR geocode_status IN ('PENDING', 'RESOLVED', 'FAILED', 'UNAVAILABLE'));

COMMENT ON COLUMN sos_requests.latitude IS
    'SOS destination latitude. As of Production MS2 this is load-bearing: it is the point '
    'every candidate professional''s real driving distance and ETA is measured to, and the '
    'radius filter (pronto.sos.max-dispatch-radius-km) is applied against that real distance. '
    'Supplied by the client when the device has a fix, otherwise geocoded from the address.';
COMMENT ON COLUMN sos_requests.geocode_status IS
    'Whether latitude/longitude were resolved, and if not, why. Distinguishes an unresolvable '
    'address (FAILED, do not retry the same string) from a provider outage (UNAVAILABLE).';

-- Backfill: every pre-MS2 SOS row that happens to already carry client-supplied coordinates
-- is marked RESOLVED so it is not needlessly re-geocoded; everything else is left NULL,
-- meaning "never attempted". Deliberately NOT rewriting any address text.
UPDATE sos_requests
SET geocode_status = 'RESOLVED'
WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
