package com.localcloud.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Flyway database migrations on startup.
 * Uses {@code baselineOnMigrate = true} for backward compatibility with
 * existing databases that already have the schema applied by SchemaManager.
 * Future schema changes go into {@code db/migration/V2__*.sql} and beyond.
 */
public class FlywayMigrationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FlywayMigrationRunner.class);
    private final DataSource dataSource;

    public FlywayMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Run Flyway migrations. Call this BEFORE SchemaManager to establish
     * the flyway_schema_history table, then SchemaManager applies the
     * base schema (which Flyway treats as baseline V1).
     */
    public void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations("classpath:db/migration")
                .load();

        int applied = flyway.migrate().migrationsExecuted;
        if (applied > 0) {
            logger.info("Flyway: {} migration(s) applied", applied);
        } else {
            logger.info("Flyway: schema is up to date (no migrations applied)");
        }
    }
}
