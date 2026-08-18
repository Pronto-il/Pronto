-- Professional weekly availability calendar (M1). Double-booking protection (product spec
-- §8) enforced by a Postgres partial exclusion constraint directly on `orders`, not
-- application logic alone. See docs/architecture/professional-weekly-calendar-design.md §6.
--
-- Scope: only rows with a non-null booked_end participate -- this structurally excludes
-- every SOS order (always booked_end = NULL) from this constraint entirely. Statuses
-- included: PENDING/CONFIRMED/ON_THE_WAY -- matches the existing Standard slot-claim
-- semantics (a slot is claimed, i.e. unavailable to anyone else, from the moment an order
-- is created in PENDING, not only once accepted). REJECTED/CANCELLED/EXPIRED/COMPLETED
-- never participate.
--
-- THE ONLY MIGRATION IN THIS SET WITH REAL FAILURE RISK: because availability_slots never
-- enforced non-overlap between a professional's own slots, it is theoretically possible for
-- pre-existing `orders` rows to already violate this new constraint. `ALTER TABLE ... ADD
-- CONSTRAINT` fails outright if so. Given there is no production data pre-launch
-- (overview.md's backend MS9 entry -- QA/dev environments reseed), this is low risk, but run
-- the pre-flight sanity query below against the target database first if this migration
-- ever fails in a seeded environment, to immediately diagnose which rows conflict rather
-- than debugging a bare constraint-violation stack trace:
--
--   SELECT o1.id AS order1_id, o2.id AS order2_id, o1.professional_id,
--          o1.booked_start AS o1_start, o1.booked_end AS o1_end,
--          o2.booked_start AS o2_start, o2.booked_end AS o2_end
--   FROM orders o1
--   JOIN orders o2 ON o1.professional_id = o2.professional_id AND o1.id < o2.id
--   WHERE o1.order_status IN ('PENDING', 'CONFIRMED', 'ON_THE_WAY')
--     AND o2.order_status IN ('PENDING', 'CONFIRMED', 'ON_THE_WAY')
--     AND o1.booked_end IS NOT NULL AND o2.booked_end IS NOT NULL
--     AND tstzrange(o1.booked_start, o1.booked_end) && tstzrange(o2.booked_start, o2.booked_end);
--
-- btree_gist is already enabled by V26, but IF NOT EXISTS here too in case this migration
-- is ever run against a database where V26 was skipped/reordered for any reason.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_no_overlap
    EXCLUDE USING gist (
        professional_id WITH =,
        tstzrange(booked_start, booked_end) WITH &&
    )
    WHERE (order_status IN ('PENDING', 'CONFIRMED', 'ON_THE_WAY') AND booked_end IS NOT NULL);
