-- Customer phone number (professional weekly availability calendar design, §2.5/§9.1).
-- Not part of the calendar/availability feature itself -- new, approved scope bundled into
-- the same design pass at the user's direction. Nullable at the DB level, mirroring
-- V20__alter_users_add_default_address.sql's exact pattern: no backfillable value for
-- pre-existing rows, and always NULL for a PROFESSIONAL row (this design collects phone for
-- CUSTOMER registration only). Enforced as required (@NotBlank) for new CUSTOMER
-- registrations at the Bean Validation / service layer, not by a DB-level NOT NULL.
--
-- VARCHAR(20) matches the length already used for default_house_number/default_apartment
-- et al. -- ample for any Israeli phone number format, with or without a country-code
-- prefix. Read-only after registration -- no edit endpoint is added in this design, same as
-- defaultAddress.

ALTER TABLE users ADD COLUMN phone VARCHAR(20);
