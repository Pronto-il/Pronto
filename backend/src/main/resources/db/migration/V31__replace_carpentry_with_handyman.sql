-- Retire the Carpentry category and make the existing General Handyman category Pronto's
-- single, first-class "הנדימן" profession.
--
-- Why this shape rather than renaming `carpentry` in place: Pronto already had a handyman
-- category (`general_handyman`, id 8) whose sub-services were already furniture assembly,
-- wall mounting, general repairs and routine maintenance — i.e. exactly the scope being
-- asked for. Renaming carpentry to "הנדימן" would have produced two near-identical Hebrew
-- categories and an ambiguous choice for the AI classifier on precisely the jobs this change
-- is about. So the surviving row is id 8, and `carpentry` is folded into it.
--
-- `categories.code` is left as `general_handyman`, deliberately: it is referenced by
-- `MockAiClassificationClient.FALLBACK_CATEGORY_CODE`, by `CategoryRoutingProfiles`, and by
-- every `handyman_*` sub-service code. The customer-visible names are what change.

-- 1. Re-point everything that referenced Carpentry at Handyman, before the row can be
--    removed. `professionals.category_id` and `issues.category_id` are the only two columns
--    in the schema that reference `categories(id)`; orders/reviews/favorites/availability all
--    reach a category transitively through those two, so they follow automatically and no
--    booking, review or availability row is orphaned.
UPDATE professionals
   SET category_id = (SELECT id FROM categories WHERE code = 'general_handyman')
 WHERE category_id = (SELECT id FROM categories WHERE code = 'carpentry');

UPDATE issues
   SET category_id = (SELECT id FROM categories WHERE code = 'general_handyman'),
       updated_at  = now()
 WHERE category_id = (SELECT id FROM categories WHERE code = 'carpentry');

-- 2. Drop Carpentry's sub-services. These are woodwork-specific (custom joinery, wooden door
--    repair) and have no equivalent under Handyman, so they are removed rather than migrated;
--    the genuinely handyman-shaped part of that scope (cabinet/door adjustment, hinges) is
--    added to Handyman's own sub-services in step 4 instead.
--    Any professional_sub_services rows pointing at them must go first — a professional who
--    had selected a carpentry sub-service keeps their (now Handyman) category, they simply
--    lose that specific sub-service selection.
DELETE FROM professional_sub_services
 WHERE sub_service_id IN (
     SELECT s.id FROM sub_services s
       JOIN categories c ON c.id = s.category_id
      WHERE c.code = 'carpentry');

DELETE FROM sub_services
 WHERE category_id = (SELECT id FROM categories WHERE code = 'carpentry');

-- 3. Remove the category itself and rename the survivor. `ux_categories_code` keeps `code`
--    unique; `display_order` is closed up so the list stays 1..7 with no hole where Carpentry
--    used to sit.
DELETE FROM categories WHERE code = 'carpentry';

UPDATE categories
   SET name_he = 'הנדימן',
       name_en = 'Handyman'
 WHERE code = 'general_handyman';

UPDATE categories SET display_order = 6 WHERE code = 'painting';
UPDATE categories SET display_order = 7 WHERE code = 'general_handyman';

-- 4. Round out Handyman's sub-services. The four existing `handyman_*` rows already covered
--    general repairs, furniture assembly, wall mounting and routine maintenance; these four
--    add the small-fixings work that customers previously had to file under Carpentry.
INSERT INTO sub_services (category_id, code, name_he, name_en, display_order)
SELECT c.id, v.code, v.name_he, v.name_en, v.display_order
FROM (VALUES
    ('handyman_curtain_rods',      'התקנת מוטות לווילונות',   'Curtain rod installation',   5),
    ('handyman_door_cabinet_adj',  'כוונון דלתות וארונות',    'Door & cabinet adjustments', 6),
    ('handyman_handles_hinges',    'החלפת ידיות וצירים',      'Handles & hinges',           7),
    ('handyman_other',             'עבודות הנדימן אחרות',     'Other handyman work',        8)
) AS v(code, name_he, name_en, display_order)
CROSS JOIN categories c
WHERE c.code = 'general_handyman';
