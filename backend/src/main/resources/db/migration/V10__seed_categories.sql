-- Seed the fixed 8-category v1.0 list. See docs/architecture/data-model.md §2.1 and
-- docs/architecture/overview.md §3.8.

INSERT INTO categories (code, name_he, name_en, display_order) VALUES
    ('plumbing',          'אינסטלציה',        'Plumbing',          1),
    ('electrical',        'חשמל',              'Electrical',        2),
    ('ac_hvac',           'מיזוג אוויר',       'AC / HVAC',         3),
    ('appliance_repair',  'תיקון מוצרי חשמל',  'Appliance Repair',  4),
    ('locksmith',         'מנעולן',            'Locksmith',         5),
    ('carpentry',         'נגרות',             'Carpentry',         6),
    ('painting',          'צביעה',             'Painting',          7),
    ('general_handyman',  'הנדימן כללי',       'General Handyman',  8);
