-- MS4 Part A: the canonical, closed list of Israeli service regions and the cities inside
-- them. Modelled deliberately on categories/sub_services (V1/V10 + V29): two reference tables
-- with a stable `code`, a Hebrew display label, a `display_order`, and a parent FK -- because
-- that pair is already the shape this codebase reads controlled catalogues in
-- (professionals.entity.Category / SubService, professionals.controller.CategoriesController),
-- and a second, differently-shaped mechanism for the same job would be the thing that rots.
--
-- Why the database and not a static frontend module: professionals.service_region_id and
-- professional_service_cities are real FKs into these tables, so "an uncontrolled city cannot
-- be persisted" is enforced by PostgreSQL rather than by whichever frontend form remembered to
-- check. A static TS array can only ever be a suggestion.
--
-- The application never writes to either table -- only migrations do, exactly as with
-- categories. Ids are therefore stable and safe to store.

CREATE TABLE service_regions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(50)    NOT NULL,
    name_he         VARCHAR(100)   NOT NULL,
    name_en         VARCHAR(100)   NOT NULL,
    display_order   SMALLINT       NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ux_service_regions_code UNIQUE (code)
);

CREATE TABLE service_cities (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    region_id       BIGINT         NOT NULL,
    code            VARCHAR(60)    NOT NULL,
    name_he         VARCHAR(100)   NOT NULL,
    name_en         VARCHAR(100)   NOT NULL,
    display_order   SMALLINT       NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ux_service_cities_code UNIQUE (code),
    -- The spelling-variant defence named in the MS4 brief: 'תל אביב', 'תל-אביב' and
    -- 'Tel Aviv' cannot become three rows, because there is exactly one row per city and
    -- everything downstream stores its id.
    CONSTRAINT ux_service_cities_name_he UNIQUE (name_he),
    CONSTRAINT fk_service_cities_region FOREIGN KEY (region_id)
        REFERENCES service_regions (id) ON DELETE RESTRICT
);

-- The region -> city filter (Part A §3) is a plain indexed FK lookup, not application logic.
CREATE INDEX idx_service_cities_region ON service_cities (region_id);

INSERT INTO service_regions (code, name_he, name_en, display_order) VALUES
    ('north',     'צפון',              'North',            1),
    ('haifa',     'חיפה והקריות',      'Haifa & Krayot',   2),
    ('sharon',    'השרון',             'Sharon',           3),
    ('gush_dan',  'גוש דן',            'Gush Dan',         4),
    ('center',    'מרכז',              'Center',           5),
    ('jerusalem', 'ירושלים והסביבה',   'Jerusalem area',   6),
    ('south',     'דרום',              'South',            7);

