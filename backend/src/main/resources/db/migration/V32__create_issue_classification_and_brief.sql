-- Issue-classification redesign: persist the clarification conversation, the routing
-- telemetry, and the Professional Brief.
--
-- Before this migration nothing about the AI pass survived the request: `POST
-- /api/issues/classify` was stateless and `POST /api/issues` stored only the customer's final
-- category. The clarification questions and answers -- the highest-signal context anyone
-- produces in the whole flow -- were discarded, and the professional received the raw
-- description and nothing else.
--
-- Three tables rather than columns on `issues`: the clarification history is one-to-many, and
-- both the classification record and the brief are optional, independently-failing artefacts
-- with their own lifecycles. Widening `issues` would have made every read of a core table
-- carry AI payloads it does not need.
--
-- List-valued columns are TEXT holding a JSON array, converted by JPA AttributeConverters
-- (`issues.entity.converter`). Deliberately not `jsonb`: nothing queries inside these values,
-- they are read as whole documents by exactly one consumer, and TEXT keeps
-- `spring.jpa.hibernate.ddl-auto: validate` unambiguous across dialects.

-- 1. The clarification conversation: one row per question asked and answered, in order.
CREATE TABLE issue_clarifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id    BIGINT      NOT NULL,
    position    SMALLINT    NOT NULL,
    question    TEXT        NOT NULL,
    answer      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_issue_clarifications_issue FOREIGN KEY (issue_id)
        REFERENCES issues (id) ON DELETE CASCADE,
    -- Ordering is data, not an accident of insertion order, and a duplicated position would
    -- silently corrupt the replayed conversation the brief prompt is built from.
    CONSTRAINT ux_issue_clarifications_issue_position UNIQUE (issue_id, position),
    CONSTRAINT ck_issue_clarifications_position CHECK (position >= 0)
);

CREATE INDEX idx_issue_clarifications_issue ON issue_clarifications (issue_id);

-- 2. Routing telemetry. `category_id` on `issues` remains the single source of truth for who
--    gets sent; this table records what the AI independently concluded, so the two can be
--    compared. That disagreement rate is the production signal for the routing-accuracy work
--    (the labelled evaluation harness measures accuracy properly; this measures drift).
CREATE TABLE issue_classifications (
    issue_id              BIGINT       PRIMARY KEY,
    ai_category_code      VARCHAR(50),
    ai_confidence         NUMERIC(4,3),
    -- JSON array of {"categoryCode": "...", "confidence": 0.0} objects.
    candidates            TEXT         NOT NULL DEFAULT '[]',
    ambiguity_reason      TEXT,
    clarification_rounds  SMALLINT     NOT NULL DEFAULT 0,
    low_confidence        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_issue_classifications_issue FOREIGN KEY (issue_id)
        REFERENCES issues (id) ON DELETE CASCADE,
    CONSTRAINT ck_issue_classifications_confidence
        CHECK (ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1)),
    CONSTRAINT ck_issue_classifications_rounds CHECK (clarification_rounds >= 0)
);

-- 3. The Professional Brief. `status` exists because generation is asynchronous and may fail:
--    a professional-facing screen must be able to tell "not ready yet" from "we tried and
--    could not", and neither state may block the booking.
CREATE TABLE issue_briefs (
    issue_id                  BIGINT       PRIMARY KEY,
    status                    VARCHAR(20)  NOT NULL,
    customer_problem_summary  TEXT,
    clarification_summary     TEXT,
    image_observations        TEXT         NOT NULL DEFAULT '[]',
    likely_issue_description  TEXT,
    likely_issue_confidence   NUMERIC(4,3),
    likely_issue_evidence     TEXT         NOT NULL DEFAULT '[]',
    possible_causes           TEXT         NOT NULL DEFAULT '[]',
    recommended_tools         TEXT         NOT NULL DEFAULT '[]',
    recommended_parts         TEXT         NOT NULL DEFAULT '[]',
    safety_notes              TEXT         NOT NULL DEFAULT '[]',
    generated_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_issue_briefs_issue FOREIGN KEY (issue_id)
        REFERENCES issues (id) ON DELETE CASCADE,
    CONSTRAINT ck_issue_briefs_status CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT ck_issue_briefs_likely_confidence
        CHECK (likely_issue_confidence IS NULL
               OR (likely_issue_confidence >= 0 AND likely_issue_confidence <= 1))
);
