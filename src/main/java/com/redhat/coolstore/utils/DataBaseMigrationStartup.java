package com.redhat.coolstore.utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.runtime.Startup;
import java.util.logging.Logger;

/**
 * Created by tqvarnst on 2017-04-04.
 * 
 * Note: Flyway migration is now handled automatically by Quarkus via quarkus.flyway.migrate-at-start=true
 * This class now only logs that the application has started.
 */
@ApplicationScoped
@Startup
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    // No need for PostConstruct - Flyway is handled by Quarkus extension via application.properties
    // The @Startup annotation ensures this bean is created at startup for any future initialization logic

}