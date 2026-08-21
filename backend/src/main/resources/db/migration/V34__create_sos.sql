-- Pronto SOS: the urgent broadcast-and-choose dispatch flow.
--
-- Distinct from the pre-existing SOS *booking* path (`GET /api/bookings/sos-professionals`
-- + `POST /api/bookings/sos-orders`, Milestone 4), which is browse-and-pick: the customer
-- reads a list and names a professional themselves. Pronto SOS inverts that -- the customer
-- names nobody, the platform matches and dispatches offers, and the customer chooses from
-- whoever accepted. Both paths coexist; neither table below touches `orders`' existing
-- columns.
--
-- Three tables, mirroring V32's precedent for shipping a cohesive feature's schema in one
-- migration: the request (one row per SOS activation), the offers fanned out to
-- professionals (one row per candidate), and the append-only event log (many rows per
-- request).

-- 1. The customer's urgent request. `issue_id` is the anchor: category, free-text
--    description, photos (`issue_images`) and the AI Professional Brief all already hang off
--    an `issues` row, and re-modelling any of them here would fork the source of truth. The
--    columns below are only what SOS genuinely adds on top of an issue.
CREATE TABLE sos_requests (
    id                        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id                  BIGINT       NOT NULL,
    customer_id               BIGINT       NOT NULL,
    -- Snapshot of `issues.category_id` at activation. Denormalized deliberately: every
    -- matching query filters on it, and it must not drift if the issue is ever re-routed
    -- mid-dispatch. `sub_service_id` is optional -- MS11 sub-services are not mandatory.
    category_id               BIGINT       NOT NULL,
    sub_service_id            BIGINT,
    -- Short customer-written headline for the dispatch card ("burst pipe, water everywhere").
    -- The full text lives on `issues.description` and is never copied here.
    issue_summary             VARCHAR(300),
    urgency                   VARCHAR(20)  NOT NULL DEFAULT 'URGENT',

    -- Service address snapshot, same shape and rationale as `orders.service_*` (V18/V22).
    service_city              VARCHAR(100) NOT NULL,
    service_street            VARCHAR(150) NOT NULL,
    service_house_number      VARCHAR(20)  NOT NULL,
    service_apartment         VARCHAR(20),
    service_floor             VARCHAR(20),
    service_entrance          VARCHAR(20),
    service_address_notes     VARCHAR(500),

    -- Optional client-supplied coordinates. Nothing in v1 matching reads these --
    -- `matching.ApproximateDistanceEtaStrategy` is city-string-based, the only distance
    -- infrastructure this codebase has. They are captured and returned so a real
    -- geocoding/routing provider can replace that strategy later without a migration or an
    -- API-contract change. Documented as forward-looking rather than left implicit.
    latitude                  NUMERIC(9,6),
    longitude                 NUMERIC(9,6),

    status                    VARCHAR(40)  NOT NULL DEFAULT 'CREATED',
    selected_professional_id  BIGINT,
    selected_offer_id         BIGINT,
    -- The `orders` row created at selection time, so an SOS job lands in the same
    -- order/review/history machinery every other booking uses. NULL until selection.
    order_id                  BIGINT,
    cancelled_by              VARCHAR(20),

    -- Deadline for professionals to respond (dispatch window). Distinct from
    -- `selection_expires_at`, the customer's ~2-minute choosing window.
    matching_expires_at       TIMESTAMPTZ,
    selection_expires_at      TIMESTAMPTZ,

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    matched_at                TIMESTAMPTZ,
    candidates_ready_at       TIMESTAMPTZ,
    selected_at               TIMESTAMPTZ,
    confirmed_at              TIMESTAMPTZ,
    cancelled_at              TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,

    CONSTRAINT fk_sos_requests_issue FOREIGN KEY (issue_id)
        REFERENCES issues (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sos_requests_customer FOREIGN KEY (customer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sos_requests_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sos_requests_sub_service FOREIGN KEY (sub_service_id)
        REFERENCES sub_services (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sos_requests_selected_professional FOREIGN KEY (selected_professional_id)
        REFERENCES professionals (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sos_requests_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE RESTRICT,

    -- One SOS request per issue. This is the structural guarantee that a customer cannot
    -- double-activate SOS for the same problem and fan out two competing dispatch waves.
    CONSTRAINT ux_sos_requests_issue UNIQUE (issue_id),
    -- One order per SOS request, enforced rather than assumed (the selection path is the
    -- only writer and is transactional, but a duplicate here would mean double-charging).
    CONSTRAINT ux_sos_requests_order UNIQUE (order_id),

    CONSTRAINT ck_sos_requests_status CHECK (status IN (
        'CREATED', 'MATCHING', 'WAITING_FOR_PROFESSIONALS', 'WAITING_FOR_CUSTOMER_SELECTION',
        'PROFESSIONAL_SELECTED', 'CONFIRMED', 'ON_THE_WAY', 'ARRIVED', 'COMPLETED',
        'CANCELLED', 'EXPIRED', 'FAILED'
    )),
    CONSTRAINT ck_sos_requests_urgency CHECK (urgency IN ('URGENT', 'EMERGENCY')),
    CONSTRAINT ck_sos_requests_cancelled_by
        CHECK (cancelled_by IS NULL OR cancelled_by IN ('CUSTOMER', 'PROFESSIONAL', 'SYSTEM')),
    CONSTRAINT ck_sos_requests_latitude
        CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90)),
    CONSTRAINT ck_sos_requests_longitude
        CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180)),
    -- A selected professional and a selected offer arrive together or not at all.
    CONSTRAINT ck_sos_requests_selection_pairing
        CHECK ((selected_professional_id IS NULL) = (selected_offer_id IS NULL))
);

