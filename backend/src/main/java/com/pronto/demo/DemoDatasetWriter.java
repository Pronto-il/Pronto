package com.pronto.demo;

import com.pronto.demo.DemoContent.CategoryContent;
import com.pronto.storage.client.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds (and, on request, tears down) the TEST/DEMO dataset. Called only by
 * {@link DemoDataSeeder}, which has already been cleared by {@link DemoDataStartupGuard}.
 *
 * <h2>Plain SQL, not the domain repositories</h2>
 *
 * Every write below is a {@link JdbcTemplate} statement against the schema Flyway owns, and this
 * package imports no service and no repository from any domain package (the single exception is
 * {@link StorageClient}, because a verification document has to physically exist somewhere for an
 * operator to open it). That is deliberate, and it is what keeps the "no {@code if (demo)} branch"
 * rule structurally true rather than merely intended: fixture data cannot leak an environment
 * concept into a domain service it never touches, and no domain class gains a demo-only method,
 * constructor or repository query. The cost — this class must know the schema — is real but
 * bounded: {@code ddl-auto: validate} plus the entity mappings still guard the schema itself, and
 * a column rename that broke this file would break it loudly on the next seed run.
 *
 * <h2>The dataset is generated to pass the real rules, not around them</h2>
 *
 * Every professional intended to be bookable is given, honestly, each of the four things
 * {@code professionals.ProfessionalEligibility} demands: {@code approval_status = 'APPROVED'}, a
 * verification document key (backed by a real uploaded file), at least one <em>enabled</em>
 * weekly working-hours row, and at least one sub-service <b>drawn from that professional's own
 * category</b>. Nothing here special-cases eligibility, and no seeded row is exempt from it: the
 * three "approved but incomplete onboarding" professionals in the lifecycle cohort below are
 * seeded precisely <em>because</em> they must not appear in the listing, which is the clearest
 * possible demonstration that D4 is enforced by the backend and not by the seed.
 *
 * <h2>Reviews are earned, not asserted</h2>
 *
 * {@code reviews} requires a {@code customer_id}, a {@code professional_id} and a unique
 * {@code order_id} ({@code ux_reviews_order}), and {@code orders} in turn requires an
 * {@code issues} row. There is no shortcut and this class does not invent one: every seeded rating
 * is backed by a real chain of a demo customer's issue → a {@code COMPLETED} order for that
 * professional → one review of that order. The rating averages and counts the listing shows are
 * therefore computed by the same correlated subqueries production would use, over data that
 * genuinely satisfies the schema.
 */
@Component
class DemoDatasetWriter {

    private static final Logger log = LoggerFactory.getLogger(DemoDatasetWriter.class);

    /**
     * <b>The operational marker for synthetic data.</b> Every account this class creates has an
     * email under this domain, so "is this row synthetic?" is answerable with one
     * {@code WHERE lower(email) LIKE '%@demo.pronto.invalid'} against the existing schema — no new
     * column, no migration, no flag to keep in sync, and nothing visible to a customer in the UI.
     *
     * <p>{@code .invalid} is reserved by RFC 2606 and is guaranteed never to resolve, so even a
     * misconfigured environment with a real SMTP sender cannot deliver mail to a demo account. A
     * plausible-looking domain would not have that property.
     *
     * <p>It is a constant rather than a property on purpose: it is simultaneously the marker, the
     * idempotency key and the thing a human greps for. A configurable marker could be changed
     * between two runs, after which neither run could recognise the other's rows.
     */
    static final String DEMO_EMAIL_DOMAIN = "demo.pronto.invalid";

    /**
     * Tables {@link #reset()} never touches: Flyway's own history (deleting it would make the
     * database claim it has no schema) and the two reference tables that are schema-owned seed
     * data, not demo data — {@code categories} and {@code sub_services} arrive via {@code V10},
     * {@code V29} and {@code V31}, and every professional row points at them.
     */
    private static final Set<String> PRESERVED_TABLES =
            Set.of("flyway_schema_history", "categories", "sub_services");

    /**
     * All wall-clock times in the dataset are built in Israel local time — the same zone
     * {@code availability.service.AvailabilityDerivationService.BUSINESS_TIMEZONE} interprets
     * {@code professional_working_hours} in. Seeding an 08:00 shift in UTC would silently place it
     * hours away from the working day the calendar then renders.
     */
    private static final ZoneId BUSINESS_TIMEZONE = ZoneId.of("Asia/Jerusalem");

