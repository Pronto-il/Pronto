-- A professional's selected sub-services -- pure many-to-many join, no independent meaning
-- beyond the relationship itself. Modeled directly on favorites (V17__create_favorites.sql).
-- See docs/architecture/product-ms11-sub-services-design.md §2.2.
-- Empty at migration time -- every existing professional starts with zero selected
-- sub-services, same "expected onboarding state, not a migration gap" framing
-- professional_working_hours already used.

CREATE TABLE professional_sub_services (
    professional_id   BIGINT       NOT NULL,
    sub_service_id    BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_professional_sub_services PRIMARY KEY (professional_id, sub_service_id),
    CONSTRAINT fk_professional_sub_services_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT fk_professional_sub_services_sub_service FOREIGN KEY (sub_service_id)
        REFERENCES sub_services (id) ON DELETE CASCADE
);
