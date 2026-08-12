-- Reference table for the fixed 8-category v1.0 service list.
-- See docs/architecture/data-model.md §2.1.

CREATE TABLE categories (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(50)  NOT NULL,
    name_he        VARCHAR(100) NOT NULL,
    name_en        VARCHAR(100) NOT NULL,
    display_order  SMALLINT     NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ux_categories_code UNIQUE (code)
);
