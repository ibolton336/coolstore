package com.redhat.coolstore.utils;

import io.quarkus.runtime.StartupEvent;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.logging.Logger;

/**
 * Quarkus handles Flyway migrations automatically via application.properties
 * This class is kept for startup logging only
 */
@ApplicationScoped
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    void onStart(@Observes StartupEvent ev) {
        logger.info("Application started - Flyway migrations handled automatically by Quarkus");
    }
}
