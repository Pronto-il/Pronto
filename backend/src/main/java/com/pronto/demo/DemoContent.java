package com.pronto.demo;

import java.util.List;
import java.util.Map;

/**
 * The synthetic Hebrew content the TEST/DEMO dataset is assembled from: names, cities, bios,
 * price bands and review text. Pure data — no Spring, no database, no behavior.
 *
 * <h2>Everything here is invented</h2>
 *
 * No name, phone number, address or review below belongs to a real person or business. Demo
 * accounts are additionally identifiable in the data itself by their reserved
 * {@code @demo.pronto.invalid} email domain ({@link DemoDatasetWriter#DEMO_EMAIL_DOMAIN}) —
 * {@code .invalid} is reserved by RFC 2606 and can never resolve, so no message can ever be
 * delivered to one of these addresses even by accident.
 *
 * <h2>Categories are read from the database, not from here</h2>
 *
 * {@link #forCategory(String)} is a <b>content pack keyed by an existing category code</b>, never
 * the list of categories. {@link DemoDatasetWriter} reads whatever {@code categories} actually
 * contains, in {@code display_order}, and asks this class to dress each one up; a category with no
 * pack gets {@link #GENERIC} rather than being skipped, and a pack whose code is no longer in the
 * table is simply never consulted. That is what keeps this file from quietly becoming a second,
 * competing definition of Pronto's service catalogue — the failure mode
 * {@code V31__replace_carpentry_with_handyman.sql} already had to clean up once.
 */
final class DemoContent {

    /**
     * 20 first names × 12 family names = 240 distinct combinations, indexed so that
     * {@code first[i % 20] + last[(i / 20) % 12]} is unique for every {@code i} below 240 —
     * comfortably more than the dataset needs, with no runtime uniqueness bookkeeping.
     */
    static final List<String> FIRST_NAMES = List.of(
            "אבי", "יוסי", "משה", "דוד", "איתי", "רונן", "עמית", "ניר", "גיא", "טל",
            "שירה", "מיכל", "נועה", "יעל", "דנה", "הילה", "רותם", "ליאור", "אורי", "מאיה");

    static final List<String> LAST_NAMES = List.of(
            "כהן", "לוי", "מזרחי", "פרץ", "ביטון", "אברהם", "דהן", "אזולאי",
            "שפירא", "גולן", "בן דוד", "חדד");

    /**
     * The cities professionals and customers are placed in.
     *
     * <p><b>Index 0 is load-bearing.</b> {@code matching.ApproximateDistanceEtaStrategy} branches
     * on a case-insensitive string comparison between the professional's {@code city} and the
     * customer's service city — 8 km / 15 min when they match, 35 km / 40 min when they do not —
     * so a dataset placing everybody in one city would exercise exactly half of the only
     * distance/ETA implementation Pronto has. {@link DemoDatasetWriter} therefore concentrates a
     * majority of both populations in {@code CITIES.get(0)} and spreads the remainder across the
     * rest, guaranteeing that a single demo search returns same-city and different-city
     * professionals side by side.
     */
    static final List<String> CITIES = List.of(
            "תל אביב", "רמת גן", "גבעתיים", "חולון", "בת ים", "פתח תקווה",
            "ראשון לציון", "הרצליה", "נתניה", "ירושלים", "חיפה", "באר שבע");

    /** Street names for the synthetic addresses on demo customers and demo orders. */
    static final List<String> STREETS = List.of(
            "הרצל", "ביאליק", "ז'בוטינסקי", "אלנבי", "דיזנגוף", "בן יהודה",
            "רוטשילד", "סוקולוב", "וייצמן", "הנביאים", "אבן גבירול", "יפו");

    /**
     * Review text. Deliberately mixed in tone — a marketplace where every review is five stars and
     * effusive does not look real, and the demo is supposed to look real. Indexes are chosen by
     * the rating that was drawn, so a 2-star review never carries "מקצוען אמיתי".
     */
    static final List<String> COMMENTS_POSITIVE = List.of(
            "הגיע בזמן, עבודה נקייה ומחיר הוגן. ממליץ בחום.",
            "שירות מעולה, הסביר הכל בסבלנות ופתר את הבעיה במקום.",
            "מקצוען אמיתי. חזר אליי תוך דקות והגיע כבר באותו היום.",
            "עבודה מדויקת, השאיר את הבית נקי. אשמח להזמין שוב.",
            "אמין, זמין ומחיר בדיוק כמו שסוכם מראש.",
            "פשוט מצוין. הבעיה נפתרה מהפעם הראשונה.");

