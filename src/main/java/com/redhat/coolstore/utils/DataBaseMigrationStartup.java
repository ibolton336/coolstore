package com.redhat.coolstore.utils;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.quarkus.runtime.StartupEvent;

/**
 * Created by tqvarnst on 2017-04-04.
 */
@ApplicationScoped
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    @Inject
    DataSource dataSource;

    void onStart(@Observes StartupEvent event) {
        try {
            logger.info("Initializing/migrating the database using FlyWay");
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
            flyway.baseline();
            // Start the db.migration
            flyway.migrate();
        } catch (FlywayException e) {
            if(logger !=null)
                logger.log(Level.SEVERE,"FAILED TO INITIALIZE THE DATABASE: " + e.getMessage(),e);
            else
                System.out.println("FAILED TO INITIALIZE THE DATABASE: " + e.getMessage() + " and injection of logger doesn't work");

        }
    }

}