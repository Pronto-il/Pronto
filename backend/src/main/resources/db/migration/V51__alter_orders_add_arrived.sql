-- Production MS2 -- the ARRIVED order status and its verification record.
--
-- WHY A NEW STATUS AT ALL. Before MS2 the professional's job progression was
-- CONFIRMED -> ON_THE_WAY -> COMPLETED, and "I am at the door" had nowhere to live: the
-- customer's tracking screen could only ever say "on the way" until the entire job was
-- finished. MS2 adds a backend-verified geographic step between the two, which is a genuinely
-- new fact about the world (the platform has checked that this person is physically at the
-- address) and therefore a genuinely new state, not a flag on an existing one.
--
--   ON_THE_WAY -> ARRIVED -> COMPLETED
--
-- No other status is added, and no existing transition is removed: CANCELLED is still
-- reachable from ON_THE_WAY, and ON_THE_WAY -> COMPLETED remains legal so that an order in
-- flight when this migration is applied, and a professional whose device has no usable GPS,
-- can still finish the job. Arrival is a verification step, not a toll gate that can strand
-- somebody mid-job -- see the bookings README for that decision and its consequence.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (order_status IN
    ('PENDING', 'CONFIRMED', 'ON_THE_WAY', 'ARRIVED', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'));

-- The verification record. Written once, by the ON_THE_WAY -> ARRIVED transition, and never
-- rewritten -- this is evidence, and evidence that can be overwritten is not evidence.
--
-- Coordinates are stored at the SAME precision as every other position in this schema. They
-- are the professional's own position at the moment they claimed arrival, which is private
-- operational data on exactly the same footing as professional_locations: no customer-facing
-- DTO reads these columns.
ALTER TABLE orders
    ADD COLUMN arrived_at                TIMESTAMPTZ,
    ADD COLUMN arrival_latitude          NUMERIC(9,6),
    ADD COLUMN arrival_longitude         NUMERIC(9,6),
    ADD COLUMN arrival_accuracy_meters   NUMERIC(8,2),
    -- How far the verified position actually was from the order's destination snapshot, in
    -- metres, as measured by the backend (Haversine -- see maps.GeoDistance). Stored rather
    -- than recomputed on demand because the destination snapshot is immutable but the
    -- CONFIGURED radius is not: an operator reviewing a dispute six months later needs to
    -- know the measured distance, not what today's pronto.location.arrival-radius-meters
    -- would have decided.
    ADD COLUMN arrival_distance_meters   NUMERIC(10,2);

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_arrival_latitude
        CHECK (arrival_latitude IS NULL OR (arrival_latitude >= -90 AND arrival_latitude <= 90)),
    ADD CONSTRAINT ck_orders_arrival_longitude
        CHECK (arrival_longitude IS NULL OR (arrival_longitude >= -180 AND arrival_longitude <= 180)),
    ADD CONSTRAINT ck_orders_arrival_accuracy
        CHECK (arrival_accuracy_meters IS NULL
               OR (arrival_accuracy_meters > 0 AND arrival_accuracy_meters <= 100000)),
    ADD CONSTRAINT ck_orders_arrival_distance
        CHECK (arrival_distance_meters IS NULL OR arrival_distance_meters >= 0);

COMMENT ON COLUMN orders.arrived_at IS
    'When the backend VERIFIED arrival (not when the button was pressed). NULL for every order '
    'that never reached ARRIVED, including every order predating Production MS2.';
COMMENT ON COLUMN orders.arrival_distance_meters IS
    'Backend-measured great-circle distance from the professional''s verified position to the '
    'order destination snapshot, at the moment of verification.';

-- The customer-facing notification for the new transition.
--
-- The SOS flow has had SOS_ARRIVED since V35; the Standard flow had no equivalent because it
-- had no arrival step to announce. Same recipient-oriented naming convention as every other
-- value here. The pre-existing list is reproduced verbatim -- V14's ORDER_REJECTED and V35's
-- SOS_* values are all preserved.

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_message_type;

-- SOS_TEMPORARILY_UNAVAILABLE is the other MS2 addition, and it exists to stop the platform
-- telling a customer something false. Before MS2, SOS could only fail one way ("nobody
-- eligible"), because distance was a string comparison that could not fail. Now that candidate
-- distance comes from an external routing provider, a provider outage is a real and distinct
-- outcome -- and reporting it as SOS_NO_PROFESSIONALS would tell a customer with a burst pipe
-- that no plumber is available, when the truth is that Pronto could not measure how far away
-- the available plumbers are. Different fact, different recovery, different message.

ALTER TABLE notifications ADD CONSTRAINT ck_notifications_message_type CHECK (message_type IN (
    'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY', 'ORDER_ARRIVED', 'ORDER_COMPLETED',
    'ORDER_CANCELLED', 'ORDER_REJECTED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION',
    'SOS_OFFER_RECEIVED', 'SOS_OFFER_EXPIRED', 'SOS_CANDIDATES_READY', 'SOS_NOT_SELECTED',
    'SOS_PROFESSIONAL_SELECTED', 'SOS_PROFESSIONAL_CONFIRMED', 'SOS_ON_THE_WAY',
    'SOS_ARRIVED', 'SOS_COMPLETED', 'SOS_CANCELLED', 'SOS_EXPIRED', 'SOS_NO_PROFESSIONALS',
    'SOS_TEMPORARILY_UNAVAILABLE'
));