    static final List<String> COMMENTS_NEUTRAL = List.of(
            "עבודה תקינה, הגיע קצת אחרי הזמן שנקבע אבל סיים יפה.",
            "בסך הכל בסדר גמור. המחיר היה מעט גבוה ממה שציפיתי.",
            "פתר את התקלה. התקשורת לפני ההגעה יכלה להיות טובה יותר.",
            "סבבה, עשה את העבודה. שום דבר יוצא דופן לטוב או לרע.");

    static final List<String> COMMENTS_NEGATIVE = List.of(
            "איחר בשעה ולא עדכן. העבודה עצמה בסדר.",
            "הבעיה חזרה אחרי שבוע ונאלצתי להזמין שוב.",
            "לא מרוצה מהתיאום. בסוף הגיע, אבל אחרי הרבה טלפונים.");

    /**
     * Per-category flavour: the price band a professional's {@code base_price} is drawn from, and
     * the bios they can be given. Prices are plausible Israeli call-out/visit fees in ILS — they
     * are invented, not sourced from any real price list, and nothing in Pronto charges anybody
     * anything.
     *
     * <p>{@code issueDescriptions} are the customer-written problem descriptions on the historical
     * issues each seeded review hangs off. They are written as a real customer would write them
     * and carry <b>no "demo"/"fake" marker</b>: this text is customer-facing, and labelling it
     * would make every demo screenshot advertise that it is fake. Identifiability lives in the
     * reserved email domain instead, where operators can see it and customers cannot.
     *
     * @param minPrice          inclusive lower bound of {@code professionals.base_price}
     * @param maxPrice          inclusive upper bound
     * @param bios              Hebrew profile bios, cycled through deterministically
     * @param issueDescriptions Hebrew issue descriptions appropriate to this trade
     */
    record CategoryContent(int minPrice, int maxPrice, List<String> bios, List<String> issueDescriptions) {
    }

    /**
     * Fallback for any category code with no pack — including any category a future migration
     * adds. Wide, unremarkable price band and bios that make no claim about a specific trade, so a
     * new category still produces a coherent demo instead of an empty one.
     */
    static final CategoryContent GENERIC = new CategoryContent(150, 400, List.of(
            "בעל מקצוע ותיק עם ניסיון רב בעבודה מול לקוחות פרטיים ועסקים קטנים.",
            "עובד באזור כבר שנים רבות, זמין לקריאות דחופות ולעבודות מתוכננות.",
            "שירות אישי, הצעת מחיר ברורה מראש ואחריות על כל עבודה."),
            List.of(
                    "צריך בעל מקצוע שיבוא לבדוק תקלה בבית, לא בטוח מה מקור הבעיה.",
                    "יש כמה תיקונים קטנים שהצטברו ואני רוצה לסגור אותם בביקור אחד.",
                    "משהו הפסיק לעבוד פתאום ואני צריך שמישהו יגיע לבדוק."));