-- The customer's "do I have a live SOS?" read, and the customer history list.
CREATE INDEX idx_sos_requests_customer_created ON sos_requests (customer_id, created_at DESC);
-- The sweep job's driving query: "which non-terminal requests have blown a deadline?".
CREATE INDEX idx_sos_requests_status_created ON sos_requests (status, created_at);
CREATE INDEX idx_sos_requests_selection_expires ON sos_requests (selection_expires_at)
    WHERE selection_expires_at IS NOT NULL;
CREATE INDEX idx_sos_requests_matching_expires ON sos_requests (matching_expires_at)
    WHERE matching_expires_at IS NOT NULL;
CREATE INDEX idx_sos_requests_selected_professional ON sos_requests (selected_professional_id)
    WHERE selected_professional_id IS NOT NULL;
CREATE INDEX idx_sos_requests_category_status ON sos_requests (category_id, status);

-- 2. One dispatched opportunity per (request, professional). Fee columns are snapshotted at
--    offer time, not read live at selection: the professional accepts a specific number, and
--    a `professionals.base_price` edit mid-flight must not silently change what either party
--    agreed to.
CREATE TABLE sos_offers (
    id                        BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sos_request_id            BIGINT        NOT NULL,
    professional_id           BIGINT        NOT NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'OFFERED',
    -- 1-based position in the ranked candidate list this offer was dispatched from, and the
    -- score that produced it. Kept for tuning the ranking against real acceptance data --
    -- without them a bad ranking is invisible after the fact.
    match_rank                SMALLINT      NOT NULL,
    match_score               NUMERIC(6,3)  NOT NULL,
    distance_km               NUMERIC(7,2),
    -- Platform's ETA estimate at dispatch; overwritten by the professional's own figure when
    -- they accept (they know their own traffic and job queue better than the estimator does).
    estimated_arrival_minutes SMALLINT,
    -- The professional's visit fee (`professionals.base_price` at dispatch time).
    visit_fee                 NUMERIC(10,2),
    -- The SOS surcharge that incentivizes taking an urgent call.
    sos_fee                   NUMERIC(10,2) NOT NULL DEFAULT 0,
    -- Pronto's cut. Computed from the *visit-related* fees only (visit_fee + sos_fee), never
    -- from the value of the repair itself -- that is the whole business model. Snapshotted so
    -- a later commission-rate change never rewrites historical economics.
    platform_commission       NUMERIC(10,2) NOT NULL DEFAULT 0,
    offered_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    viewed_at                 TIMESTAMPTZ,
    responded_at              TIMESTAMPTZ,
    expires_at                TIMESTAMPTZ   NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_sos_offers_request FOREIGN KEY (sos_request_id)
        REFERENCES sos_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_sos_offers_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE RESTRICT,

    -- The duplicate-offer guard. A dispatch expansion wave re-running over an overlapping
    -- candidate pool must not be able to offer the same professional the same job twice.
    CONSTRAINT ux_sos_offers_request_professional UNIQUE (sos_request_id, professional_id),

    CONSTRAINT ck_sos_offers_status CHECK (status IN (
        'OFFERED', 'VIEWED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SELECTED', 'NOT_SELECTED'
    )),
    CONSTRAINT ck_sos_offers_match_rank CHECK (match_rank >= 1),
    CONSTRAINT ck_sos_offers_eta CHECK (estimated_arrival_minutes IS NULL OR estimated_arrival_minutes >= 0),
    CONSTRAINT ck_sos_offers_visit_fee CHECK (visit_fee IS NULL OR visit_fee >= 0),
    CONSTRAINT ck_sos_offers_sos_fee CHECK (sos_fee >= 0),
    CONSTRAINT ck_sos_offers_commission CHECK (platform_commission >= 0)
);

