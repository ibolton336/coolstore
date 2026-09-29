package com.redhat.coolstore.utils;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by tqvarnst on 2017-04-04.
 */
@ApplicationScoped
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    @Inject
    DataSource dataSource;

    @PostConstruct
    @Transactional
    void startup() {


        try {
            logger.info("Initializing/migrating the database using FlyWay");
            // Note: Quarkus handles Flyway migrations automatically via application.properties
            // This manual migration code is redundant but kept for compatibility
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .load();
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