    private static final Map<String, CategoryContent> BY_CATEGORY_CODE = Map.of(
            "plumbing", new CategoryContent(180, 420, List.of(
                    "אינסטלטור מוסמך עם 12 שנות ניסיון. מתמחה בפתיחת סתימות ובאיתור נזילות ללא הרס.",
                    "עבודות אינסטלציה לבית ולעסק: החלפת דודים, תיקון נזילות והתקנת כלים סניטריים.",
                    "זמין גם לקריאות דחופות. ציוד איתור נזילות מתקדם ואחריות מלאה על כל עבודה."),
                    List.of(
                            "יש נזילה מתחת לכיור במטבח, מטפטף גם כשהברז סגור.",
                            "האסלה בשירותים סתומה ולא נפתחת עם פומפה.",
                            "לחץ המים במקלחת נחלש מאוד בחודש האחרון.",
                            "הדוד מטפטף מהצד ויש כתם רטוב בתקרה של השכן.")),
            "electrical", new CategoryContent(200, 480, List.of(
                    "חשמלאי מוסמך בעל רישיון. תיקון תקלות, שדרוג לוחות חשמל ובדיקות בטיחות.",
                    "מתמחה בתשתיות חשמל בדירות ישנות ובהתקנת גופי תאורה ושקעים.",
                    "עבודה לפי תקן, דוח בדיקה בסיום וליווי מלא מול חברת החשמל."),
                    List.of(
                            "הממסר קופץ כל פעם שמפעילים את המדיח.",
                            "שקע בסלון הפסיק לעבוד ויש ריח של חשמל שרוף.",
                            "צריך להתקין שני גופי תאורה בחדר השינה ובמסדרון.",
                            "לוח החשמל ישן ואני רוצה בדיקת בטיחות ושדרוג.")),
            "ac_hvac", new CategoryContent(220, 520, List.of(
                    "טכנאי מיזוג עם ניסיון במערכות מפוצלות ומיני מרכזיות. התקנה, תיקון ותחזוקה.",
                    "ניקוי וחיטוי מזגנים, טעינת גז ואיתור נזילות במערכת הקירור.",
                    "שירות מהיר בעונת השיא, כולל התקנות באותו היום כשיש זמינות."),
                    List.of(
                            "המזגן בסלון מנשב אוויר לא קר ומטפטף מים פנימה.",
                            "צריך להתקין מזגן חדש בחדר עבודה, יש כבר נקודת חשמל.",
                            "המזגן מרעיש מאוד מאז תחילת הקיץ.",
                            "רוצה ניקוי וחיטוי לשלושה מזגנים לפני העונה.")),
            "appliance_repair", new CategoryContent(160, 380, List.of(
                    "טכנאי מוצרי חשמל: מכונות כביסה, מדיחים, תנורים ומקררים מכל היצרנים.",
                    "מגיע עם חלקי חילוף נפוצים ברכב, כך שרוב התקלות נסגרות בביקור אחד.",
                    "אבחון מדויק לפני עבודה והצעת מחיר שקופה, כולל אחריות על החלק שהוחלף."),
                    List.of(
                            "מכונת הכביסה לא מסיימת סחיטה ומשאירה מים בתוף.",
                            "המקרר לא מקרר בתא העליון אבל המקפיא עובד.",
                            "התנור לא מתחמם למרות שהתאורה נדלקת.",
                            "המדיח משאיר כלים מלוכלכים ויש ריח לא נעים.")),
            "locksmith", new CategoryContent(250, 600, List.of(
                    "מנעולן זמין לפריצות דלתות, החלפת צילינדרים והתקנת מנעולי בטחון.",
                    "פתיחת דלתות ללא נזק ברוב המקרים, כולל דלתות פלדה ורב בריח.",
                    "שירות מהיר לאורך כל היום, כולל שדרוג מנעולים לאחר פריצה."),
                    List.of(
                            "נשארתי בחוץ, המפתח נשבר בתוך המנעול.",
                            "צריך להחליף צילינדר בדלת הכניסה אחרי שאיבדתי מפתח.",
                            "המנעול בדלת הממ\"ד תקוע ולא מסתובב.",
                            "רוצה לשדרג את מנעול הדלת למנעול בטחון.")),
            "painting", new CategoryContent(140, 340, List.of(
                    "צבע ותיק לעבודות פנים וחוץ, כולל שפכטל, תיקוני קיר וצביעת תקרות.",
                    "עבודה נקייה עם כיסוי מלא של הרהיטים והרצפה, וסיום בזמן שנקבע.",
                    "ייעוץ בבחירת גוונים וסוגי צבע בהתאם לחדר ולתקציב."),
                    List.of(
                            "צריך לצבוע סלון ומסדרון בדירת שלושה חדרים.",
                            "יש נזק רטיבות בתקרה שצריך שפכטל וצביעה מחדש.",
                            "רוצה לצבוע חדר ילדים לפני כניסה לדירה.",
                            "הקירות במרפסת מתקלפים וצריך טיפול וצביעת חוץ.")),
            "general_handyman", new CategoryContent(150, 330, List.of(
                    "הנדימן לכל עבודות התחזוקה בבית: תלייה על קיר, הרכבת רהיטים ותיקונים כלליים.",
                    "מגיע עם כל הכלים, מטפל ברשימת תיקונים שלמה בביקור אחד.",
                    "כוונון דלתות וארונות, החלפת ידיות וצירים ותחזוקה שוטפת."),
                    List.of(
                            "צריך לתלות טלוויזיה על הקיר ולהרכיב שתי מדפים.",
                            "דלת הארון במטבח לא נסגרת טוב וצריך כוונון.",
                            "יש רשימה של תיקונים קטנים בבית אחרי מעבר דירה.",
                            "צריך להרכיב ארון וקומודה שהגיעו באריזה.")));

    /** The pack for {@code code}, or {@link #GENERIC} for a category this file knows nothing about. */
    static CategoryContent forCategory(String code) {
        return BY_CATEGORY_CODE.getOrDefault(code, GENERIC);
    }

    private DemoContent() {
    }
}
