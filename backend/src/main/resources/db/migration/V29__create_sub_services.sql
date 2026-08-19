-- Sub-services under each of the 8 fixed categories -- MS11 (Services & Sub-services).
-- See docs/architecture/product-ms11-sub-services-design.md §2.1/§2.3.
-- Combines create+seed in one migration (unlike categories' historical V1/V10 split,
-- which was an artifact of categories predating the seed-data need being finalized).

CREATE TABLE sub_services (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id     BIGINT         NOT NULL,
    code            VARCHAR(50)    NOT NULL,
    name_he         VARCHAR(100)   NOT NULL,
    name_en         VARCHAR(100)   NOT NULL,
    display_order   SMALLINT       NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ux_sub_services_code UNIQUE (code),
    CONSTRAINT fk_sub_services_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT
);

CREATE INDEX idx_sub_services_category ON sub_services (category_id);

-- Seed data (34 rows across the 8 categories) -- placeholder product content pending real
-- sign-off, see design doc §2.3/§6 item 1. name_en glosses are straightforward English
-- translations of name_he, same pattern categories' own seed already uses.
INSERT INTO sub_services (category_id, code, name_he, name_en, display_order)
SELECT c.id, v.code, v.name_he, v.name_en, v.display_order
FROM (VALUES
    ('plumbing',          'plumbing_unclog',              'פתיחת סתימות',                 'Unclogging',                 1),
    ('plumbing',          'plumbing_leak_repair',          'תיקון נזילות',                 'Leak repair',                2),
    ('plumbing',          'plumbing_faucet_install',       'התקנת ברזים',                  'Faucet installation',        3),
    ('plumbing',          'plumbing_boiler_replace',       'החלפת דוד מים',                'Water heater replacement',   4),
    ('plumbing',          'plumbing_toilet_repair',        'תיקון אסלות ומקלחות',          'Toilet & shower repair',     5),
    ('electrical',        'electrical_fault_repair',       'תיקון תקלות חשמל',             'Electrical fault repair',    1),
    ('electrical',        'electrical_outlet_install',     'התקנת שקעים ומפסקים',          'Outlet & switch installation', 2),
    ('electrical',        'electrical_lighting_install',   'התקנת גופי תאורה',             'Lighting fixture installation', 3),
    ('electrical',        'electrical_panel_upgrade',      'שדרוג לוח חשמל',               'Electrical panel upgrade',  4),
    ('electrical',        'electrical_safety_check',       'בדיקות בטיחות חשמל',           'Electrical safety inspection', 5),
    ('ac_hvac',           'hvac_install',                  'התקנת מזגן',                   'AC installation',            1),
    ('ac_hvac',           'hvac_repair',                   'תיקון מזגן',                   'AC repair',                  2),
    ('ac_hvac',           'hvac_maintenance',              'ניקוי ותחזוקת מזגן',           'AC cleaning & maintenance',  3),
    ('ac_hvac',           'hvac_gas_refill',               'טעינת גז קירור',               'Refrigerant gas refill',     4),
    ('appliance_repair',  'appliance_washer_repair',       'תיקון מכונת כביסה',            'Washing machine repair',     1),
    ('appliance_repair',  'appliance_fridge_repair',       'תיקון מקרר',                   'Refrigerator repair',        2),
    ('appliance_repair',  'appliance_dishwasher_repair',   'תיקון מדיח כלים',              'Dishwasher repair',          3),
    ('appliance_repair',  'appliance_oven_repair',         'תיקון תנור/כיריים',            'Oven / stovetop repair',     4),
    ('locksmith',         'locksmith_lockout',             'פריצת דלת',                    'Lockout / door opening',     1),
    ('locksmith',         'locksmith_cylinder_replace',    'החלפת צילינדר',                'Cylinder replacement',       2),
    ('locksmith',         'locksmith_lock_install',        'התקנת מנעול',                  'Lock installation',          3),
    ('locksmith',         'locksmith_key_duplication',     'שכפול מפתחות',                 'Key duplication',            4),
    ('carpentry',         'carpentry_furniture_repair',    'תיקון והרכבת רהיטים',          'Furniture repair & assembly', 1),
    ('carpentry',         'carpentry_cabinet_install',     'התקנת ארונות',                 'Cabinet installation',       2),
    ('carpentry',         'carpentry_custom_woodwork',     'עבודות עץ בהתאמה אישית',       'Custom woodwork',            3),
    ('carpentry',         'carpentry_door_repair',         'תיקון דלתות עץ',               'Wooden door repair',         4),
    ('painting',          'painting_interior_walls',       'צביעת קירות פנים',             'Interior wall painting',     1),
    ('painting',          'painting_exterior',             'צביעת חוץ',                    'Exterior painting',          2),
    ('painting',          'painting_wall_patching',        'שפכטל ותיקוני קיר',            'Wall patching & spackling',  3),
    ('painting',          'painting_ceilings',             'צביעת תקרות',                  'Ceiling painting',           4),
    ('general_handyman',  'handyman_general_repairs',      'תיקונים כלליים בבית',          'General home repairs',       1),
    ('general_handyman',  'handyman_furniture_assembly',   'הרכבת רהיטים',                 'Furniture assembly',         2),
    ('general_handyman',  'handyman_wall_mounting',        'תלייה על קיר (מדפים/תמונות)',  'Wall mounting (shelves/pictures)', 3),
    ('general_handyman',  'handyman_routine_maintenance',  'תחזוקה שוטפת',                 'Routine maintenance',        4)
) AS v(category_code, code, name_he, name_en, display_order)
JOIN categories c ON c.code = v.category_code;