    /** Weekly shift patterns, Sunday (index 0) through Saturday (index 6). {@code null} = day off. */
    private static final Shift[][] WORKING_HOUR_PATTERNS = {
            {shift(8, 0, 17, 0), shift(8, 0, 17, 0), shift(8, 0, 17, 0), shift(8, 0, 17, 0),
                    shift(8, 0, 17, 0), shift(8, 0, 13, 0), null},
            {shift(7, 30, 16, 30), shift(7, 30, 16, 30), shift(7, 30, 16, 30), shift(7, 30, 16, 30),
                    shift(7, 30, 16, 30), null, null},
            {shift(9, 0, 18, 0), shift(9, 0, 18, 0), shift(9, 0, 18, 0), shift(9, 0, 18, 0),
                    shift(9, 0, 18, 0), shift(9, 0, 13, 0), null},
            {shift(8, 0, 19, 0), shift(8, 0, 19, 0), shift(8, 0, 19, 0), shift(8, 0, 19, 0),
                    shift(8, 0, 15, 0), null, null},
            {shift(10, 0, 20, 0), shift(10, 0, 20, 0), shift(10, 0, 20, 0), shift(10, 0, 20, 0),
                    shift(10, 0, 20, 0), null, shift(10, 0, 14, 0)},
    };

    /**
     * How many reviews each bookable professional gets, cycled by index. Includes zeros on
     * purpose: a marketplace where every professional already has reviews cannot demonstrate what
     * a brand-new joiner looks like, and {@code SosMatchingService.UNRATED_RATING_SCORE} exists
     * specifically to keep that joiner dispatchable. Mean is ~5.3.
     */
    private static final int[] REVIEW_COUNTS = {0, 2, 4, 7, 11, 3, 6, 1, 9, 5, 0, 8, 13, 4, 6, 2, 10, 5};

    /**
     * Rating pools by quality tier (excellent / good / mixed), cycled per professional so the
     * marketplace shows a spread of averages rather than a wall of 5.0s. Ratings are drawn from
     * the tier's pool in order, which keeps the dataset reproducible.
     */
    private static final int[][] RATING_TIERS = {
            {5, 5, 5, 4, 5, 4},
            {5, 4, 4, 5, 3, 4},
            {4, 3, 5, 3, 4, 2},
    };

    /** How many demo customers exist to file issues, hold orders and write the reviews. */
    private static final int CUSTOMER_COUNT = 14;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final StorageClient storageClient;
    private final DemoDataProperties properties;

    DemoDatasetWriter(JdbcTemplate jdbcTemplate,
                       PasswordEncoder passwordEncoder,
                       StorageClient storageClient,
                       DemoDataProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.storageClient = storageClient;
        this.properties = properties;
    }

