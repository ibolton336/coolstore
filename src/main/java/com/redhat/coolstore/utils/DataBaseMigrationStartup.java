package com.redhat.coolstore.utils;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Database migration is now handled by Quarkus Flyway extension.
 * Configured in application.properties with:
 * quarkus.flyway.migrate-at-start=true
 * quarkus.flyway.baseline-on-migrate=true
 * quarkus.flyway.locations=classpath:db/migration
 */
@ApplicationScoped
@Startup
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    @PostConstruct
    private void startup() {
        logger.info("Database migration is handled by Quarkus Flyway extension at startup");
    }

}
