package com.pronto.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The explicit demo-data loader. Does nothing at all unless {@code pronto.demo-data.mode} says
 * otherwise, which is why an ordinary application startup — in any environment, including this
 * one — cannot duplicate, modify or delete demo data.
 *
 * <h2>Why an {@code ApplicationRunner}, when the guard is a {@code @PostConstruct}</h2>
 *
 * The two have opposite requirements and therefore opposite placements.
 * {@link DemoDataStartupGuard} must run <em>before</em> the web server binds its port, because its
 * job is to prevent the application from ever serving traffic in a dangerous configuration — so it
 * runs during bean instantiation. This class must run <em>after</em> the context is fully
 * refreshed, because Flyway's migration bean has to have finished first: seeding into a
 * half-migrated schema would fail on a missing column, and seeding before {@code V40} would fail
 * on {@code ck_users_role} the moment it tried to create the demo ADMIN.
 *
 * <p>Bean ordering between the two is not left to luck: the guard is a constructor dependency of
 * this class, so Spring cannot instantiate the seeder until the guard's {@code @PostConstruct} has
 * already passed or thrown.
 *
 * <h2>What each mode does</h2>
 *
 * <pre>
 *   off    (default)  nothing at all — not even a database read
 *   seed              build the dataset, unless demo accounts already exist
 *   reset             wipe every application row in the TEST/DEMO database, then build it
 * </pre>
 *
 * <h2>Failure is loud</h2>
 *
 * A seeding failure is rethrown, which fails the {@code ApplicationRunner} and terminates startup.
 * The alternative — log the exception and carry on — would leave a half-built marketplace behind
 * whichever screen the demonstrator opened first, and "some professionals are missing" is a far
 * more expensive thing to debug than a stack trace at startup.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DemoDataProperties properties;
    private final DemoDataStartupGuard guard;
    private final DemoDatasetWriter writer;

    public DemoDataSeeder(DemoDataProperties properties, DemoDataStartupGuard guard, DemoDatasetWriter writer) {
        this.properties = properties;
        this.guard = guard;
        this.writer = writer;
    }

    @Override
    public void run(ApplicationArguments args) {
        DemoDataMode mode = properties.getMode();
        if (mode == DemoDataMode.OFF) {
            return;
        }

        // Second check, after the startup guard already refused to let a dangerous configuration
        // boot. Cheap, and it is the check that still holds if this class is ever invoked from
        // somewhere the guard does not cover.
        guard.requireSeedingPermitted();

        if (mode == DemoDataMode.RESET) {
            log.warn("demo.seed.reset-requested — every application row in this database is about to be "
                    + "deleted and the demo dataset rebuilt");
            writer.reset();
        } else {
            int existing = writer.countExistingDemoAccounts();
            if (existing > 0) {
                log.info("demo.seed.skipped reason=already-present demoAccounts={} "
                        + "(set DEMO_DATA_MODE=reset to rebuild)", existing);
                return;
            }
        }

        long startedAt = System.currentTimeMillis();
        DemoDatasetWriter.SeedSummary summary = writer.seed();
        log.info("demo.seed.done professionals={} bookable={} multiCategory={} pending={} rejected={} "
                        + "approvedIncomplete={} sosAvailable={} customers={} regions={} orders={} reviews={} "
                        + "favorites={} withProfilePhoto={} noProfilePhoto={} perCategory=[{}] elapsedMs={}",
                summary.professionals(), summary.bookable(), summary.multiCategory(), summary.pending(),
                summary.rejected(), summary.approvedIncomplete(), summary.sosAvailable(), summary.customers(),
                summary.regions(), summary.orders(), summary.reviews(), summary.favorites(),
                summary.withProfilePhoto(), summary.professionals() - summary.withProfilePhoto(),
                summary.perCategory(), System.currentTimeMillis() - startedAt);
        log.info("demo.seed.notice This database now contains SYNTHETIC data only (accounts under @{}). "
                + "It must never be treated as, promoted to, or copied into production data.",
                DemoDatasetWriter.DEMO_EMAIL_DOMAIN);
    }
}
