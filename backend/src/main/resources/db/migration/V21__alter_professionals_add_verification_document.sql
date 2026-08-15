-- Professional verification-document reference, collected at registration (Backend
-- Registration Flow Separation task). Nullable at the DB level, same convention as
-- V20/V15/V18 -- existing professionals (registered before this change) have no
-- document on file; enforced as required for new PROFESSIONAL registrations at the
-- service layer (AuthService), which uploads the file and persists the resulting key
-- as part of the same registration transaction.
--
-- Stores only an object-storage key (storage.client.StorageClient), never the raw
-- document bytes, same pattern as the pre-existing profile_image_key column (V15).

ALTER TABLE professionals ADD COLUMN verification_document_key VARCHAR(500);