    /**
     * @return how many demo accounts already exist. Non-zero means {@link DemoDataMode#SEED} must
     *         do nothing — that is the whole of this package's idempotency, and it is deliberately
     *         a single cheap query rather than a row-by-row reconciliation.
     */
    @Transactional(readOnly = true)
    public int countExistingDemoAccounts() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(email) LIKE ?",
                Integer.class, "%@" + DEMO_EMAIL_DOMAIN);
        return count == null ? 0 : count;
    }

    /**
     * Empties every application table in the TEST/DEMO database so {@link #seed()} can rebuild it.
     * One {@code TRUNCATE ... RESTART IDENTITY CASCADE} over every table {@code pg_tables} reports,
     * minus {@link #PRESERVED_TABLES}.
     *
     * <p><b>Why "everything", not "only the rows I created".</b> A selective delete has to walk the
     * whole foreign-key graph in exactly the right order, and it breaks the moment a demonstration
     * does what demonstrations are for — a demo customer books a demo professional, and now
     * {@code fk_orders_professional ON DELETE RESTRICT} refuses to let the professional go.
     * Truncating is correct here for a reason that does not generalise anywhere else in Pronto:
     * <em>every</em> row in the TEST/DEMO database is demo data by definition. The guard that makes
     * that assumption safe is {@link DemoDataStartupGuard}'s database-name check, which is why this
     * method can never run against LOCAL or Production.
     *
     * <p>The table list is discovered from the catalogue rather than hardcoded, so a future
     * migration adding a table does not silently leave stale rows behind after a reset.
     */
    @Transactional
    public void reset() {
        List<String> tables = jdbcTemplate.queryForList(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename",
                        String.class).stream()
                .filter(table -> !PRESERVED_TABLES.contains(table))
                .toList();
        if (tables.isEmpty()) {
            log.warn("demo.reset.skipped reason=no-application-tables (have the Flyway migrations run?)");
            return;
        }
        jdbcTemplate.execute(tables.stream()
                .map(table -> "\"" + table + "\"")
                .collect(Collectors.joining(", ", "TRUNCATE TABLE ", " RESTART IDENTITY CASCADE")));
        log.info("demo.reset.done tables={}", tables.size());
    }

    /** Writes the whole dataset. Assumes a database with no demo accounts in it; see {@link #reset()}. */
    @Transactional
    public SeedSummary seed() {
        List<CategorySeed> categories = loadCategories();
        if (categories.isEmpty()) {
            throw new IllegalStateException("Refusing to seed: no usable category was found. The Flyway "
                    + "migrations must have run (and seeded categories/sub_services) first.");
        }

        // One BCrypt evaluation for the whole run. See DemoDataProperties#password.
        String passwordHash = passwordEncoder.encode(properties.getPassword());

        long adminUserId = insertUser("צוות פרונטו — ניהול", "demo.admin@" + DEMO_EMAIL_DOMAIN,
                passwordHash, "ADMIN", null, null, null, null);

        List<DemoCustomer> customers = seedCustomers(passwordHash);

        List<BookableProfessional> bookable = new ArrayList<>();
        Map<String, int[]> perCategory = new LinkedHashMap<>();
        int index = 0;
        int sosAvailableTotal = 0;

        // ---- the bookable population: one pool per category, read from the database ----
        for (int position = 0; position < categories.size(); position++) {
            CategorySeed category = categories.get(position);
            int poolSize = bookablePoolSize(position);
            int sosCount = sosAvailableCount(position, poolSize);
            for (int i = 0; i < poolSize; i++) {
                SeededProfessional seeded = insertProfessional(index++, passwordHash, category,
                        "APPROVED", adminUserId, null, true, true, true, i < sosCount);
                bookable.add(new BookableProfessional(seeded.id(), category, seeded.basePrice()));
            }
            perCategory.put(category.code(), new int[]{poolSize, sosCount});
            sosAvailableTotal += sosCount;
        }

        // ---- the approval-lifecycle cohort: what the operator queue and D4 need to be
        //      demonstrable. None of these is bookable, each for a different, real reason. ----
        int pending = 0;
        for (int i = 0; i < 6; i++) { // awaiting review, onboarding complete
            insertProfessional(index++, passwordHash, categories.get(i % categories.size()),
                    "PENDING", null, null, true, true, true, false);
            pending++;
        }
        int rejected = 0;
        for (int i = 0; i < 2; i++) { // reviewed and refused
            insertProfessional(index++, passwordHash, categories.get((i + 2) % categories.size()),
                    "REJECTED", adminUserId,
                    "המסמך שהועלה אינו תעודה בתוקף. יש להעלות תעודה מקצועית עדכנית ולהגיש שוב.",
                    true, true, true, false);
            rejected++;
        }
        // APPROVED, and still not bookable — the point of D4. One row per missing onboarding element.
        insertProfessional(index++, passwordHash, categories.get(0), "APPROVED", adminUserId, null,
                true, true, false, false);   // no sub-service under their own category
        insertProfessional(index++, passwordHash, categories.get(1 % categories.size()), "APPROVED",
                adminUserId, null, true, false, true, false);   // no enabled working-hours day
        insertProfessional(index++, passwordHash, categories.get(2 % categories.size()), "APPROVED",
                adminUserId, null, false, true, true, false);   // no verification document
        int approvedIncomplete = 3;

        int[] history = seedReviewHistory(bookable, customers);
        int favorites = seedFavorites(customers, bookable);

        String perCategoryReport = perCategory.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()[0] + "(sos:" + entry.getValue()[1] + ")")
                .collect(Collectors.joining(", "));

        return new SeedSummary(index, bookable.size(), pending, rejected, approvedIncomplete,
                customers.size(), history[0], history[1], favorites, sosAvailableTotal, perCategoryReport);
    }

    // ------------------------------------------------------------------ categories

    /**
     * The categories, and their sub-services, <b>as the database actually has them</b> — ordered by
     * {@code display_order}, so "the first category" is a stable, data-driven notion rather than a
     * hardcoded {@code plumbing}. A category with no sub-services cannot produce an eligible
     * professional at all, so it is skipped with a warning rather than silently producing rows that
     * fail eligibility for a reason nobody could see.
     */
    private List<CategorySeed> loadCategories() {
        Map<Long, List<Long>> subServicesByCategory = new LinkedHashMap<>();
        for (long[] row : jdbcTemplate.query(
                "SELECT id, category_id FROM sub_services ORDER BY category_id, display_order, id",
                (rs, rowNum) -> new long[]{rs.getLong(1), rs.getLong(2)})) {
            subServicesByCategory.computeIfAbsent(row[1], key -> new ArrayList<>()).add(row[0]);
        }

        List<CategorySeed> categories = new ArrayList<>();
        for (CategorySeed candidate : jdbcTemplate.query(
                "SELECT id, code, name_he FROM categories ORDER BY display_order, id",
                (rs, rowNum) -> new CategorySeed(rs.getLong(1), rs.getString(2), rs.getString(3),
                        subServicesByCategory.getOrDefault(rs.getLong(1), List.of())))) {
            if (candidate.subServiceIds().isEmpty()) {
                log.warn("demo.seed.category-skipped code={} reason=no-sub-services", candidate.code());
                continue;
            }
            categories.add(candidate);
        }
        return categories;
    }

    /**
     * How many bookable professionals the category at {@code position} (0-based, by
     * {@code display_order}) gets.
     *
     * <p>The first category deliberately gets far more than the rest, and that number is not
     * arbitrary: the SOS defaults are {@code candidate-pool-size=8} and
     * {@code expansion-pool-increment=8} over {@code max-search-expansions=2}, so demonstrating an
     * initial dispatch <em>and</em> a real "סרוק שוב" expansion requires strictly more than 16
     * eligible, SOS-available professionals in one category. 20 with 18 SOS-available produces 8
     * offers initially, 8 more on the first expansion and the final 2 on the second — every wave
     * finding somebody new, with no SOS rule bent to make it happen.
     */
    private static int bookablePoolSize(int position) {
        return position == 0 ? 20 : Math.max(7, 11 - position);
    }

    /** SOS-available share: nearly all of the first category (see {@link #bookablePoolSize}), half elsewhere. */
    private static int sosAvailableCount(int position, int poolSize) {
        return position == 0 ? poolSize - 2 : (poolSize + 1) / 2;
    }

    // ------------------------------------------------------------------ people

    private List<DemoCustomer> seedCustomers(String passwordHash) {
        List<DemoCustomer> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMER_COUNT; i++) {
            String city = cityFor(i);
            String street = DemoContent.STREETS.get((i * 5) % DemoContent.STREETS.size());
            String houseNumber = String.valueOf(3 + (i * 7) % 60);
            String phone = "05" + (i % 5) + "-" + String.format("%07d", 2100000L + (i * 134579L) % 7000000L);
            long userId = insertUser(fullName(i + 40), "demo.customer." + (i + 1) + "@" + DEMO_EMAIL_DOMAIN,
                    passwordHash, "CUSTOMER", phone, city, street, houseNumber);
            customers.add(new DemoCustomer(userId, city, street, houseNumber));
        }
        return customers;
    }

    /**
     * Creates one professional and everything a professional owns. The three onboarding booleans
     * are exactly the elements {@code ProfessionalEligibility} tests, exposed individually so the
     * lifecycle cohort can omit one at a time and prove the rule bites.
     */
    private SeededProfessional insertProfessional(int index, String passwordHash, CategorySeed category,
                                                    String approvalStatus, Long reviewerUserId,
                                                    String rejectionReason, boolean withDocument,
                                                    boolean withWorkingHours, boolean withSubServices,
                                                    boolean sosAvailable) {
        CategoryContent content = DemoContent.forCategory(category.code());
        String name = fullName(index);
        String city = cityFor(index);
        BigDecimal basePrice = basePrice(content, index);

        long userId = insertUser(name, "demo.pro." + (index + 1) + "@" + DEMO_EMAIL_DOMAIN,
                passwordHash, "PROFESSIONAL", null, null, null, null);

        boolean reviewed = reviewerUserId != null && !"PENDING".equals(approvalStatus);
        OffsetDateTime reviewedAt = reviewed ? OffsetDateTime.now().minusDays(5 + index % 40) : null;

        Long professionalId = jdbcTemplate.queryForObject("""
                        INSERT INTO professionals (user_id, category_id, service_area, approval_status,
                                reliability_score, base_price, bio, city, verification_document_key,
                                approval_reviewed_at, approval_reviewed_by, approval_rejection_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id""",
                Long.class,
                userId, category.id(), city + " והסביבה", approvalStatus,
                reliabilityScore(index), basePrice,
                content.bios().get(index % content.bios().size()), city,
                withDocument ? uploadVerificationDocument(userId, name) : null,
                reviewedAt, reviewed ? reviewerUserId : null, rejectionReason);

        if (withWorkingHours) {
            insertWorkingHours(professionalId, index);
        }
        if (withSubServices) {
            insertSubServices(professionalId, category, index);
        }
        // data-model.md §2.6: one sos_availability row per professional from creation, exactly as
        // AuthService#register does it — an absent row is excluded by SosCandidateRepository's
        // inner join, which is a different statement from "opted out".
        jdbcTemplate.update("INSERT INTO sos_availability (professional_id, is_available) VALUES (?, ?)",
                professionalId, sosAvailable);
        return new SeededProfessional(professionalId, basePrice);
    }

    private long insertUser(String fullName, String email, String passwordHash, String role,
                             String phone, String city, String street, String houseNumber) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (full_name, email, password_hash, role, email_verified, phone,
                                default_city, default_street, default_house_number)
                        VALUES (?, ?, ?, ?, true, ?, ?, ?, ?)
                        RETURNING id""",
                Long.class, fullName, email, passwordHash, role, phone, city, street, houseNumber);
    }

    /**
     * All seven weekdays, exactly as {@code AvailabilityService#updateWorkingHours} and
     * {@code AuthService#persistWorkingHours} write them — a disabled day carries {@code null}
     * times, which is what {@code ck_professional_working_hours_times} is built around.
     */
    private void insertWorkingHours(long professionalId, int index) {
        Shift[] pattern = WORKING_HOUR_PATTERNS[index % WORKING_HOUR_PATTERNS.length];
        List<Object[]> batch = new ArrayList<>(7);
        for (int weekday = 0; weekday < 7; weekday++) {
            Shift shift = pattern[weekday];
            batch.add(new Object[]{professionalId, weekday, shift != null,
                    shift == null ? null : shift.start(), shift == null ? null : shift.end()});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO professional_working_hours (professional_id, weekday, enabled, start_time, end_time)
                VALUES (?, ?, ?, ?, ?)""", batch);
    }

    /**
     * Two to four sub-services, taken from {@code category}'s own list and nowhere else. The
     * eligibility predicate joins {@code sub_services.category_id = p.category_id}, so a
     * cross-category selection here would produce a professional who looks fully onboarded and is
     * still, correctly, invisible.
     */
    private void insertSubServices(long professionalId, CategorySeed category, int index) {
        List<Long> available = category.subServiceIds();
        int count = Math.min(available.size(), 2 + index % 3);
        Set<Long> chosen = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            chosen.add(available.get((index + i) % available.size()));
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO professional_sub_services (professional_id, sub_service_id) VALUES (?, ?)",
                chosen.stream().map(id -> new Object[]{professionalId, id}).toList());
    }

    // ------------------------------------------------------------------ history

    /**
     * The completed work the ratings are computed from: for each bookable professional, N chains of
     * {@code issue → COMPLETED order → review}. Roughly one in five is an SOS job
     * ({@code urgency_type = 'SOS'}, {@code booked_end} null, a surcharge on the order), matching
     * how {@code BookingsService} records an SOS booking.
     *
     * <p>Every order is in the past and {@code COMPLETED}, which is also what keeps it clear of
     * {@code ck_orders_no_overlap} — that exclusion constraint only covers
     * {@code PENDING}/{@code CONFIRMED}/{@code ON_THE_WAY} rows with a non-null {@code booked_end}.
     * No live orders are seeded: a demonstration creates those itself, and inventing them here
     * would occupy calendar slots the demo then cannot book.
     *
     * @return {@code [orders, reviews]}
     */
    private int[] seedReviewHistory(List<BookableProfessional> professionals, List<DemoCustomer> customers) {
        LocalDate today = LocalDate.now(BUSINESS_TIMEZONE);
        int orders = 0;
        int reviews = 0;

        for (int index = 0; index < professionals.size(); index++) {
            BookableProfessional professional = professionals.get(index);
            CategoryContent content = DemoContent.forCategory(professional.category().code());
            int[] ratingPool = RATING_TIERS[index % RATING_TIERS.length];
            int reviewCount = REVIEW_COUNTS[index % REVIEW_COUNTS.length];

            for (int k = 0; k < reviewCount; k++) {
                DemoCustomer customer = customers.get((index * 3 + k) % customers.size());
                boolean sos = (index + k) % 5 == 0;

                LocalDate day = today.minusDays(9 + ((index * 7L + k * 13L) % 300L));
                ZonedDateTime start = ZonedDateTime.of(day, LocalTime.of(9 + ((index + k) % 4) * 2, 0),
                        BUSINESS_TIMEZONE);
                ZonedDateTime end = start.plusHours(2);
                BigDecimal surcharge = sos ? new BigDecimal("50.00") : BigDecimal.ZERO;

                Long issueId = jdbcTemplate.queryForObject("""
                                INSERT INTO issues (customer_id, category_id, description, urgency_type, status,
                                        created_at, updated_at)
                                VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?)
                                RETURNING id""",
                        Long.class, customer.userId(), professional.category().id(),
                        content.issueDescriptions().get((index + k) % content.issueDescriptions().size()),
                        sos ? "SOS" : "STANDARD",
                        start.minusDays(2).toOffsetDateTime(), end.toOffsetDateTime());

                Long orderId = jdbcTemplate.queryForObject("""
                                INSERT INTO orders (issue_id, customer_id, professional_id, booked_start, booked_end,
                                        order_status, final_price, base_price_snapshot, sos_surcharge,
                                        service_city, service_street, service_house_number, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?)
                                RETURNING id""",
                        Long.class, issueId, customer.userId(), professional.id(),
                        start.toOffsetDateTime(), sos ? null : end.toOffsetDateTime(),
                        professional.basePrice().add(surcharge), professional.basePrice(), surcharge,
                        customer.city(), customer.street(), customer.houseNumber(),
                        start.minusDays(2).plusHours(1).toOffsetDateTime(), end.toOffsetDateTime());
                orders++;

                int rating = ratingPool[k % ratingPool.length];
                jdbcTemplate.update("""
                                INSERT INTO reviews (professional_id, customer_id, order_id, rating, comment,
                                        created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        professional.id(), customer.userId(), orderId, rating, commentFor(rating, index + k),
                        end.plusHours(3).toOffsetDateTime(), end.plusHours(3).toOffsetDateTime());
                reviews++;
            }
        }
        return new int[]{orders, reviews};
    }

    /**
     * A few favourites per demo customer, drawn only from the bookable pool —
     * {@code FavoritesService#addFavorite} now refuses an ineligible professional (MS1 D-B), so
     * seeding an ineligible favourite would create data the application itself would never have
     * let a customer create.
     */
    private int seedFavorites(List<DemoCustomer> customers, List<BookableProfessional> professionals) {
        List<Object[]> batch = new ArrayList<>();
        for (int c = 0; c < customers.size(); c++) {
            Set<Long> chosen = new LinkedHashSet<>();
            for (int j = 0; j < 3; j++) {
                chosen.add(professionals.get((c * 17 + j * 5) % professionals.size()).id());
            }
            for (Long professionalId : chosen) {
                batch.add(new Object[]{customers.get(c).userId(), professionalId});
            }
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO favorites (customer_id, professional_id) VALUES (?, ?)", batch);
        return batch.size();
    }

    // ------------------------------------------------------------------ verification documents

    /**
     * Uploads a small generated placeholder image through the real {@link StorageClient} and
     * returns its key.
     *
     * <p>A non-null {@code verification_document_key} is all eligibility requires, so this could
     * have been a made-up string. It is not, for one reason: the operator review flow MS1 just
     * built mints a presigned URL from that key and opens it, and a key pointing at nothing would
     * turn every demo of that screen into a storage error. Writing a real object through the same
     * client production uses keeps the demonstration honest end to end.
     *
     * <p>The key is deterministic (no {@code UUID}), so re-running the seeder overwrites the same
     * object instead of accumulating orphans. The image is labelled in English as synthetic — it is
     * an operator-facing compliance document, never shown to a customer, and a placeholder that did
     * <em>not</em> say so would be the dishonest option.
     */
    private String uploadVerificationDocument(long userId, String professionalName) {
        String key = "verification-documents/" + userId + "/demo-verification-document.png";
        storageClient.upload(key, renderPlaceholderDocument(professionalName), "image/png");
        return key;
    }

    private static byte[] renderPlaceholderDocument(String professionalName) {
        BufferedImage image = new BufferedImage(760, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(0x1F, 0x2A, 0x37));
            graphics.drawRect(24, 24, image.getWidth() - 49, image.getHeight() - 49);

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString("PRONTO - TEST/DEMO ENVIRONMENT", 56, 100);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            graphics.drawString("SYNTHETIC VERIFICATION DOCUMENT", 56, 150);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            graphics.drawString("This is not a real certificate, licence or identity document.", 56, 196);
            graphics.drawString("It was generated by the demo data seeder and has no legal meaning.", 56, 226);
            graphics.drawString("Account: " + professionalName, 56, 286);
            graphics.drawString("Domain:  @" + DEMO_EMAIL_DOMAIN, 56, 316);
            graphics.drawString("Never treat TEST/DEMO content as production data.", 56, 360);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the placeholder verification document.", e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ deterministic content

    /**
     * Deterministic name selection: unique for every index below
     * {@code FIRST_NAMES.size() * LAST_NAMES.size()} (240), which the dataset stays comfortably
     * under. Determinism matters more than variety here — the same seed run twice must produce the
     * same marketplace, or a QA finding cannot be reproduced.
     */
    private static String fullName(int index) {
        int firsts = DemoContent.FIRST_NAMES.size();
        return DemoContent.FIRST_NAMES.get(index % firsts) + " "
                + DemoContent.LAST_NAMES.get((index / firsts) % DemoContent.LAST_NAMES.size());
    }

    /**
     * Three in five land in {@code CITIES.get(0)}, the rest spread across the others — see
     * {@link DemoContent#CITIES} for why exercising both branches of
     * {@code ApproximateDistanceEtaStrategy} is a requirement of the dataset rather than a detail.
     */
    private static String cityFor(int index) {
        if (index % 5 < 3) {
            return DemoContent.CITIES.get(0);
        }
        return DemoContent.CITIES.get(1 + (index / 5) % (DemoContent.CITIES.size() - 1));
    }

    private static BigDecimal basePrice(CategoryContent content, int index) {
        int span = content.maxPrice() - content.minPrice();
        int raw = content.minPrice() + (index * 37) % (span + 1);
        return BigDecimal.valueOf(Math.round(raw / 10.0) * 10L);
    }

    /**
     * {@code professionals.reliability_score} is {@code NUMERIC(3,2)} constrained to {@code [0, 5]}
     * by {@code ck_professionals_reliability_score}, but {@code SosMatchingService} reads it as an
     * already-normalised {@code [0, 1]} figure and clamps anything above 1. Seeding 0.70–0.98
     * respects the consumer, not just the constraint — seeding "4.60" would pass the CHECK and then
     * silently score every professional identically at the clamp ceiling.
     */
    private static BigDecimal reliabilityScore(int index) {
        return BigDecimal.valueOf(70 + (index * 13) % 29, 2);
    }

    private static String commentFor(int rating, int index) {
        List<String> pool = rating >= 4
                ? DemoContent.COMMENTS_POSITIVE
                : (rating == 3 ? DemoContent.COMMENTS_NEUTRAL : DemoContent.COMMENTS_NEGATIVE);
        // Roughly one review in six carries no text, as in any real marketplace.
        return index % 6 == 5 ? null : pool.get(index % pool.size());
    }

    private static Shift shift(int startHour, int startMinute, int endHour, int endMinute) {
        return new Shift(LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }

    // ------------------------------------------------------------------ value types

    private record Shift(LocalTime start, LocalTime end) {
    }

    private record CategorySeed(long id, String code, String nameHe, List<Long> subServiceIds) {
    }

    private record SeededProfessional(long id, BigDecimal basePrice) {
    }

    private record BookableProfessional(long id, CategorySeed category, BigDecimal basePrice) {
    }

    private record DemoCustomer(long userId, String city, String street, String houseNumber) {
    }

    /** What {@link DemoDataSeeder} logs after a successful run. Counts only, no personal data. */
    record SeedSummary(int professionals, int bookable, int pending, int rejected, int approvedIncomplete,
                        int customers, int orders, int reviews, int favorites, int sosAvailable,
                        String perCategory) {
    }
}
