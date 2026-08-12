-- Shared users table for both CUSTOMER and PROFESSIONAL roles.
-- See docs/architecture/data-model.md §2.2.

CREATE TABLE users (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name               VARCHAR(150) NOT NULL,
    email                   VARCHAR(255) NOT NULL,
    password_hash           VARCHAR(255) NOT NULL,
    role                    VARCHAR(20)  NOT NULL,
    email_verified          BOOLEAN      NOT NULL DEFAULT false,
    failed_login_attempts   SMALLINT     NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'PROFESSIONAL'))
);

-- Case-insensitive uniqueness on email (rejects Foo@x.com vs foo@x.com duplicates).
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));
