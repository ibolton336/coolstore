package com.redhat.coolstore.utils;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import javax.sql.DataSource;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by tqvarnst on 2017-04-04.
 * Note: Quarkus Flyway extension handles migration automatically via config
 */
@ApplicationScoped
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    @Inject
    DataSource dataSource;

    void onStart(@Observes StartupEvent ev) {
        // Quarkus Flyway extension handles migration automatically
        // This method is kept for any custom initialization logic if needed
        logger.info("Application starting - Flyway migration configured via application.properties");
    }

}