-- The professional's inbox ("my live offers"), the hottest query in the whole flow.
CREATE INDEX idx_sos_offers_professional_status ON sos_offers (professional_id, status, created_at DESC);
-- Candidate assembly for one request.
CREATE INDEX idx_sos_offers_request_status ON sos_offers (sos_request_id, status);
-- The offer-expiry sweep.
CREATE INDEX idx_sos_offers_expires_at ON sos_offers (expires_at)
    WHERE status IN ('OFFERED', 'VIEWED');
-- Responsiveness statistics feeding the ranker (acceptance rate / response speed).
CREATE INDEX idx_sos_offers_professional_created ON sos_offers (professional_id, created_at DESC);

-- `sos_requests.selected_offer_id` -> `sos_offers.id` is added here rather than inline above,
-- because the two tables reference each other and `sos_offers` did not exist yet at that point.
ALTER TABLE sos_requests ADD CONSTRAINT fk_sos_requests_selected_offer
    FOREIGN KEY (selected_offer_id) REFERENCES sos_offers (id) ON DELETE RESTRICT;

-- 3. Append-only chronological history. Deliberately its own table rather than a reuse of
--    `notifications`: notifications are per-recipient delivery records that get read/dismissed,
--    whereas this is an immutable per-request audit trail that both parties see the same view
--    of. It is also what the realtime layer will replay/publish from in the next phase, which
--    is why every row carries enough context to be rendered without re-querying the request.
CREATE TABLE sos_events (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sos_request_id  BIGINT       NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    actor_type      VARCHAR(20)  NOT NULL,
    actor_user_id   BIGINT,
    professional_id BIGINT,
    sos_offer_id    BIGINT,
    from_status     VARCHAR(40),
    to_status       VARCHAR(40),
    -- Free-text human-readable context ("ETA 18 min", "no eligible professionals in radius").
    -- Not JSON: nothing queries inside it, same reasoning V32 records for its TEXT columns.
    detail          TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_sos_events_request FOREIGN KEY (sos_request_id)
        REFERENCES sos_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_sos_events_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE SET NULL,
    CONSTRAINT fk_sos_events_offer FOREIGN KEY (sos_offer_id)
        REFERENCES sos_offers (id) ON DELETE SET NULL,

    CONSTRAINT ck_sos_events_type CHECK (event_type IN (
        'SOS_CREATED', 'MATCHING_STARTED', 'OFFERS_SENT', 'OFFER_VIEWED',
        'PROFESSIONAL_RESPONDED', 'CANDIDATES_READY', 'CUSTOMER_SELECTION_STARTED',
        'PROFESSIONAL_SELECTED', 'PROFESSIONAL_CONFIRMED', 'ON_THE_WAY', 'ARRIVED',
        'COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED'
    )),
    CONSTRAINT ck_sos_events_actor_type CHECK (actor_type IN ('CUSTOMER', 'PROFESSIONAL', 'SYSTEM'))
);

-- Timeline read: every event for one request, in order. The frontend timeline's only query.
CREATE INDEX idx_sos_events_request_created ON sos_events (sos_request_id, created_at, id);

-- Duplicate-event backstop. The listed types are the repeatable ones (many professionals
-- respond to one request; an offer can be viewed by each of its recipients); every other
-- lifecycle event happens exactly once per request. In practice each of those is already
-- emitted inside a guarded atomic status transition that can only win once, so this index
-- should never fire -- it is an assertion against a future regression in the state machine,
-- not a routine code path.
CREATE UNIQUE INDEX ux_sos_events_singleton ON sos_events (sos_request_id, event_type)
    WHERE event_type NOT IN ('PROFESSIONAL_RESPONDED', 'OFFER_VIEWED');