INSERT INTO service_cities (region_id, code, name_he, name_en, display_order)
SELECT r.id, v.code, v.name_he, v.name_en, v.display_order
FROM (VALUES
    -- צפון
    ('north', 'nahariya',        'נהריה',              'Nahariya',            1),
    ('north', 'akko',            'עכו',                'Akko',                2),
    ('north', 'karmiel',         'כרמיאל',             'Karmiel',             3),
    ('north', 'maalot_tarshiha', 'מעלות-תרשיחא',       'Maalot-Tarshiha',     4),
    ('north', 'safed',           'צפת',                'Safed',               5),
    ('north', 'kiryat_shmona',   'קריית שמונה',        'Kiryat Shmona',       6),
    ('north', 'tiberias',        'טבריה',              'Tiberias',            7),
    ('north', 'nof_hagalil',     'נוף הגליל',          'Nof HaGalil',         8),
    ('north', 'nazareth',        'נצרת',               'Nazareth',            9),
    ('north', 'afula',           'עפולה',              'Afula',              10),
    ('north', 'beit_shean',      'בית שאן',            'Beit Shean',         11),
    ('north', 'migdal_haemek',   'מגדל העמק',          'Migdal HaEmek',      12),
    ('north', 'yokneam',         'יקנעם עילית',        'Yokneam Illit',      13),
    ('north', 'shefaram',        'שפרעם',              'Shefa-Amr',          14),
    ('north', 'sakhnin',         'סחנין',              'Sakhnin',            15),
    ('north', 'tamra',           'טמרה',               'Tamra',              16),

    -- חיפה והקריות
    ('haifa', 'haifa',              'חיפה',              'Haifa',              1),
    ('haifa', 'kiryat_ata',         'קריית אתא',         'Kiryat Ata',         2),
    ('haifa', 'kiryat_bialik',      'קריית ביאליק',      'Kiryat Bialik',      3),
    ('haifa', 'kiryat_motzkin',     'קריית מוצקין',      'Kiryat Motzkin',     4),
    ('haifa', 'kiryat_yam',         'קריית ים',          'Kiryat Yam',         5),
    ('haifa', 'nesher',             'נשר',               'Nesher',             6),
    ('haifa', 'tirat_carmel',       'טירת כרמל',         'Tirat Carmel',       7),
    ('haifa', 'zichron_yaakov',     'זכרון יעקב',        'Zichron Yaakov',     8),
    ('haifa', 'hadera',             'חדרה',              'Hadera',             9),
    ('haifa', 'or_akiva',           'אור עקיבא',         'Or Akiva',          10),
    ('haifa', 'pardes_hanna',       'פרדס חנה-כרכור',    'Pardes Hanna-Karkur', 11),
    ('haifa', 'binyamina',          'בנימינה-גבעת עדה',  'Binyamina-Givat Ada', 12),

    -- השרון
    ('sharon', 'netanya',        'נתניה',           'Netanya',         1),
    ('sharon', 'herzliya',       'הרצליה',          'Herzliya',        2),
    ('sharon', 'raanana',        'רעננה',           'Raanana',         3),
    ('sharon', 'kfar_saba',      'כפר סבא',         'Kfar Saba',       4),
    ('sharon', 'hod_hasharon',   'הוד השרון',       'Hod HaSharon',    5),
    ('sharon', 'ramat_hasharon', 'רמת השרון',       'Ramat HaSharon',  6),
    ('sharon', 'even_yehuda',    'אבן יהודה',       'Even Yehuda',     7),
    ('sharon', 'tel_mond',       'תל מונד',         'Tel Mond',        8),
    ('sharon', 'kadima_zoran',   'קדימה-צורן',      'Kadima-Zoran',    9),
    ('sharon', 'pardesiya',      'פרדסיה',          'Pardesiya',      10),
    ('sharon', 'kfar_yona',      'כפר יונה',        'Kfar Yona',      11),
    ('sharon', 'tira',           'טירה',            'Tira',           12),
    ('sharon', 'tayibe',         'טייבה',           'Tayibe',         13),
    ('sharon', 'qalansawe',      'קלנסווה',         'Qalansawe',      14),

    -- גוש דן
    ('gush_dan', 'tel_aviv',       'תל אביב',        'Tel Aviv',        1),
    ('gush_dan', 'ramat_gan',      'רמת גן',         'Ramat Gan',       2),
    ('gush_dan', 'givatayim',      'גבעתיים',        'Givatayim',       3),
    ('gush_dan', 'bnei_brak',      'בני ברק',        'Bnei Brak',       4),
    ('gush_dan', 'holon',          'חולון',          'Holon',           5),
    ('gush_dan', 'bat_yam',        'בת ים',          'Bat Yam',         6),
    ('gush_dan', 'petah_tikva',    'פתח תקווה',      'Petah Tikva',     7),
    ('gush_dan', 'givat_shmuel',   'גבעת שמואל',     'Givat Shmuel',    8),
    ('gush_dan', 'kiryat_ono',     'קריית אונו',     'Kiryat Ono',      9),
    ('gush_dan', 'or_yehuda',      'אור יהודה',      'Or Yehuda',      10),
    ('gush_dan', 'yehud_monosson', 'יהוד-מונוסון',   'Yehud-Monosson', 11),
    ('gush_dan', 'ganei_tikva',    'גני תקווה',      'Ganei Tikva',    12),
    ('gush_dan', 'savyon',         'סביון',          'Savyon',         13),
    ('gush_dan', 'azor',           'אזור',           'Azor',           14),
    ('gush_dan', 'rosh_haayin',    'ראש העין',       'Rosh HaAyin',    15),
    ('gush_dan', 'kafr_qasim',     'כפר קאסם',       'Kafr Qasim',     16),

    -- מרכז
    ('center', 'rishon_lezion',  'ראשון לציון',           'Rishon LeZion',   1),
    ('center', 'rehovot',        'רחובות',                'Rehovot',         2),
    ('center', 'ness_ziona',     'נס ציונה',              'Ness Ziona',      3),
    ('center', 'yavne',          'יבנה',                  'Yavne',           4),
    ('center', 'gan_yavne',      'גן יבנה',               'Gan Yavne',       5),
    ('center', 'gedera',         'גדרה',                  'Gedera',          6),
    ('center', 'lod',            'לוד',                   'Lod',             7),
    ('center', 'ramla',          'רמלה',                  'Ramla',           8),
    ('center', 'beer_yaakov',    'באר יעקב',              'Beer Yaakov',     9),
    ('center', 'modiin',         'מודיעין-מכבים-רעות',    'Modiin',         10),
    ('center', 'shoham',         'שוהם',                  'Shoham',         11),
    ('center', 'elad',           'אלעד',                  'Elad',           12),
    ('center', 'mazkeret_batya', 'מזכרת בתיה',            'Mazkeret Batya', 13),
    ('center', 'kiryat_ekron',   'קריית עקרון',           'Kiryat Ekron',   14),

    -- ירושלים והסביבה
    ('jerusalem', 'jerusalem',      'ירושלים',       'Jerusalem',      1),
    ('jerusalem', 'mevaseret_zion', 'מבשרת ציון',    'Mevaseret Zion', 2),
    ('jerusalem', 'beit_shemesh',   'בית שמש',       'Beit Shemesh',   3),
    ('jerusalem', 'maale_adumim',   'מעלה אדומים',   'Maale Adumim',   4),
    ('jerusalem', 'givat_zeev',     'גבעת זאב',      'Givat Zeev',     5),
    ('jerusalem', 'beitar_illit',   'ביתר עילית',    'Beitar Illit',   6),
    ('jerusalem', 'efrat',          'אפרת',          'Efrat',          7),
    ('jerusalem', 'tzur_hadassah',  'צור הדסה',      'Tzur Hadassah',  8),

    -- דרום
    ('south', 'ashdod',        'אשדוד',        'Ashdod',        1),
    ('south', 'ashkelon',      'אשקלון',       'Ashkelon',      2),
    ('south', 'kiryat_gat',    'קריית גת',     'Kiryat Gat',    3),
    ('south', 'kiryat_malachi', 'קריית מלאכי', 'Kiryat Malachi', 4),
    ('south', 'sderot',        'שדרות',        'Sderot',        5),
    ('south', 'netivot',       'נתיבות',       'Netivot',       6),
    ('south', 'ofakim',        'אופקים',       'Ofakim',        7),
    ('south', 'beer_sheva',    'באר שבע',      'Beer Sheva',    8),
    ('south', 'rahat',         'רהט',          'Rahat',         9),
    ('south', 'lehavim',       'להבים',        'Lehavim',      10),
    ('south', 'meitar',        'מיתר',         'Meitar',       11),
    ('south', 'dimona',        'דימונה',       'Dimona',       12),
    ('south', 'arad',          'ערד',          'Arad',         13),
    ('south', 'yeruham',       'ירוחם',        'Yeruham',      14),
    ('south', 'mitzpe_ramon',  'מצפה רמון',    'Mitzpe Ramon', 15),
    ('south', 'eilat',         'אילת',         'Eilat',        16)
) AS v(region_code, code, name_he, name_en, display_order)
JOIN service_regions r ON r.code = v.region_code;
