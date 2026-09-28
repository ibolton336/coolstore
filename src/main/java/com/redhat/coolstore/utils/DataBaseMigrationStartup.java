package com.redhat.coolstore.utils;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.logging.Logger;

/**
 * Created by tqvarnst on 2017-04-04.
 */
@ApplicationScoped
public class DataBaseMigrationStartup {

    private static final Logger logger = Logger.getLogger(DataBaseMigrationStartup.class.getName());

    void onStart(@Observes StartupEvent ev) {
        logger.info("Database migration handled by Quarkus Flyway extension");
    }

}