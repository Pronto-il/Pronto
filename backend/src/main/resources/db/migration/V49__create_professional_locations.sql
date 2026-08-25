-- Production MS2 (Real Maps, Geocoding, Distance, ETA & Professional Live Location).
--
-- The professional's CURRENT DEVICE POSITION -- the origin every real driving distance and
-- ETA in this platform is measured from.
--
-- Why a dedicated table rather than three more columns on `professionals`:
--
--   1. Different lifecycle. `professionals` is profile/business data that changes when a
--      person edits their profile. This is transient operational telemetry that is rewritten
--      every few minutes while somebody is working. Mixing them means every location ping
--      bumps professionals.updated_at, which is the column the profile screens and the
--      approval audit trail read.
--   2. Different privacy class. This table is never joined into any customer-facing
--      projection. Keeping it separate makes "did a customer-facing DTO just read raw GPS"
--      answerable by looking at which tables a query touches, rather than by auditing which
--      columns of a wide `professionals` row a SELECT * happened to pull.
--   3. Different retention. A future milestone that wants to purge stale positions, or to
--      keep a short movement history, changes one table.
--
-- REPLACE/UPDATE SEMANTICS, NOT A GPS HISTORY. professional_id is the PRIMARY KEY: exactly
-- one current row per professional, upserted. MS2 deliberately does not build live location
-- history or route replay -- see the maps package README.

CREATE TABLE professional_locations (
    professional_id  BIGINT         PRIMARY KEY,
    -- Same NUMERIC(9,6) precision and the same range CHECKs sos_requests.latitude/longitude
    -- already use (V34), deliberately: two tables holding the same kind of value should not
    -- disagree about what a legal value is. 6 decimal places is ~11 cm at the equator, far
    -- finer than any consumer GPS fix.
    latitude         NUMERIC(9,6)   NOT NULL,
    longitude        NUMERIC(9,6)   NOT NULL,
    -- The device's own reported horizontal accuracy, in metres (the radius of the 68%
    -- confidence circle, which is what the W3C Geolocation API's `coords.accuracy` means).
    -- NOT NULL: a fix with no accuracy figure cannot be quality-checked, and MS2's whole
    -- position is that an unqualified fix must not be treated as a precise one.
    accuracy_meters  NUMERIC(8,2)   NOT NULL,
    -- When the DEVICE took the reading (client clock).
    captured_at      TIMESTAMPTZ    NOT NULL,
    -- When THIS SERVER accepted it. Freshness is evaluated against both -- see
    -- maps.config.LocationProperties: a client whose clock is wrong (or is lying) cannot
    -- make a stale fix look fresh, because the server timestamp is not client-controlled.
    updated_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT fk_professional_locations_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT ck_professional_locations_latitude
        CHECK (latitude >= -90 AND latitude <= 90),
    CONSTRAINT ck_professional_locations_longitude
        CHECK (longitude >= -180 AND longitude <= 180),
    -- Strictly positive: 0 would claim a perfect fix, which no GPS receiver can produce.
    -- The upper bound is a sanity rail, not the product rule -- the product rule is
    -- pronto.location.max-accuracy-meters, which is configurable and much tighter. A value
    -- this large is a malformed client, not a poor fix.
    CONSTRAINT ck_professional_locations_accuracy
        CHECK (accuracy_meters > 0 AND accuracy_meters <= 100000)
);

COMMENT ON TABLE professional_locations IS
    'Current device position per professional (one row, upserted). Private operational data: '
    'never exposed to customers in any DTO. Used as the routing origin for real distance/ETA '
    'and as the geofence input for verified arrival.';
COMMENT ON COLUMN professional_locations.captured_at IS
    'Device-reported capture time. Client-supplied; never trusted alone -- freshness is the '
    'stricter of this and updated_at.';
COMMENT ON COLUMN professional_locations.updated_at IS
    'Server receive time. Not client-controllable.';

-- The freshness sweep ("who has a usable position right now") is the only non-PK access
-- pattern MS2 has, and it filters on the server timestamp.
CREATE INDEX idx_professional_locations_updated_at ON professional_locations (updated_at);
